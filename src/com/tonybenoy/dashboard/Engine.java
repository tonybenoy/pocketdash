package com.tonybenoy.dashboard;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * The hotspot/NAS engine (formerly the hotspot_smb Magisk module) shipped inside this app.
 *
 * deploy() puts the bundled scripts under /data/adb/dashboard/engine (root only), and is safe to run at any time:
 *  - scripts and the legacy web panel are refreshed only when the app's bundled version changes;
 *  - config.sh is NEVER created: it is copied from the old module once if it is missing, otherwise left alone, and
 *    if there is none the engine does not start;
 *  - the toolset (Samba, SSH, Syncthing, Transmission, nginx...) is copied from the old module when present, and only
 *    unpacked from the bundled tarball on a phone that never had it;
 *  - runtime state (SSH host keys, Syncthing identity, Samba/Transmission data, /data/adb/hotspot_smb) is never touched.
 * Works before the first unlock: assets live in the APK and staging uses device-protected storage.
 */
final class Engine {
    private Engine() { }

    static final String DIR = "/data/adb/dashboard/engine";
    static final String MODULE = "/data/adb/modules/hotspot_smb";

    /** @return null when the engine is ready to run, otherwise what is wrong */
    static synchronized String deploy(Context ctx) {
        try {
            Context de = ctx.isDeviceProtectedStorage() ? ctx : ctx.createDeviceProtectedStorageContext();
            AssetManager am = ctx.getAssets();
            String bundled = readAsset(am, "engine/VERSION").trim();
            String deployed = Root.su("cat " + DIR + "/.version 2>/dev/null", 10).trim();
            boolean scripts = !bundled.equals(deployed);

            File stage = new File(de.getFilesDir(), "engine-stage");
            if (scripts) {
                deleteTree(stage);
                copyAssetTree(am, "engine", stage, true);
            }
            boolean needPrefix = !"yes".equals(Root.su("[ -x " + DIR + "/prefix/bin/smbd ] && [ -x " + DIR + "/prefix/bin/sshd ] && echo yes", 10).trim());
            boolean moduleHasPrefix = "yes".equals(Root.su("[ -x " + MODULE + "/prefix/bin/smbd ] && echo yes", 10).trim());
            if (needPrefix && !moduleHasPrefix) {            // phone that never had the module: unpack the bundled toolset
                copyAsset(am, "engine/prefix.tar.gz", new File(stage, "prefix.tar.gz"));
            }

            StringBuilder sh = new StringBuilder();
            sh.append("set -e\nBB=/data/adb/magisk/busybox\nmkdir -p ").append(DIR).append("\n");
            if (scripts) {
                // atomic per file (copy aside, then rename over): a shell that is running a script keeps its old copy
                sh.append("rm -rf ").append(DIR).append("/.new; mkdir -p ").append(DIR).append("/.new\n");
                sh.append("$BB cp -r ").append(Root.q(stage.getPath())).append("/. ").append(DIR).append("/.new/ 2>&1\n");
                sh.append("( cd ").append(DIR).append("/.new && find . -type f | while read f; do mkdir -p \"../$(dirname \"$f\")\"; mv -f \"$f\" \"../$f\"; done )\n");
                sh.append("rm -rf ").append(DIR).append("/.new\n");
                sh.append("rm -f ").append(DIR).append("/prefix.tar.gz\n");
                sh.append("chmod 755 ").append(DIR).append("/*.sh ").append(DIR).append("/panel/www ").append(DIR)
                  .append("/panel/www/cgi-bin ").append(DIR).append("/panel/www/cgi-bin/* 2>/dev/null || true\n");
            }
            // config: copy once from the old module, never create
            sh.append("if [ ! -f ").append(DIR).append("/config.sh ]; then\n")
              .append("  if [ -f ").append(MODULE).append("/config.sh ]; then cp -p ").append(MODULE).append("/config.sh ")
              .append(DIR).append("/config.sh && chmod 644 ").append(DIR).append("/config.sh; fi\nfi\n");
            if (needPrefix) {
                sh.append("if [ -x ").append(MODULE).append("/prefix/bin/smbd ]; then\n")
                  .append("  rm -rf ").append(DIR).append("/prefix; $BB cp -a ").append(MODULE).append("/prefix ").append(DIR).append("/prefix\n")
                  .append("elif [ -f ").append(Root.q(stage.getPath())).append("/prefix.tar.gz ]; then\n")
                  .append("  mkdir -p ").append(DIR).append("/prefix && tar -xzf ").append(Root.q(stage.getPath()))
                  .append("/prefix.tar.gz -C ").append(DIR).append("/prefix && chmod -R 755 ").append(DIR).append("/prefix/bin ")
                  .append(DIR).append("/prefix/lib ").append(DIR).append("/prefix/libexec 2>/dev/null || true\nfi\n");
            }
            if (scripts) sh.append("echo ").append(bundled).append(" > ").append(DIR).append("/.version\n");
            sh.append("echo done\n");
            String out = Root.su(sh.toString(), 600);
            if (scripts) deleteTree(stage);
            if (!out.contains("done")) return "deploy failed: " + out.trim();

            if (!"yes".equals(Root.su("[ -f " + DIR + "/config.sh ] && echo yes", 10).trim()))
                return "no config.sh to migrate (looked in " + MODULE + "); the engine will not start without one";
            if (!"yes".equals(Root.su("[ -x " + DIR + "/prefix/bin/smbd ] && echo yes", 10).trim()))
                return "toolset (prefix) missing after deploy";
            return null;
        } catch (Exception e) {
            return "deploy error: " + e.getMessage();
        }
    }

    // ---- asset helpers

    private static String readAsset(AssetManager am, String name) throws Exception {
        try (InputStream in = am.open(name)) {
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return o.toString("UTF-8");
        }
    }

    private static void copyAsset(AssetManager am, String name, File to) throws Exception {
        to.getParentFile().mkdirs();
        try (InputStream in = am.open(name); FileOutputStream o = new FileOutputStream(to)) {
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
        }
    }

    /** Copies an asset directory, skipping the large toolset tarball unless asked for separately. */
    private static void copyAssetTree(AssetManager am, String path, File to, boolean skipTarball) throws Exception {
        String[] kids = am.list(path);
        if (kids == null || kids.length == 0) {                       // a file
            if (skipTarball && path.endsWith("prefix.tar.gz")) return;
            copyAsset(am, path, to);
            return;
        }
        to.mkdirs();
        for (String k : kids) copyAssetTree(am, path + "/" + k, new File(to, k), skipTarball);
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
