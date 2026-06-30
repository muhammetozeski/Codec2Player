package com.codec2.player;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.util.Log;

import java.util.ArrayList;

/**
 * SADECE TEST/GELISTIRME icin. {@link #ON} = false yapildiginda hicbir test kancasi
 * calismaz (receiver kaydedilmez) -> uretimde tamamen olu kod.
 *
 * Kullanim (uygulama acikken):
 *   adb shell am broadcast -a com.codec2.player.TEST --es cmd addplay --es arg /sdcard/test.mp3
 *   adb shell am broadcast -a com.codec2.player.TEST --es cmd state
 *   adb logcat -d -s C2TEST          (durum ciktisi)
 *
 * Komutlar: addplay(arg=yol/uri) play(arg=index) toggle pause next prev seek(arg=0..1) clear state
 */
public final class TestHooks {

    /** Test bitince false yap: tum kancalar deaktif olur. */
    public static final boolean ON = true;

    public static final String ACTION = "com.codec2.player.TEST";
    private static final String TAG = "C2TEST";

    private BroadcastReceiver rx;

    public void install(final PlaybackService svc) {
        if (!ON) return;
        rx = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { handle(svc, i); }
        };
        IntentFilter f = new IntentFilter(ACTION);
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33)
                svc.registerReceiver(rx, f, Context.RECEIVER_EXPORTED);
            else
                svc.registerReceiver(rx, f);
            Log.i(TAG, "test hooks ON (action=" + ACTION + ")");
        } catch (Throwable e) { Log.i(TAG, "install error: " + e); }
    }

    public void uninstall(PlaybackService svc) {
        if (rx != null) { try { svc.unregisterReceiver(rx); } catch (Throwable ignore) {} rx = null; }
    }

    private void handle(PlaybackService svc, Intent i) {
        if (!ON) return;
        String cmd = i.getStringExtra("cmd");
        if (cmd == null) return;
        String arg = i.getStringExtra("arg");
        try {
            switch (cmd) {
                case "addplay": {
                    String uri = (arg.startsWith("content:") || arg.startsWith("file:"))
                            ? arg : Uri.fromFile(new java.io.File(arg)).toString();
                    ArrayList<Item> items = new ArrayList<>();
                    String nm = arg.substring(arg.lastIndexOf('/') + 1);
                    items.add(new Item(uri, nm));
                    svc.addItems(items);
                    int idx = svc.indexOfUri(uri);
                    if (idx >= 0) svc.playIndex(idx);
                    break;
                }
                case "play":   svc.playIndex(arg != null ? Integer.parseInt(arg) : svc.getCurrent()); break;
                case "toggle": svc.toggle(); break;
                case "pause":  svc.pause(); break;
                case "next":   svc.playIndex(svc.neighbor(+1)); break;
                case "prev":   svc.playIndex(svc.neighbor(-1)); break;
                case "seek":   svc.seekFraction(Float.parseFloat(arg)); break;
                case "clear":  svc.clearPlaylist(); break;
                case "state":  break;   // sadece durum bas
                default:       Log.i(TAG, "bilinmeyen cmd: " + cmd); return;
            }
        } catch (Throwable e) { Log.i(TAG, "cmd '" + cmd + "' error: " + e); }
        dumpState(svc);
    }

    private void dumpState(PlaybackService svc) {
        ArrayList<Item> pl = svc.getPlaylist();
        int cur = svc.getCurrent();
        boolean has = cur >= 0 && cur < pl.size();
        Log.i(TAG, "state playing=" + svc.isPlaying()
                + " idx=" + cur
                + " mode=" + (has ? pl.get(cur).mode : 0)
                + " posMs=" + (svc.positionSamples() * 1000L / Math.max(1, svc.sampleRate()))
                + " totalMs=" + (svc.totalSamples() * 1000L / Math.max(1, svc.sampleRate()))
                + " size=" + pl.size()
                + " name=" + (has ? pl.get(cur).name : "-"));
    }
}
