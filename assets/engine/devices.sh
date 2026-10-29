#!/system/bin/sh
# Per-device control and usage for the hotspot.
#   devices.sh tick [iface]          sync firewall rules, count bytes, notice new devices (nat.sh runs this every ~30 s)
#   devices.sh block <mac>           drop all of a device's traffic
#   devices.sh unblock <mac>
#   devices.sh limit <mac> <kBps>    cap both directions in kilobytes per second (0 = no limit)
#   devices.sh forget <mac>          let a device be "new" again
#   devices.sh report                one dev=... line per device (see below), for the app
#
# State (all under $PERSIST, survives reboots):
#   blocked      one MAC per line
#   limits       "mac kBps"
#   known        "mac epoch"   every device ever seen, for new-device detection
#   new_devices  "epoch<TAB>mac<TAB>ip<TAB>host<TAB>action"  events for the app, which empties it
#   usage/       one file per day, "mac down_bytes up_bytes"
#
# Firewall: chain DASH_PRE (raw, PREROUTING, from clients: block, limit, count uploads by MAC) and DASH_POST
# (mangle, POSTROUTING, to clients: block, limit, count downloads by IP), IPv4 and IPv6. They sit in the raw and
# mangle tables so the NAT script's own FORWARD rules cannot bypass them. Usage counts what actually got through.
MODDIR=$(cd "$(dirname "$0")" && pwd)
. "$MODDIR/lib.sh" >/dev/null 2>&1
BB=/data/adb/magisk/busybox
BLK=$PERSIST/blocked LIM=$PERSIST/limits KNOWN=$PERSIST/known NEWQ=$PERSIST/new_devices USG=$PERSIST/usage
T=/dev/.dash_dev
mkdir -p "$PERSIST" "$USG" "$T" 2>/dev/null

APIF=${2:-}
case "$1" in tick) ;; *) APIF=$(ap_if 2>/dev/null) ;; esac
[ -n "$APIF" ] || APIF=$(ap_if 2>/dev/null)
PFX=""; [ -n "$HOTSPOT_IP" ] && PFX="${HOTSPOT_IP%.*}."

lc()  { echo "$1" | tr 'A-Z' 'a-z'; }
okmac() { echo "$1" | grep -qiE '^([0-9a-f]{2}:){5}[0-9a-f]{2}$'; }

# "mac ip" for every IPv4 client we know of: neighbour table plus unexpired DHCP leases
cmap() {
  { awk -v i="$APIF" '$6==i && $3=="0x2" {print tolower($4), $1}' /proc/net/arp 2>/dev/null
    "$BB" dumpleases -f /data/local/tmp/udhcpd.leases 2>/dev/null | awk 'NR>1 && $NF!="expired" && $1 ~ /:/ {print tolower($1), $2}'
  } | awk -v p="$PFX" 'NF==2 && (p=="" || index($2,p)==1)' | sort -u
}
# "mac ipv6" for clients' global IPv6 addresses
cmap6() {
  ip -6 neigh show dev "$APIF" 2>/dev/null | awk '$1 !~ /^fe80/ && $2=="lladdr" && $NF!="FAILED" {print tolower($3), $1}' | sort -u
}
hostof() { "$BB" dumpleases -f /data/local/tmp/udhcpd.leases 2>/dev/null | awk -v m="$(lc "$1")" 'NR>1 && NF>=4 && tolower($1)==m {print $3; exit}'; }
isblocked() { [ -f "$BLK" ] && grep -qix "$(lc "$1")" "$BLK"; }
limitof() { [ -f "$LIM" ] && awk -v m="$(lc "$1")" 'tolower($1)==m && $2>0 {print $2; exit}' "$LIM"; }

