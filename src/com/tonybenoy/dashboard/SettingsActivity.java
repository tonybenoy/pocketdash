package com.tonybenoy.dashboard;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Schema-driven editor for the engine's configuration (see ConfigStore.SECTIONS). */
public class SettingsActivity extends Activity {
    private static final int BG = Color.TRANSPARENT, TEXT = Glass.TEXT, DIM = Glass.DIM;
    private static final int GREEN = Glass.MINT, AMBER = Glass.AMBER;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<String, String> values = new HashMap<>();
    private final Map<String, String> pending = new LinkedHashMap<>();
    private final Map<String, TextView> valueViews = new HashMap<>();
    private LinearLayout form;
    private TextView saveBtn, status;
    private String keysText = "", keysOriginal = "";
    private TextView keysSummary;
    private boolean override;
    private boolean fresh;          // this phone has no configuration yet: setup mode

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(12), dp(14), dp(12), dp(10));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = tv(18, TEXT, Typeface.DEFAULT_BOLD);
        back.setText("←  Settings");
        back.setOnClickListener(v -> onBackPressed());
        head.addView(back, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        status = tv(13, DIM, Typeface.DEFAULT);
        head.addView(status);
        root.addView(head);

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(0, dp(12), 0, dp(12));
        sv.addView(form);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        saveBtn = tv(16, Color.BLACK, Typeface.DEFAULT_BOLD);
        saveBtn.setGravity(Gravity.CENTER);
        saveBtn.setOnClickListener(v -> save());
        root.addView(saveBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));
        AuroraView aurora = new AuroraView(this);
        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        frame.addView(aurora, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        frame.addView(root, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(frame);
        aurora.setTime(System.currentTimeMillis());
        refreshSave();

        status.setText("loading…");
        new Thread(() -> {
            try {
                final boolean noConfig = !ConfigStore.configExists();
                Map<String, String> v = ConfigStore.read();
                String k = ConfigStore.readKeys();
                ui.post(() -> {
                    fresh = noConfig;
                    values.putAll(v);
                    override = "1".equals(v.get("_override"));
                    keysText = keysOriginal = k;
                    build();
                    status.setText("");
                });
            } catch (Exception e) {
                ui.post(() -> status.setText("could not read settings: " + e.getMessage()));
            }
        }, "cfg-read").start();
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView tv(float sp, int color, Typeface tf) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(tf);
        t.setIncludeFontPadding(false);
        return t;
    }

    private LinearLayout card(String title, String note) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        int sp = dp(Glass.CARD_PAD);
        c.setPadding(sp + dp(18), sp + dp(13), sp + dp(18), sp + dp(9));
        c.setBackground(Glass.card(this, 26));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = -dp(2);
        c.setLayoutParams(lp);
        TextView t = tv(12, DIM, Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.14f);
        t.setText(title.toUpperCase());
        c.addView(t);
        if (note != null && !note.isEmpty()) {
            TextView n = tv(12, DIM, Typeface.DEFAULT);
            n.setText(note);
            n.setPadding(0, dp(6), 0, dp(2));
            c.addView(n);
        }
        return c;
    }

    private void build() {
        form.removeAllViews();
        valueViews.clear();
        if (override) {
            TextView w = tv(13, AMBER, Typeface.DEFAULT);
            w.setText("/sdcard/hotspot_smb.conf exists and overrides config.sh for any keys it sets. "
                + "If a change here does not stick, check that file.");
            w.setPadding(0, 0, 0, dp(12));
            form.addView(w);
        }
        if (fresh) {
            LinearLayout nb = card("New phone setup", "This phone has no configuration yet, so nothing has been started. Fill in the details below "
                + "(the suggested values suit most setups), then tap Create configuration. Network name, Wi-Fi password and the "
                + "service passwords are required; use Generate in a password box if you like. Or restore a saved setup under Backup & restore.");
            form.addView(nb);
        }
        for (ConfigStore.Section s : ConfigStore.SECTIONS) {
            LinearLayout c = card(s.title, s.note);
            for (ConfigStore.Field f : s.fields) c.addView(row(f));
            if (s.title.equals("SSH")) c.addView(keysRow());
            form.addView(c);
        }
        final android.content.SharedPreferences ap = getSharedPreferences("dashboard", MODE_PRIVATE);
        LinearLayout nt = card("Notifications", "A notification when a new device joins your hotspot. Allow notifications for this app in Android "
            + "if none appear.");
        final TextView notif = tv(16, TEXT, Typeface.DEFAULT_BOLD);
        notif.setPadding(0, dp(12), 0, dp(8));
        notif.setText("New device alerts: " + (ap.getBoolean("notify_new", true) ? "On" : "Off"));
        notif.setOnClickListener(v -> {
            boolean now = !ap.getBoolean("notify_new", true);
            ap.edit().putBoolean("notify_new", now).apply();
            notif.setText("New device alerts: " + (now ? "On" : "Off"));
            if (now && android.os.Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
        });
        nt.addView(notif);
        final TextView bnotif = tv(16, TEXT, Typeface.DEFAULT_BOLD);
        bnotif.setPadding(0, dp(12), 0, dp(8));
        bnotif.setText("Power cut and battery alerts: " + (ap.getBoolean("notify_battery", true) ? "On" : "Off"));
        bnotif.setOnClickListener(v -> {
            boolean now = !ap.getBoolean("notify_battery", true);
            ap.edit().putBoolean("notify_battery", now).apply();
            bnotif.setText("Power cut and battery alerts: " + (now ? "On" : "Off"));
        });
        nt.addView(bnotif);
        form.addView(nt);
        LinearLayout pr = card("Presence", "Dimmed clock while nobody is around; bright and expanded when someone looks at it (or touches it). "
            + "Uses the front camera's built-in face detector all the time, so the camera indicator stays on. Nothing is recorded, stored or sent.");
        final TextView presence = tv(16, TEXT, Typeface.DEFAULT_BOLD);
        presence.setPadding(0, dp(12), 0, dp(8));
        presence.setText("Wake on face: " + (ap.getBoolean("presence", true) ? "On" : "Off") + "   (applies when the dashboard reopens)");
        presence.setOnClickListener(v -> {
            boolean now = !ap.getBoolean("presence", true);
            ap.edit().putBoolean("presence", now).apply();
            presence.setText("Wake on face: " + (now ? "On" : "Off") + "   (applies when the dashboard reopens)");
            if (now && checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{android.Manifest.permission.CAMERA}, 2);
        });
        pr.addView(presence);
        final TextView gazeRow = tv(16, TEXT, Typeface.DEFAULT_BOLD);
        gazeRow.setPadding(0, dp(12), 0, dp(8));
        gazeRow.setText("Also require open eyes looking at the screen (slower): " + (ap.getBoolean("gaze", false) ? "On" : "Off"));
        gazeRow.setOnClickListener(v -> {
            boolean now = !ap.getBoolean("gaze", false);
            ap.edit().putBoolean("gaze", now).apply();
            gazeRow.setText("Also require open eyes looking at the screen (slower): " + (now ? "On" : "Off"));
        });
        pr.addView(gazeRow);
        pr.addView(percentRow("Idle brightness", "idle_pct", 6, 1, 40));
        pr.addView(secondsRow("Go back to idle this long after the last face", "hold_s", 5, 1, 60));
        pr.addView(percentRow("Brightness when someone looks (minimum)", "active_pct", 45, 15, 100));
        form.addView(pr);
        LinearLayout bk = card("Backup & restore", "Move this phone's whole configuration to another phone: export here, import there. "
            + "The file holds your passwords in plain text, so keep it private.");
        bk.addView(action("Export settings\u2026", v -> exportSettings()));
        bk.addView(action("Import settings\u2026", v -> importSettings()));
        form.addView(bk);
    }

    /** A "label: N%" row that edits a number kept in the app's preferences. */
    private View percentRow(final String label, final String key, final int def, final int min, final int max) {
        final android.content.SharedPreferences ap = getSharedPreferences("dashboard", MODE_PRIVATE);
        final TextView t = tv(16, TEXT, Typeface.DEFAULT);
        t.setPadding(0, dp(10), 0, dp(8));
        t.setText(label + ": " + ap.getInt(key, def) + "%");
        t.setBackground(Glass.rowPress(this, 14));
        t.setOnClickListener(v -> {
            final EditText e = new EditText(this);
            e.setInputType(InputType.TYPE_CLASS_NUMBER);
            e.setText(String.valueOf(ap.getInt(key, def)));
            e.setSelection(e.getText().length());
            new AlertDialog.Builder(this).setTitle(label).setMessage(min + " to " + max + " percent").setView(e)
                .setPositiveButton("OK", (d, w) -> {
                    int n;
                    try { n = Integer.parseInt(e.getText().toString().trim()); } catch (Exception ex) { return; }
                    n = Math.max(min, Math.min(max, n));
                    ap.edit().putInt(key, n).apply();
                    t.setText(label + ": " + n + "%");
                }).setNegativeButton("Cancel", null).show();
        });
        return t;
    }

    /** A "label: N s" row that edits a number of seconds kept in the app's preferences. */
    private View secondsRow(final String label, final String key, final int def, final int min, final int max) {
        final android.content.SharedPreferences ap = getSharedPreferences("dashboard", MODE_PRIVATE);
        final TextView t = tv(16, TEXT, Typeface.DEFAULT);
        t.setPadding(0, dp(10), 0, dp(8));
        t.setText(label + ": " + ap.getInt(key, def) + " s");
        t.setBackground(Glass.rowPress(this, 14));
        t.setOnClickListener(v -> {
            final EditText e = new EditText(this);
            e.setInputType(InputType.TYPE_CLASS_NUMBER);
            e.setText(String.valueOf(ap.getInt(key, def)));
            e.setSelection(e.getText().length());
            new AlertDialog.Builder(this).setTitle(label).setMessage(min + " to " + max + " seconds").setView(e)
                .setPositiveButton("OK", (d, w) -> {
                    int n;
                    try { n = Integer.parseInt(e.getText().toString().trim()); } catch (Exception ex) { return; }
                    n = Math.max(min, Math.min(max, n));
                    ap.edit().putInt(key, n).apply();
                    t.setText(label + ": " + n + " s");
                }).setNegativeButton("Cancel", null).show();
        });
        return t;
    }

    private View action(String label, View.OnClickListener l) {
        TextView t = tv(16, TEXT, Typeface.DEFAULT_BOLD);
        t.setText(label);
        t.setPadding(0, dp(12), 0, dp(8));
        t.setOnClickListener(l);
        return t;
    }

    private View row(final ConfigStore.Field f) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(0, dp(10), 0, dp(6));
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        TextView label = tv(16, TEXT, Typeface.DEFAULT);
        label.setText(f.label);
        TextView val = tv(16, DIM, Typeface.DEFAULT_BOLD);
        val.setGravity(Gravity.END);
        valueViews.put(f.key, val);
        line.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        line.addView(val, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(line);
        if (!f.hint.isEmpty()) {
            TextView h = tv(12, DIM, Typeface.DEFAULT);
            h.setText(f.hint);
            h.setPadding(0, dp(2), 0, 0);
            r.addView(h);
        }
        r.setBackground(Glass.rowPress(this, 14));
        r.setOnClickListener(v -> edit(f));
        showValue(f);
        return r;
    }

    /** Value the engine actually uses: what is set, otherwise its built-in default. */
    private String effective(String key) {
        String v = values.get(key);
        if (v != null && !v.isEmpty()) return v;
        if (fresh && ConfigStore.FRESH_DEFAULTS.containsKey(key)) return ConfigStore.FRESH_DEFAULTS.get(key);
        String d = ConfigStore.DEFAULTS.get(key);
        return d == null ? "" : d;
    }

    private String current(String key) {
        return pending.containsKey(key) ? pending.get(key) : effective(key);
    }

    private void showValue(ConfigStore.Field f) {
        TextView v = valueViews.get(f.key);
        if (v == null) return;
        String cur = current(f.key);
        String shown;
        switch (f.type) {
            case ConfigStore.PASSWORD: shown = cur.isEmpty() ? "not set" : "••••••"; break;
            case ConfigStore.SWITCH: shown = cur.equals("1") ? "On" : "Off"; break;
            case ConfigStore.CHOICE: shown = f.key.equals("HOTSPOT_BAND") ? (cur.equals("2") ? "2.4 GHz" : cur.equals("5") ? "5 GHz" : "auto") : cur; break;
            default: shown = cur.isEmpty() ? "—" : cur;
        }
        v.setText(shown);
        boolean changed = pending.containsKey(f.key);
        v.setTextColor(changed ? AMBER : (f.type == ConfigStore.SWITCH && cur.equals("1") ? GREEN : DIM));
    }

    private void set(ConfigStore.Field f, String v) {
        if (v.equals(effective(f.key))) pending.remove(f.key); else pending.put(f.key, v);
        showValue(f);
        refreshSave();
    }

    private void edit(final ConfigStore.Field f) {
        switch (f.type) {
            case ConfigStore.SWITCH:
                set(f, current(f.key).equals("1") ? "0" : "1");
                return;
            case ConfigStore.CHOICE: {
                final String[] labels = new String[f.choices.length];
                for (int i = 0; i < labels.length; i++) labels[i] = f.choices[i].equals("2") ? "2.4 GHz" : f.choices[i].equals("5") ? "5 GHz" : f.choices[i];
                new AlertDialog.Builder(this).setTitle(f.label)
                    .setItems(labels, (d, i) -> set(f, f.choices[i])).show();
                return;
            }
            case ConfigStore.DIR:
            case ConfigStore.FILE:
                PathPicker.show(this, f.label, current(f.key), f.type == ConfigStore.FILE, p -> set(f, p), () -> editText(f));
                return;
            default:
                editText(f);
        }
    }

    private void editText(final ConfigStore.Field f) {
        final EditText e = new EditText(this);
        e.setText(current(f.key));
        e.setSingleLine(true);
        e.setSelection(e.getText().length());
        if (f.type == ConfigStore.NUMBER) e.setInputType(InputType.TYPE_CLASS_NUMBER);
        else if (f.type == ConfigStore.PASSWORD) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(f.label).setMessage(f.hint.isEmpty() ? null : f.hint).setView(e)
            .setPositiveButton("OK", (d, w) -> set(f, e.getText().toString().trim()))
            .setNegativeButton("Cancel", null);
        if (f.type == ConfigStore.PASSWORD) {
            b.setNeutralButton("Generate", (d, w) -> {
                final String g = generate();
                set(f, g);
                new AlertDialog.Builder(this).setTitle("New password").setMessage(g + "\n\nNote it down; it is saved when you tap Save.")
                    .setPositiveButton("OK", null).show();
            });
        }
        b.show();
    }

    private static String generate() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 14; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.toString();
    }

    private View keysRow() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(0, dp(10), 0, dp(6));
        TextView label = tv(16, TEXT, Typeface.DEFAULT);
        label.setText("Allowed SSH keys");
        keysSummary = tv(14, DIM, Typeface.DEFAULT);
        keysSummary.setPadding(0, dp(2), 0, 0);
        r.addView(label);
        r.addView(keysSummary);
        r.setOnClickListener(v -> editKeys());
        showKeys();
        return r;
    }

    private int keyCount() {
        int n = 0;
        for (String l : keysText.split("\n")) if (!l.trim().isEmpty() && !l.trim().startsWith("#")) n++;
        return n;
    }

    private void showKeys() {
        if (keysSummary == null) return;
        keysSummary.setText(keyCount() + (keyCount() == 1 ? " key" : " keys") + (keysText.equals(keysOriginal) ? "" : "  ● changed")
            + "  ·  tap to edit");
        keysSummary.setTextColor(keysText.equals(keysOriginal) ? DIM : AMBER);
    }

    private void editKeys() {
        final EditText e = new EditText(this);
        e.setText(keysText);
        e.setMinLines(6);
        e.setGravity(Gravity.TOP);
        e.setTypeface(Typeface.MONOSPACE);
        e.setTextSize(11);
        new AlertDialog.Builder(this).setTitle("Allowed SSH keys").setMessage("One public key per line.").setView(e)
            .setPositiveButton("OK", (d, w) -> { keysText = e.getText().toString().replace("\r", ""); showKeys(); refreshSave(); })
            .setNegativeButton("Cancel", null).show();
    }

    private void refreshSave() {
        boolean any = fresh || !pending.isEmpty() || !keysText.equals(keysOriginal);
        saveBtn.setText(fresh ? "Create configuration" : any ? "Save changes" : "No changes");
        saveBtn.setBackground(any ? Glass.accent(this, GREEN, 22) : Glass.raised(this, 22));
        saveBtn.setTextColor(any ? Color.BLACK : DIM);
        saveBtn.setEnabled(any);
    }

    @Override
    public void onBackPressed() {
        if (pending.isEmpty() && keysText.equals(keysOriginal)) { super.onBackPressed(); return; }
        new AlertDialog.Builder(this).setTitle("Discard changes?")
            .setPositiveButton("Discard", (d, w) -> finish()).setNegativeButton("Keep editing", null).show();
    }

    /** First setup on a phone without a configuration: validate, create config.sh, offer to start the services. */
    private void createFresh() {
        final Map<String, String> all = new LinkedHashMap<>();
        for (ConfigStore.Section sec : ConfigStore.SECTIONS) for (ConfigStore.Field f : sec.fields) all.put(f.key, current(f.key));
        List<String> missing = ConfigStore.missingForNew(all);
        if (!missing.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String m : missing) sb.append("\u2022 ").append(m).append('\n');
            new AlertDialog.Builder(this).setTitle("Still needed").setMessage(sb.toString().trim()).setPositiveButton("OK", null).show();
            return;
        }
        final String keys = keysText;
        status.setText("creating\u2026");
        new Thread(() -> {
            try {
                ConfigStore.create(getFilesDir(), all);
                if (!keys.equals(keysOriginal)) ConfigStore.saveKeys(getFilesDir(), keys);
                ui.post(() -> {
                    values.putAll(all);
                    pending.clear();
                    keysOriginal = keys;
                    fresh = false;
                    status.setText("created");
                    build();
                    refreshSave();
                    new AlertDialog.Builder(this).setTitle("Configuration created")
                        .setMessage("Start the hotspot and services now? (To start them automatically after each reboot, turn on "
                            + "\u201CEngine\u201D in the Services card.)")
                        .setPositiveButton("Start now", (d, w) -> new Thread(() -> {
                            try { ServiceCtl.restartAll(); } catch (Exception ignored) { }
                            ui.post(() -> Toast.makeText(this, "Started", Toast.LENGTH_SHORT).show());
                        }, "svc-start").start())
                        .setNegativeButton("Later", null).show();
                });
            } catch (Exception e) {
                ui.post(() -> { status.setText(""); Toast.makeText(this, "Could not create: " + e.getMessage(), Toast.LENGTH_LONG).show(); });
            }
        }, "cfg-create").start();
    }

    // ---- backup & restore

    private void exportSettings() {
        if (fresh) { Toast.makeText(this, "Nothing to export yet", Toast.LENGTH_SHORT).show(); return; }
        PathPicker.show(this, "Export to which folder?", "/sdcard/Download", false, dir ->
            new AlertDialog.Builder(this).setTitle("Export settings?")
                .setMessage("The file contains your passwords in plain text. Anything that can read " + dir + " can read it.")
                .setPositiveButton("Export", (d, w) -> new Thread(() -> {
                    try {
                        final String out = ConfigStore.exportTo(dir);
                        ui.post(() -> new AlertDialog.Builder(this).setTitle("Exported").setMessage(out).setPositiveButton("OK", null).show());
                    } catch (Exception e) {
                        ui.post(() -> Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
                    }
                }, "cfg-export").start())
                .setNegativeButton("Cancel", null).show(), null);
    }

    private void importSettings() {
        PathPicker.show(this, "Choose the settings file", "/sdcard/Download", true, path ->
            new AlertDialog.Builder(this).setTitle("Replace the configuration?")
                .setMessage("Uses " + path + " instead of the current settings. The current ones are kept as a backup next to config.sh.")
                .setPositiveButton("Import", (d, w) -> new Thread(() -> {
                    try {
                        ConfigStore.importFrom(getFilesDir(), path);
                        ui.post(() -> { Toast.makeText(this, "Imported. Restart services to apply.", Toast.LENGTH_LONG).show(); recreate(); });
                    } catch (Exception e) {
                        ui.post(() -> Toast.makeText(this, "Import refused: " + e.getMessage(), Toast.LENGTH_LONG).show());
                    }
                }, "cfg-import").start())
                .setNegativeButton("Cancel", null).show(), null);
    }

    private void save() {
        if (fresh) { createFresh(); return; }
        final Map<String, String> changes = new LinkedHashMap<>(pending);
        final String keys = keysText;
        final boolean keysChanged = !keys.equals(keysOriginal);
        status.setText("saving…");
        new Thread(() -> {
            try {
                Set<String> apply = changes.isEmpty() ? new java.util.LinkedHashSet<String>() : ConfigStore.save(getFilesDir(), changes);
                if (keysChanged) { ConfigStore.saveKeys(getFilesDir(), keys); apply.add("ssh"); }
                final Set<String> todo = apply;
                ui.post(() -> {
                    values.putAll(changes);
                    pending.clear();
                    keysOriginal = keys;
                    status.setText("saved");
                    build();
                    refreshSave();
                    offerRestart(todo);
                });
            } catch (Exception e) {
                ui.post(() -> { status.setText(""); Toast.makeText(this, "Save failed: " + e.getMessage(), Toast.LENGTH_LONG).show(); });
            }
        }, "cfg-save").start();
    }

    private void offerRestart(final Set<String> services) {
        if (services.isEmpty()) return;
        StringBuilder names = new StringBuilder();
        boolean hs = false;
        for (String s : services) {
            for (String[] sv : ServiceCtl.SERVICES) if (sv[0].equals(s)) { if (names.length() > 0) names.append(", "); names.append(sv[1]); }
            if (s.equals("hotspot")) hs = true;
        }
        new AlertDialog.Builder(this).setTitle("Restart to apply?")
            .setMessage(names + (hs ? "\n\nThe hotspot restarts and connected devices drop for about 30 seconds." : ""))
            .setPositiveButton("Restart now", (d, w) -> new Thread(() -> {
                for (String s : services) { try { ServiceCtl.restart(s); } catch (Exception ignored) { } }
                ui.post(() -> Toast.makeText(this, "Restarted", Toast.LENGTH_SHORT).show());
            }, "svc-restart").start())
            .setNegativeButton("Later", null).show();
    }
}
