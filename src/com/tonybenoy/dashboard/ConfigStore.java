package com.tonybenoy.dashboard;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads and edits the engine's configuration (config.sh, plus sms.conf for the SMS keys, which takes precedence).
 * Only the lines that change are touched; comments and every other line stay as they are.
 */
final class ConfigStore {
    private ConfigStore() { }

    static final int TEXT = 0, PASSWORD = 1, NUMBER = 2, SWITCH = 3, CHOICE = 4, DIR = 5, FILE = 6;

    static final class Field {
        final String key, label, hint;
        final int type;
        final String[] choices;
        Field(String key, String label, int type, String hint, String... choices) {
            this.key = key; this.label = label; this.type = type; this.hint = hint; this.choices = choices;
        }
    }

    static final class Section {
        final String title, note, apply;      // apply = service to restart after saving ("" = none)
        final Field[] fields;
        Section(String title, String note, String apply, Field... fields) {
            this.title = title; this.note = note; this.apply = apply; this.fields = fields;
        }
    }

    static final Section[] SECTIONS = {
        new Section("Hotspot", "Name, password and band apply when the hotspot restarts, which drops connected devices for ~30 s.", "hotspot",
            new Field("HOTSPOT_SSID", "Network name (SSID)", TEXT, ""),
            new Field("HOTSPOT_PASS", "Wi-Fi password", PASSWORD, "WPA2, at least 8 characters"),
            new Field("HOTSPOT_BAND", "Default band", CHOICE, "The band switch on the dashboard overrides this", "5", "2"),
            new Field("HOTSPOT_IP", "Gateway address", TEXT, "Fixed address of the phone on the hotspot"),
            new Field("HOTSPOT_DHCP_START", "DHCP range start", NUMBER, "Last number of the first address handed out"),
            new Field("HOTSPOT_DHCP_END", "DHCP range end", NUMBER, "Last number of the last address handed out"),
            new Field("HOTSPOT_SHARE_INTERNET", "Share internet (NAT)", SWITCH, "")),
        new Section("File sharing (SMB)", "Windows, Mac and Linux can open these shares. Applies when file sharing restarts.", "samba",
            new Field("SHARE_NAME", "Share name", TEXT, "Shown as \\\\phone\\<name>"),
            new Field("SHARE_PATH", "Shared folder", DIR, "Folder on the phone that the share shows"),
            new Field("SMB_USER", "User name", TEXT, "Must be a user Android knows (root, shell)"),
            new Field("SMB_PASS", "Password", PASSWORD, ""),
            new Field("SMB_NETBIOS_NAME", "Computer name", TEXT, "How the phone appears on the network")),
        new Section("Torrents (Transmission)", "Web UI at http://<phone>:<port>. Applies when Transmission restarts.", "transmission",
            new Field("TR_PORT", "Web UI port", NUMBER, ""),
            new Field("TR_USER", "User name", TEXT, ""),
            new Field("TR_PASS", "Password", PASSWORD, ""),
            new Field("TR_DOWNLOAD_DIR", "Download folder", DIR, "Also shared over SMB as \u201Ctorrents\u201D")),
        new Section("Syncthing", "Syncthing keeps its own login once created, so a changed user or password only takes effect "
            + "if you reset its config; change those in its web UI.", "syncthing",
            new Field("ENABLE_SYNCTHING", "Run Syncthing", SWITCH, ""),
            new Field("ST_PORT", "Web UI port", NUMBER, ""),
            new Field("ST_USER", "User name (first setup)", TEXT, ""),
            new Field("ST_PASS", "Password (first setup)", PASSWORD, "")),
        new Section("SSH", "Key login only. Edit the allowed keys below.", "ssh",
            new Field("ENABLE_SSH", "Run SSH server", SWITCH, ""),
            new Field("SSH_PORT", "Port", NUMBER, "")),
        new Section("Devices", "Per-device blocking, speed limits and usage counting run in the engine. Blocking works by the device's "
            + "MAC address; a phone using a private (randomised) address looks like a new device if it changes that setting.", "",
            new Field("DEVICES_AUTOBLOCK", "Block new devices until I allow them", SWITCH, "Unknown devices get no network access; you get a notification")),
        new Section("Battery", "Protects the battery of a phone that lives on a charger. A lower range wears the battery less but leaves "
            + "less reserve for a power cut. Blank means acc's own value stays; nothing here is changed until you edit it.", "battery",
            new Field("BAT_PAUSE", "Stop charging at (%)", NUMBER, "30 to 100"),
            new Field("BAT_RESUME", "Start charging again below (%)", NUMBER, "At least 20 and below the stop value"),
            new Field("BAT_MAX_TEMP", "Pause charging above (\u00B0C)", NUMBER, "35 to 60"),
            new Field("BAT_SHUTDOWN_TEMP", "Shut the phone down above (\u00B0C)", NUMBER, "45 to 70, and above the pause temperature")),
        new Section("Alerts (SMS)", "Texts you when something goes down, and lets you text commands to the phone. "
            + "Saved to sms.conf, which takes precedence.", "sms",
            new Field("SMS_TO", "Send alerts to", TEXT, "Phone number(s), space separated"),
            new Field("SMS_ALLOWED", "Accept commands from", TEXT, "Phone number(s), defaults to the alert number"),
            new Field("SMS_PIN", "Command PIN", PASSWORD, "Every command must start with this"),
            new Field("SMS_NOTIFY_RECOVERY", "Also text when a service recovers", SWITCH, ""),
            new Field("SMS_MAX_PER_HOUR", "Max texts per hour", NUMBER, "Hard cap against runaway alerts")),
        new Section("Services", "Switching a service off keeps it from starting; it does not stop one that is already running. "
            + "Use the Services card to stop or restart.", "",
            new Field("ENABLE_WATCHDOG", "Watchdog (restarts dead services)", SWITCH, ""),
            new Field("ENABLE_PROXY", "Web proxy on port 80", SWITCH, ""),
            new Field("ENABLE_PANEL", "Legacy web panel", SWITCH, "Replaced by this app"),
            new Field("ENABLE_UNBOUND", "Local DNS (Unbound)", SWITCH, ""),
            new Field("ENABLE_WG", "WireGuard", SWITCH, ""),
            new Field("WG_CONF", "WireGuard config file", FILE, ""),
            new Field("BOOT_DELAY", "Wait after boot (seconds)", NUMBER, "")),
        new Section("Legacy web panel", "The :8080 page. Kept until the engine moves into the app.", "panel",
            new Field("PANEL_USER", "User name", TEXT, ""),
            new Field("PANEL_PASS", "Password", PASSWORD, ""),
            new Field("PANEL_PORT", "Port", NUMBER, "")),
    };

