package com.tonybenoy.dashboard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

/**
 * Background. Pure black by default (GLOW = false): the glass panels get their depth from rim light and shadows alone.
 * Set GLOW to true for the optional version with a few large, soft colour glows that drift very slowly. The glass panels sit on top of it
 * and show it through. It is updated once a second (the drift is a pixel every couple of seconds), so it costs nothing,
 * and it stays dark overall so the screen is comfortable at night and on an always-on display.
 */
public class AuroraView extends View {
    private static final boolean GLOW = false;
    private static final int[] COLORS = {0xFF18C9A6, 0xFF6B4DFF, 0xFF1F7AE0, 0xFFC23AAE};
    private static final float[] FX = {0.16f, 0.86f, 0.52f, 0.06f};
    private static final float[] FY = {0.18f, 0.30f, 0.92f, 0.82f};
    private static final float[] RAD = {0.72f, 0.66f, 0.80f, 0.46f};
    private static final float[] ALPHA = {0.30f, 0.28f, 0.26f, 0.16f};

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private RadialGradient[] glow;
    private float[] radius;
    private Paint base;
    private long timeMs;

    public AuroraView(Context c) {
        super(c);
    }

    public void setTime(long ms) {
        timeMs = ms;
        if (GLOW) invalidate();          // pure black never changes, so nothing to redraw
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        base = new Paint();
        base.setShader(new LinearGradient(0, 0, 0, h, GLOW ? 0xFF070910 : 0xFF000000, GLOW ? 0xFF0B1019 : 0xFF000000, Shader.TileMode.CLAMP));
        glow = new RadialGradient[COLORS.length];
        radius = new float[COLORS.length];
        float big = Math.max(w, h);
        for (int i = 0; i < COLORS.length; i++) {
            radius[i] = RAD[i] * big;
            int a = (int) (ALPHA[i] * 255);
            glow[i] = new RadialGradient(radius[i], radius[i], radius[i], (COLORS[i] & 0x00FFFFFF) | (a << 24), COLORS[i] & 0x00FFFFFF, Shader.TileMode.CLAMP);
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        if (base == null) return;
        int w = getWidth(), h = getHeight();
        c.drawRect(0, 0, w, h, base);
        if (!GLOW) return;
        double t = timeMs;
        for (int i = 0; i < COLORS.length; i++) {
            float cx = w * FX[i] + (float) (0.05 * w * Math.sin(t / 41000.0 + i * 1.7));
            float cy = h * FY[i] + (float) (0.04 * h * Math.cos(t / 53000.0 + i * 2.3));
            c.save();
            c.translate(cx - radius[i], cy - radius[i]);
            paint.setShader(glow[i]);
            c.drawCircle(radius[i], radius[i], radius[i], paint);
            c.restore();
        }
    }
}
