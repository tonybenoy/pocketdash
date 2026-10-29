package com.tonybenoy.dashboard;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.transition.TransitionManager;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Always-on dashboard. Default view is the full hotspot / clients / throughput / weather
 * panel next to a flip clock; tapping the clock makes the clock take most of the screen,
 * tapping the info side (or 60s without touch) goes back to the full panel.
 */
public class MainActivity extends Activity implements RootLink.Listener, SensorEventListener {

    private static final int BG = Color.TRANSPARENT;       // the aurora behind everything is the background
    private static final int TEXT = Glass.TEXT;
    private static final int DIM = Glass.DIM;
    private static final int GREEN = Glass.MINT;
    private static final int RED = Glass.RED;
    private static final int AMBER = Glass.AMBER;
    private static final long RETURN_MS = 60000;
    private static final long WEATHER_EVERY_MIN = 15;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private final SimpleDateFormat fmtDate = new SimpleDateFormat("EEEE, d MMMM");
    private final RootLink link = new RootLink();
    private ScheduledExecutorService weatherExec;
    private SharedPreferences prefs;

    // state
    private boolean expanded;          // set in onCreate: collapsed (big clock) when presence mode is on, expanded otherwise

    // presence mode: dim, clock-dominant while idle; bright and expanded while someone is looking at it
    // how long it stays bright after the last face (on top of ~2 s to notice the face is gone) and after the last touch
    private static final int DEFAULT_HOLD_S = 5;
    private boolean presenceOn, presenceActive, facesPausedHot;
    private volatile boolean faceNow;
    private volatile long lastFace;
    private FaceWatcher faces;
    private android.animation.ValueAnimator brightAnim;
    private long lastHeatCheck;
    private long lastTouch = System.currentTimeMillis();
    private volatile RootLink.Probe probe;
    private volatile String rootError;
    private volatile long probeAt;
    private volatile boolean switching;
    private volatile Weather.Data weather;
    private volatile String weatherErr = "loading…";
    private long prevRx = -1, prevTx, prevNs;
    private float downBps, upBps;
    private int lastMinute = -1;

    // ambient-light auto dim
    private SensorManager sensors;
    private boolean autoDim = true;
    private float smoothLux = -1;
    private float appliedBrightness = -2;
    private TextView dDim;

    // views
    private LinearLayout root, clockSection;
    private AuroraView aurora;
    private FrameLayout statusSection;
    private FlipClockView flip;
    private TextView tDate;
    private View compact, detail;
    private boolean land;

    // compact side
    private TextView cDot, cState, cSsid, cBand, cSpeed, cNames, cWeather, cBattery;
    // detail side
    private TextView dDot, dState, dSsid, dBand, dGateway, seg24, seg5, dSwitchNote;
    private TextView dClientsTitle, dSpeedDown, dSpeedUp, dWxBig, dWxDesc, dWxHL;
    private TextView dUpstream, dBattery, dUpdated;
    private TextView cBt, dBtStatus, dBtNow, dBtSetup, dBtBoot;

    // bluetooth speaker
    private volatile Map<String, String> bt;
    private volatile boolean btBusy;
    private volatile String btMsg = "";
    private long lastHeal;