    /** What the engine uses when a key is not set in config.sh (the ${VAR:-default} values in service.sh / lib.sh). */
    static final Map<String, String> DEFAULTS = new HashMap<>();
    static {
        String[][] d = {{"HOTSPOT_DHCP_START", "100"}, {"HOTSPOT_DHCP_END", "200"}, {"HOTSPOT_SHARE_INTERNET", "1"},
            {"SMB_NETBIOS_NAME", "PHONE"}, {"ENABLE_PROXY", "1"}, {"ENABLE_PANEL", "1"}, {"ENABLE_UNBOUND", "0"},
            {"ENABLE_WATCHDOG", "1"}, {"ENABLE_SYNCTHING", "1"}, {"ENABLE_SSH", "1"}, {"ENABLE_WG", "0"}, {"SSH_PORT", "22"},
            {"SMS_MAX_PER_HOUR", "6"}, {"SMS_NOTIFY_RECOVERY", "1"}, {"TR_PORT", "9091"}, {"ST_PORT", "8384"},
            {"PANEL_PORT", "8080"}, {"HOTSPOT_BAND", "5"}, {"DEVICES_AUTOBLOCK", "0"}};
        for (String[] kv : d) DEFAULTS.put(kv[0], kv[1]);
    }

    /** Suggested values for a phone that has no configuration yet (never applied to an existing config). */
    static final Map<String, String> FRESH_DEFAULTS = new HashMap<>();
    static {
        String[][] d = {{"HOTSPOT_BAND", "5"}, {"HOTSPOT_IP", "192.168.43.1"}, {"SHARE_NAME", "share"}, {"SHARE_PATH", "/sdcard"},
            {"SMB_USER", "root"}, {"TR_USER", "admin"}, {"TR_DOWNLOAD_DIR", "/sdcard/Download/torrents"}, {"ST_USER", "admin"},
            {"PANEL_USER", "admin"}, {"WG_CONF", "/data/adb/hotspot_smb/wg0.conf"}, {"BOOT_DELAY", "1"}};
        for (String[] kv : d) FRESH_DEFAULTS.put(kv[0], kv[1]);
    }