# --------------------------------------------------------------------------- rules
hook() { # <tool> <table> <chain> <dir> <our-chain>
  $1 -t "$2" -C "$3" "$4" "$APIF" -j "$5" 2>/dev/null || $1 -t "$2" -I "$3" 1 "$4" "$APIF" -j "$5" 2>/dev/null
}
rebuild() {
  for ipt in iptables ip6tables; do
    $ipt -t raw -N DASH_PRE 2>/dev/null;    $ipt -t raw -F DASH_PRE
    $ipt -t mangle -N DASH_POST 2>/dev/null; $ipt -t mangle -F DASH_POST
  done
  n=0
  # blocked devices: nothing from them gets in (v4 and v6, by MAC)
  [ -f "$BLK" ] && for m in $(grep -E '^([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}' "$BLK" | tr 'A-F' 'a-f'); do
    for ipt in iptables ip6tables; do $ipt -t raw -A DASH_PRE -m mac --mac-source "$m" -j DROP; done
  done
  # one pass per MAC for the upload side (limit, then count)
  for m in $( { cmap | awk '{print $1}'; cmap6 | awk '{print $1}'; } | sort -u ); do
    isblocked "$m" && continue
    n=$((n+1)); lk=$(limitof "$m")
    for ipt in iptables ip6tables; do
      if [ -n "$lk" ]; then
        $ipt -t raw -A DASH_PRE -m mac --mac-source "$m" -m hashlimit --hashlimit-above "${lk}kb/s" --hashlimit-burst $((lk*2048)) \
          --hashlimit-mode srcip --hashlimit-name "dash_u$n$( [ $ipt = ip6tables ] && echo v6 )" -j DROP
      fi
      $ipt -t raw -A DASH_PRE -m mac --mac-source "$m" -m comment --comment "dash:$m:up"
    done
  done
  # download side, per client address
  n=0
  cmap | while read m ip; do
    n=$((n+1))
    if isblocked "$m"; then iptables -t mangle -A DASH_POST -d "$ip" -j DROP; continue; fi
    lk=$(limitof "$m")
    [ -n "$lk" ] && iptables -t mangle -A DASH_POST -d "$ip" -m hashlimit --hashlimit-above "${lk}kb/s" --hashlimit-burst $((lk*2048)) \
      --hashlimit-mode dstip --hashlimit-name "dash_d$n" -j DROP
    iptables -t mangle -A DASH_POST -d "$ip" -m comment --comment "dash:$m:dn"
  done
  n=0
  cmap6 | while read m ip; do
    n=$((n+1))
    if isblocked "$m"; then ip6tables -t mangle -A DASH_POST -d "$ip" -j DROP; continue; fi
    lk=$(limitof "$m")
    [ -n "$lk" ] && ip6tables -t mangle -A DASH_POST -d "$ip" -m hashlimit --hashlimit-above "${lk}kb/s" --hashlimit-burst $((lk*2048)) \
      --hashlimit-mode dstip --hashlimit-name "dash_d6_$n" -j DROP
    ip6tables -t mangle -A DASH_POST -d "$ip" -m comment --comment "dash:$m:dn"
  done
}

# --------------------------------------------------------------------------- who is connected
# A device that is really associated always answers an ARP request. (The driver's own station table cannot be used: on
# this phone it lists only the hotspot's own radio address and misses real clients.) Written for report() to read.
presence() {
  cmap > "$T/cands"
  : > "$T/online.new"
  while read m ip; do
    ( "$BB" arping -I "$APIF" -c 1 -w 2 -f "$ip" >/dev/null 2>&1 && echo "$m" >> "$T/online.new" ) &
  done < "$T/cands"
  wait
  sort -u "$T/online.new" > "$T/online"
}

# --------------------------------------------------------------------------- counting
sample() {
  { iptables -t raw -L DASH_PRE -n -v -x 2>/dev/null; iptables -t mangle -L DASH_POST -n -v -x 2>/dev/null
    ip6tables -t raw -L DASH_PRE -n -v -x 2>/dev/null; ip6tables -t mangle -L DASH_POST -n -v -x 2>/dev/null
  } | awk '{ if (match($0, /dash:[0-9a-f:]+:(up|dn)/)) { k = substr($0, RSTART+5, RLENGTH-5); b[k] += $2 } } END { for (k in b) print k, b[k] }' > "$T/cur"
  [ -f "$T/last" ] || : > "$T/last"
  # bytes since the previous sample (a counter that went down was reset by a rebuild or reboot: take it whole)
  awk 'FILENAME == ARGV[1] { last[$1] = $2; next } { d = $2 - ($1 in last ? last[$1] : 0); if (d < 0) d = $2; if (d > 0) print $1, d }' "$T/last" "$T/cur" > "$T/delta"
  cp "$T/cur" "$T/last"
  [ -s "$T/delta" ] || return 0
  F="$USG/$(date +%F)"
  [ -f "$F" ] || : > "$F"
  awk 'FILENAME == ARGV[1] { k = $1; dir = substr(k, length(k)-1); m = substr(k, 1, length(k)-3); if (dir == "dn") dn[m] += $2; else up[m] += $2; seen[m] = 1; next }
       { if ($1 in seen) { $2 += dn[$1]; $3 += up[$1]; delete seen[$1]; delete dn[$1]; delete up[$1] } print $1, $2, $3 }
       END { for (m in seen) print m, dn[m] + 0, up[m] + 0 }' "$T/delta" "$F" > "$F.new" && mv "$F.new" "$F"
}

