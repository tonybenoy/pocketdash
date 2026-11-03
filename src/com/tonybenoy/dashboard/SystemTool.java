package com.tonybenoy.dashboard;

import android.content.Context;
import android.os.Looper;

import java.lang.reflect.Method;

/**
 * Tiny helper that runs as root through app_process (outside the app's own sandbox) to call a hidden system API.
 *
 *   SystemTool netpolicy <uid>    clear a "no network" policy (POLICY_REJECT_ALL) that Android sometimes leaves on a
 *                                 freshly reinstalled app, which silently blocks weather, upstream checks and so on.
 */
public class SystemTool {
    public static void main(String[] args) throws Exception {
        if (args.length >= 1 && args[0].equals("btpair")) {
            btPair(args.length > 1 ? Integer.parseInt(args[1]) : 120);
            return;
        }
        if (args.length >= 1 && args[0].equals("btconnect")) {
            btConnect();
            return;
        }
        if (args.length < 2 || !args[0].equals("netpolicy")) {
            System.out.println("usage: SystemTool netpolicy <uid> | btconnect");
            System.exit(2);
        }
        Looper.prepareMainLooper();
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        Context ctx = (Context) at.getMethod("getSystemContext").invoke(thread);
        Object np = ctx.getSystemService("netpolicy");
        int uid = Integer.parseInt(args[1]);
        Method get = np.getClass().getMethod("getUidPolicy", int.class);
        System.out.println("before=" + get.invoke(np, uid));
        np.getClass().getMethod("setUidPolicy", int.class, int.class).invoke(np, uid, 0);
        System.out.println("after=" + get.invoke(np, uid));
        System.exit(0);
    }

    /**
     * Ask the Bluetooth stack to connect the A2DP sink to every paired device that can be an audio source
     * (phones that stream to us). Prints one line per attempt. Needs root: connect() is privileged.
     */
    private static void btConnect() throws Exception {
        Looper.prepareMainLooper();
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        final Context ctx = (Context) at.getMethod("getSystemContext").invoke(thread);
        // an app gets this during start-up; a bare app_process does not, and the adapter would be null
        try { at.getMethod("initializeMainlineModules").invoke(null); } catch (Throwable t) { System.out.println("init: " + t); }
        android.bluetooth.BluetoothManager bm = (android.bluetooth.BluetoothManager) ctx.getSystemService("bluetooth");
        android.bluetooth.BluetoothAdapter a0 = bm == null ? null : bm.getAdapter();
        if (a0 == null) a0 = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
        final android.bluetooth.BluetoothAdapter ad = a0;
        if (ad == null) { System.out.println("no adapter"); System.exit(1); }
        final Looper main = Looper.myLooper();
        final boolean[] done = {false};
        android.bluetooth.BluetoothProfile.ServiceListener l = new android.bluetooth.BluetoothProfile.ServiceListener() {
            @Override public void onServiceConnected(int profile, android.bluetooth.BluetoothProfile proxy) {
                try {
                    Method connect = proxy.getClass().getMethod("connect", android.bluetooth.BluetoothDevice.class);
                    android.os.ParcelUuid src = android.os.ParcelUuid.fromString("0000110a-0000-1000-8000-00805f9b34fb");
                    int tried = 0;
                    for (android.bluetooth.BluetoothDevice d : ad.getBondedDevices()) {
                        android.os.ParcelUuid[] u = d.getUuids();
                        boolean source = false;
                        if (u != null) for (android.os.ParcelUuid x : u) if (src.equals(x)) source = true;
                        if (!source) continue;
                        if (proxy.getConnectionState(d) != android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) continue;
                        System.out.println("connect " + d.getName() + " -> " + connect.invoke(proxy, d));
                        tried++;
                    }
                    if (tried == 0) System.out.println("nothing to connect");
                } catch (Throwable t) {
                    Throwable c = t.getCause() != null ? t.getCause() : t;
                    System.out.println("error: " + c);
                }
                synchronized (done) { done[0] = true; }
                main.quit();
            }
            @Override public void onServiceDisconnected(int profile) { }
        };
        // 11 = BluetoothProfile.A2DP_SINK (hidden constant)
        if (!ad.getProfileProxy(ctx, l, 11)) { System.out.println("no sink profile"); System.exit(1); }
        new android.os.Handler(main).postDelayed(main::quit, 10000);
        Looper.loop();
        System.exit(done[0] ? 0 : 1);
    }