    private static final String CONFIG = ServiceCtl.ENGINE + "/config.sh";
    private static final String SMS_CONF = ServiceCtl.PERSIST + "/sms.conf";
    static final String KEYS_FILE = ServiceCtl.PERSIST + "/authorized_keys";

    static boolean isSms(String key) { return key.startsWith("SMS_"); }

    /** Effective values: config.sh, then the optional /sdcard override, then sms.conf, exactly as the engine sources them. */
    static Map<String, String> read() throws Exception {
        StringBuilder keys = new StringBuilder();
        for (Section s : SECTIONS) for (Field f : s.fields) keys.append(f.key).append(' ');
        String out = Root.su("sh -c '. " + CONFIG + " >/dev/null 2>&1; [ -f /sdcard/hotspot_smb.conf ] && . /sdcard/hotspot_smb.conf; "
            + "[ -f " + SMS_CONF + " ] && . " + SMS_CONF + "; for k in " + keys + "; do eval \"v=\\${$k}\"; echo \"$k=$v\"; done; "
            + "for pair in BAT_PAUSE:pause_capacity BAT_RESUME:resume_capacity BAT_MAX_TEMP:max_temp BAT_SHUTDOWN_TEMP:shutdown_temp; do "
            + "k=${pair%%:*}; a=${pair##*:}; eval \"v=\\${$k}\"; [ -z \"$v\" ] && v=$(acc -s 2>/dev/null | sed -n \"s/^$a=//p\" | head -1); echo \"$k=$v\"; done; "
            + "echo \"_override=$([ -f /sdcard/hotspot_smb.conf ] && echo 1 || echo 0)\"'", 20);
        Map<String, String> m = new HashMap<>();
        for (String line : out.split("\n")) {
            int i = line.indexOf('=');
            if (i > 0) m.put(line.substring(0, i), line.substring(i + 1));
        }
        return m;
    }

