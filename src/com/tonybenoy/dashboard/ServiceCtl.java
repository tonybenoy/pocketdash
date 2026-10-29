package com.tonybenoy.dashboard;

/**
 * Control of the hotspot_smb engine's services. Today the engine is the Magisk module; every call goes through its
 * own lib.sh (svc_restart is serialised and does the same as the module's watchdog), so behaviour is identical.
 */
final class ServiceCtl {
    private ServiceCtl() { }

    static final String ENGINE = Engine.DIR;
    static final String PERSIST = "/data/adb/hotspot_smb";

    /** key, label */
    static final String[][] SERVICES = {
        {"hotspot", "Hotspot"},
        {"network", "Fixed subnet · DHCP · NAT"},
        {"samba", "File sharing (SMB)"},
        {"transmission", "Torrents"},
        {"syncthing", "Syncthing"},
        {"ssh", "SSH"},
        {"unbound", "Local DNS"},
        {"proxy", "Web proxy (:80)"},
        {"panel", "Web panel (:8080)"},
        {"wg", "WireGuard"},
        {"watchdog", "Watchdog"},
        {"sms", "SMS commands"},
    };

    static String restart(String svc) throws Exception {
        switch (svc) {
            case "battery":
                return Root.su("sh " + ENGINE + "/battery.sh apply", 60);
            case "watchdog":
            case "sms":
                return Root.su("sh " + ENGINE + "/service.sh " + svc, 60);
            default:
                return Root.su("sh -c '. " + ENGINE + "/lib.sh; svc_restart " + svc + "'", 240);
        }
    }

    static String restartAll() throws Exception {
        return Root.su("sh -c '. " + ENGINE + "/lib.sh; for s in $SERVICES; do svc_enabled $s && svc_restart $s; done; "
            + "sh " + ENGINE + "/service.sh watchdog; sh " + ENGINE + "/service.sh sms'", 600);
    }

    static void setFlag(String name, boolean present) throws Exception {
        Root.su(present ? "mkdir -p " + PERSIST + "; touch " + PERSIST + "/" + name : "rm -f " + PERSIST + "/" + name, 10);
    }
}
