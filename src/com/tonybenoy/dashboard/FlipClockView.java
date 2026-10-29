package com.tonybenoy.dashboard;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

/**
 * Split-flap HH:MM clock that fills its bounds (one row when wide, two stacked rows when tall).
 * Cards are brushed-glass: a lit rim, a soft sheen on the upper half, a real hinge, gradient digits and a drop shadow
 * that is rendered once per size. A thin seconds bar runs underneath.
 */
public class FlipClockView extends View {
    private static final long ANIM_MS = 650;
    private static final float CARD_RATIO_WIDE = 1.35f;   // height / width
    private static final float CARD_RATIO_STACK = 1.15f;

    private final int[] cur = {-1, -1, -1, -1};
    private final int[] prev = new int[4];
    private final long[] start = new long[4];
    private int sec;

    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shade = new Paint();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint secPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float dp;
    private Bitmap shadowBmp;
    private int shadowForW, shadowForH;
    private float shadowPad;

    public FlipClockView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        shade.setColor(0xFF000000);
        secPaint.setColor(Glass.DIM);
        secPaint.setTextAlign(Paint.Align.CENTER);
        secPaint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
    }

    public void setTime(int h, int m, int s) {
        int[] d = {h / 10, h % 10, m / 10, m % 10};
        long now = SystemClock.uptimeMillis();
        for (int i = 0; i < 4; i++) {
            if (cur[i] != d[i]) {
                if (cur[i] < 0) { prev[i] = d[i]; start[i] = 0; }
                else { prev[i] = cur[i]; start[i] = now; }
                cur[i] = d[i];
            }
        }
        sec = s;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (cur[0] < 0) return;
        float W = getWidth(), H = getHeight();
        float g = 12 * dp, colon = 30 * dp;
        float secH = 50 * dp;

        float wideW = Math.min((W - 3 * g - colon) / 4f, (H - secH) * 0.92f / CARD_RATIO_WIDE);
        float stackW = Math.min((W - g) / 2f, (H - secH - g) * 0.92f / (2 * CARD_RATIO_STACK));
        boolean stacked = stackW > wideW;
        float cw = stacked ? stackW : wideW;
        float ch = cw * (stacked ? CARD_RATIO_STACK : CARD_RATIO_WIDE);

        float totalH = stacked ? 2 * ch + g : ch;
        float top = (H - totalH - secH) / 2f;
        long now = SystemClock.uptimeMillis();
        boolean animating = false;

        float[] xs = new float[4], ys = new float[4];
        float rowW;
        if (stacked) {
            float x0 = (W - (2 * cw + g)) / 2f;
            rowW = 2 * cw + g;
            xs[0] = x0; xs[1] = x0 + cw + g; ys[0] = ys[1] = top;
            xs[2] = x0; xs[3] = x0 + cw + g; ys[2] = ys[3] = top + ch + g;
        } else {
            rowW = 4 * cw + 3 * g + colon;                  // three gaps: before the colon, after it, and between the minute digits
            float x0 = (W - rowW) / 2f;
            xs[0] = x0; xs[1] = x0 + cw + g;
            xs[2] = x0 + 2 * cw + 2 * g + colon; xs[3] = xs[2] + cw + g;
            ys[0] = ys[1] = ys[2] = ys[3] = top;
            float cx = x0 + 2 * cw + g + (g + colon) / 2f;    // middle of the gap between the hour and minute cards
            float rad = Math.max(3.5f * dp, ch * 0.04f);
            for (float cy : new float[]{top + ch * 0.36f, top + ch * 0.64f}) {
                paint.setShader(new RadialGradient(cx, cy, rad * 3.2f, 0x5546E3B7, 0x0046E3B7, Shader.TileMode.CLAMP));
                c.drawCircle(cx, cy, rad * 3.2f, paint);
                paint.setShader(null);
                paint.setColor(Glass.MINT);
                c.drawCircle(cx, cy, rad, paint);
            }
        }

        for (int i = 0; i < 4; i++) {
            float p = start[i] == 0 ? 1f : Math.min(1f, (now - start[i]) / (float) ANIM_MS);
            if (p < 1f) animating = true;
            drawShadow(c, xs[i], ys[i], cw, ch);
            drawCard(c, xs[i], ys[i], cw, ch, i, p);
        }

        // seconds: a thin glass track with a glowing fill
        float barW = Math.min(rowW * 0.9f, W * 0.8f), barH = 5 * dp;
        float bx = (W - barW) / 2f, by = top + totalH + 16 * dp;
        paint.setShader(null);
        paint.setColor(0x22FFFFFF);
        c.drawRoundRect(new RectF(bx, by, bx + barW, by + barH), barH, barH, paint);
        float fillW = Math.max(barH, barW * (sec + 1) / 60f);
        paint.setMaskFilter(new BlurMaskFilter(5 * dp, BlurMaskFilter.Blur.NORMAL));
        paint.setColor(0x6646E3B7);
        c.drawRoundRect(new RectF(bx, by, bx + fillW, by + barH), barH, barH, paint);
        paint.setMaskFilter(null);
        paint.setShader(new LinearGradient(bx, 0, bx + barW, 0, Glass.MINT, Glass.BLUE, Shader.TileMode.CLAMP));
        c.drawRoundRect(new RectF(bx, by, bx + fillW, by + barH), barH, barH, paint);
        paint.setShader(null);
        secPaint.setTextSize(15 * dp);
        c.drawText(String.format("%02d", sec), W / 2f, by + barH + 20 * dp, secPaint);

        if (animating) postInvalidateOnAnimation();
    }

    /** Drop shadow (dark to the bottom-right, a faint light halo to the top-left); cached per card size. */
    private void drawShadow(Canvas c, float x, float y, float w, float h) {
        int bw = Math.round(w), bh = Math.round(h);
        if (shadowBmp == null || shadowForW != bw || shadowForH != bh) {
            shadowPad = 16 * dp;
            int pw = bw + Math.round(2 * shadowPad), ph = bh + Math.round(2 * shadowPad);
            shadowBmp = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(shadowBmp);
            float rad = w * 0.09f;
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            RectF body = new RectF(shadowPad, shadowPad, shadowPad + w, shadowPad + h);
            p.setMaskFilter(new BlurMaskFilter(12 * dp, BlurMaskFilter.Blur.NORMAL));
            p.setColor(0xB0000000);
            RectF s = new RectF(body);
            s.offset(4 * dp, 7 * dp);
            cv.drawRoundRect(s, rad, rad, p);
            p.setMaskFilter(new BlurMaskFilter(10 * dp, BlurMaskFilter.Blur.NORMAL));
            p.setColor(0x16FFFFFF);
            s = new RectF(body);
            s.offset(-4 * dp, -4 * dp);
            cv.drawRoundRect(s, rad, rad, p);
            shadowForW = bw;
            shadowForH = bh;
        }
        c.drawBitmap(shadowBmp, x - shadowPad, y - shadowPad, null);
    }

    private void drawCard(Canvas c, float x, float y, float w, float h, int i, float p) {
        r.set(x, y, x + w, y + h);
        text.setTextSize(h * 0.80f);
        if (p >= 1f) {
            drawHalf(c, cur[i], true, 1f, 0);
            drawHalf(c, cur[i], false, 1f, 0);
        } else {
            drawHalf(c, cur[i], true, 1f, 0);
            drawHalf(c, prev[i], false, 1f, 0);
            double a = p * Math.PI;
            if (p < 0.5f) {
                float s = (float) Math.cos(a);
                drawHalf(c, prev[i], true, s, (int) ((1 - s) * 150));
            } else {
                float s = (float) -Math.cos(a);
                drawHalf(c, cur[i], false, s, (int) ((1 - s) * 150));
            }
        }
        // hinge: a dark seam with a lit lip beneath it
        float cy = r.centerY();
        paint.setShader(null);
        paint.setColor(0xF0000000);
        c.drawRect(r.left, cy - 1.6f * dp, r.right, cy + 1.2f * dp, paint);
        paint.setColor(0x1CFFFFFF);
        c.drawRect(r.left, cy + 1.2f * dp, r.right, cy + 2.2f * dp, paint);
    }

    private void drawHalf(Canvas c, int digit, boolean top, float sy, int shadeAlpha) {
        float cy = r.centerY();
        c.save();
        c.scale(1f, Math.max(sy, 0.001f), r.centerX(), cy);
        c.clipRect(r.left, top ? r.top : cy, r.right, top ? cy : r.bottom);
        float rad = r.width() * 0.09f;

        // brushed metal body
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(top
            ? new LinearGradient(0, r.top, 0, cy, 0xFF313845, 0xFF242935, Shader.TileMode.CLAMP)
            : new LinearGradient(0, cy, 0, r.bottom, 0xFF1F242E, 0xFF161A22, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, rad, rad, paint);

        // glass sheen from the top-left
        paint.setShader(new LinearGradient(r.left, r.top, r.centerX(), cy, top ? 0x30FFFFFF : 0x10FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, rad, rad, paint);

        // digit with a vertical gradient and a soft shadow
        text.setShader(new LinearGradient(0, r.top, 0, r.bottom, 0xFFFFFFFF, 0xFFB9C3D6, Shader.TileMode.CLAMP));
        text.setShadowLayer(4 * dp, 0, 2 * dp, 0x99000000);
        Paint.FontMetrics fm = text.getFontMetrics();
        c.drawText(String.valueOf(digit), r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2f, text);

        // lit rim, brightest at the top-left
        paint.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom, 0x77FFFFFF, 0x14FFFFFF, Shader.TileMode.CLAMP));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f * dp);
        RectF in = new RectF(r);
        in.inset(0.75f * dp, 0.75f * dp);
        c.drawRoundRect(in, rad, rad, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);

        if (shadeAlpha > 0) {
            shade.setAlpha(Math.min(255, shadeAlpha));
            c.drawRect(r, shade);
        }
        c.restore();
    }
}
