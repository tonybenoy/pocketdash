package com.tonybenoy.dashboard;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.ColorFilter;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;

/**
 * The look of the app: "liquid glass" panels with neumorphic depth.
 *
 * Panels are translucent (the aurora background shows through), with a specular sheen, a rim light that is brightest
 * on the top-left, and a soft double shadow (light from the top-left, dark to the bottom-right). Buttons are raised
 * soft pills that press inward. Every surface is rendered once into a bitmap per size, so drawing costs nothing.
 */
final class Glass {
    private Glass() { }

    static final int MINT = 0xFF46E3B7, BLUE = 0xFF5AA9FF, AMBER = 0xFFFFB84D, RED = 0xFFFF6B7D;
    static final int TEXT = 0xFFF2F5FA, DIM = 0xFF98A2B5;

    /** Room each drawable reserves around the visible shape for its shadows, in dp. */
    static final float CARD_PAD = 10, PILL_PAD = 5;

    static Drawable card(Context c, float radiusDp) {
        return new Surface(c, Surface.CARD, radiusDp, CARD_PAD, 0);
    }

    /** Raised soft pill that sinks when pressed. */
    static Drawable raised(Context c, float radiusDp) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, new Surface(c, Surface.INSET, radiusDp, PILL_PAD, 0));
        s.addState(new int[]{}, new Surface(c, Surface.RAISED, radiusDp, PILL_PAD, 0));
        s.setEnterFadeDuration(60);
        s.setExitFadeDuration(120);
        return s;
    }

    /** Selected / primary control: a glowing coloured glass pill. */
    static Drawable accent(Context c, int color, float radiusDp) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, new Surface(c, Surface.ACCENT, radiusDp, PILL_PAD, mix(color, Color.BLACK, 0.25f)));
        s.addState(new int[]{}, new Surface(c, Surface.ACCENT, radiusDp, PILL_PAD, color));
        s.setEnterFadeDuration(60);
        s.setExitFadeDuration(120);
        return s;
    }

    /** Faint highlight for tappable rows. */
    static Drawable rowPress(Context c, float radiusDp) {
        StateListDrawable s = new StateListDrawable();
        GradientHolder g = new GradientHolder(c, radiusDp);
        s.addState(new int[]{android.R.attr.state_pressed}, g);
        s.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        return s;
    }

    static int mix(int a, int b, float t) {
        return Color.argb(255,
            (int) (Color.red(a) * (1 - t) + Color.red(b) * t),
            (int) (Color.green(a) * (1 - t) + Color.green(b) * t),
            (int) (Color.blue(a) * (1 - t) + Color.blue(b) * t));
    }

    // ------------------------------------------------------------------------------------------------

    /** One rounded surface, cached as a bitmap of exactly its size. */
    static final class Surface extends Drawable {
        static final int CARD = 0, RAISED = 1, INSET = 2, ACCENT = 3;

        private final int kind, accent;
        private final float density, radius, pad;
        private Bitmap cache;
        private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG);

        Surface(Context c, int kind, float radiusDp, float padDp, int accent) {
            this.kind = kind;
            this.accent = accent;
            this.density = c.getResources().getDisplayMetrics().density;
            this.radius = radiusDp * density;
            this.pad = padDp * density;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (b.width() <= 2 || b.height() <= 2) return;
            if (cache == null || cache.getWidth() != b.width() || cache.getHeight() != b.height()) cache = render(b.width(), b.height());
            canvas.drawBitmap(cache, b.left, b.top, bmp);
        }

        private Paint fill(int color) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(color);
            return p;
        }

        private void shadow(Canvas cv, RectF body, float r, int color, float blur, float dx, float dy) {
            Paint p = fill(color);
            p.setMaskFilter(new BlurMaskFilter(Math.max(1f, blur), BlurMaskFilter.Blur.NORMAL));
            RectF s = new RectF(body);
            s.offset(dx, dy);
            cv.drawRoundRect(s, r, r, p);
        }

        private void clearBody(Canvas cv, RectF body, float r) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
            cv.drawRoundRect(body, r, r, p);
        }

        private Bitmap render(int w, int h) {
            Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(bm);
            RectF body = new RectF(pad, pad, w - pad, h - pad);
            float r = Math.min(radius, Math.min(body.width(), body.height()) / 2f);
            float d = density;

            switch (kind) {
                case CARD:
                    shadow(cv, body, r, 0x99000000, pad * 0.85f, pad * 0.30f, pad * 0.40f);     // dark, bottom-right
                    shadow(cv, body, r, 0x10FFFFFF, pad * 0.70f, -pad * 0.28f, -pad * 0.28f);   // light, top-left
                    clearBody(cv, body, r);                                                     // glass: shadows only outside
                    cv.drawRoundRect(body, r, r, fill(0xB3131821));                             // lifted glass tint, keeps text legible
                    glassBody(cv, body, r, 0x34FFFFFF, 0x10FFFFFF, 0x44FFFFFF, 0x2E000000);
                    rim(cv, body, r, 1.3f * d, 0xCCFFFFFF, 0x30FFFFFF, 0x16FFFFFF, 0x55FFFFFF);
                    break;
                case RAISED:
                    shadow(cv, body, r, 0x8C000000, pad * 0.9f, pad * 0.35f, pad * 0.45f);
                    shadow(cv, body, r, 0x16FFFFFF, pad * 0.7f, -pad * 0.3f, -pad * 0.3f);
                    clearBody(cv, body, r);
                    cv.drawRoundRect(body, r, r, fill(0xB31B212C));
                    glassBody(cv, body, r, 0x3AFFFFFF, 0x12FFFFFF, 0x38FFFFFF, 0x2A000000);
                    rim(cv, body, r, 1.1f * d, 0xB0FFFFFF, 0x2AFFFFFF, 0x16FFFFFF, 0x48FFFFFF);
                    break;
                case INSET: {
                    shadow(cv, body, r, 0x30000000, pad * 0.4f, 0, pad * 0.1f);
                    clearBody(cv, body, r);
                    cv.drawRoundRect(body, r, r, fill(0xB0080A0F));
                    innerShadow(cv, body, r);
                    rim(cv, body, r, 1f * d, 0x30FFFFFF, 0x10FFFFFF, 0x10FFFFFF, 0x22FFFFFF);
                    break;
                }
                default: {                                                                      // ACCENT
                    shadow(cv, body, r, accent & 0x00FFFFFF | 0x77000000, pad * 1.15f, 0, pad * 0.25f);   // coloured glow
                    shadow(cv, body, r, 0x66000000, pad * 0.8f, pad * 0.3f, pad * 0.45f);
                    Paint p = fill(accent);
                    p.setShader(new LinearGradient(0, body.top, 0, body.bottom, mix(accent, Color.WHITE, 0.22f), mix(accent, Color.BLACK, 0.12f), Shader.TileMode.CLAMP));
                    cv.drawRoundRect(body, r, r, p);
                    glassBody(cv, body, r, 0x00FFFFFF, 0x00FFFFFF, 0x55FFFFFF, 0x22000000);
                    rim(cv, body, r, 1.2f * d, 0xDDFFFFFF, 0x44FFFFFF, 0x22FFFFFF, 0x55FFFFFF);
                }
            }
            return bm;
        }

        /** Vertical glass gradient, diagonal specular sheen, bottom depth shade and a lit top edge. */
        private void glassBody(Canvas cv, RectF body, float r, int top, int bottom, int sheen, int shade) {
            float d = density;
            cv.save();
            Path clip = new Path();
            clip.addRoundRect(body, r, r, Path.Direction.CW);
            cv.clipPath(clip);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            if (top != 0 || bottom != 0) {
                p.setShader(new LinearGradient(0, body.top, 0, body.bottom, top, bottom, Shader.TileMode.CLAMP));
                cv.drawRect(body, p);
            }
            p.setShader(new LinearGradient(body.left, body.top, body.left + body.width() * 0.65f, body.top + body.height() * 0.55f,
                sheen, sheen & 0x00FFFFFF, Shader.TileMode.CLAMP));
            cv.drawRect(body, p);
            p.setShader(new LinearGradient(0, body.bottom - body.height() * 0.42f, 0, body.bottom, shade & 0x00FFFFFF, shade, Shader.TileMode.CLAMP));
            cv.drawRect(body, p);
            p.setShader(new LinearGradient(body.left + r, 0, body.right - r, 0, new int[]{0x00FFFFFF, 0x70FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
            cv.drawRect(body.left + r * 0.5f, body.top + d, body.right - r * 0.5f, body.top + 2.2f * d, p);
            cv.restore();
        }

        /** Rim light: bright top-left, softer bottom-right, like the edge of a glass pane. */
        private void rim(Canvas cv, RectF body, float r, float width, int c0, int c1, int c2, int c3) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(width);
            p.setShader(new LinearGradient(body.left, body.top, body.right, body.bottom, new int[]{c0, c1, c2, c3},
                new float[]{0f, 0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP));
            RectF in = new RectF(body);
            in.inset(width / 2f, width / 2f);
            cv.drawRoundRect(in, r - width / 2f, r - width / 2f, p);
        }

        /** Pressed look: shadow cast inside from the top-left, a faint light along the bottom-right. */
        private void innerShadow(Canvas cv, RectF body, float r) {
            float d = density;
            cv.save();
            Path clip = new Path();
            clip.addRoundRect(body, r, r, Path.Direction.CW);
            cv.clipPath(clip);
            for (int i = 0; i < 2; i++) {
                Path frame = new Path();
                frame.setFillType(Path.FillType.EVEN_ODD);
                RectF outer = new RectF(body);
                outer.inset(-40 * d, -40 * d);
                frame.addRect(outer, Path.Direction.CW);
                RectF inner = new RectF(body);
                inner.offset(i == 0 ? 2.2f * d : -1.6f * d, i == 0 ? 2.6f * d : -1.6f * d);
                frame.addRoundRect(inner, r, r, Path.Direction.CW);
                Paint p = fill(i == 0 ? 0xCC000000 : 0x14FFFFFF);
                p.setMaskFilter(new BlurMaskFilter(i == 0 ? 3.5f * d : 2.5f * d, BlurMaskFilter.Blur.NORMAL));
                cv.drawPath(frame, p);
            }
            cv.restore();
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** Soft rounded highlight used for row presses. */
    private static final class GradientHolder extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float r;

        GradientHolder(Context c, float radiusDp) {
            r = radiusDp * c.getResources().getDisplayMetrics().density;
            p.setColor(0x1CFFFFFF);
        }

        @Override public void draw(Canvas c) { c.drawRoundRect(new RectF(getBounds()), r, r, p); }
        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
