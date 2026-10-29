# Runs as root inside one long-lived su process. Every 2s prints interface byte counters
# ("net=<rx> <tx>"); every 10s prints a full hotspot snapshot between probe_begin/probe_end.
# The AP interface name moves between wlan0/wlan2, so it is looked up with `iw` (ground truth;
# the wifi dump keeps stale entries from earlier AP sessions).
BB=/data/adb/magisk/busybox; [ -x "$BB" ] || BB=busybox
LEASES=/data/local/tmp/udh""cpd.leases   # (split on purpose: this script's own text must never match an engine process check)
LT=/data/local/tmp/.dash_leases
BTD=/data/local/tmp/.btdump
BTC=/data/misc/bluedroid/bt_config.conf
iface=
n=0
while true; do
  if [ $((n % 5)) -eq 0 ]; then
    iface=$(iw dev 2>/dev/null | awk '/Interface/{i=$2} /type AP/{print i; exit}')
    ssid=; freq=
    if [ -n "$iface" ]; then
      info=$(iw dev "$iface" info 2>/dev/null)
      ssid=$(echo "$info" | sed -n 's/^[[:space:]]*ssid //p' | head -1)
      freq=$(echo "$info" | sed -n 's/.*channel [0-9]* (\([0-9]*\) MHz).*/\1/p' | head -1)
    fi
    ip=
    [ -n "$iface" ] && ip=$(ip -4 addr show "$iface" 2>/dev/null | sed -n 's/.*inet \([0-9.]*\).*/\1/p' | head -1)
    echo probe_begin
    echo "iface=$iface"; echo "ssid=$ssid"; echo "freq=$freq"; echo "ip=$ip"
    if [ -n "$ip" ]; then
      pfx=${ip%.*}.
      : > "$LT"
      [ -f "$LEASES" ] && "$BB" dumpleases -f "$LEASES" > "$LT" 2>/dev/null
      # leases rows: MAC IP HOST EXPIRES (host column is blank for some devices -> 3 fields)
      awk -v i="$iface" -v p="$pfx" '
        NR==FNR { if ($1 ~ /^[0-9a-f][0-9a-f]:/ && NF>=4) h[$1]=$3; next }
        $6==i && $3=="0x2" && index($1,p)==1 && !s[$4]++ { print "client=" $1 "|" $4 "|" h[$4] }' "$LT" /proc/net/arp
    fi
    echo probe_end
  fi
  if [ $((n % 2)) -eq 0 ]; then
    # Bluetooth speaker: state of the receiver, who is connected, what is playing
    dumpsys bluetooth_manager > "$BTD" 2>/dev/null
    echo bt_begin
    echo "bt.sink=$(grep -c 'A2DP Sink State: Enabled' "$BTD")"
    # judged in init's mount namespace: this long-lived shell keeps the snapshot of mounts it started with
    ORIGAPK=$(ls /apex/com.android.bt/app/Bluetooth@*/Bluetooth.apk 2>/dev/null | head -1); ov=0
    PATCHED=/data/local/tmp/Bluetooth.patched.apk
    [ -f "$PATCHED" ] && [ -n "$ORIGAPK" ] && [ "$("$BB" nsenter -t 1 -m stat -c '%i:%s' "$ORIGAPK" 2>/dev/null)" = "$(stat -c '%i:%s' "$PATCHED")" ] && ov=1
    echo "bt.overlay=$ov"
    echo "bt.boot=$([ -f /data/adb/dashboard/autostart ] && echo 1 || echo 0)"
    echo "bt.name=$(sed -n 's/^  Name: //p' "$BTD" | head -1)"
    suffix=$(sed -n 's/.*mDevice: XX:XX:XX:XX:\([0-9A-Fa-f:]*\) name=A2dpSinkStateMachine state=Connected.*/\1/p' "$BTD" | head -1)
    if [ -n "$suffix" ]; then
      echo "bt.connected=1"
      # the dump masks the first bytes of the address, so match the device in bt_config.conf by its last two bytes
      echo "bt.device=$(awk -v s=":$(echo "$suffix" | tr 'A-F' 'a-f')" '/^\[/ {ok = (tolower($0) ~ (s "\\]$"))} ok && /^Name = / {sub(/^Name = /,""); print; exit}' "$BTC" 2>/dev/null)"
      addr=$(grep -m1 '(Addressed)' "$BTD")
      echo "bt.title=$(echo "$addr" | sed -n 's/.*mTitle=\(.*\) mPlayingTime=.*/\1/p')"
      echo "bt.player=$(echo "$addr" | sed -n 's/.*AvrcpPlayer id=[0-9]* name=\(.*\) track=.*/\1/p')"
      echo "bt.state=$(grep -m1 -o 'playbackState=PlaybackState {state=STATE_[A-Z]*' "$BTD" | sed 's/.*STATE_//')"
    else
      echo "bt.connected=0"
    fi
    echo bt_end
  fi
  if [ $((n % 5)) -eq 0 ]; then
    # engine services, checked with the engine's own definitions (lib.sh)
    echo svc_begin
    L=/data/adb/dashboard/engine/lib.sh; [ -f "$L" ] || L=/data/adb/modules/hotspot_smb/lib.sh
    ( . "$L" >/dev/null 2>&1
      for s in $SERVICES; do
        if svc_enabled "$s"; then svc_up "$s" && echo "svc.$s=1" || echo "svc.$s=0"; else echo "svc.$s=-"; fi
      done )
    ps -A -o ARGS 2>/dev/null | grep -q '[w]atchdog.sh' && echo svc.watchdog=1 || echo svc.watchdog=0
    ps -A -o ARGS 2>/dev/null | grep -q '[s]ms.sh' && echo svc.sms=1 || echo svc.sms=0
    [ -e /data/adb/hotspot_smb/watchdog.off ] && echo flag.wd_off=1 || echo flag.wd_off=0
    [ -e /data/adb/hotspot_smb/hotspot.off ] && echo flag.hs_off=1 || echo flag.hs_off=0
    [ -f /data/adb/dashboard/engine_takeover ] && echo flag.engine=1 || echo flag.engine=0
    [ -f /data/adb/dashboard/engine/battery.sh ] && sh /data/adb/dashboard/engine/battery.sh report 2>/dev/null
    echo svc_end
  fi
  if [ $((n % 5)) -eq 0 ] && [ -f /data/adb/dashboard/engine/devices.sh ]; then
    # per-device state, usage and controls (one dev= line per device)
    echo dev_begin
    sh /data/adb/dashboard/engine/devices.sh report "$iface" 2>/dev/null
    echo dev_end
  fi
  [ -n "$iface" ] && awk -v i="$iface:" '$1==i {print "net=" $2 " " $10}' /proc/net/dev
  n=$((n+1))
  sleep 2
done
