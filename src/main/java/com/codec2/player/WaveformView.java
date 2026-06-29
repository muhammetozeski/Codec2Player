package com.codec2.player;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/** Tum dosyanin dalga formu + oynatma kafasi. Dokun/surukle ile sarma. */
public class WaveformView extends View {

    public interface SeekListener { void onSeek(float fraction); }

    private float[] peaks = new float[0];
    private float progress = 0f;     // 0..1
    private float liveLevel = 0f;    // 0..1 anlik
    private SeekListener seekListener;
    private final Paint base = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint played = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint head = new Paint(Paint.ANTI_ALIAS_FLAG);

    public WaveformView(Context c, AttributeSet a) {
        super(c, a);
        base.setColor(0x22394F66);
        head.setColor(Color.WHITE);
        head.setStrokeWidth(3f);
    }

    public void setSeekListener(SeekListener l) { seekListener = l; }

    public void setPeaks(float[] p) { peaks = (p != null) ? p : new float[0]; invalidate(); }

    public void setProgress(float f) {
        progress = f < 0 ? 0 : (f > 1 ? 1 : f);
        invalidate();
    }

    public void setLevel(float l) { liveLevel = l; }

    @Override
    protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        if (peaks.length == 0 || w == 0) return;
        float mid = h / 2f;
        float bw = (float) w / peaks.length;
        float playedX = progress * w;

        played.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{ 0xFF7FE0FF, 0xFF4FA8FF, 0xFF8A6BFF },
                new float[]{ 0f, 0.5f, 1f }, Shader.TileMode.CLAMP));

        for (int i = 0; i < peaks.length; i++) {
            float x = i * bw;
            float amp = peaks[i];
            // oynatma kafasinin oldugu cubuk canli genlikle nabiz atsin
            if (Math.abs(x - playedX) < bw * 2) amp = Math.max(amp, liveLevel);
            float bh = Math.max(2f, amp * (h * 0.46f));
            Paint p = (x <= playedX) ? played : base;
            cv.drawRoundRect(x + bw * 0.15f, mid - bh, x + bw * 0.85f, mid + bh, bw * 0.3f, bw * 0.3f, p);
        }
        // oynatma kafasi (dalga yuksekligiyle sinirli, tasmaz)
        float top = h * 0.12f, bot = h * 0.88f;
        cv.drawLine(playedX, top, playedX, bot, head);
        cv.drawCircle(playedX, mid, 7f, head);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
                float f = e.getX() / Math.max(1, getWidth());
                setProgress(f);
                if (seekListener != null) seekListener.onSeek(f);
                return true;
        }
        return super.onTouchEvent(e);
    }
}
