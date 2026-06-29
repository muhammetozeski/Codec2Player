package com.codec2.player;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

/**
 * PCM (8 kHz mono 16-bit) oynatma motoru. Tam sarma (seek), oynat/duraklat,
 * konum bildirimi ve anlik genlik (visualizer icin). AudioTrack STREAM modu;
 * yazma blokladigi icin oynatma kendiliginden senkron olur.
 */
public final class PlayerEngine {

    public interface Listener {
        void onProgress(int posSamples, int totalSamples);
        void onStateChanged(boolean playing);
        void onCompleted();
    }

    private static final int HZ = 8000;
    private static final int CHUNK = 1024;

    private final Listener listener;
    private short[] pcm = new short[0];
    private int total = 0;
    private volatile int pos = 0;
    private volatile boolean playing = false;
    private volatile boolean alive = true;
    private volatile int level = 0;       // 0..32767 anlik genlik
    private AudioTrack track;
    private Thread thread;

    public PlayerEngine(Listener l) {
        this.listener = l;
        int min = AudioTrack.getMinBufferSize(HZ,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(min, CHUNK * 2 * 4);
        track = new AudioTrack(AudioManager.STREAM_MUSIC, HZ,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                bufSize, AudioTrack.MODE_STREAM);
        thread = new Thread(new Runnable() {
            @Override public void run() { loop(); }
        }, "c2-playback");
        thread.start();
    }

    private void loop() {
        while (alive) {
            if (!playing || pos >= total) {
                level = 0;
                sleep(20);
                if (playing && pos >= total) {
                    playing = false;
                    notifyState();
                    if (listener != null) listener.onCompleted();
                }
                continue;
            }
            int n = Math.min(CHUNK, total - pos);
            track.write(pcm, pos, n);
            // anlik genlik
            int peak = 0;
            int end = pos + n;
            for (int i = pos; i < end; i += 8) {
                int v = pcm[i]; if (v < 0) v = -v;
                if (v > peak) peak = v;
            }
            level = peak;
            pos += n;
            if (listener != null) listener.onProgress(pos, total);
        }
    }

    public void setTrack(short[] newPcm) {
        boolean wasPlaying = playing;
        playing = false;
        sleep(30);
        flush();
        pcm = (newPcm != null) ? newPcm : new short[0];
        total = pcm.length;
        pos = 0;
        if (wasPlaying) { /* yeni parca otomatik baslamasin; cagiran karar verir */ }
        notifyProgress();
    }

    public void play() {
        if (total == 0) return;
        if (pos >= total) pos = 0;
        playing = true;
        try { track.play(); } catch (IllegalStateException ignore) {}
        notifyState();
    }

    public void pause() {
        playing = false;
        try { track.pause(); } catch (IllegalStateException ignore) {}
        notifyState();
    }

    public void toggle() { if (playing) pause(); else play(); }

    public boolean isPlaying() { return playing; }

    public int positionSamples() { return pos; }
    public int totalSamples() { return total; }
    public int level() { return level; }
    public int sampleRate() { return HZ; }

    /** @param fraction 0..1 */
    public void seekFraction(float fraction) {
        if (total == 0) return;
        if (fraction < 0) fraction = 0; if (fraction > 1) fraction = 1;
        boolean wasPlaying = playing;
        playing = false;
        sleep(15);
        flush();
        pos = (int) (fraction * total);
        if (pos >= total) pos = total - 1;
        if (pos < 0) pos = 0;
        notifyProgress();
        if (wasPlaying) play();
    }

    private void flush() {
        try { track.pause(); track.flush(); } catch (IllegalStateException ignore) {}
    }

    public void release() {
        alive = false;
        playing = false;
        try { thread.join(300); } catch (InterruptedException ignore) {}
        try { track.stop(); } catch (Throwable ignore) {}
        track.release();
    }

    private void notifyState() {
        if (listener != null) listener.onStateChanged(playing);
    }
    private void notifyProgress() {
        if (listener != null) listener.onProgress(pos, total);
    }

    private static void sleep(int ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignore) {}
    }
}
