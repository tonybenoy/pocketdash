package com.tonybenoy.dashboard;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * First-run setup: one screen per step, every step shows whether it is already done (so the wizard is also safe to run
 * on a phone that is set up). Nothing here is required except root, and every step can be skipped.
 *
 *   Welcome, Root, Permissions, Keep running, Hotspot and sharing, Preferences, Finish
 */
public class WizardActivity extends Activity {
    private static final int TEXT = Glass.TEXT, DIM = Glass.DIM, GREEN = Glass.MINT, AMBER = Glass.AMBER;
    private static final String[] TITLES = {"Welcome", "Root access", "Permissions", "Keep running", "Hotspot and sharing",
        "Preferences", "All set"};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private LinearLayout body, nav;
    private TextView stepLabel;
    private int step;
    private boolean manual;                     // opened from the dashboard instead of first launch

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("dashboard", MODE_PRIVATE);
        manual = getIntent().getBooleanExtra("manual", false);
        // like the dashboard: this phone usually sits locked, so show over the lock screen and keep the screen on
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        step = b == null ? 0 : b.getInt("step", 0);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(28), dp(22), dp(28), dp(18));
        stepLabel = tv(12, DIM, Typeface.DEFAULT_BOLD);
        stepLabel.setLetterSpacing(0.14f);
        root.addView(stepLabel);

        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(this);
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(nav, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        AuroraView aurora = new AuroraView(this);
        FrameLayout frame = new FrameLayout(this);
        frame.addView(aurora, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        frame.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(frame);
        aurora.setTime(System.currentTimeMillis());
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();                                // a permission dialog or the settings screen may have changed things
    }

    @Override
    protected void onSaveInstanceState(Bundle o) {
        super.onSaveInstanceState(o);
        o.putInt("step", step);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        render();
    }

    @Override
    public void onBackPressed() {
        if (step > 0) { step--; render(); } else if (manual) finish();   // first launch: cannot back out of the wizard
        else finish();
    }

    // ---------------------------------------------------------------- steps

    private void render() {
        body.removeAllViews();
        nav.removeAllViews();
        stepLabel.setText("SETUP  ·  " + (step + 1) + " OF " + TITLES.length);
        TextView h = tv(30, TEXT, Typeface.DEFAULT_BOLD);
        h.setText(TITLES[step]);
        body.addView(h);
        switch (step) {
            case 0: stepWelcome(); break;
            case 1: stepRoot(); break;
            case 2: stepPermissions(); break;
            case 3: stepKeepRunning(); break;
            case 4: stepHotspot(); break;
            case 5: stepPrefs(); break;
            default: stepDone(); break;
        }
        if (step > 0 && step < TITLES.length - 1) nav.addView(button("Back", false, v -> { step--; render(); }), navLp());
        nav.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        if (step < TITLES.length - 1) {
            nav.addView(button(step == 0 ? "Start" : "Next", true, v -> { step++; render(); }), navLp());
        } else {
            nav.addView(button("Open the dashboard", true, v -> finishWizard()), navLp());
        }
    }

    private void stepWelcome() {
        text("This phone becomes an always-on Wi-Fi hotspot, file share and Bluetooth speaker, with a clock on the screen.");
        text("This takes about two minutes. Every step shows what is already done, and you can skip any of them.");
        if (!manual) note("You can open this again later from the Device card.");
    }

    private void stepRoot() {
        text("The hotspot, firewall rules and Bluetooth speaker need root. When Magisk asks, choose Grant.");
        final TextView st = status("checking…", DIM);
        final View[] again = new View[1];
        again[0] = action("Check again", v -> render());
        new Thread(() -> {
            boolean ok;
            try { ok = Root.su("id -u", 15).trim().equals("0"); } catch (Exception e) { ok = false; }
            final boolean fin = ok;
            ui.post(() -> {
                st.setText(fin ? "✓ Root access granted" : "✗ No root access yet. Tap “Check again” and grant it in the Magisk prompt.");
                st.setTextColor(fin ? GREEN : AMBER);
            });
        }, "wiz-root").start();
        body.addView(again[0], actionLp());
    }

    private void stepPermissions() {
        text("Optional, and you can change them later in Android settings.");
        if (Build.VERSION.SDK_INT >= 33) {
            boolean n = has(android.Manifest.permission.POST_NOTIFICATIONS);
            permRow("Notifications", "New devices on the hotspot, power loss, hot battery", n,
                () -> requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1));
        }
        boolean c = has(android.Manifest.permission.CAMERA);
        permRow("Camera", "Wakes the screen when you look at it. Frames are checked in memory and never saved.", c,
            () -> requestPermissions(new String[]{android.Manifest.permission.CAMERA}, 2));
    }

    private void stepKeepRunning() {
        text("Stops Android from putting the app to sleep, so the hotspot controls and clock keep running.");
        final TextView st = status("checking…", DIM);
        final String pkg = getPackageName();
        new Thread(() -> {
            boolean on;
            try { on = !Root.su("dumpsys deviceidle whitelist | grep -c " + pkg, 15).trim().equals("0"); } catch (Exception e) { on = false; }
            final boolean fin = on;
            ui.post(() -> {
                st.setText(fin ? "✓ Exempt from battery optimisation" : "Not exempt yet");
                st.setTextColor(fin ? GREEN : AMBER);
                if (!fin) body.addView(action("Allow it to run in the background", v -> new Thread(() -> {
                    try { Root.su("dumpsys deviceidle whitelist +" + pkg, 15); } catch (Exception ignored) { }
                    ui.post(this::render);
                }, "wiz-idle").start()), actionLp());
            });
        }, "wiz-idle-check").start();
    }

    private void stepHotspot() {
        text("Hotspot name and password, the file share and its login. These are saved on the phone, never in the app.");
        final TextView st = status("checking…", DIM);
        new Thread(() -> {
            boolean exists;
            try { exists = ConfigStore.configExists(); } catch (Exception e) { exists = false; }
            final boolean fin = exists;
            ui.post(() -> {
                st.setText(fin ? "✓ Already configured" : "Not configured yet");
                st.setTextColor(fin ? GREEN : AMBER);
                body.addView(action(fin ? "Review settings" : "Set up the hotspot",
                    v -> startActivity(new Intent(this, SettingsActivity.class))), actionLp());
            });
        }, "wiz-cfg").start();
    }

    private void stepPrefs() {
        // clock
        sectionLabel("Clock");
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final boolean h24 = prefs.getBoolean("clock24", false);
        row.addView(choice("12-hour  (3:05 PM)", !h24, v -> { prefs.edit().putBoolean("clock24", false).apply(); render(); }), choiceLp());
        row.addView(choice("24-hour  (15:05)", h24, v -> { prefs.edit().putBoolean("clock24", true).apply(); render(); }), choiceLp());
        body.addView(row);

        sectionLabel("Bluetooth speaker");
        toggle("Play audio from my other phone", "Sets up the Bluetooth speaker when the dashboard opens (Bluetooth restarts once)",
            prefs.getBoolean("speaker", false), on -> prefs.edit().putBoolean("speaker", on).apply());
        toggle("Auto-connect", "Reconnects to your paired phone by itself",
            prefs.getBoolean("autoconnect", true), on -> prefs.edit().putBoolean("autoconnect", on).apply());

        sectionLabel("After a restart");
        toggle("Start by itself", "Opens the dashboard and brings the hotspot back after a reboot",
            prefs.getBoolean("wiz_boot", true), on -> prefs.edit().putBoolean("wiz_boot", on).apply());
    }

    private void stepDone() {
        text("That is everything. The dashboard opens next.");
        if (prefs.getBoolean("speaker", false)) note("The Bluetooth speaker sets itself up when the dashboard opens. Bluetooth restarts once, about 30 seconds.");
        note("To pair another phone later, use “Pair a new phone” on the Bluetooth card.");
    }

    private void finishWizard() {
        final boolean boot = prefs.getBoolean("wiz_boot", true);
        prefs.edit().putBoolean("wizard_done", true).putBoolean("asked_cam", true).putBoolean("asked_notif", true).apply();
        new Thread(() -> {
            try {
                if (boot) {
                    Root.su("mkdir -p /data/adb/dashboard && touch /data/adb/dashboard/autostart", 15);
                    if (ConfigStore.configExists()) Root.su("touch /data/adb/dashboard/engine_takeover", 15);
                }
            } catch (Exception ignored) { }
            ui.post(() -> {
                if (!manual) startActivity(new Intent(this, MainActivity.class));
                finish();
            });
        }, "wiz-finish").start();
    }

    // ---------------------------------------------------------------- pieces

    private boolean has(String perm) { return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED; }

    private void permRow(String name, String why, boolean granted, Runnable ask) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(16), 0, 0);
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        TextView n = tv(17, TEXT, Typeface.DEFAULT_BOLD);
        n.setText(name);
        TextView w = tv(13, DIM, Typeface.DEFAULT);
        w.setText(why);
        w.setPadding(0, dp(3), 0, 0);
        t.addView(n);
        t.addView(w);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView b = granted ? tv(14, GREEN, Typeface.DEFAULT_BOLD) : button("Allow", true, v -> ask.run());
        b.setGravity(Gravity.CENTER);
        if (granted) b.setText("✓ Allowed");
        row.addView(b, new LinearLayout.LayoutParams(dp(130), dp(46)));
        body.addView(row);
    }

    private interface OnToggle { void set(boolean on); }

    private void toggle(String name, String why, boolean on, OnToggle cb) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, 0);
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        TextView n = tv(16, TEXT, Typeface.DEFAULT);
        n.setText(name);
        TextView w = tv(12, DIM, Typeface.DEFAULT);
        w.setText(why);
        w.setPadding(0, dp(2), 0, 0);
        t.addView(n);
        t.addView(w);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView pill = tv(13, on ? Color.BLACK : DIM, Typeface.DEFAULT_BOLD);
        pill.setGravity(Gravity.CENTER);
        pill.setText(on ? "ON" : "OFF");
        pill.setBackground(on ? Glass.accent(this, GREEN, 18) : Glass.raised(this, 18));
        View.OnClickListener c = v -> { boolean now = !pill.getText().equals("ON"); cb.set(now); render(); };
        pill.setOnClickListener(c);
        row.setOnClickListener(c);
        row.addView(pill, new LinearLayout.LayoutParams(dp(68), dp(38)));
        body.addView(row);
    }

    private TextView choice(String label, boolean selected, View.OnClickListener c) {
        TextView t = tv(15, selected ? Color.BLACK : TEXT, Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setText(label);
        t.setBackground(selected ? Glass.accent(this, GREEN, 18) : Glass.raised(this, 18));
        t.setOnClickListener(c);
        return t;
    }

    private LinearLayout.LayoutParams choiceLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(54), 1f);
        lp.topMargin = dp(10);
        lp.rightMargin = dp(10);
        return lp;
    }

    private TextView button(String label, boolean primary, View.OnClickListener c) {
        TextView t = tv(16, primary ? Color.BLACK : TEXT, Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setText(label);
        t.setBackground(primary ? Glass.accent(this, GREEN, 18) : Glass.raised(this, 18));
        t.setOnClickListener(c);
        return t;
    }

    private LinearLayout.LayoutParams navLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(190), dp(52));
        lp.leftMargin = dp(12);
        return lp;
    }

    private View action(String label, View.OnClickListener c) { return button(label, false, c); }

    private LinearLayout.LayoutParams actionLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(300), dp(50));
        lp.topMargin = dp(16);
        return lp;
    }

    private void text(String s) {
        TextView t = tv(16, TEXT, Typeface.DEFAULT);
        t.setText(s);
        t.setLineSpacing(0, 1.15f);
        t.setPadding(0, dp(14), 0, 0);
        body.addView(t);
    }

    private void note(String s) {
        TextView t = tv(13, DIM, Typeface.DEFAULT);
        t.setText(s);
        t.setPadding(0, dp(12), 0, 0);
        body.addView(t);
    }

    private TextView status(String s, int color) {
        TextView t = tv(16, color, Typeface.DEFAULT_BOLD);
        t.setText(s);
        t.setPadding(0, dp(16), 0, 0);
        body.addView(t);
        return t;
    }

    private void sectionLabel(String s) {
        TextView t = tv(12, DIM, Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.14f);
        t.setText(s.toUpperCase());
        t.setPadding(0, dp(20), 0, 0);
        body.addView(t);
    }

    private TextView tv(float sp, int color, Typeface tf) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(tf);
        t.setIncludeFontPadding(false);
        return t;
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