# --------------------------------------------------------------------------- tick
tick() {
  ip -4 addr show dev "$APIF" 2>/dev/null | grep -q 'inet ' || return 0
  mkdir "$T/lock" 2>/dev/null || { [ -n "$(find "$T/lock" -maxdepth 0 -mmin +2 2>/dev/null)" ] && rmdir "$T/lock" 2>/dev/null; return 0; }
  trap 'rmdir "$T/lock" 2>/dev/null' EXIT
  now=$(date +%s)

  # first run: everything on the hotspot right now counts as known, silently
  if [ ! -f "$KNOWN" ]; then cmap | awk -v t="$now" '{print $1, t}' | sort -u > "$KNOWN"; : > "$NEWQ"; fi

  presence
  sample

  cmap | while read m ip; do
    grep -qi "^$m " "$KNOWN" && continue
    echo "$m $now" >> "$KNOWN"
    act=allowed
    if [ "$DEVICES_AUTOBLOCK" = 1 ]; then isblocked "$m" || echo "$m" >> "$BLK"; act=blocked; fi
    printf '%s\t%s\t%s\t%s\t%s\n' "$now" "$m" "$ip" "$(hostof "$m")" "$act" >> "$NEWQ"
  done

  h=$( { echo "$APIF"; cat "$BLK" 2>/dev/null; cat "$LIM" 2>/dev/null; cmap; cmap6; } | "$BB" md5sum | cut -c1-16)
  if [ "$h" != "$(cat "$T/hash" 2>/dev/null)" ] || ! iptables -t raw -S DASH_PRE >/dev/null 2>&1; then
    rebuild
    : > "$T/last"                 # counters restart from zero
    echo "$h" > "$T/hash"
  fi
  hook iptables raw PREROUTING -i DASH_PRE;   hook ip6tables raw PREROUTING -i DASH_PRE
  hook iptables mangle POSTROUTING -o DASH_POST; hook ip6tables mangle POSTROUTING -o DASH_POST
  find "$USG" -type f -mtime +400 -exec rm -f {} + 2>/dev/null
}

# --------------------------------------------------------------------------- report
report() {
  TODAY=$(date +%F); MONTH=$(date +%Y-%m)
  { [ -n "$(find "$T/online" -mmin -3 2>/dev/null)" ] && awk '{print "S", tolower($1)}' "$T/online"
    cmap | awk '{print "C", $1, $2}'
    "$BB" dumpleases -f /data/local/tmp/udhcpd.leases 2>/dev/null | awk 'NR>1 && NF>=4 {print "H", tolower($1), $3}'
    [ -f "$BLK" ] && grep -E '^([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}' "$BLK" | tr 'A-F' 'a-f' | awk '{print "B", $1}'
    [ -f "$LIM" ] && awk '$2>0 {print "L", tolower($1), $2}' "$LIM"
    cat "$USG/$MONTH"-* 2>/dev/null | awk '{print "M", tolower($1), $2, $3}'
    [ -f "$USG/$TODAY" ] && awk '{print "T", tolower($1), $2, $3}' "$USG/$TODAY"
  } | awk -v self="$(cat /sys/class/net/$APIF/address 2>/dev/null)" '
    $2==self { next }
    $1=="S" { on[$2]=1; all[$2]=1 }
    $1=="C" { ip[$2]=$3; all[$2]=1 }
    $1=="H" { host[$2]=$3 }
    $1=="B" { blk[$2]=1; all[$2]=1 }
    $1=="L" { lim[$2]=$3 }
    $1=="M" { mdn[$2]+=$3; mup[$2]+=$4; all[$2]=1 }
    $1=="T" { tdn[$2]+=$3; tup[$2]+=$4 }
    END { for (m in all) printf "dev=%s|%s|%s|%d|%d|%d|%d|%d|%d|%d\n", m, ip[m], host[m], on[m]+0, blk[m]+0, lim[m]+0, tdn[m]+0, tup[m]+0, mdn[m]+0, mup[m]+0 }'
}

case "$1" in
  tick)    tick ;;
  report)  report ;;
  block)   okmac "$2" || { echo "bad mac"; exit 2; }; m=$(lc "$2"); mkdir -p "$PERSIST"; isblocked "$m" || echo "$m" >> "$BLK"; tick ;;
  unblock) okmac "$2" || { echo "bad mac"; exit 2; }; m=$(lc "$2"); [ -f "$BLK" ] && grep -vix "$m" "$BLK" > "$BLK.new"; [ -f "$BLK.new" ] && mv "$BLK.new" "$BLK"; tick ;;
  limit)   okmac "$2" || { echo "bad mac"; exit 2; }; m=$(lc "$2"); k=${3:-0}
           case "$k" in ''|*[!0-9]*) echo "bad rate"; exit 2 ;; esac
           [ -f "$LIM" ] && grep -vi "^$m " "$LIM" > "$LIM.new" || : > "$LIM.new"
           [ "$k" -gt 0 ] && echo "$m $k" >> "$LIM.new"; mv "$LIM.new" "$LIM"; tick ;;
  forget)  okmac "$2" || { echo "bad mac"; exit 2; }; m=$(lc "$2"); [ -f "$KNOWN" ] && grep -vi "^$m " "$KNOWN" > "$KNOWN.new" && mv "$KNOWN.new" "$KNOWN" ;;
  *) echo "usage: devices.sh tick|report|block <mac>|unblock <mac>|limit <mac> <kBps>|forget <mac>"; exit 2 ;;
esac