    private static Context sysContext() throws Exception {
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        Context ctx = (Context) at.getMethod("getSystemContext").invoke(thread);
        // an app gets this during start-up; a bare app_process does not, and the Bluetooth adapter would be null
        try { at.getMethod("initializeMainlineModules").invoke(null); } catch (Throwable ignored) { }
        return ctx;
    }

    private static void scanMode(android.bluetooth.BluetoothAdapter ad, int mode) throws Exception {
        ad.getClass().getMethod("setScanMode", int.class).invoke(ad, mode);
    }

    /**
     * Pairing mode: visible to other phones for <seconds>, and any pairing request that arrives is confirmed here
     * (no code to compare, no dialog to unlock the phone for). Always switches discoverability off again.
     * Prints "paired <name>" for each device that bonds.
     */
    private static void btPair(final int seconds) throws Exception {
        Looper.prepareMainLooper();
        final Context ctx = sysContext();
        android.bluetooth.BluetoothManager bm = (android.bluetooth.BluetoothManager) ctx.getSystemService("bluetooth");
        final android.bluetooth.BluetoothAdapter ad = bm.getAdapter();
        if (ad == null) { System.out.println("no adapter"); System.exit(1); }
        final Looper main = Looper.myLooper();
        final android.os.Handler h = new android.os.Handler(main);

        final android.content.BroadcastReceiver rx = new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context c, android.content.Intent i) {
                try {
                    android.bluetooth.BluetoothDevice d = i.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE);
                    if (d == null) return;
                    if (android.bluetooth.BluetoothDevice.ACTION_PAIRING_REQUEST.equals(i.getAction())) {
                        int variant = i.getIntExtra("android.bluetooth.device.extra.PAIRING_VARIANT", -1);
                        System.out.println("pairing request from " + d.getName() + " variant=" + variant);
                        if (variant == 0) {          // PIN: use the common default
                            Method sp = d.getClass().getMethod("setPin", byte[].class);
                            sp.invoke(d, (Object) "0000".getBytes());
                        } else {                     // passkey confirmation / consent: just say yes
                            d.getClass().getMethod("setPairingConfirmation", boolean.class).invoke(d, true);
                        }
                        try { abortBroadcast(); } catch (Throwable ignored) { }   // no system dialog on the screen
                    } else if (android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(i.getAction())
                            && i.getIntExtra(android.bluetooth.BluetoothDevice.EXTRA_BOND_STATE, 0)
                                == android.bluetooth.BluetoothDevice.BOND_BONDED) {
                        System.out.println("paired " + d.getName());
                        h.postDelayed(main::quit, 1500);   // done: give the stack a moment, then stop
                    }
                } catch (Throwable t) {
                    Throwable c2 = t.getCause() != null ? t.getCause() : t;
                    System.out.println("error: " + c2);
                }
            }
        };
        android.content.IntentFilter f = new android.content.IntentFilter(android.bluetooth.BluetoothDevice.ACTION_PAIRING_REQUEST);
        f.addAction(android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        f.setPriority(android.content.IntentFilter.SYSTEM_HIGH_PRIORITY);
        ctx.registerReceiver(rx, f, null, h);

        try {
            ad.getClass().getMethod("setDiscoverableTimeout", java.time.Duration.class)
                .invoke(ad, java.time.Duration.ofSeconds(seconds));
        } catch (Throwable t) { System.out.println("timeout: " + t); }
        scanMode(ad, android.bluetooth.BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE);
        System.out.println("discoverable for " + seconds + " s, mode=" + ad.getScanMode());
        h.postDelayed(main::quit, seconds * 1000L);
        try {
            Looper.loop();
        } finally {
            try { ctx.unregisterReceiver(rx); } catch (Throwable ignored) { }
            try { scanMode(ad, android.bluetooth.BluetoothAdapter.SCAN_MODE_CONNECTABLE); } catch (Throwable ignored) { }
            System.out.println("done, mode=" + ad.getScanMode());
        }
        System.exit(0);
    }
}
