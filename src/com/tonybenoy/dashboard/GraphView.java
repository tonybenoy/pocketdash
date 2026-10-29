package com.tonybenoy.dashboard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Rolling download/upload throughput graph (values in bits per second). */
public class GraphView extends View {
    public static final int DOWN = 0xFF46E3B7, UP = 0xFF5AA9FF;
    private static final int N = 150;                       // 5 minutes at one sample / 2s
    private static final float[] STEPS = {1e6f, 2e6f, 5e6f, 10e6f, 20e6f, 50e6f, 100e6f, 200e6f, 500e6f, 1e9f};

    private final float[] down = new float[N], up = new float[N];
    private int count;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint();
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float dp;

    public GraphView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2 * dp);
        line.setStrokeJoin(Paint.Join.ROUND);
        fill.setStyle(Paint.Style.FILL);
        grid.setColor(0x22FFFFFF);
        grid.setStrokeWidth(1 * dp);
        label.setColor(0xFF8A8A8A);
        label.setTextSize(11 * dp);
    }

    public void add(float d, float u) {
        if (count == N) {
            System.arraycopy(down, 1, down, 0, N - 1);
            System.arraycopy(up, 1, up, 0, N - 1);
            count--;
        }
        down[count] = d;
        up[count] = u;
        count++;
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float max = 0;
        for (int i = 0; i < count; i++) max = Math.max(max, Math.max(down[i], up[i]));
        float scale = STEPS[STEPS.length - 1];
        for (float s : STEPS) if (max <= s) { scale = s; break; }

        for (int i = 1; i <= 3; i++) {
            float y = h - h * i / 4f;
            c.drawLine(0, y, w, y, grid);
        }
        c.drawText(fmt(scale), 4 * dp, 13 * dp, label);

        draw(c, up, UP, scale, w, h);
        draw(c, down, DOWN, scale, w, h);
    }

    private void draw(Canvas c, float[] v, int color, float scale, float w, float h) {
        if (count < 2) return;
        float step = w / (N - 1f);
        float x0 = w - (count - 1) * step;          // newest sample sits at the right edge
        path.reset();
        for (int i = 0; i < count; i++) {
            float x = x0 + i * step;
            float y = h - Math.min(1f, v[i] / scale) * (h - 2 * dp) - dp;
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        line.setColor(color);
        line.setAlpha(0x30);
        line.setStrokeWidth(6 * dp);              // soft glow under the line
        c.drawPath(path, line);
        line.setStrokeWidth(2 * dp);
        line.setColor(color);
        c.drawPath(path, line);
        path.lineTo(w, h);
        path.lineTo(x0, h);
        path.close();
        fill.setShader(new android.graphics.LinearGradient(0, 0, 0, h, (color & 0x00FFFFFF) | 0x55000000, color & 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP));
        c.drawPath(path, fill);
    }

    public static String fmt(float bps) {
        if (bps >= 1e9f) return String.format("%.1f Gb/s", bps / 1e9f);
        if (bps >= 1e6f) return String.format(bps >= 1e7f ? "%.0f Mb/s" : "%.1f Mb/s", bps / 1e6f);
        return String.format("%.0f kb/s", bps / 1e3f);
    }
}
