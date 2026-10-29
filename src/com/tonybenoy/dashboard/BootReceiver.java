package com.tonybenoy.dashboard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Starts things at boot, driven by marker files under /data/adb/dashboard (readable before the first unlock).
 *  - LOCKED_BOOT_COMPLETED (direct boot, i.e. before anyone unlocks): if engine_takeover exists, run the hotspot_smb
 *    engine's boot sequence (hotspot, subnet/DHCP/NAT first, the rest as soon as storage unlocks) in init's mount
 *    namespace, exactly where Magisk would have run it.
 *  - BOOT_COMPLETED (after the first unlock): if autostart exists, keep the screen on while plugged in and open the dashboard.
 */
public class BootReceiver extends BroadcastReceiver {
    static final String DIR = "/data/adb/dashboard";

    @Override
    public void onReceive(Context c, Intent i) {
        final Context ctx = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        final String action = i.getAction();
        if (action == null) return;
        final PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                String engine = ServiceCtl.ENGINE;
                if (Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
                    String problem = Engine.deploy(ctx);
                    if (problem != null) {
                        // never leave the hotspot down because of a deploy problem: fall back to the old module's copy
                        engine = Engine.MODULE;
                    }
                }
                Root.su(script(action, engine), 25);
            } catch (Exception ignored) {
            } finally {
                pr.finish();
            }
        }, "boot-receiver").start();
    }

    static String script(String action, String engine) {
        if (Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            return "[ -f " + DIR + "/engine_takeover ] || exit 0\n"
                + "mkdir /dev/.dash_engine 2>/dev/null || exit 0\n"                    // once per boot (tmpfs, cleared on reboot)
                + "BB=/data/adb/magisk/busybox\n"
                + "echo \"$(date '+%F %T') dashboard: starting engine at boot\" >> /data/local/tmp/hotspot_smb.log\n"
                + "$BB setsid nohup $BB nsenter -t 1 -m sh " + engine + "/service.sh >/dev/null 2>&1 </dev/null &\n"
                + "echo started\n";
        }
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            return "[ -f " + DIR + "/autostart ] || exit 0\n"
                + "settings put global stay_on_while_plugged_in 7\n"
                + "dumpsys deviceidle whitelist +com.tonybenoy.dashboard >/dev/null 2>&1\n"
                + "am start -n com.tonybenoy.dashboard/.MainActivity >/dev/null 2>&1\n";
        }
        return "exit 0\n";
    }
}
