package com.tonybenoy.dashboard;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Folder / file browser that lists the phone's storage as root. (Android's own picker cannot select /sdcard itself,
 * which is what a share path usually is, and cannot reach /storage or /data.)
 */
final class PathPicker {
    private PathPicker() { }

    interface OnPick { void picked(String path); }

    static void show(final Activity a, String title, String start, final boolean files, final OnPick cb, final Runnable onType) {
        final float d = a.getResources().getDisplayMetrics().density;
        final String[] cur = {start == null || start.isEmpty() ? "/sdcard" : start};
        if (files && cur[0].lastIndexOf('/') > 0 && !cur[0].endsWith("/")) cur[0] = cur[0].substring(0, cur[0].lastIndexOf('/'));

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (20 * d), (int) (8 * d), (int) (20 * d), 0);
        final TextView pathView = new TextView(a);
        pathView.setTypeface(Typeface.MONOSPACE);
        pathView.setTextSize(13);
        pathView.setTextColor(0xFF9E9E9E);
        pathView.setPadding(0, 0, 0, (int) (8 * d));
        box.addView(pathView);
        final ListView list = new ListView(a);
        final List<String> rows = new ArrayList<>();
        final ArrayAdapter<String> ad = new ArrayAdapter<String>(a, android.R.layout.simple_list_item_1, rows) {
            @Override
            public View getView(int pos, View v, ViewGroup parent) {
                TextView t = (TextView) super.getView(pos, v, parent);
                String r = rows.get(pos);
                t.setTextSize(16);
                t.setTextColor(r.endsWith("/") || r.equals("..") ? Color.WHITE : 0xFFBDBDBD);
                return t;
            }
        };
        list.setAdapter(ad);
        box.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (300 * d)));

        AlertDialog.Builder b = new AlertDialog.Builder(a).setTitle(title).setView(box)
            .setNegativeButton("Cancel", null);
        if (onType != null) b.setNeutralButton("Type path", (x, w) -> onType.run());
        if (!files) b.setPositiveButton("Use this folder", (x, w) -> cb.picked(cur[0]));
        final AlertDialog dlg = b.create();

        final Runnable[] load = new Runnable[1];
        load[0] = () -> {
            final String path = cur[0];
            pathView.setText(path);
            new Thread(() -> {
                String out;
                try { out = Root.su("ls -1Ap " + Root.q(path) + " 2>&1", 15); } catch (Exception e) { out = "ls: " + e.getMessage(); }
                final List<String> dirs = new ArrayList<>(), fl = new ArrayList<>();
                boolean failed = false;
                for (String line : out.split("\n")) {
                    if (line.isEmpty()) continue;
                    if (line.startsWith("ls:")) { failed = true; break; }
                    if (line.endsWith("/")) dirs.add(line); else if (files) fl.add(line);
                }
                Collections.sort(dirs, String.CASE_INSENSITIVE_ORDER);
                Collections.sort(fl, String.CASE_INSENSITIVE_ORDER);
                final boolean bad = failed;
                a.runOnUiThread(() -> {
                    if (bad && !path.equals("/")) {                           // vanished or unreadable: go up
                        cur[0] = path.contains("/") && path.lastIndexOf('/') > 0 ? path.substring(0, path.lastIndexOf('/')) : "/";
                        load[0].run();
                        return;
                    }
                    rows.clear();
                    if (!path.equals("/")) rows.add("..");
                    rows.addAll(dirs);
                    rows.addAll(fl);
                    ad.notifyDataSetChanged();
                });
            }, "path-list").start();
        };
        list.setOnItemClickListener((p, v, pos, id) -> {
            String r = rows.get(pos);
            if (r.equals("..")) {
                int i = cur[0].lastIndexOf('/');
                cur[0] = i <= 0 ? "/" : cur[0].substring(0, i);
                load[0].run();
            } else if (r.endsWith("/")) {
                cur[0] = (cur[0].equals("/") ? "" : cur[0]) + "/" + r.substring(0, r.length() - 1);
                load[0].run();
            } else {
                dlg.dismiss();
                cb.picked((cur[0].equals("/") ? "" : cur[0]) + "/" + r);
            }
        });
        dlg.show();
        load[0].run();
    }
}
