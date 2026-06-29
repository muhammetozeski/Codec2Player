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

/** Daire seklinde, nabiz gibi isiyan oynat/duraklat butonu. Renk mavi<->pembe
 *  arasinda HER iki yonde de yumusak gecer (REVERSE animasyon). Kutuphanesiz. */
public class GlowButton extends View {

    private boolean playing = false;
    private float pulse = 0f;     // 0..1 isima nabzi
    private float colorT = 0f;    // 0..1 renk gecisi (ping-pong)
    private float progress = 0f;  // 0..1 oynatma ilerlemesi (halka)
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint prog = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private ValueAnimator pulseAnim, colorAnim;

    public GlowButton(Context c, AttributeSet a) {
        super(c, a);
        icon.setColor(Color.WHITE);
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(0x55FFFFFF);
        prog.setStyle(Paint.Style.STROKE);
        prog.setStrokeCap(Paint.Cap.ROUND);
        setClickable(true);

        pulseAnim = ValueAnimator.ofFloat(0f, 1f);
        pulseAnim.setDuration(1400);
        pulseAnim.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnim.setRepeatMode(ValueAnimator.REVERSE);
        pulseAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator v) {
                pulse = (float) v.getAnimatedValue();
                invalidate();
            }
        });
        pulseAnim.start();

        // Renk gecisi: REVERSE oldugu icin 1'e varinca 0'a YAVASCA geri doner (ani degil)
        colorAnim = ValueAnimator.ofFloat(0f, 1f);
        colorAnim.setDuration(4200);
        colorAnim.setRepeatCount(ValueAnimator.INFINITE);
        colorAnim.setRepeatMode(ValueAnimator.REVERSE);
        colorAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator v) {
                colorT = (float) v.getAnimatedValue();
            }
        });
        colorAnim.start();
    }

    public void setPlaying(boolean p) { playing = p; invalidate(); }

    public void setProgress(float p) { progress = p < 0 ? 0 : (p > 1 ? 1 : p); }

    private int accent() {
        // mavi (~205) <-> pembe/magenta (~325) arasi yumusak gidip gelir
        float hue = 205f + 120f * smooth(colorT);
        float sat = playing ? 0.62f : 0.42f;
        float val = playing ? 1f : 0.85f;
        return Color.HSVToColor(new float[]{ hue, sat, val });
    }

    // kenarlarda yavaslayan yumusak egri (ease-in-out)
    private static float smooth(float t) { return t * t * (3f - 2f * t); }

    @Override
    protected void onDraw(Canvas cv) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float base = Math.min(cx, cy);
        float r = base * 0.62f;
        int ac = accent();

        float glowR = r * (1.35f + 0.35f * pulse);
        int a = (int) (110 + 90 * pulse);
        glow.setShader(new RadialGradient(cx, cy, glowR,
                new int[]{ withAlpha(ac, a), withAlpha(ac, 0) },
                new float[]{ 0.45f, 1f }, Shader.TileMode.CLAMP));
        cv.drawCircle(cx, cy, glowR, glow);

        disc.setShader(new RadialGradient(cx, cy - r * 0.3f, r * 1.4f,
                new int[]{ lighten(ac), ac, darken(ac) },
                new float[]{ 0f, 0.55f, 1f }, Shader.TileMode.CLAMP));
        cv.drawCircle(cx, cy, r, disc);

        ring.setStrokeWidth(r * 0.06f);
        cv.drawCircle(cx, cy, r, ring);

        // oynatma ilerleme halkasi (disin disinda yay)
        if (progress > 0f) {
            float rr = r * 1.14f;
            prog.setStrokeWidth(r * 0.08f);
            prog.setColor(lighten(ac));
            android.graphics.RectF oval = new android.graphics.RectF(cx - rr, cy - rr, cx + rr, cy + rr);
            cv.drawArc(oval, -90f, progress * 360f, false, prog);
        }

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

    private static int withAlpha(int c, int a) { return (a << 24) | (c & 0x00FFFFFF); }
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
        if (pulseAnim != null) pulseAnim.cancel();
        if (colorAnim != null) colorAnim.cancel();
    }
}
