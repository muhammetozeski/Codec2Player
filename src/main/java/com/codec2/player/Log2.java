package com.codec2.player;

import android.os.Handler;
import android.os.Looper;

/** Basit, uygulama geneli günlük tamponu. Uzun islemler (donusturme, tarama)
 *  buraya yazar; LogActivity canli gosterir. */
public final class Log2 {

    public interface Listener { void onLog(String full); }

    private static final StringBuilder sb = new StringBuilder();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Listener listener;

    public static void setListener(Listener l) { listener = l; }

    public static synchronized void add(String line) {
        sb.append(line).append('\n');
        notifyNow();
    }

    public static synchronized String all() { return sb.toString(); }

    public static synchronized void clear() { sb.setLength(0); notifyNow(); }

    private static void notifyNow() {
        final Listener l = listener;
        final String full = sb.toString();
        if (l != null) main.post(new Runnable() {
            @Override public void run() { l.onLog(full); }
        });
    }

    private Log2() {}
}
