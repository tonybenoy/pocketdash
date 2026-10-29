package com.tonybenoy.dashboard;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/** Runs one command as root (su) and returns its combined output. */
final class Root {
    private Root() { }

    static String su(String cmd, int timeoutSec) throws Exception {
        Process p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append('\n');
        }
        p.waitFor(timeoutSec, TimeUnit.SECONDS);
        return sb.toString();
    }

    /** Single-quote a value for a shell command line. */
    static String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