    // services card + draggable board
    private volatile Map<String, String> svc;
    private volatile List<String[]> devices;     // mac, ip, host, online, blocked, limitKBps, todayDn, todayUp, monthDn, monthUp
    private TextView dBatMain, dBatSub, dBatLimit, dBatTemp;
    private LinearLayout dUsageList;
    private TextView dUsageTotals;
    private TextView[] svcDots, svcStates;
    private TextView btnWd, btnKeep, btnEngine;
    private volatile String engineProblem = "";
    private final LinkedHashMap<String, View> cards = new LinkedHashMap<>();
    private static final String[] DEFAULT_ORDER = {"hotspot", "clients", "bluetooth", "throughput", "usage", "battery", "weather", "device", "services"};
    private static final int[] EST_HEIGHT = {4, 3, 4, 3, 4, 4, 4, 3, 7};      // rough relative heights, to balance two columns
    private LinearLayout board;
    private ScrollView boardScroll;
    private LinearLayout dClientList;
    private TextView[] dHours = new TextView[5];
    private GraphView graph;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("dashboard", MODE_PRIVATE);
        autoDim = prefs.getBoolean("autodim", true);
        presenceOn = prefs.getBoolean("presence", true);
        expanded = !presenceOn;
        if (presenceOn) lastTouch = 0;            // starting the app is not a touch: begin idle
        sensors = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().getAttributes().layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        build();
        startForegroundService(new Intent(this, KeepAliveService.class));
        if (presenceOn && !prefs.getBoolean("asked_cam", false)
                && checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putBoolean("asked_cam", true).apply();
            requestPermissions(new String[]{android.Manifest.permission.CAMERA}, 2);       // for wake-on-face
        }
        if (Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("asked_notif", false)
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putBoolean("asked_notif", true).apply();
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);   // for new-device alerts
        }
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        build();
        render();
    }

    @Override
    protected void onStart() {
        super.onStart();
        new Thread(() -> {
            clearOwnNetworkBlock();
            String problem = Engine.deploy(getApplicationContext());
            engineProblem = problem == null ? "" : problem;
            ui.post(this::renderServices);
        }, "engine-deploy").start();
        link.start(readAsset("probe.sh"), this);
        weatherExec = Executors.newSingleThreadScheduledExecutor();
        weatherExec.scheduleWithFixedDelay(this::refreshWeather, 0, WEATHER_EVERY_MIN, TimeUnit.MINUTES);
        ui.post(tick);
        Sensor light = sensors.getDefaultSensor(Sensor.TYPE_LIGHT);
        if (light != null) sensors.registerListener(this, light, SensorManager.SENSOR_DELAY_NORMAL);
        startFaces();
    }

    @Override
    protected void onStop() {
        super.onStop();
        ui.removeCallbacks(tick);
        stopFaces();
        sensors.unregisterListener(this);
        link.stop();
        if (weatherExec != null) weatherExec.shutdownNow();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideBars();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        lastTouch = System.currentTimeMillis();
        return super.dispatchTouchEvent(e);
    }

    private void hideBars() {
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.systemBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name);
             BufferedReader r = new BufferedReader(new InputStreamReader(in))) {
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append('\n');
            return sb.toString();
        } catch (Exception e) {
            return "echo probe_begin; echo probe_end";
        }
    }

    // ---------------------------------------------------------------- view helpers

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView tv(float sp, int color, Typeface tf) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(tf);
        t.setIncludeFontPadding(false);
        return t;
    }

    private TextView tv(float sp, int color) {
        return tv(sp, color, Typeface.DEFAULT);
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        int sp = dp(Glass.CARD_PAD);                         // room the glass reserves for its shadows
        c.setPadding(sp + dp(18), sp + dp(15), sp + dp(18), sp + dp(15));
        c.setBackground(Glass.card(this, 26));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = -dp(2);
        c.setLayoutParams(lp);
        return c;
    }

    private TextView title(String s) {
        TextView t = tv(11.5f, DIM, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setLetterSpacing(0.18f);
        t.setText(s);
        t.setPadding(0, 0, 0, dp(8));
        return t;
    }

    private LinearLayout stateRow(TextView[] out) {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView dot = tv(20, RED);
        dot.setText("●");
        TextView st = tv(13, DIM, Typeface.DEFAULT_BOLD);
        st.setLetterSpacing(0.12f);
        st.setPadding(dp(8), 0, 0, 0);
        head.addView(dot);
        head.addView(st);
        out[0] = dot;
        out[1] = st;
        return head;
    }

    // ---------------------------------------------------------------- build

    private void build() {
        land = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;

        root = new LinearLayout(this);
        root.setOrientation(land ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(8), dp(12), dp(8), dp(12));

        clockSection = new LinearLayout(this);
        clockSection.setOrientation(LinearLayout.VERTICAL);
        clockSection.setGravity(Gravity.CENTER);
        flip = new FlipClockView(this);
        clockSection.addView(flip, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        tDate = tv(20, DIM);
        tDate.setGravity(Gravity.CENTER);
        tDate.setPadding(0, dp(4), 0, dp(4));
        clockSection.addView(tDate, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        statusSection = new FrameLayout(this);
        compact = buildCompact();
        detail = buildDetail();
        statusSection.addView(compact, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        statusSection.addView(detail, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(clockSection, sectionParams(0.66f, 0));
        root.addView(statusSection, sectionParams(0.34f, dp(12)));

        statusSection.setOnClickListener(v -> { if (!expanded) setExpanded(true); });
        clockSection.setOnClickListener(v -> { if (expanded) setExpanded(false); });
        flip.setOnClickListener(v -> { if (expanded) setExpanded(false); });

        applyMode();
        aurora = new AuroraView(this);
        FrameLayout frame = new FrameLayout(this);
        frame.addView(aurora, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        frame.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(frame);
        aurora.setTime(System.currentTimeMillis());
        lastMinute = -1;
        appliedBrightness = -2;
        applyDim();
    }

    private LinearLayout.LayoutParams sectionParams(float weight, int margin) {
        LinearLayout.LayoutParams lp = land
            ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
            : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, weight);
        if (land) lp.leftMargin = margin; else lp.topMargin = margin;
        return lp;
    }

    private void setExpanded(boolean e) {
        expanded = e;
        if (!presenceOn) lastTouch = System.currentTimeMillis();       // presence mode times out on real touches only
        TransitionManager.beginDelayedTransition(root);
        applyMode();
    }

    private void applyMode() {
        float clockW = land ? (expanded ? 0.34f : 0.66f) : (expanded ? 0.26f : 0.62f);
        ((LinearLayout.LayoutParams) clockSection.getLayoutParams()).weight = clockW;
        ((LinearLayout.LayoutParams) statusSection.getLayoutParams()).weight = 1f - clockW;
        compact.setVisibility(expanded ? View.GONE : View.VISIBLE);
        detail.setVisibility(expanded ? View.VISIBLE : View.GONE);
        clockSection.requestLayout();
    }

    private View buildCompact() {
        LinearLayout c = card();
        c.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL);
        c.setLayoutParams(lp);

        TextView[] sr = new TextView[2];
        c.addView(stateRow(sr));
        cDot = sr[0];
        cState = sr[1];
        cSsid = tv(26, TEXT, Typeface.DEFAULT_BOLD);
        cSsid.setPadding(0, dp(8), 0, 0);
        cBand = tv(15, GREEN, Typeface.DEFAULT_BOLD);
        cBand.setPadding(0, dp(4), 0, 0);
        cSpeed = tv(16, TEXT);
        cSpeed.setPadding(0, dp(8), 0, 0);
        cNames = tv(13, DIM);
        cNames.setPadding(0, dp(4), 0, 0);
        cBt = tv(14, TEXT);
        cBt.setPadding(0, dp(8), 0, 0);
        cWeather = tv(16, TEXT);
        cWeather.setPadding(0, dp(12), 0, 0);
        cBattery = tv(13, DIM);
        cBattery.setPadding(0, dp(8), 0, 0);
        TextView hint = tv(11, DIM);
        hint.setText("tap for details");
        hint.setPadding(0, dp(10), 0, 0);
        c.addView(cSsid);
        c.addView(cBand);
        c.addView(cSpeed);
        c.addView(cNames);
        c.addView(cBt);
        c.addView(cWeather);
        c.addView(cBattery);
        c.addView(hint);

        FrameLayout wrap = new FrameLayout(this);
        wrap.addView(c);
        return wrap;
    }

    private View buildDetail() {
        // --- hotspot card with band switch
        LinearLayout hs = card();
        TextView[] sr = new TextView[2];
        hs.addView(stateRow(sr));
        dDot = sr[0];
        dState = sr[1];
        dSsid = tv(30, TEXT, Typeface.DEFAULT_BOLD);
        dSsid.setPadding(0, dp(8), 0, 0);
        dBand = tv(16, GREEN, Typeface.DEFAULT_BOLD);
        dBand.setPadding(0, dp(4), 0, 0);
        dGateway = tv(13, DIM, Typeface.MONOSPACE);
        dGateway.setPadding(0, dp(2), 0, dp(12));
        LinearLayout segRow = new LinearLayout(this);
        segRow.setOrientation(LinearLayout.HORIZONTAL);
        seg24 = seg("2.4 GHz", 2);
        seg5 = seg("5 GHz", 5);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, dp(54), 1f);
        sp.rightMargin = dp(8);
        segRow.addView(seg24, sp);
        segRow.addView(seg5, new LinearLayout.LayoutParams(0, dp(54), 1f));
        dSwitchNote = tv(12, DIM);
        dSwitchNote.setPadding(0, dp(6), 0, 0);
        hs.addView(dSsid);
        hs.addView(dBand);
        hs.addView(dGateway);
        hs.addView(segRow);
        hs.addView(dSwitchNote);

        // --- clients
        LinearLayout cl = card();
        dClientsTitle = title("CONNECTED DEVICES");
        dClientList = new LinearLayout(this);
        dClientList.setOrientation(LinearLayout.VERTICAL);
        cl.addView(dClientsTitle);
        cl.addView(dClientList);

        // --- bluetooth speaker
        LinearLayout btc = card();
        btc.addView(title("BLUETOOTH SPEAKER"));
        dBtStatus = tv(17, TEXT, Typeface.DEFAULT_BOLD);
        dBtNow = tv(14, DIM);
        dBtNow.setPadding(0, dp(4), 0, 0);
        dBtSetup = tv(15, Color.BLACK, Typeface.DEFAULT_BOLD);
        dBtSetup.setGravity(Gravity.CENTER);
        dBtSetup.setOnClickListener(v -> onSpeakerButton());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        bp.topMargin = dp(10);
        dBtBoot = tv(13, DIM);
        dBtBoot.setPadding(0, dp(10), 0, 0);
        dBtBoot.setOnClickListener(v -> toggleBoot());
        btc.addView(dBtStatus);
        btc.addView(dBtNow);
        btc.addView(dBtSetup, bp);
        btc.addView(dBtBoot);

        // --- throughput
        LinearLayout th = card();
        th.addView(title("THROUGHPUT · LAST 5 MIN"));
        LinearLayout sp2 = new LinearLayout(this);
        sp2.setOrientation(LinearLayout.HORIZONTAL);
        dSpeedDown = tv(18, GraphView.DOWN, Typeface.DEFAULT_BOLD);
        dSpeedUp = tv(18, GraphView.UP, Typeface.DEFAULT_BOLD);
        sp2.addView(dSpeedDown, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        sp2.addView(dSpeedUp, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        graph = new GraphView(this);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(110));
        gp.topMargin = dp(8);
        th.addView(sp2);
        th.addView(graph, gp);

        // --- weather
        LinearLayout wx = card();
        wx.addView(title("WEATHER"));
        dWxBig = tv(34, TEXT, Typeface.DEFAULT_BOLD);
        dWxDesc = tv(15, TEXT);
        dWxDesc.setPadding(0, dp(2), 0, 0);
        dWxHL = tv(13, DIM);
        dWxHL.setPadding(0, dp(2), 0, dp(10));
        LinearLayout hours = new LinearLayout(this);
        hours.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < dHours.length; i++) {
            dHours[i] = tv(13, TEXT);
            dHours[i].setGravity(Gravity.CENTER);
            hours.addView(dHours[i], new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        wx.addView(dWxBig);
        wx.addView(dWxDesc);
        wx.addView(dWxHL);
        wx.addView(hours);

        // --- device
        LinearLayout dv = card();
        dv.addView(title("DEVICE"));
        dUpstream = tv(16, TEXT);
        dBattery = tv(16, TEXT);
        dBattery.setPadding(0, dp(6), 0, 0);
        dUpdated = tv(12, DIM);
        dUpdated.setPadding(0, dp(8), 0, 0);
        dDim = tv(14, DIM);
        dDim.setPadding(0, dp(6), 0, 0);
        dDim.setOnClickListener(v -> {
            autoDim = !autoDim;
            prefs.edit().putBoolean("autodim", autoDim).apply();
            appliedBrightness = -2;
            applyDim();
        });
        dv.addView(dUpstream);
        dv.addView(dBattery);
        dv.addView(dDim);
        dv.addView(dUpdated);

        // --- services (status + restart for the engine's services)
        LinearLayout svcc = card();
        svcc.addView(title("SERVICES"));
        svcDots = new TextView[ServiceCtl.SERVICES.length];
        svcStates = new TextView[ServiceCtl.SERVICES.length];
        for (int i = 0; i < ServiceCtl.SERVICES.length; i++) {
            final String key = ServiceCtl.SERVICES[i][0], label = ServiceCtl.SERVICES[i][1];
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));
            svcDots[i] = tv(14, DIM);
            svcDots[i].setText("\u25CF");
            TextView name = tv(15, TEXT);
            name.setText(label);
            name.setPadding(dp(8), 0, 0, 0);
            svcStates[i] = tv(13, DIM);
            svcStates[i].setGravity(Gravity.END);
            row.addView(svcDots[i]);
            row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(svcStates[i]);
            row.setOnClickListener(v -> confirmRestart(key, label));
            svcc.addView(row);
        }
        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setPadding(0, dp(10), 0, 0);
        TextView btnAll = pill("Restart all");
        btnAll.setOnClickListener(v -> confirmRestartAll());
        btnWd = pill("Watchdog");
        btnWd.setOnClickListener(v -> toggleFlag("watchdog.off", "flag.wd_off"));
        btnKeep = pill("Keep hotspot on");
        btnKeep.setOnClickListener(v -> toggleFlag("hotspot.off", "flag.hs_off"));
        for (TextView t : new TextView[]{btnAll, btnWd, btnKeep}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), 1f);
            lp.rightMargin = dp(6);
            foot.addView(t, lp);
        }
        svcc.addView(foot);
        LinearLayout foot2 = new LinearLayout(this);
        foot2.setOrientation(LinearLayout.HORIZONTAL);
        foot2.setPadding(0, dp(6), 0, 0);
        TextView btnSet = pill("\u2699  Settings");
        btnSet.setOnClickListener(v -> { if (!lockedOut()) startActivity(new Intent(this, SettingsActivity.class)); });
        TextView btnReset = pill("Reset panel layout");
        btnReset.setOnClickListener(v -> { prefs.edit().remove("card_order").apply(); TransitionManager.beginDelayedTransition(board); layoutCards(); });
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, dp(46), 1f);
        l1.rightMargin = dp(6);
        foot2.addView(btnSet, l1);
        foot2.addView(btnReset, new LinearLayout.LayoutParams(0, dp(46), 1f));
        svcc.addView(foot2);
        LinearLayout foot3 = new LinearLayout(this);
        foot3.setPadding(0, dp(6), 0, 0);
        btnEngine = pill("Engine");
        btnEngine.setOnClickListener(v -> toggleEngine());
        foot3.addView(btnEngine, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));
        svcc.addView(foot3);

        // --- draggable board: long-press a panel, drop it where you want it
        cards.clear();
        cards.put("hotspot", hs);
        cards.put("clients", cl);
        cards.put("bluetooth", btc);
        LinearLayout usc = card();
        usc.addView(title("USAGE"));
        dUsageTotals = tv(15, TEXT);
        dUsageTotals.setPadding(0, 0, 0, dp(8));
        dUsageList = new LinearLayout(this);
        dUsageList.setOrientation(LinearLayout.VERTICAL);
        usc.addView(dUsageTotals);
        usc.addView(dUsageList);
        cards.put("throughput", th);
        cards.put("usage", usc);
        LinearLayout bat = card();
        bat.addView(title("BATTERY"));
        dBatMain = tv(22, TEXT, Typeface.DEFAULT_BOLD);
        dBatSub = tv(14, DIM);
        dBatSub.setPadding(0, dp(4), 0, 0);
        dBatLimit = tv(14, TEXT);
        dBatLimit.setPadding(0, dp(10), 0, 0);
        dBatTemp = tv(13, DIM);
        dBatTemp.setPadding(0, dp(2), 0, dp(10));
        bat.addView(dBatMain);
        bat.addView(dBatSub);
        bat.addView(dBatLimit);
        bat.addView(dBatTemp);
        LinearLayout brow = new LinearLayout(this);
        TextView bLimit = pill("Change limit\u2026");
        bLimit.setOnClickListener(v -> batteryLimitDialog());
        TextView bFull = pill("Charge to 100% once");
        bFull.setOnClickListener(v -> chargeOnce());
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, dp(46), 1f);
        bl.rightMargin = dp(6);
        brow.addView(bLimit, bl);
        brow.addView(bFull, new LinearLayout.LayoutParams(0, dp(46), 1f));
        bat.addView(brow);
        cards.put("battery", bat);
        cards.put("weather", wx);
        cards.put("device", dv);
        cards.put("services", svcc);
        for (Map.Entry<String, View> e : cards.entrySet()) makeDraggable(e.getValue(), e.getValue(), e.getKey());
        board = new LinearLayout(this);
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sv.addView(board);
        boardScroll = sv;
        layoutCards();
        sv.setOnDragListener(this::onBoardDrag);
        return sv;
    }

    private TextView seg(String label, final int ghz) {
        TextView t = tv(16, TEXT, Typeface.DEFAULT_BOLD);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Glass.raised(this, 18));
        t.setOnClickListener(v -> confirmBand(ghz));
        return t;
    }

    // ---------------------------------------------------------------- periodic

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            Calendar c = Calendar.getInstance();
            flip.setTime(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND));
            tDate.setText(fmtDate.format(new Date()));
            aurora.setTime(System.currentTimeMillis());

            int minute = (int) (System.currentTimeMillis() / 60000L);
            if (minute != lastMinute) {
                lastMinute = minute;
                // Nudge everything a few px each minute to avoid burn-in on a 24/7 display.
                root.setTranslationX(rnd.nextInt(17) - 8);
                root.setTranslationY(rnd.nextInt(17) - 8);
                renderDevice();
            }
            if (presenceOn) { evalPresence(); heatGuard(); }
            else if (!expanded && System.currentTimeMillis() - lastTouch > RETURN_MS) setExpanded(true);
            ui.postDelayed(this, 1000 - (System.currentTimeMillis() % 1000));
        }
    };

    private void refreshWeather() {
        try {
            weather = Weather.fetch();
            weatherErr = null;
        } catch (Exception e) {
            if (weather == null) weatherErr = "weather unavailable";
        }
        ui.post(this::renderWeather);
    }

    // ---------------------------------------------------------------- RootLink.Listener (background thread)

    @Override
    public void onProbe(RootLink.Probe p) {
        probe = p;
        rootError = null;
        probeAt = System.currentTimeMillis();
        ui.post(this::render);
    }

    @Override
    public void onNet(long rx, long tx, long ns) {
        boolean ok = prevRx >= 0 && rx >= prevRx && tx >= prevTx && ns > prevNs;
        if (ok) {
            double dt = (ns - prevNs) / 1e9;
            upBps = (float) ((rx - prevRx) * 8 / dt);      // received from clients = their upload
            downBps = (float) ((tx - prevTx) * 8 / dt);    // sent to clients = their download
            final float d = downBps, u = upBps;
            ui.post(() -> { graph.add(d, u); renderSpeed(); });
        }
        prevRx = rx; prevTx = tx; prevNs = ns;
    }

    @Override
    public void onBt(Map<String, String> m) {
        bt = m;
        // Adopt an already-working setup, and put it back after a reboot / Bluetooth module update.
        boolean up = "1".equals(m.get("sink")) && "1".equals(m.get("overlay"));
        if (up && !prefs.getBoolean("speaker", false)) prefs.edit().putBoolean("speaker", true).apply();
        if (!up && prefs.getBoolean("speaker", false) && !btBusy
                && System.currentTimeMillis() - lastHeal > 10 * 60 * 1000L) {
            lastHeal = System.currentTimeMillis();
            applySpeaker(false);
        }
        ui.post(this::renderBt);
    }

    @Override
    public void onError(String msg) {
        rootError = msg;
        probe = null;
        prevRx = -1;
        ui.post(this::render);
    }

    // ---------------------------------------------------------------- render

    private void render() {
        RootLink.Probe p = probe;
        String state, ssid = "—", band = "", gw = "";
        int stateColor, bandColor = DIM;
        int n = 0;
        if (switching) {
            state = "RESTARTING HOTSPOT…"; stateColor = AMBER;
            band = "devices will reconnect in ~30s"; bandColor = AMBER;
        } else if (p == null) {
            state = rootError != null ? "STATUS UNAVAILABLE" : "CHECKING…";
            stateColor = AMBER;
            band = rootError != null ? rootError : "";
            bandColor = AMBER;
        } else if (p.ip.isEmpty()) {
            state = "HOTSPOT OFF"; stateColor = RED;
            ssid = p.ssid.isEmpty() ? "—" : p.ssid;
            band = "not broadcasting"; bandColor = RED;
        } else {
            state = "HOTSPOT ON"; stateColor = GREEN;
            ssid = p.ssid.isEmpty() ? "(unknown SSID)" : p.ssid;
            band = bandText(p.freq);
            bandColor = p.freq > 0 && p.freq < 3000 ? AMBER : GREEN;
            gw = p.iface + "  " + p.ip;
            n = p.clients.size();
        }
        cDot.setTextColor(stateColor); dDot.setTextColor(stateColor);
        cState.setText(state); dState.setText(state);
        cSsid.setText(ssid); dSsid.setText(ssid);
        cBand.setText(band); dBand.setText(band);
        cBand.setTextColor(bandColor); dBand.setTextColor(bandColor);
        dGateway.setText(gw);

        int freq = p != null && !p.ip.isEmpty() ? p.freq : 0;
        styleSeg(seg24, freq > 0 && freq < 3000);
        styleSeg(seg5, freq >= 4900);
        dSwitchNote.setText(switching ? "restarting hotspot…"
            : "tap a band to switch — the hotspot restarts");

        renderClients(p, n);
        renderSpeed();
        renderWeather();
        renderBt();
        renderServices();
        renderBattery();
        renderDevice();
    }

    private void styleSeg(TextView t, boolean selected) {
        t.setBackground(selected ? Glass.accent(this, GREEN, 18) : Glass.raised(this, 18));
        t.setTextColor(selected ? Color.BLACK : TEXT);
    }

    private void renderClients(RootLink.Probe p, int n) {
        List<String[]> dv = devices;
        if (dv != null) { renderClientsFromDevices(dv); return; }
        dClientsTitle.setText("CONNECTED DEVICES · " + n);
        dClientList.removeAllViews();
        StringBuilder names = new StringBuilder();
        if (p == null || n == 0) {
            TextView none = tv(15, DIM);
            none.setText(p == null ? "—" : "no devices connected");
            dClientList.addView(none);
        } else {
            for (String[] c : p.clients) {
                final String ip = c[0], mac = c.length > 1 ? c[1] : "";
                final String host = c.length > 2 ? c[2] : "";
                String nick = prefs.getString("name_" + mac, "");
                String label = !nick.isEmpty() ? nick : host;
                if (!label.isEmpty()) { if (names.length() > 0) names.append(", "); names.append(label); }
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(0, dp(6), 0, dp(6));
                TextView name = tv(17, TEXT, Typeface.DEFAULT_BOLD);
                name.setText(label.isEmpty() ? "Unnamed device" : label);
                TextView info = tv(12, DIM, Typeface.MONOSPACE);
                info.setText(ip + "  " + mac + (privateMac(mac) ? "  (private MAC)" : ""));
                row.addView(name);
                row.addView(info);
                row.setOnClickListener(v -> nameDialog(mac, ip));
                dClientList.addView(row);
            }
        }
        cNames.setText(n == 0 ? (p == null ? "" : "no devices connected")
            : n + (n == 1 ? " device" : " devices") + (names.length() > 0 ? ": " + names : ""));
    }

    // ---------------------------------------------------------------- devices: list, control, usage

    @Override
    public void onDevices(List<String[]> list) {
        devices = list;
        ui.post(() -> { renderClientsFromDevices(list); renderUsage(); });
    }

    private static long num(String[] d, int i) {
        try { return i < d.length ? Long.parseLong(d[i]) : 0; } catch (Exception e) { return 0; }
    }

    private String deviceLabel(String[] d) {
        String nick = prefs.getString("name_" + d[0], "");
        if (!nick.isEmpty()) return nick;
        if (d.length > 2 && !d[2].isEmpty()) return d[2];
        return "";
    }

    private static String fmtBytes(long b) {
        if (b < 1000) return b + " B";
        if (b < 1000000L) return String.format("%.0f KB", b / 1e3);
        if (b < 1000000000L) return String.format("%.1f MB", b / 1e6);
        return String.format("%.2f GB", b / 1e9);
    }

    private SpannableStringBuilder updown(long dn, long up) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        append(sb, "\u2193 ", GraphView.DOWN);
        append(sb, fmtBytes(dn), TEXT);
        append(sb, "   \u2191 ", GraphView.UP);
        append(sb, fmtBytes(up), TEXT);
        return sb;
    }

    private void renderClientsFromDevices(List<String[]> dv) {
        if (dClientList == null) return;
        int online = 0;
        for (String[] d : dv) if (d.length > 3 && "1".equals(d[3])) online++;
        dClientsTitle.setText("CONNECTED DEVICES \u00B7 " + online);
        dClientList.removeAllViews();
        List<String[]> rows = new ArrayList<>();
        for (String[] d : dv) if (d.length > 4 && ("1".equals(d[3]) || "1".equals(d[4]))) rows.add(d);
        Collections.sort(rows, (a, b) -> {
            int c = Integer.compare(b[3].equals("1") ? 1 : 0, a[3].equals("1") ? 1 : 0);
            return c != 0 ? c : deviceLabel(a).compareToIgnoreCase(deviceLabel(b));
        });
        StringBuilder names = new StringBuilder();
        if (rows.isEmpty()) {
            TextView none = tv(15, DIM);
            none.setText("no devices connected");
            dClientList.addView(none);
        }
        for (final String[] d : rows) {
            final String mac = d[0], ip = d[1], label = deviceLabel(d);
            boolean blocked = "1".equals(d[4]), on = "1".equals(d[3]);
            long limit = num(d, 5);
            if (on && !label.isEmpty()) { if (names.length() > 0) names.append(", "); names.append(label); }
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));
            SpannableStringBuilder nm = new SpannableStringBuilder();
            append(nm, label.isEmpty() ? "Unnamed device" : label, blocked ? DIM : TEXT);
            if (label.isEmpty() && !blocked) append(nm, "   tap to name", DIM);
            if (blocked) append(nm, "   BLOCKED", RED);
            if (limit > 0) append(nm, "   limit " + (limit * 8 / 1000 >= 1 ? (limit * 8 / 1000) + " Mb/s" : (limit * 8) + " kb/s"), AMBER);
            if (!on && blocked) append(nm, "   offline", DIM);
            TextView name = tv(17, TEXT, Typeface.DEFAULT_BOLD);
            name.setText(nm);
            TextView info = tv(12, DIM, Typeface.MONOSPACE);
            info.setText((ip.isEmpty() ? "no address yet" : ip) + "  " + mac + (privateMac(mac) ? "  (private MAC)" : ""));
            row.addView(name);
            row.addView(info);
            if (num(d, 6) + num(d, 7) > 0) {
                TextView use = tv(13, TEXT);
                use.setText(updown(num(d, 6), num(d, 7)));
                use.setPadding(0, dp(2), 0, 0);
                row.addView(use);
            }
            row.setBackground(Glass.rowPress(this, 14));
            row.setOnClickListener(v -> deviceSheet(mac, ip, label, blocked, limit));
            makeDraggable(cards.get("clients"), row, "clients");
            dClientList.addView(row);
        }
        cNames.setText(online == 0 ? "no devices connected"
            : online + (online == 1 ? " device" : " devices") + (names.length() > 0 ? ": " + names : ""));
    }

    private void deviceSheet(final String mac, final String ip, final String label, final boolean blocked, final long limitKb) {
        if (lockedOut()) return;
        final List<String> items = new ArrayList<>();
        items.add("Rename");
        items.add(blocked ? "Allow this device" : "Block this device");
        items.add("Limit speed\u2026");
        if (limitKb > 0) items.add("Remove speed limit");
        new AlertDialog.Builder(this).setTitle(label.isEmpty() ? mac : label)
            .setItems(items.toArray(new String[0]), (d, i) -> {
                String it = items.get(i);
                if (it.equals("Rename")) nameDialog(mac, ip);
                else if (it.startsWith("Block")) new AlertDialog.Builder(this).setTitle("Block " + (label.isEmpty() ? mac : label) + "?")
                    .setMessage("It loses all network access through this hotspot until you allow it again. It stays blocked across reboots.")
                    .setPositiveButton("Block", (x, w) -> deviceCmd("block " + mac))
                    .setNegativeButton("Cancel", null).show();
                else if (it.startsWith("Allow")) deviceCmd("unblock " + mac);
                else if (it.startsWith("Limit")) limitDialog(mac);
                else deviceCmd("limit " + mac + " 0");
            }).show();
    }

    private void limitDialog(final String mac) {
        final String[] labels = {"1 Mb/s", "2 Mb/s", "5 Mb/s", "10 Mb/s", "25 Mb/s", "50 Mb/s"};
        final int[] kBps = {125, 250, 625, 1250, 3125, 6250};
        new AlertDialog.Builder(this).setTitle("Limit speed (each direction)")
            .setItems(labels, (d, i) -> deviceCmd("limit " + mac + " " + kBps[i])).show();
    }

    private void deviceCmd(final String args) {
        runService("Applying\u2026", () -> Root.su("sh " + ServiceCtl.ENGINE + "/devices.sh " + args, 40));
    }

    private void renderUsage() {
        List<String[]> dv = devices;
        if (dUsageList == null) return;
        dUsageList.removeAllViews();
        if (dv == null) { dUsageTotals.setText("\u2014"); return; }
        long tdn = 0, tup = 0, mdn = 0, mup = 0;
        List<String[]> rows = new ArrayList<>();
        for (String[] d : dv) {
            tdn += num(d, 6); tup += num(d, 7); mdn += num(d, 8); mup += num(d, 9);
            if (num(d, 8) + num(d, 9) > 0) rows.add(d);
        }
        Collections.sort(rows, (a, b) -> Long.compare(num(b, 8) + num(b, 9), num(a, 8) + num(a, 9)));
        SpannableStringBuilder sb = new SpannableStringBuilder();
        append(sb, "Today  ", DIM);
        sb.append(updown(tdn, tup));
        sb.append("\n");
        append(sb, "Month  ", DIM);
        sb.append(updown(mdn, mup));
        dUsageTotals.setText(sb);
        if (rows.isEmpty()) {
            TextView none = tv(13, DIM);
            none.setText("Counting has started. Totals build up as devices use the hotspot, and are kept across reboots.");
            dUsageList.addView(none);
            return;
        }
        long max = num(rows.get(0), 8) + num(rows.get(0), 9);
        for (int i = 0; i < Math.min(rows.size(), 8); i++) {
            String[] d = rows.get(i);
            String label = deviceLabel(d);
            long total = num(d, 8) + num(d, 9);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(6), 0, dp(4));
            LinearLayout top = new LinearLayout(this);
            TextView name = tv(15, TEXT, Typeface.DEFAULT_BOLD);
            name.setText(label.isEmpty() ? "\u2026" + d[0].substring(Math.max(0, d[0].length() - 8)) : label);
            TextView tot = tv(14, DIM);
            tot.setText(fmtBytes(total) + " this month");
            tot.setGravity(Gravity.END);
            top.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            top.addView(tot, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            LinearLayout bar = new LinearLayout(this);
            View fill = new View(this), rest = new View(this);
            android.graphics.drawable.GradientDrawable fg = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, new int[]{Glass.MINT, Glass.BLUE});
            fg.setCornerRadius(dp(3));
            fill.setBackground(fg);
            float f = Math.max(0.03f, max == 0 ? 0f : (float) total / max);
            bar.addView(fill, new LinearLayout.LayoutParams(0, dp(6), f));
            bar.addView(rest, new LinearLayout.LayoutParams(0, dp(6), 1f - f));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bp.topMargin = dp(4);
            TextView sub = tv(12, DIM);
            SpannableStringBuilder ss = new SpannableStringBuilder();
            append(ss, "today  ", DIM);
            ss.append(updown(num(d, 6), num(d, 7)));
            sub.setText(ss);
            sub.setPadding(0, dp(3), 0, 0);
            row.addView(top);
            row.addView(bar, bp);
            row.addView(sub);
            makeDraggable(cards.get("usage"), row, "usage");
            dUsageList.addView(row);
        }
    }

    private static boolean privateMac(String mac) {
        try { return (Integer.parseInt(mac.substring(0, 2), 16) & 2) != 0; } catch (Exception e) { return false; }
    }

    private void renderSpeed() {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        append(sb, "↓ ", GraphView.DOWN);
        append(sb, GraphView.fmt(downBps), TEXT);
        append(sb, "   ↑ ", GraphView.UP);
        append(sb, GraphView.fmt(upBps), TEXT);
        cSpeed.setText(sb);
        dSpeedDown.setText("↓ " + GraphView.fmt(downBps));
        dSpeedUp.setText("↑ " + GraphView.fmt(upBps));
    }

    private static void append(SpannableStringBuilder sb, String s, int color) {
        int st = sb.length();
        sb.append(s);
        sb.setSpan(new ForegroundColorSpan(color), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void renderWeather() {
        Weather.Data w = weather;
        if (w == null) {
            cWeather.setText(weatherErr == null ? "" : weatherErr);
            dWxBig.setText("—");
            dWxDesc.setText(weatherErr == null ? "" : weatherErr);
            dWxHL.setText("");
            for (TextView h : dHours) h.setText("");
            return;
        }
        String e = Weather.emoji(w.code, w.day);
        cWeather.setText(e + " " + Math.round(w.temp) + "°  " + Weather.text(w.code)
            + "  ·  H " + Math.round(w.hi) + "°  L " + Math.round(w.lo) + "°");
        dWxBig.setText(e + " " + Math.round(w.temp) + "°C");
        dWxDesc.setText(Weather.text(w.code) + "  ·  wind " + Math.round(w.wind) + " km/h");
        dWxHL.setText("Today H " + Math.round(w.hi) + "° / L " + Math.round(w.lo) + "°"
            + (w.city.isEmpty() ? "" : "  ·  " + w.city));
        for (int i = 0; i < dHours.length; i++) {
            if (i < w.hours.size()) {
                Weather.Hour h = w.hours.get(i);
                dHours[i].setText(h.label + "\n" + Weather.emoji(h.code, h.day) + "\n" + Math.round(h.temp) + "°");
            } else {
                dHours[i].setText("");
            }
        }
    }

    /** Parts that don't need root: upstream link, battery, probe age. */
    private void renderDevice() {
        String up = upstream();
        dUpstream.setText("Upstream: " + up);

        Intent bi = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (bi != null) {
            int lvl = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            float temp = bi.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f;
            int plugged = bi.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            String s = String.format("Battery %d%%  ·  %.1f°C  ·  %s",
                lvl * 100 / Math.max(scale, 1), temp, plugged != 0 ? "plugged in" : "ON BATTERY");
            boolean warn = temp >= 42f || plugged == 0;
            dBattery.setText(s);
            dBattery.setTextColor(warn ? AMBER : TEXT);
            cBattery.setText(up + "  ·  " + s);
            cBattery.setTextColor(warn ? AMBER : DIM);
        }
        long at = probeAt;
        dUpdated.setText(at > 0 ? "hotspot status updated " + (System.currentTimeMillis() - at) / 1000 + "s ago" : "");
    }

    private String upstream() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            if (n == null) return "offline";
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            if (nc == null) return "unknown";
            String t = nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "Mobile data"
                : nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "Wi-Fi"
                : nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "Ethernet" : "other";
            return nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? t : t + " (no internet)";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String bandText(int f) {
        if (f <= 0) return "band unknown";
        if (f < 3000) return "2.4 GHz  ·  ch " + (f == 2484 ? 14 : (f - 2407) / 5) + "  ·  " + f + " MHz";
        if (f < 5900) return "5 GHz  ·  ch " + ((f - 5000) / 5) + "  ·  " + f + " MHz";
        return "6 GHz  ·  " + f + " MHz";
    }

    // ---------------------------------------------------------------- ambient-light auto dim

    @Override
    public void onSensorChanged(SensorEvent e) {
        float lux = e.values[0];
        smoothLux = smoothLux < 0 ? lux : smoothLux * 0.8f + lux * 0.2f;
        applyDim();
    }

    @Override
    public void onAccuracyChanged(Sensor s, int accuracy) { }

    /** Screen brightness follows room light on a log scale; very dark rooms also get a dimmer UI. */
    private void applyDim() {
        if (dDim == null) return;
        float auto = smoothLux < 0 ? -1f
            : 0.02f + 0.98f * (float) Math.pow(Math.min(1.0, Math.log10(smoothLux + 1.0) / 3.0), 1.6);   // 1000 lx -> full, gentle in dim rooms
        float target;
        if (presenceOn) {
            float idleMax = prefs.getInt("idle_pct", 6) / 100f, activeMin = prefs.getInt("active_pct", 45) / 100f;
            float base = autoDim && auto >= 0 ? auto : (presenceActive ? activeMin : idleMax);
            target = presenceActive ? Math.max(base, activeMin) : Math.min(base, idleMax);
        } else if (!autoDim || smoothLux < 0) {
            target = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        } else {
            target = auto;
        }
        if (Math.abs(target - appliedBrightness) > 0.02f || target != appliedBrightness && target < 0.05f) {
            fadeBrightness(target);
            appliedBrightness = target;
        }
        root.setAlpha(autoDim && smoothLux >= 0 && smoothLux < 3f && !presenceOn ? 0.85f : 1f);
        String lux = smoothLux < 0 ? "no sensor" : Math.round(smoothLux) + " lx";
        int pct = Math.round(Math.max(target, 0f) * 100);
        dDim.setText(presenceOn
            ? String.format("%s  \u00B7  %s  \u00B7  screen %d%%  (tap to switch room-light adaption %s)",
                presenceActive ? "Someone is looking" : "Idle, dimmed", lux, pct, autoDim ? "off" : "on")
            : autoDim
                ? String.format("Auto-dim on  \u00B7  %s  \u00B7  screen %d%%  (tap to turn off)", lux, pct)
                : "Auto-dim off  (tap to turn on)");
    }

    /** Smooth change of the screen brightness instead of a jump. */
    private void fadeBrightness(final float to) {
        final WindowManager.LayoutParams lp = getWindow().getAttributes();
        float from = lp.screenBrightness < 0 ? Math.max(0.05f, to) : lp.screenBrightness;
        if (brightAnim != null) brightAnim.cancel();
        if (to < 0 || Math.abs(to - from) < 0.03f) {
            lp.screenBrightness = to;
            getWindow().setAttributes(lp);
            return;
        }
        brightAnim = android.animation.ValueAnimator.ofFloat(from, to);
        brightAnim.setDuration(700);
        brightAnim.addUpdateListener(a -> {
            lp.screenBrightness = (Float) a.getAnimatedValue();
            getWindow().setAttributes(lp);
        });
        brightAnim.start();
    }

    // ---------------------------------------------------------------- presence (face / touch)

    private void startFaces() {
        if (!presenceOn || facesPausedHot) return;
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        if (faces == null) {
            faces = new FaceWatcher(this, present -> {
                faceNow = present;
                android.util.Log.i("Presence", present ? "face seen" : "face gone");
                if (!present) lastFace = System.currentTimeMillis();       // hold the bright state for a while after the last face
                evalPresence();
            });
        }
        faces.start();
    }

    private void stopFaces() {
        if (faces != null) faces.stop();
        faceNow = false;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == 2 && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) startFaces();
    }

    /** Idle = collapsed clock, dim. Active = expanded, bright: a face in view, or a touch in the last 30 s. */
    private void evalPresence() {
        if (!presenceOn || root == null) return;
        long now = System.currentTimeMillis();
        long faceHold = prefs.getInt("hold_s", DEFAULT_HOLD_S) * 1000L;
        long touchHold = Math.max(10000L, faceHold);
        boolean active = faceNow || now - lastFace < faceHold || now - lastTouch < touchHold;
        if (active == presenceActive) return;
        presenceActive = active;
        android.util.Log.i("Presence", (active ? "ACTIVE" : "IDLE") + " (face=" + faceNow + ", sinceFace=" + (now - lastFace) / 1000 + "s, sinceTouch=" + (now - lastTouch) / 1000 + "s)");
        setExpanded(active);
        appliedBrightness = -2;
        applyDim();
    }

    /** The camera adds heat to a phone that sits on a charger: pause face watching when the battery gets hot. */
    private void heatGuard() {
        long now = System.currentTimeMillis();
        if (now - lastHeatCheck < 10000) return;
        lastHeatCheck = now;
        Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) return;
        float t = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f;
        if (t >= 42f && !facesPausedHot) { facesPausedHot = true; stopFaces(); }
        else if (t <= 39f && facesPausedHot) { facesPausedHot = false; startFaces(); }
    }

    // ---------------------------------------------------------------- draggable panels

    private TextView pill(String text) {
        TextView t = tv(13, TEXT, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setText(text);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(6), 0, dp(6), 0);
        t.setBackground(Glass.raised(this, 18));
        return t;
    }

    private List<String> cardOrder() {
        List<String> ord = new ArrayList<>();
        for (String k : prefs.getString("card_order", "").split(",")) if (cards.containsKey(k) && !ord.contains(k)) ord.add(k);
        for (String k : DEFAULT_ORDER) if (cards.containsKey(k) && !ord.contains(k)) ord.add(k);
        return ord;
    }

    private static void detach(View v) {
        if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
    }

    private void layoutCards() {
        for (View v : cards.values()) detach(v);
        board.removeAllViews();
        List<String> ord = cardOrder();
        if (!land) {
            board.setOrientation(LinearLayout.VERTICAL);
            for (String k : ord) board.addView(cards.get(k));
            return;
        }
        board.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout left = new LinearLayout(this), right = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        right.setOrientation(LinearLayout.VERTICAL);
        int lh = 0, rh = 0;
        for (String k : ord) {
            int h = 3;
            for (int i = 0; i < DEFAULT_ORDER.length; i++) if (DEFAULT_ORDER[i].equals(k)) h = EST_HEIGHT[i];
            if (lh <= rh) { left.addView(cards.get(k)); lh += h; } else { right.addView(cards.get(k)); rh += h; }
        }
        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        a.rightMargin = dp(12);
        board.addView(left, a);
        board.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    /** Long-press anywhere on a panel (including its buttons) starts a drag of the whole panel. */
    private void makeDraggable(final View card, View v, final String key) {
        v.setOnLongClickListener(x -> card.startDragAndDrop(ClipData.newPlainText("panel", key),
            new View.DragShadowBuilder(card), key, 0));
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) makeDraggable(card, g.getChildAt(i), key);
        }
    }

    /** Panel whose centre is nearest to the pointer (excluding the dragged one); out[0]=1 if the pointer is below its centre. */
    private String targetAt(float x, float y, String dragged, boolean[] after) {
        int[] origin = new int[2], p = new int[2];
        boardScroll.getLocationOnScreen(origin);
        String best = null;
        double bd = Double.MAX_VALUE;
        for (Map.Entry<String, View> e : cards.entrySet()) {
            if (e.getKey().equals(dragged) || e.getValue().getParent() == null) continue;
            View c = e.getValue();
            c.getLocationOnScreen(p);
            double cx = p[0] - origin[0] + c.getWidth() / 2.0, cy = p[1] - origin[1] + c.getHeight() / 2.0;
            double d = Math.hypot(cx - x, (cy - y) * 1.5);
            if (d < bd) { bd = d; best = e.getKey(); after[0] = y > cy; }
        }
        return best;
    }

    private void resetHover() {
        for (View c : cards.values()) { c.setScaleX(1f); c.setScaleY(1f); }
    }

    private boolean onBoardDrag(View v, DragEvent e) {
        if (!(e.getLocalState() instanceof String)) return false;
        String key = (String) e.getLocalState();
        boolean[] after = new boolean[1];
        switch (e.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                cards.get(key).setAlpha(0.35f);
                return true;
            case DragEvent.ACTION_DRAG_LOCATION: {
                resetHover();
                String t = targetAt(e.getX(), e.getY(), key, after);
                if (t != null) { cards.get(t).setScaleX(1.03f); cards.get(t).setScaleY(1.03f); }
                if (e.getY() < dp(70)) boardScroll.scrollBy(0, -dp(16));
                else if (e.getY() > boardScroll.getHeight() - dp(70)) boardScroll.scrollBy(0, dp(16));
                return true;
            }
            case DragEvent.ACTION_DROP: {
                String t = targetAt(e.getX(), e.getY(), key, after);
                if (t != null) {
                    List<String> ord = cardOrder();
                    ord.remove(key);
                    int idx = ord.indexOf(t);
                    ord.add(after[0] ? idx + 1 : idx, key);
                    StringBuilder sb = new StringBuilder();
                    for (String k : ord) sb.append(sb.length() > 0 ? "," : "").append(k);
                    prefs.edit().putString("card_order", sb.toString()).apply();
                    TransitionManager.beginDelayedTransition(board);
                    layoutCards();
                }
                return true;
            }
            case DragEvent.ACTION_DRAG_ENDED:
                for (View c : cards.values()) c.setAlpha(1f);
                resetHover();
                return true;
            default:
                return true;
        }
    }

    // ---------------------------------------------------------------- battery

    private static int ival(Map<String, String> m, String k, int def) {
        try { return Integer.parseInt(m.get(k)); } catch (Exception e) { return def; }
    }

    private void renderBattery() {
        if (dBatMain == null) return;
        Map<String, String> m = svc;
        if (m == null || m.get("bat.level") == null) { dBatMain.setText("\u2014"); dBatSub.setText(""); dBatLimit.setText(""); dBatTemp.setText(""); return; }
        int lvl = ival(m, "bat.level", -1), temp = ival(m, "bat.temp", 0), mv = ival(m, "bat.mv", 0), ma = ival(m, "bat.ma", 0);
        boolean plugged = "1".equals(m.get("bat.plugged"));
        String st = m.get("bat.status") == null ? "" : m.get("bat.status");
        String state; int color;
        if (!plugged) { state = "ON BATTERY"; color = RED; }
        else if (st.equals("Charging")) { state = "Charging"; color = GREEN; }
        else if (st.equals("Full")) { state = "Full"; color = GREEN; }
        else { state = "Held at limit"; color = GREEN; }
        SpannableStringBuilder sb = new SpannableStringBuilder();
        append(sb, lvl + "%", TEXT);
        append(sb, "   " + state, color);
        dBatMain.setText(sb);
        float c = temp / 10f;
        String health = m.get("bat.health") == null ? "" : m.get("bat.health");
        dBatSub.setText(String.format("%.1f\u00B0C  \u00B7  %.2f V  \u00B7  %s%d mA  \u00B7  health %s (%d%%)  \u00B7  %s cycles",
            c, mv / 1000f, ma > 0 ? "+" : "", ma, health, ival(m, "bat.health_pct", 0), m.get("bat.cycles") == null ? "?" : m.get("bat.cycles")));
        dBatSub.setTextColor(c >= 45f ? RED : c >= 40f ? AMBER : DIM);
        boolean acc = "1".equals(m.get("bat.acc"));
        int pause = ival(m, "bat.pause", -1), resume = ival(m, "bat.resume", -1);
        dBatLimit.setText(!acc ? "Charge limiter is not running" : "Charging stops at " + pause + "%, resumes below " + resume + "%");
        dBatLimit.setTextColor(acc ? TEXT : AMBER);
        dBatTemp.setText("Pauses charging above " + m.get("bat.max_temp") + "\u00B0C  \u00B7  shuts the phone down above " + m.get("bat.shutdown_temp") + "\u00B0C");
    }

    private void batteryLimitDialog() {
        if (lockedOut()) return;
        final String[] labels = {"Longest life: 45% \u2013 50%", "Balanced: 55% \u2013 60%", "Standard: 70% \u2013 75%", "Higher: 80% \u2013 85%", "No limit: 95% \u2013 100%"};
        final int[][] v = {{50, 45}, {60, 55}, {75, 70}, {85, 80}, {100, 95}};
        new AlertDialog.Builder(this).setTitle("Charge limit")
            .setItems(labels, (d, i) -> new AlertDialog.Builder(this).setTitle(labels[i] + "?")
                .setMessage("A lower range wears the battery less, which matters for a phone that stays plugged in. It also leaves "
                    + "less reserve to keep the hotspot running during a power cut.")
                .setPositiveButton("Apply", (x, w) -> setBatteryLimit(v[i][0], v[i][1]))
                .setNegativeButton("Cancel", null).show()).show();
    }

    private void setBatteryLimit(final int pause, final int resume) {
        runService("Applying charge limit\u2026", () -> {
            Map<String, String> ch = new LinkedHashMap<>();
            ch.put("BAT_PAUSE", String.valueOf(pause));
            ch.put("BAT_RESUME", String.valueOf(resume));
            ConfigStore.save(getFilesDir(), ch);
            ServiceCtl.restart("battery");
        });
    }

    private void chargeOnce() {
        if (lockedOut()) return;
        new AlertDialog.Builder(this).setTitle("Charge to 100% once?")
            .setMessage("Charges fully now, e.g. before a trip, then goes back to your normal limit.")
            .setPositiveButton("Charge", (d, w) -> runService("Charging to 100%\u2026", () -> Root.su("sh " + ServiceCtl.ENGINE + "/battery.sh full 100", 20)))
            .setNegativeButton("Cancel", null).show();
    }

    // ---------------------------------------------------------------- services

    @Override
    public void onSvc(Map<String, String> m) {
        svc = m;
        ui.post(this::renderServices);
    }

    private void renderServices() {
        Map<String, String> m = svc;
        renderBattery();
        if (svcDots == null) return;
        for (int i = 0; i < ServiceCtl.SERVICES.length; i++) {
            String v = m == null ? null : m.get("svc." + ServiceCtl.SERVICES[i][0]);
            if (v == null) { svcDots[i].setTextColor(DIM); svcStates[i].setText(""); continue; }
            switch (v) {
                case "1": svcDots[i].setTextColor(GREEN); svcStates[i].setText("running"); svcStates[i].setTextColor(DIM); break;
                case "0": svcDots[i].setTextColor(RED); svcStates[i].setText("down"); svcStates[i].setTextColor(RED); break;
                default: svcDots[i].setTextColor(0xFF444444); svcStates[i].setText("off"); svcStates[i].setTextColor(DIM);
            }
        }
        boolean wdOff = m != null && "1".equals(m.get("flag.wd_off")), keepOff = m != null && "1".equals(m.get("flag.hs_off"));
        btnWd.setText(wdOff ? "Watchdog paused" : "Watchdog on");
        btnWd.setTextColor(wdOff ? AMBER : TEXT);
        btnKeep.setText(keepOff ? "Keep-on: off" : "Keep-on: on");
        btnKeep.setTextColor(keepOff ? AMBER : TEXT);
        boolean eng = m != null && "1".equals(m.get("flag.engine"));
        btnEngine.setText(engineProblem.startsWith("no config.sh") ? "No configuration yet: open Settings to set up this phone"
            : !engineProblem.isEmpty() ? "Engine problem: " + engineProblem
            : eng ? "Engine is part of this app and starts at boot (tap to change)" : "Engine does not start at boot (tap to change)");
        btnEngine.setTextColor(!engineProblem.isEmpty() ? AMBER : eng ? GREEN : TEXT);
    }

    private void confirmRestart(final String key, final String label) {
        if (lockedOut()) return;
        boolean hs = key.equals("hotspot");
        new AlertDialog.Builder(this).setTitle("Restart " + label + "?")
            .setMessage(hs ? "Connected devices drop for about 30 seconds." : null)
            .setPositiveButton("Restart", (d, w) -> runService("Restarting " + label + "\u2026", () -> ServiceCtl.restart(key)))
            .setNegativeButton("Cancel", null).show();
    }

    private void confirmRestartAll() {
        if (lockedOut()) return;
        new AlertDialog.Builder(this).setTitle("Restart every service?")
            .setMessage("Includes the hotspot, so connected devices drop for a while. This takes a couple of minutes.")
            .setPositiveButton("Restart all", (d, w) -> runService("Restarting all services\u2026", ServiceCtl::restartAll))
            .setNegativeButton("Cancel", null).show();
    }

    private interface Job { void run() throws Exception; }

    private void runService(final String msg, final Job job) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try { job.run(); } catch (Exception ignored) { }
            ui.post(() -> Toast.makeText(this, "Done", Toast.LENGTH_SHORT).show());
        }, "svc-job").start();
    }

    private void toggleFlag(final String file, String flagKey) {
        if (lockedOut()) return;
        Map<String, String> m = svc;
        final boolean present = m != null && "1".equals(m.get(flagKey));
        runService(present ? "Enabled" : "Disabled", () -> ServiceCtl.setFlag(file, !present));
    }

    // ---------------------------------------------------------------- bluetooth speaker

    private void renderBt() {
        Map<String, String> m = bt;
        if (dBtStatus == null) return;
        if (m == null) {
            dBtStatus.setText("checking\u2026");
            cBt.setText("");
            return;
        }
        boolean sink = "1".equals(m.get("sink")), overlay = "1".equals(m.get("overlay"));
        boolean conn = "1".equals(m.get("connected"));
        String dev = m.get("device") == null || m.get("device").isEmpty() ? "a device" : m.get("device");
        String title = m.get("title") == null ? "" : m.get("title");
        String player = m.get("player") == null ? "" : m.get("player");
        String state = m.get("state") == null ? "" : m.get("state");
        boolean playing = state.equals("PLAYING");

        if (btBusy) {
            dBtStatus.setText("Setting up\u2026");
            dBtNow.setText("Bluetooth restarts, this takes about 30 seconds");
            cBt.setText("\uD83D\uDD0A Setting up Bluetooth speaker\u2026");
        } else if (!sink || !overlay) {
            dBtStatus.setText(sink ? "Almost set up" : "Not set up");
            dBtNow.setText(btMsg.isEmpty() ? "Turns this phone into a Bluetooth speaker for your other phone" : btMsg);
            cBt.setText("\uD83D\uDD0A Bluetooth speaker: not set up");
        } else if (!conn) {
            dBtStatus.setText("\u25CF Ready");
            dBtNow.setText("Pair or connect to \u201C" + (m.get("name") == null ? "this phone" : m.get("name")) + "\u201D from your phone");
            cBt.setText("\uD83D\uDD0A Bluetooth speaker ready");
        } else {
            dBtStatus.setText("\u25CF Connected: " + dev);
            String now = title.isEmpty() ? (playing ? "playing" : "nothing playing")
                : (playing ? "\u25B6 " : "\u275A\u275A ") + title;
            dBtNow.setText(now + (player.isEmpty() ? "" : "  \u00B7  " + player));
            cBt.setText("\uD83D\uDD0A " + dev + (title.isEmpty() ? "" : "  " + (playing ? "\u25B6 " : "\u275A\u275A ") + title));
        }
        dBtStatus.setTextColor(!sink || !overlay ? AMBER : GREEN);

        boolean on = sink && overlay;
        dBtSetup.setVisibility(btBusy ? View.GONE : View.VISIBLE);
        dBtSetup.setText(on ? "Turn speaker mode off" : "Set up Bluetooth speaker");
        dBtSetup.setTextColor(on ? TEXT : Color.BLACK);
        dBtSetup.setBackground(on ? Glass.raised(this, 18) : Glass.accent(this, GREEN, 18));
        dBtBoot.setText("1".equals(m.get("boot"))
            ? "Start at boot: on  (tap to turn off)" : "Start at boot: off  (tap to turn on)");
    }

    private void onSpeakerButton() {
        if (lockedOut() || btBusy) return;
        Map<String, String> m = bt;
        boolean on = m != null && "1".equals(m.get("sink")) && "1".equals(m.get("overlay"));
        if (on) {
            new AlertDialog.Builder(this)
                .setTitle("Turn Bluetooth speaker mode off?")
                .setMessage("Bluetooth restarts and any phone streaming to this one disconnects.")
                .setPositiveButton("Turn off", (d, w) -> { prefs.edit().putBoolean("speaker", false).apply(); turnSpeakerOff(); })
                .setNegativeButton("Cancel", null)
                .show();
        } else {
            prefs.edit().putBoolean("speaker", true).apply();
            applySpeaker(true);
        }
    }

    /** Patch the installed Bluetooth app copy (resource lookup by name, so it works across versions), then overlay it. */
    private void applySpeaker(final boolean userInitiated) {
        if (btBusy) return;
        btBusy = true;
        btMsg = "";
        ui.post(this::renderBt);
        new Thread(() -> {
            String result;
            try {
                File dir = getFilesDir();
                File in = new File(dir, "bt_in.apk"), out = new File(dir, "bt_out.apk"), sh = new File(dir, "btspeaker.sh");
                copyAsset("btspeaker.sh", sh);
                String uid = String.valueOf(android.os.Process.myUid());
                su("ORIG=$(ls /apex/com.android.bt/app/Bluetooth@*/Bluetooth.apk | head -1); cp \"$ORIG\" '" + in
                    + "' && chown " + uid + ":" + uid + " '" + in + "' && chmod 600 '" + in + "'", 60);
                int n = BtPatcher.patchBoolTrue(in, out, "a2dp_sink_automatically_request_audio_focus");
                if (n == 0) throw new IllegalStateException("this Bluetooth version has no such setting");
                result = su("sh '" + sh + "' apply '" + out + "'", 150).trim();
                in.delete();
                out.delete();
            } catch (Exception e) {
                result = "failed: " + e.getMessage();
            }
            final String r = result;
            btMsg = r.endsWith("ok") ? "" : r;
            btBusy = false;
            ui.post(this::renderBt);
        }, "bt-setup").start();
    }

    private void turnSpeakerOff() {
        btBusy = true;
        ui.post(this::renderBt);
        new Thread(() -> {
            try {
                File sh = new File(getFilesDir(), "btspeaker.sh");
                copyAsset("btspeaker.sh", sh);
                su("sh '" + sh + "' off", 150);
            } catch (Exception ignored) { }
            btBusy = false;
            ui.post(this::renderBt);
        }, "bt-off").start();
    }

    /** Start-at-boot is a marker file read by BootReceiver after the first unlock; no boot script is installed. */
    private void toggleBoot() {
        if (lockedOut()) return;
        Map<String, String> m = bt;
        final boolean on = m != null && "1".equals(m.get("boot"));
        new AlertDialog.Builder(this)
            .setTitle(on ? "Stop starting at boot?" : "Start the dashboard at boot?")
            .setMessage(on ? "The dashboard will no longer open by itself after a reboot."
                : "After each reboot, once the phone has been unlocked, this app opens the dashboard, keeps the screen on "
                + "while plugged in, and re-applies the Bluetooth speaker setup.")
            .setPositiveButton(on ? "Turn off" : "Turn on", (d, w) -> new Thread(() -> {
                try {
                    su(on ? "rm -f /data/adb/dashboard/autostart"
                          : "mkdir -p /data/adb/dashboard && touch /data/adb/dashboard/autostart", 15);
                } catch (Exception ignored) { }
            }, "boot-toggle").start())
            .setNegativeButton("Cancel", null)
            .show();
    }

    /** Engine at boot: this app (direct boot) instead of the Magisk module. Takes effect at the next reboot. */
    private void toggleEngine() {
        if (lockedOut()) return;
        Map<String, String> m = svc;
        final boolean on = m != null && "1".equals(m.get("flag.engine"));
        new AlertDialog.Builder(this)
            .setTitle(on ? "Hand the engine back to the module?" : "Run the engine from this app at boot?")
            .setMessage(on ? "Re-enables the hotspot_smb Magisk module and stops this app starting the engine. Takes effect at the next reboot."
                : "At boot this app starts the hotspot, DHCP/NAT, file sharing and the other services, even before the phone is "
                + "unlocked, and the hotspot_smb Magisk module is disabled so only one of them starts them. Takes effect at the next "
                + "reboot. If the hotspot ever fails to come back, turn this off here or delete /data/adb/modules/hotspot_smb/disable.")
            .setPositiveButton(on ? "Hand back" : "Turn on", (d, w) -> new Thread(() -> {
                try {
                    su(on ? "rm -f /data/adb/dashboard/engine_takeover; rm -f " + Engine.MODULE + "/disable"
                          : "mkdir -p /data/adb/dashboard && touch /data/adb/dashboard/engine_takeover; [ -d " + Engine.MODULE
                            + " ] && touch " + Engine.MODULE + "/disable; true", 15);
                } catch (Exception ignored) { }
            }, "engine-toggle").start())
            .setNegativeButton("Cancel", null)
            .show();
    }

    /** A reinstalled app can come back with Android's "no network" flag set; clear it (needs root). */
    private void clearOwnNetworkBlock() {
        try {
            int uid = android.os.Process.myUid();
            String n = su("dumpsys netpolicy | grep -c 'UID=" + uid + " policy=262144'", 15).trim();
            if (!n.isEmpty() && !n.equals("0"))
                su("CLASSPATH=" + getPackageCodePath() + " app_process /system/bin com.tonybenoy.dashboard.SystemTool netpolicy " + uid, 20);
        } catch (Exception ignored) { }
    }

    private void copyAsset(String name, File to) throws Exception {
        try (InputStream in = getAssets().open(name); FileOutputStream o = new FileOutputStream(to)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        }
    }

    /** Runs one root command and returns its output. */
    private static String su(String cmd, int timeoutSec) throws Exception {
        Process p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append('\n');
        }
        p.waitFor(timeoutSec, TimeUnit.SECONDS);
        return sb.toString();
    }

    // ---------------------------------------------------------------- actions

    private boolean lockedOut() {
        if (!getSystemService(KeyguardManager.class).isKeyguardLocked()) return false;
        Toast.makeText(this, "Unlock the phone to change settings", Toast.LENGTH_SHORT).show();
        return true;
    }

    private void nameDialog(final String mac, String ip) {
        if (lockedOut()) return;
        final EditText e = new EditText(this);
        e.setText(prefs.getString("name_" + mac, ""));
        e.setSingleLine(true);
        new AlertDialog.Builder(this)
            .setTitle("Name this device")
            .setMessage(ip + "  " + mac)
            .setView(e)
            .setPositiveButton("Save", (d, w) -> {
                prefs.edit().putString("name_" + mac, e.getText().toString().trim()).apply();
                render();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void confirmBand(final int ghz) {
        if (lockedOut()) return;
        RootLink.Probe p = probe;
        if (switching || p == null || p.ip.isEmpty()) return;
        boolean already = ghz == 2 ? p.freq < 3000 : p.freq >= 4900;
        if (already) return;
        new AlertDialog.Builder(this)
            .setTitle("Switch hotspot to " + (ghz == 2 ? "2.4" : "5") + " GHz?")
            .setMessage("The hotspot restarts and connected devices drop for about 15–20 seconds.\n\n"
                + (ghz == 2 ? "2.4 GHz: longer range, works with older devices, slower."
                            : "5 GHz: faster, shorter range."))
            .setPositiveButton("Switch", (d, w) -> switchBand(ghz))
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void switchBand(final int ghz) {
        switching = true;
        render();
        // The engine's own sequence: remember the band, restart the access point, then re-apply the fixed subnet and
        // DHCP/NAT straight away. (Restarting through Android's tethering API instead lets Android's DHCP hand clients a
        // lease the engine then pulls the subnet out from under, and they lose DNS.)
        final String script =
            "E=" + ServiceCtl.ENGINE + "\n"
          + "cmd wifi force-softap-band enabled " + ghz + "\n"
          + "mkdir -p " + ServiceCtl.PERSIST + "; echo " + ghz + " > " + ServiceCtl.PERSIST + "/band\n"
          + "sh $E/service.sh hotspot; sh $E/service.sh network\n";
        new Thread(() -> {
            try {
                Process pr = new ProcessBuilder("su", "-c", script).redirectErrorStream(true).start();
                BufferedReader r = new BufferedReader(new InputStreamReader(pr.getInputStream()));
                while (r.readLine() != null) { /* drain */ }
                pr.waitFor(120, TimeUnit.SECONDS);
                pr.destroy();
            } catch (Exception ignored) { }
            ui.postDelayed(() -> { switching = false; render(); }, 4000);
        }, "band-switch").start();
    }
}