    /** Writes the changed keys; returns the set of services that should be restarted. */
    static Set<String> save(File scratch, Map<String, String> changes) throws Exception {
        Map<String, String> cfg = new LinkedHashMap<>(), sms = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : changes.entrySet()) {
            if (e.getValue().indexOf('\n') >= 0 || e.getValue().indexOf('\r') >= 0) throw new IllegalArgumentException(e.getKey() + ": no line breaks");
            (isSms(e.getKey()) ? sms : cfg).put(e.getKey(), e.getValue());
        }
        if (!cfg.isEmpty()) writeFile(scratch, CONFIG, cfg, "644");
        if (!sms.isEmpty()) writeFile(scratch, SMS_CONF, sms, "600");
        Set<String> apply = new LinkedHashSet<>();
        for (Section s : SECTIONS) {
            if (s.apply.isEmpty()) continue;
            for (Field f : s.fields) if (changes.containsKey(f.key)) { apply.add(s.apply); break; }
        }
        return apply;
    }

    private static void writeFile(File scratch, String target, Map<String, String> changes, String mode) throws Exception {
        boolean exists = "yes".equals(Root.su("[ -f " + Root.q(target) + " ] && echo yes", 10).trim());
        if (!exists && target.equals(CONFIG)) throw new IllegalStateException("config.sh not found; refusing to create a new one");
        String text = exists ? Root.su("cat " + Root.q(target), 10) : "";
        String edited = apply(text, changes);
        File tmp = new File(scratch, "cfg.tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(edited.getBytes("UTF-8")); }
        // never install a config the engine's shell cannot parse: a broken config.sh would stop the hotspot at its next restart
        if (!Root.su("sh -n " + Root.q(tmp.getPath()) + " && echo SYNTAX_OK", 10).contains("SYNTAX_OK")) {
            tmp.delete();
            throw new IllegalStateException("refusing to write: the edited config has a syntax error");
        }
        // keep one untouched backup of the original, then overwrite in place (keeps owner and mode)
        Root.su("[ -f " + Root.q(target) + " ] && cp -n " + Root.q(target) + " " + Root.q(target + ".dashboard-bak") + "; "
            + "mkdir -p " + Root.q(new File(target).getParent()) + "; cat " + Root.q(tmp.getPath()) + " > " + Root.q(target)
            + "; chmod " + mode + " " + Root.q(target), 20);
        tmp.delete();
    }

    /** Pure text edit, public for testing: replace KEY=... lines (keeping a trailing comment) or append. */
    static String apply(String text, Map<String, String> changes) {
        List<String> lines = new ArrayList<>();
        for (String l : text.split("\n", -1)) lines.add(l);
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) lines.remove(lines.size() - 1);
        for (Map.Entry<String, String> e : changes.entrySet()) {
            Pattern p = Pattern.compile("^(\\s*)" + Pattern.quote(e.getKey())
                + "=(\"(?:[^\"\\\\]|\\\\.)*\"|'[^']*'|[^\\s#]*)(\\s*#.*)?$");
            boolean found = false;
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = p.matcher(lines.get(i));
                if (m.matches()) {
                    lines.set(i, m.group(1) + e.getKey() + "=" + quote(e.getValue()) + (m.group(3) == null ? "" : m.group(3)));
                    found = true;
                    break;
                }
            }
            if (!found) lines.add(e.getKey() + "=" + quote(e.getValue()));
        }
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        return sb.toString();
    }

    /** Double-quoted shell string that cannot be broken out of. */
    static String quote(String v) {
        return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$").replace("`", "\\`") + "\"";
    }

    static String readKeys() throws Exception {
        return Root.su("[ -f " + KEYS_FILE + " ] && cat " + KEYS_FILE, 10);
    }

    static void saveKeys(File scratch, String text) throws Exception {
        File tmp = new File(scratch, "keys.tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(text.getBytes("UTF-8")); }
        Root.su("mkdir -p " + ServiceCtl.PERSIST + "; cat " + Root.q(tmp.getPath()) + " > " + KEYS_FILE + "; chmod 600 " + KEYS_FILE, 10);
        tmp.delete();
    }

    // ---------------------------------------------------------------- new device, backup, restore

    static boolean configExists() throws Exception {
        return "yes".equals(Root.su("[ -f " + Root.q(CONFIG) + " ] && echo yes", 10).trim());
    }

    /** Which required values are missing for a brand-new configuration (empty list = fine). */
    static List<String> missingForNew(Map<String, String> v) {
        List<String> m = new ArrayList<>();
        if (blank(v.get("HOTSPOT_SSID"))) m.add("Network name (SSID)");
        if (v.get("HOTSPOT_PASS") == null || v.get("HOTSPOT_PASS").length() < 8) m.add("Wi-Fi password (at least 8 characters)");
        if (blank(v.get("SMB_USER"))) m.add("File sharing user name");
        if (blank(v.get("SMB_PASS"))) m.add("File sharing password");
        if (blank(v.get("TR_PASS"))) m.add("Torrents password");
        if (!"0".equals(v.get("ENABLE_SYNCTHING")) && blank(v.get("ST_PASS"))) m.add("Syncthing password (or switch Syncthing off)");
        return m;
    }

    private static boolean blank(String s) { return s == null || s.trim().isEmpty(); }

    /** Text of a new config.sh from the entered values. */
    static String buildNew(Map<String, String> v) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Hotspot + services configuration, created by the Dashboard app on ")
          .append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date())).append(".\n")
          .append("# Edit it in the app (Settings). Keep this file private: it holds passwords.\n");
        for (Section s : SECTIONS) {
            StringBuilder part = new StringBuilder();
            for (Field f : s.fields) {
                String val = v.get(f.key);
                if (val != null && !val.isEmpty()) part.append(f.key).append("=").append(quote(val)).append("\n");
            }
            if (part.length() > 0) sb.append("\n# ").append(s.title).append("\n").append(part);
        }
        return sb.toString();
    }

    /**
     * The engine sources this file as root, so only plain assignments and comments are accepted.
     * @return null if fine, otherwise the first offending line
     */
    static String validate(String text) {
        if (text.length() > 65536) return "file is too large";
        Pattern ok = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*=(\"(?:[^\"\\\\$`]|\\\\.)*\"|'[^']*'|[^\\s;&|<>`$()\"']*)(\\s+#.*)?$");
        boolean hasSsid = false;
        for (String raw : text.split("\n")) {
            String l = raw.trim();
            if (l.isEmpty() || l.startsWith("#")) continue;
            if (!ok.matcher(l).matches()) return l.length() > 60 ? l.substring(0, 60) + "\u2026" : l;
            if (l.startsWith("HOTSPOT_SSID=")) hasSsid = true;
        }
        return hasSsid ? null : "no HOTSPOT_SSID in this file";
    }

    /** Creates config.sh on a phone that has none. Refuses to replace an existing one. */
    static void create(File scratch, Map<String, String> values) throws Exception {
        if (configExists()) throw new IllegalStateException("a configuration already exists; not replacing it");
        List<String> missing = missingForNew(values);
        if (!missing.isEmpty()) throw new IllegalArgumentException("missing: " + missing.get(0));
        install(scratch, buildNew(values), false);
    }

    /** Installs text as config.sh after validating it and a shell syntax check; backs up whatever was there. */
    private static void install(File scratch, String text, boolean backup) throws Exception {
        String bad = validate(text);
        if (bad != null) throw new IllegalArgumentException("not a valid config: " + bad);
        File tmp = new File(scratch, "cfg.new");
        try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(text.getBytes("UTF-8")); }
        if (!Root.su("sh -n " + Root.q(tmp.getPath()) + " && echo SYNTAX_OK", 10).contains("SYNTAX_OK")) {
            tmp.delete();
            throw new IllegalStateException("the config has a syntax error");
        }
        String ts = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
        Root.su("mkdir -p " + Root.q(new File(CONFIG).getParent()) + "; "
            + (backup ? "[ -f " + Root.q(CONFIG) + " ] && cp " + Root.q(CONFIG) + " " + Root.q(CONFIG + ".before-import-" + ts) + "; "
                        + "[ -f " + Root.q(SMS_CONF) + " ] && mv " + Root.q(SMS_CONF) + " " + Root.q(SMS_CONF + ".before-import-" + ts) + "; " : "")
            + "cat " + Root.q(tmp.getPath()) + " > " + Root.q(CONFIG) + "; chmod 644 " + Root.q(CONFIG), 20);
        tmp.delete();
    }

    /** Writes the whole configuration (config.sh plus sms.conf, which overrides it) to one file in dir. */
    static String exportTo(String dir) throws Exception {
        String name = "hotspot-config-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmm").format(new java.util.Date()) + ".sh";
        String out = Root.su("D=" + Root.q(dir) + "; mkdir -p \"$D\"; F=\"$D/" + name + "\"; "
            + "{ cat " + Root.q(CONFIG) + "; if [ -f " + Root.q(SMS_CONF) + " ]; then echo; echo '# --- settings that were in sms.conf (they override the ones above) ---'; cat "
            + Root.q(SMS_CONF) + "; fi; } > \"$F\" && chmod 600 \"$F\" && echo \"$F\"", 20).trim();
        if (!out.endsWith(name)) throw new IllegalStateException("could not write the file: " + out);
        return out;
    }

    /** Replaces the configuration with the chosen file (validated first); the previous one is kept as a backup. */
    static void importFrom(File scratch, String path) throws Exception {
        String text = Root.su("cat " + Root.q(path), 10);
        install(scratch, text, true);
    }

    /** Test harness for the pure text edit. */
    public static void main(String[] a) {
        Map<String, String> ch = new LinkedHashMap<>();
        ch.put("SHARE_NAME", "my \"share\" $HOME `x` \\");
        ch.put("SMB_PASS", "p#ss w0rd'!");
        ch.put("NEW_KEY", "v");
        String in = "# comment\nSHARE_NAME=\"share\"   # name\nSMB_USER=\"root\"\nSMB_PASS=\"old\"\n";
        System.out.print(apply(in, ch));
    }
}
