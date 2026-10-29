package com.tonybenoy.dashboard;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/**
 * Keeps the process alive and brings the dashboard back to the front whenever the screen
 * turns on. The launch goes through su because apps can't start activities from the background.
 */
public class KeepAliveService extends Service {
    private static final String CHANNEL = "dashboard";
    private static final long CHECK_MS = 15000;
    private static final int IDLE_LOCK_MS = 120000;   // unlocked + untouched this long -> lock again

    private final Handler h = new Handler(Looper.getMainLooper());
    private KeyguardManager km;
    private PowerManager pm;
    private volatile boolean locking;

    /** New devices noticed by the engine (devices.sh) show up here; turn each into a notification. */
    private long lastHot, lastLow;
    private boolean hotNow;

    private void alert(int id, String title, String text, boolean ongoingCancel) {
        SharedPreferences prefs = getSharedPreferences("dashboard", MODE_PRIVATE);
        if (!prefs.getBoolean("notify_battery", true)) return;
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, "battery").setSmallIcon(android.R.drawable.ic_lock_idle_low_battery)
            .setContentTitle(title).setContentText(text).setContentIntent(open).setAutoCancel(true).build();
        getSystemService(NotificationManager.class).notify(id, n);
    }

    private int batteryPercent() {
        Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return b == null ? -1 : b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / Math.max(1, b.getIntExtra(BatteryManager.EXTRA_SCALE, 100));
    }

    /** Power cut / restored: this phone is a hotspot that is supposed to stay on a charger. */
    private final BroadcastReceiver power = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_POWER_DISCONNECTED.equals(i.getAction())) {
                alert(2003, "Power lost", "Running on battery at " + batteryPercent() + "%. The hotspot stays up until the battery runs out.", false);
            } else {
                getSystemService(NotificationManager.class).cancel(2003);
                alert(2004, "Power restored", "Charging again (" + batteryPercent() + "%).", false);
            }
        }
    };

    /** Hot battery and low battery on battery power. */
    private final Runnable batteryCheck = new Runnable() {
        @Override
        public void run() {
            Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b != null) {
                float t = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f;
                int plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
                long now = System.currentTimeMillis();
                float limit = getSharedPreferences("dashboard", MODE_PRIVATE).getFloat("bat_alert_temp", 43f);
                if (t >= limit && now - lastHot > 10 * 60 * 1000L) {
                    lastHot = now;
                    alert(2001, "Battery is hot", String.format("%.1f\u00B0C. Charging pauses at the limit set in Settings, and the phone shuts down if it gets much hotter.", t), false);
                }
                int pct = batteryPercent();
                if (plugged == 0 && pct >= 0 && pct <= 20 && now - lastLow > 30 * 60 * 1000L) {
                    lastLow = now;
                    alert(2002, "Battery low", pct + "% and not charging. The hotspot will stop when it runs out.", false);
                }
            }
            h.postDelayed(this, 30000);
        }
    };

    private final Runnable newDevices = new Runnable() {
        @Override
        public void run() {
            new Thread(() -> {
                try {
                    String out = Root.su("F=/data/adb/hotspot_smb/new_devices; if [ -s $F ]; then mv $F $F.read; cat $F.read; rm -f $F.read; fi", 15);
                    for (String line : out.split("\n")) {
                        String[] f = line.split("\t", -1);
                        if (f.length >= 5) notifyDevice(f[1], f[2], f[3], f[4]);
                    }
                } catch (Exception ignored) { }
            }, "new-devices").start();
            h.postDelayed(this, 30000);
        }
    };

    private void notifyDevice(String mac, String ip, String host, String action) {
        SharedPreferences prefs = getSharedPreferences("dashboard", MODE_PRIVATE);
        if (!prefs.getBoolean("notify_new", true)) return;
        String nick = prefs.getString("name_" + mac, "");
        String name = !nick.isEmpty() ? nick : !host.isEmpty() ? host : mac;
        boolean blocked = action.equals("blocked");
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, "devices")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(blocked ? "New device blocked" : "New device on your hotspot")
            .setContentText(name + (ip.isEmpty() ? "" : "  \u00B7  " + ip) + (blocked ? "  \u00B7  tap to review" : ""))
            .setContentIntent(open).setAutoCancel(true).build();
        getSystemService(NotificationManager.class).notify(mac.hashCode(), n);
    }

    /**
     * The screen never times out (stay-on-while-plugged), so a phone that was unlocked once would stay
     * unlocked forever. Lock it again after idle; the dashboard shows over the lock screen.
     */
    private final Runnable autoLock = new Runnable() {
        @Override
        public void run() {
            if (!locking && pm.isInteractive() && !km.isKeyguardLocked()) {
                locking = true;
                new Thread(() -> {
                    try {
                        new ProcessBuilder("su", "-c",
                            "idle=$(dumpsys power | sed -n 's/.*lastUserActivityTime=[0-9]* (\\([0-9]*\\) ms ago).*/\\1/p' | head -1)\n"
                          + "if [ -n \"$idle\" ] && [ \"$idle\" -ge " + IDLE_LOCK_MS + " ]; then\n"
                          + "  input keyevent KEYCODE_SLEEP; sleep 1; input keyevent KEYCODE_WAKEUP\n"
                          + "fi\n").start().waitFor();
                    } catch (Exception ignored) {
                    } finally {
                        locking = false;
                    }
                }, "auto-lock").start();
            }
            h.postDelayed(this, CHECK_MS);
        }
    };

    private final BroadcastReceiver screenOn = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            new Thread(() -> {
                try {
                    new ProcessBuilder("su", "-c",
                        "am start -n com.tonybenoy.dashboard/.MainActivity").start().waitFor();
                } catch (Exception ignored) { }
            }).start();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(
            new NotificationChannel(CHANNEL, "Dashboard", NotificationManager.IMPORTANCE_MIN));
        nm.createNotificationChannel(new NotificationChannel("devices", "New devices", NotificationManager.IMPORTANCE_DEFAULT));
        nm.createNotificationChannel(new NotificationChannel("battery", "Battery and power", NotificationManager.IMPORTANCE_HIGH));
        Notification n = new Notification.Builder(this, CHANNEL)
            .setContentTitle("Dashboard running")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
        registerReceiver(screenOn, new IntentFilter(Intent.ACTION_SCREEN_ON));
        km = getSystemService(KeyguardManager.class);
        pm = getSystemService(PowerManager.class);
        h.postDelayed(autoLock, CHECK_MS);
        h.postDelayed(newDevices, 5000);
        h.postDelayed(batteryCheck, 8000);
        IntentFilter pf = new IntentFilter(Intent.ACTION_POWER_CONNECTED);
        pf.addAction(Intent.ACTION_POWER_DISCONNECTED);
        registerReceiver(power, pf);
    }

    @Override
    public int onStartCommand(Intent i, int flags, int id) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        h.removeCallbacks(autoLock);
        h.removeCallbacks(newDevices);
        h.removeCallbacks(batteryCheck);
        unregisterReceiver(power);
        unregisterReceiver(screenOn);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }
}
