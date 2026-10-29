#!/system/bin/sh
# Battery protection for a phone that lives on a charger (charging itself is done by acc, which is already installed).
#   battery.sh report        bat.* lines for the app
#   battery.sh apply         push the BAT_* values from config.sh into acc (only keys that are set; unset = leave acc alone)
#   battery.sh enforce       same, but only touches acc when its settings differ (acc resets itself at boot)
#   battery.sh full [pct]    charge once to pct (default 100); acc's normal limit applies again afterwards
#
# config.sh keys (all optional):
#   BAT_PAUSE          stop charging at this %          (30-100)
#   BAT_RESUME         start charging again below this % (20 up to BAT_PAUSE-1)
#   BAT_MAX_TEMP       pause charging above this °C      (35-60)
#   BAT_SHUTDOWN_TEMP  power the phone off above this °C (45-70, above BAT_MAX_TEMP)
MODDIR=$(cd "$(dirname "$0")" && pwd)
. "$MODDIR/lib.sh" >/dev/null 2>&1
PS=/sys/class/power_supply/battery

accget() { acc -s 2>/dev/null | sed -n "s/^$1=//p" | head -1; }
isnum()  { case "$1" in ''|*[!0-9]*) return 1 ;; esac; return 0; }
rd()     { cat "$PS/$1" 2>/dev/null; }

apply() { # $1 = enforce|apply
  rc=0
  # make sure the charging daemon is alive
  ps -A -o ARGS 2>/dev/null | grep -q '[a]ccd.sh' || accd >/dev/null 2>&1

  if [ -n "$BAT_PAUSE" ] || [ -n "$BAT_RESUME" ]; then
    p=${BAT_PAUSE:-$(accget pause_capacity)}; r=${BAT_RESUME:-$(accget resume_capacity)}
    if isnum "$p" && isnum "$r" && [ "$p" -ge 30 ] && [ "$p" -le 100 ] && [ "$r" -ge 20 ] && [ "$r" -lt "$p" ]; then
      if [ "$(accget pause_capacity)" != "$p" ] || [ "$(accget resume_capacity)" != "$r" ]; then
        acc -s pause_capacity="$p" resume_capacity="$r" >/dev/null 2>&1 && echo "charge limit set: resume $r%, pause $p%"
      fi
    else echo "ignored invalid charge limit (resume=$r pause=$p)"; rc=2; fi
  fi

  if [ -n "$BAT_MAX_TEMP" ]; then
    if isnum "$BAT_MAX_TEMP" && [ "$BAT_MAX_TEMP" -ge 35 ] && [ "$BAT_MAX_TEMP" -le 60 ]; then
      if [ "$(accget max_temp)" != "$BAT_MAX_TEMP" ]; then
        # cooling starts 5 degrees earlier than the hard pause
        acc -s max_temp="$BAT_MAX_TEMP" cooldown_temp=$((BAT_MAX_TEMP - 5)) >/dev/null 2>&1 && echo "max temperature set: $BAT_MAX_TEMP C"
      fi
    else echo "ignored invalid BAT_MAX_TEMP=$BAT_MAX_TEMP"; rc=2; fi
  fi

  if [ -n "$BAT_SHUTDOWN_TEMP" ]; then
    mt=${BAT_MAX_TEMP:-$(accget max_temp)}
    if isnum "$BAT_SHUTDOWN_TEMP" && [ "$BAT_SHUTDOWN_TEMP" -ge 45 ] && [ "$BAT_SHUTDOWN_TEMP" -le 70 ] && { ! isnum "$mt" || [ "$BAT_SHUTDOWN_TEMP" -gt "$mt" ]; }; then
      [ "$(accget shutdown_temp)" = "$BAT_SHUTDOWN_TEMP" ] || { acc -s shutdown_temp="$BAT_SHUTDOWN_TEMP" >/dev/null 2>&1 && echo "shutdown temperature set: $BAT_SHUTDOWN_TEMP C"; }
    else echo "ignored invalid BAT_SHUTDOWN_TEMP=$BAT_SHUTDOWN_TEMP"; rc=2; fi
  fi
  return $rc
}

report() {
  lvl=$(rd capacity); st=$(rd status); t=$(rd temp); v=$(rd voltage_now); i=$(rd current_now)
  full=$(rd charge_full); design=$(rd charge_full_design)
  plugged=0; dumpsys battery 2>/dev/null | grep -qE '(AC|USB|Wireless) powered: true' && plugged=1
  health=0; isnum "$full" && isnum "$design" && [ "$design" -gt 0 ] && health=$(( full * 100 / design ))
  echo "bat.level=$lvl"
  echo "bat.status=$st"
  echo "bat.plugged=$plugged"
  echo "bat.temp=$t"                                  # tenths of a degree
  echo "bat.mv=$(( ${v:-0} / 1000 ))"
  echo "bat.ma=$(( ${i:-0} / 1000 ))"                 # negative = discharging
  echo "bat.health=$(rd health)"
  echo "bat.health_pct=$health"
  echo "bat.cycles=$(rd cycle_count)"
  echo "bat.pause=$(accget pause_capacity)"
  echo "bat.resume=$(accget resume_capacity)"
  echo "bat.max_temp=$(accget max_temp)"
  echo "bat.shutdown_temp=$(accget shutdown_temp)"
  echo "bat.acc=$(ps -A -o ARGS 2>/dev/null | grep -c '[a]ccd.sh')"
}

case "$1" in
  report)  report ;;
  apply)   apply apply ;;
  enforce) apply enforce >/dev/null 2>&1 ;;
  full)    p=${2:-100}; isnum "$p" && [ "$p" -ge 30 ] && [ "$p" -le 100 ] || { echo "bad percentage"; exit 2; }
           # acc -f charges without the pause limit until p% is reached, then goes back to normal
           nohup acc -f "$p" >/dev/null 2>&1 < /dev/null &
           echo "charging once to $p%" ;;
  *) echo "usage: battery.sh report|apply|enforce|full [pct]"; exit 2 ;;
esac
