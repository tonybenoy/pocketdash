# Runs as root. Makes this phone a Bluetooth speaker (A2DP sink).
#   btspeaker.sh apply <patched-Bluetooth.apk>   enable the profiles + overlay the patched Bluetooth app (idempotent)
#   btspeaker.sh off                              undo it
# The patched apk is the installed Bluetooth.apk with bool a2dp_sink_automatically_request_audio_focus=true;
# without that the sink pauses the source a second after it starts and stays silent.
BB=/data/adb/magisk/busybox
RP=$(command -v resetprop || echo /data/adb/magisk/resetprop)
DST=/data/local/tmp/Bluetooth.patched.apk
ORIG=$(ls /apex/com.android.bt/app/Bluetooth@*/Bluetooth.apk 2>/dev/null | head -1)
ZYG=$(pidof zygote64)

bt_restart() { cmd bluetooth_manager disable >/dev/null 2>&1; sleep 4; cmd bluetooth_manager enable >/dev/null 2>&1; sleep 15; }
bt_on()      { dumpsys bluetooth_manager 2>/dev/null | grep -q '^  state: ON'; }
# true if the patched file is what a given mount namespace sees at the Bluetooth app's path (the global `mount` list
# cannot tell: it shows every layer, and the layers pile up if you mount again)
mounted_in() { $BB nsenter -t "$1" -m cmp -s "$DST" "$ORIG" 2>/dev/null; }
# remove every stacked layer, not just the top one
unmount()    { for pid in $ZYG 1; do i=0; while mounted_in $pid && [ $i -lt 10 ]; do $BB nsenter -t $pid -m umount "$ORIG" 2>/dev/null; i=$((i+1)); done; done; }

case "$1" in
  apply)
    $RP bluetooth.profile.a2dp.sink.enabled true
    $RP bluetooth.profile.avrcp.controller.enabled true
    need=0
    dumpsys bluetooth_manager | grep -q 'A2DP Sink State: Enabled' || need=1
    dumpsys bluetooth_manager | grep -q '^Profile: AvrcpControllerService' || need=1
    missing=0
    for pid in 1 $ZYG; do mounted_in $pid || missing=1; done
    if [ "$missing" = 1 ]; then
      [ -f "$2" ] && [ -n "$ORIG" ] || { echo "no patched apk / original not found"; exit 1; }
      cp "$2" "$DST" && chmod 644 "$DST" || { echo "copy failed"; exit 1; }
      # must carry the original's SELinux label or the Bluetooth app cannot open it and crash-loops
      chcon "$(ls -Z /apex/com.android.bt/apex_manifest.pb | awk '{print $1}')" "$DST" || { echo "relabel failed"; exit 1; }
      for pid in 1 $ZYG; do mounted_in $pid || $BB nsenter -t $pid -m mount --bind "$DST" "$ORIG"; done
      need=1
    fi
    if [ "$need" = 1 ]; then
      bt_restart
      if ! bt_on; then echo "bluetooth did not come back, rolled back"; unmount; bt_restart; exit 1; fi
    fi
    echo ok ;;
  off)
    unmount
    $RP --delete bluetooth.profile.a2dp.sink.enabled
    $RP --delete bluetooth.profile.avrcp.controller.enabled
    bt_restart
    echo off ;;
  *) echo "usage: btspeaker.sh apply <apk> | off"; exit 2 ;;
esac
