package com.codec2.player;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** Canli enerji eşitleyici (EQ) çubuklari. codec2 mono oldugu icin tek genlik
 *  degerini per-bar faz salinimiyla "spektrum" gibi gosterir. Kutuphanesiz. */
public class SpectrumView extends View {

    private static final int BARS = 28;
    private float level = 0f;   // 0..1 hedef
    private float shown = 0f;   // yumusatilmis
    private float t = 0f;
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ValueAnimator anim;

    public SpectrumView(Context c, AttributeSet a) {
        super(c, a);
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(1000);
        anim.setInterpolator(new LinearInterpolator());
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.addUpdateListener(v -> {
            t += 0.045f;
            shown += (level - shown) * 0.25f;   // yumusak takip
            invalidate();
        });
        anim.start();
    }

    public void setLevel(float l) { level = l < 0 ? 0 : (l > 1 ? 1 : l); }

    @Override
    protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        if (w == 0) return;
        float bw = (float) w / BARS;
        float baseline = h * 0.5f;
        bar.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{0xFF8FE3FF, 0xFF5AA9FF, 0xFF9A6BFF},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        for (int i = 0; i < BARS; i++) {
            float osc = 0.45f + 0.55f * (0.5f + 0.5f * (float) Math.sin(t * 3.1 + i * 0.55));
            // kenarlarda sonen zarf (ortada yuksek)
            float env = 0.55f + 0.45f * (float) Math.sin(Math.PI * (i + 0.5) / BARS);
            float amp = Math.max(0.05f, shown) * osc * env;
            float bh = amp * h * 0.46f;
            float x = i * bw;
            cv.drawRoundRect(x + bw * 0.2f, baseline - bh, x + bw * 0.8f, baseline + bh,
                    bw * 0.3f, bw * 0.3f, bar);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (anim != null) anim.cancel();
    }
}
