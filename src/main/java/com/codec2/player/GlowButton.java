package com.codec2.player;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/** Daire seklinde, nabiz gibi isiyan oynat/duraklat butonu. Kutuphanesiz. */
public class GlowButton extends View {

    private boolean playing = false;
    private float pulse = 0f;       // 0..1
    private int hue = 0;            // canli renk donusu
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private ValueAnimator anim;

    public GlowButton(Context c, AttributeSet a) {
        super(c, a);
        icon.setColor(Color.WHITE);
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(0x55FFFFFF);
        setClickable(true);
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(1400);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setRepeatMode(ValueAnimator.REVERSE);
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                pulse = (float) a.getAnimatedValue();
                hue = (hue + 1) % 360;
                invalidate();
            }
        });
        anim.start();
    }

    public void setPlaying(boolean p) { playing = p; invalidate(); }

    private int accent() {
        // oynatirken canli camgobegi-mor; duraklatinca daha sakin
        float[] hsv = { (playing ? (200 + (hue % 120)) : 210) % 360, 0.65f, 1f };
        return Color.HSVToColor(hsv);
    }

    @Override
    protected void onDraw(Canvas cv) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float base = Math.min(cx, cy);
        float r = base * 0.62f;
        int ac = accent();

        // dis isima (pulse ile buyuyup parlar)
        float glowR = r * (1.35f + 0.35f * pulse);
        int a = (int) (110 + 90 * pulse);
        glow.setShader(new RadialGradient(cx, cy, glowR,
                new int[]{ withAlpha(ac, a), withAlpha(ac, 0) },
                new float[]{ 0.45f, 1f }, Shader.TileMode.CLAMP));
        cv.drawCircle(cx, cy, glowR, glow);

        // ana disk
        disc.setShader(new RadialGradient(cx, cy - r * 0.3f, r * 1.4f,
                new int[]{ lighten(ac), ac, darken(ac) },
                new float[]{ 0f, 0.55f, 1f }, Shader.TileMode.CLAMP));
        cv.drawCircle(cx, cy, r, disc);

        ring.setStrokeWidth(r * 0.06f);
        cv.drawCircle(cx, cy, r, ring);

        // ikon
        float s = r * 0.5f;
        path.reset();
        if (playing) {
            float bw = s * 0.34f, gap = s * 0.36f, bh = s;
            cv.drawRoundRect(cx - gap - bw, cy - bh, cx - gap, cy + bh, bw * 0.4f, bw * 0.4f, icon);
            cv.drawRoundRect(cx + gap, cy - bh, cx + gap + bw, cy + bh, bw * 0.4f, bw * 0.4f, icon);
        } else {
            float off = s * 0.18f;
            path.moveTo(cx - s * 0.55f + off, cy - s);
            path.lineTo(cx + s * 0.85f + off, cy);
            path.lineTo(cx - s * 0.55f + off, cy + s);
            path.close();
            cv.drawPath(path, icon);
        }
    }

    private static int withAlpha(int c, int a) {
        return (a << 24) | (c & 0x00FFFFFF);
    }
    private static int lighten(int c) {
        float[] h = new float[3]; Color.colorToHSV(c, h); h[2] = Math.min(1f, h[2] * 1.25f); h[1] *= 0.7f;
        return Color.HSVToColor(h);
    }
    private static int darken(int c) {
        float[] h = new float[3]; Color.colorToHSV(c, h); h[2] *= 0.55f;
        return Color.HSVToColor(h);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (anim != null) anim.cancel();
    }
}
