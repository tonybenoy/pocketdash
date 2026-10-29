package com.tonybenoy.dashboard;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One long-lived root shell that streams hotspot snapshots and interface counters. */
public class RootLink {

    public static class Probe {
        public String iface = "", ssid = "", ip = "";
        public int freq;
        public final List<String[]> clients = new ArrayList<>(); // {ip, mac}
    }

    public interface Listener {
        void onProbe(Probe p);
        void onNet(long rx, long tx, long nanos);
        void onBt(Map<String, String> bt);
        void onSvc(Map<String, String> svc);
        void onDevices(List<String[]> devices);
        void onError(String msg);
    }

    private volatile boolean running;
    private volatile Process proc;
    private Thread thread;

    public void start(final String script, final Listener l) {
        running = true;
        thread = new Thread(() -> {
            while (running) {
                try {
                    proc = new ProcessBuilder("su", "-c", script).redirectErrorStream(true).start();
                    BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                    Probe cur = null;
                    Map<String, String> curBt = null, curSvc = null;
                    List<String[]> curDev = null;
                    String line;
                    boolean any = false;
                    while (running && (line = r.readLine()) != null) {
                        any = true;
                        if (line.equals("probe_begin")) { cur = new Probe(); continue; }
                        if (line.equals("probe_end")) { if (cur != null) l.onProbe(cur); cur = null; continue; }
                        if (line.equals("dev_begin")) { curDev = new ArrayList<>(); continue; }
                        if (line.equals("dev_end")) { if (curDev != null) l.onDevices(curDev); curDev = null; continue; }
                        if (line.equals("svc_begin")) { curSvc = new HashMap<>(); continue; }
                        if (line.equals("svc_end")) { if (curSvc != null) l.onSvc(curSvc); curSvc = null; continue; }
                        if (line.equals("bt_begin")) { curBt = new HashMap<>(); continue; }
                        if (line.equals("bt_end")) { if (curBt != null) l.onBt(curBt); curBt = null; continue; }
                        int i = line.indexOf('=');
                        if (i < 0) continue;
                        String k = line.substring(0, i), v = line.substring(i + 1).trim();
                        if (curDev != null && k.equals("dev")) {
                            curDev.add(v.split("\\|", -1));
                        } else if (curSvc != null && (k.startsWith("svc.") || k.startsWith("flag.") || k.startsWith("bat."))) {
                            curSvc.put(k, v);
                        } else if (curBt != null && k.startsWith("bt.")) {
                            curBt.put(k.substring(3), v);
                        } else if (k.equals("net")) {
                            String[] f = v.split(" ");
                            try { l.onNet(Long.parseLong(f[0]), Long.parseLong(f[1]), System.nanoTime()); }
                            catch (Exception ignored) { }
                        } else if (cur != null) {
                            switch (k) {
                                case "iface": cur.iface = v; break;
                                case "ssid": cur.ssid = v; break;
                                case "ip": cur.ip = v; break;
                                case "freq": try { cur.freq = Integer.parseInt(v); } catch (Exception ignored) { } break;
                                case "client": cur.clients.add(v.split("\\|")); break;
                                default: break;
                            }
                        }
                    }
                    if (running) l.onError(any ? "root shell ended" : "root unavailable (su denied?)");
                } catch (Exception e) {
                    if (running) l.onError("root error: " + e.getMessage());
                } finally {
                    if (proc != null) proc.destroy();
                }
                try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
            }
        }, "root-link");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        if (proc != null) proc.destroy();
        if (thread != null) thread.interrupt();
    }
}
