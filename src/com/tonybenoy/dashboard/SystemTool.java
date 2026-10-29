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
        if (args.length < 2 || !args[0].equals("netpolicy")) {
            System.out.println("usage: SystemTool netpolicy <uid>");
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
}
