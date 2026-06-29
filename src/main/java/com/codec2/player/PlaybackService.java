package com.codec2.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Random;

/**
 * Arka planda calma + bildirim kontrolu + MediaSession + pil (wakelock yalniz
 * calarken). On plana gelince bildirim kalkar (setUiVisible). Tum oynatma
 * mantigi burada; Activity sadece ince bir kabuk.
 */
public class PlaybackService extends Service implements PlayerEngine.Listener {

    public static final String ACT_TOGGLE = "com.codec2.player.TOGGLE";
    public static final String ACT_NEXT   = "com.codec2.player.NEXT";
    public static final String ACT_PREV   = "com.codec2.player.PREV";
    public static final String ACT_STOP   = "com.codec2.player.STOP";

    private static final String CHANNEL = "c2play";
    private static final int NID = 42;
    private static final int HZ = 8000;

    public interface Callback {
        void onTrackChanged(int index);
        void onStateChanged(boolean playing);
        void onPlaylistChanged();
    }

    public final class LocalBinder extends Binder {
        public PlaybackService get() { return PlaybackService.this; }
    }

    private final LocalBinder binder = new LocalBinder();
    private final ArrayList<Item> playlist = new ArrayList<>();
    private int current = -1;
    private boolean shuffle = false;
    private int repeatMode = 0; // 0 kapalı, 1 tümü, 2 tekli
    private final Random rnd = new Random();
    private android.content.BroadcastReceiver noisy;
    private int sleepMin = 0;
    private final android.os.Handler sleepH = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable sleepR = new Runnable() {
        @Override public void run() { stopPlayback(); sleepMin = 0; if (cb != null) cb.onStateChanged(false); }
    };
    private PlayerEngine engine;
    private Callback cb;
    private boolean uiVisible = false;
    private MediaSession session;
    private PowerManager.WakeLock wake;
    private SharedPreferences prefs;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("c2player", MODE_PRIVATE);
        engine = new PlayerEngine(this);
        shuffle = prefs.getBoolean("shuffle", false);
        repeatMode = prefs.getInt("repeatMode", 0);
        engine.setSpeed(prefs.getFloat("speed", 1f));
        loadPlaylist();
        createChannel();
        setupSession();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "c2player:play");
        wake.setReferenceCounted(false);
        // kulaklik/cikis cekilince otomatik duraklat
        noisy = new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { pause(); }
        };
        registerReceiver(noisy, new android.content.IntentFilter(
                android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY));
    }

    @Override public IBinder onBind(Intent i) { return binder; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = (intent != null) ? intent.getAction() : null;
        if (a != null) {
            switch (a) {
                case ACT_TOGGLE: toggle(); break;
                case ACT_NEXT:   playIndex(neighbor(+1)); break;
                case ACT_PREV:   playIndex(neighbor(-1)); break;
                case ACT_STOP:   stopPlayback(); break;
            }
        }
        return START_STICKY;
    }

    // ---------- disari acik API ----------

    public void setCallback(Callback c) { cb = c; }
    public ArrayList<Item> getPlaylist() { return playlist; }
    public int getCurrent() { return current; }
    public boolean isPlaying() { return engine.isPlaying(); }
    public int positionSamples() { return engine.positionSamples(); }
    public int totalSamples() { return engine.totalSamples(); }
    public int level() { return engine.level(); }
    public boolean isShuffle() { return shuffle; }
    public int getRepeatMode() { return repeatMode; }

    public void setShuffle(boolean s) { shuffle = s; prefs.edit().putBoolean("shuffle", s).apply(); }
    public void cycleRepeat() { repeatMode = (repeatMode + 1) % 3; prefs.edit().putInt("repeatMode", repeatMode).apply(); }

    public float getSpeed() { return engine.getSpeed(); }
    public void cycleSpeed() {
        float[] sp = {1f, 1.25f, 1.5f, 2f, 0.75f};
        float cur = engine.getSpeed();
        int idx = 0;
        for (int i = 0; i < sp.length; i++) if (Math.abs(sp[i] - cur) < 0.01f) { idx = i; break; }
        float ns = sp[(idx + 1) % sp.length];
        engine.setSpeed(ns);
        prefs.edit().putFloat("speed", ns).apply();
    }

    public int getSleepMin() { return sleepMin; }
    public void cycleSleep() {
        int[] opts = {0, 15, 30, 60};
        int idx = 0;
        for (int i = 0; i < opts.length; i++) if (opts[i] == sleepMin) { idx = i; break; }
        sleepMin = opts[(idx + 1) % opts.length];
        sleepH.removeCallbacks(sleepR);
        if (sleepMin > 0) sleepH.postDelayed(sleepR, sleepMin * 60000L);
    }

    public int indexOfUri(String uri) {
        if (uri == null) return -1;
        for (int i = 0; i < playlist.size(); i++) if (uri.equals(playlist.get(i).uri)) return i;
        return -1;
    }

    public void addItems(ArrayList<Item> items) {
        java.util.HashSet<String> have = new java.util.HashSet<>();
        for (Item it : playlist) have.add(it.uri);
        for (Item it : items) if (have.add(it.uri)) playlist.add(it);  // tekrarlari atla
        savePlaylist();
        if (cb != null) cb.onPlaylistChanged();
    }

    public void moveItem(int from, int to) {
        if (from < 0 || to < 0 || from >= playlist.size() || to >= playlist.size()) return;
        Item it = playlist.remove(from);
        playlist.add(to, it);
        if (current == from) current = to;
        else if (from < current && to >= current) current--;
        else if (from > current && to <= current) current++;
        savePlaylist();
        if (cb != null) cb.onPlaylistChanged();
    }

    public void clearPlaylist() {
        stopPlayback();
        playlist.clear();
        current = -1;
        savePlaylist();
        if (cb != null) cb.onPlaylistChanged();
    }

    public void setUiVisible(boolean v) {
        uiVisible = v;
        if (v) {
            stopForeground(true);          // on planda: bildirim yok
        } else if (engine.isPlaying()) {
            startForeground(NID, buildNotification());  // arka planda calarken: bildirim
        } else {
            stopForeground(true);
            stopSelfIfIdle();
        }
    }

    public void toggle() { if (engine.isPlaying()) pause(); else play(); }

    public void play() {
        if (current < 0 && !playlist.isEmpty()) { playIndex(0); return; }
        engine.play();
    }

    public void pause() { engine.pause(); }

    public void seekFraction(float f) { engine.seekFraction(f); updatePlaybackState(); }

    public void playIndex(final int idx) {
        if (idx < 0 || idx >= playlist.size()) return;
        current = idx;
        final Item it = playlist.get(idx);
        if (cb != null) cb.onTrackChanged(idx);
        new Thread(new Runnable() {
            @Override public void run() {
                byte[] bytes;
                try { bytes = readAll(Uri.parse(it.uri)); } catch (Exception e) { return; }
                final Decoder.Result r = Decoder.decode(bytes, Codec2.MODE_1300);
                if (r == null || r.pcm == null) return;
                it.mode = r.mode;
                it.durSec = r.pcm.length / HZ;
                engine.setTrack(r.pcm);
                engine.play();
                postNotificationAndState();
                if (cb != null) cb.onPlaylistChanged();
            }
        }, "svc-decode").start();
    }

    public void stopPlayback() {
        engine.pause();
        releaseWake();
        stopForeground(true);
        stopSelfIfIdle();
    }

    public int neighbor(int dir) {
        if (playlist.isEmpty()) return -1;
        if (shuffle) return rnd.nextInt(playlist.size());
        if (current < 0) return 0;
        int n = current + dir;
        if (n < 0) n = playlist.size() - 1;
        if (n >= playlist.size()) n = 0;
        return n;
    }

    // ---------- PlayerEngine.Listener ----------

    @Override public void onProgress(int pos, int tot) { /* Activity ticker poluyor */ }

    @Override
    public void onStateChanged(boolean playing) {
        if (playing) acquireWake(); else releaseWake();
        postNotificationAndState();
        if (cb != null) cb.onStateChanged(playing);
    }

    @Override
    public void onCompleted() {
        if (repeatMode == 2) { engine.seekFraction(0f); engine.play(); return; }
        if (shuffle) { playIndex(rnd.nextInt(Math.max(1, playlist.size()))); return; }
        int n = current + 1;
        if (n >= playlist.size()) {
            if (repeatMode == 1) n = 0;     // tumu: basa don
            else { engine.pause(); return; } // kapali: liste sonunda dur
        }
        playIndex(n);
    }

    // ---------- bildirim / oturum ----------

    private void postNotificationAndState() {
        updatePlaybackState();
        if (!uiVisible) {
            if (engine.isPlaying()) startForeground(NID, buildNotification());
            else {
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                stopForeground(false);
                nm.notify(NID, buildNotification());  // duraklatinca da bildirim kalsin (arka planda)
            }
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Oynatma",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
    }

    private void setupSession() {
        session = new MediaSession(this, "c2player");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { play(); }
            @Override public void onPause() { pause(); }
            @Override public void onSkipToNext() { playIndex(neighbor(+1)); }
            @Override public void onSkipToPrevious() { playIndex(neighbor(-1)); }
            @Override public void onStop() { stopPlayback(); }
            @Override public void onSeekTo(long ms) {
                int tot = engine.totalSamples();
                if (tot > 0) engine.seekFraction((ms / 1000f * HZ) / tot);
            }
        });
        session.setActive(true);
    }

    private void updatePlaybackState() {
        if (session == null) return;
        long pos = engine.positionSamples() * 1000L / HZ;
        int st = engine.isPlaying() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        PlaybackState ps = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO
                        | PlaybackState.ACTION_STOP)
                .setState(st, pos, engine.isPlaying() ? 1f : 0f)
                .build();
        session.setPlaybackState(ps);
    }

    private PendingIntent svcPI(String action, int rc) {
        Intent i = new Intent(this, PlaybackService.class).setAction(action);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) fl |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getService(this, rc, i, fl);
    }

    private Notification buildNotification() {
        String title = (current >= 0 && current < playlist.size()) ? playlist.get(current).name : "Codec2";
        boolean playing = engine.isPlaying();

        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) fl |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent content = PendingIntent.getActivity(this, 9, open, fl);

        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(title)
                .setContentIntent(content)
                .setOngoing(playing)
                .setVisibility(Notification.VISIBILITY_PUBLIC);

        b.addAction(new Notification.Action.Builder(android.R.drawable.ic_media_previous, "Önceki", svcPI(ACT_PREV, 1)).build());
        b.addAction(new Notification.Action.Builder(
                playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                playing ? "Duraklat" : "Oynat", svcPI(ACT_TOGGLE, 2)).build());
        b.addAction(new Notification.Action.Builder(android.R.drawable.ic_media_next, "Sonraki", svcPI(ACT_NEXT, 3)).build());

        if (Build.VERSION.SDK_INT >= 21) {
            Notification.MediaStyle style = new Notification.MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2);
            if (session != null) style.setMediaSession(session.getSessionToken());
            b.setStyle(style);
        }
        return b.build();
    }

    private void stopSelfIfIdle() {
        if (!engine.isPlaying() && !uiVisible) stopSelf();
    }

    private void acquireWake() { try { if (wake != null && !wake.isHeld()) wake.acquire(); } catch (Exception ignore) {} }
    private void releaseWake() { try { if (wake != null && wake.isHeld()) wake.release(); } catch (Exception ignore) {} }

    // ---------- dosya / kalicilik ----------

    private byte[] readAll(Uri uri) throws Exception {
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) throw new Exception("akis yok");
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[16 * 1024];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return o.toByteArray();
        } finally { in.close(); }
    }

    private void savePlaylist() {
        StringBuilder sb = new StringBuilder();
        for (Item it : playlist) sb.append(it.uri).append('\t').append(it.name).append('\n');
        prefs.edit().putString("list", sb.toString()).apply();
    }

    private void loadPlaylist() {
        String s = prefs.getString("list", "");
        if (s.isEmpty()) return;
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        boolean cleaned = false;
        for (String line : s.split("\n")) {
            if (line.isEmpty()) continue;
            int t = line.indexOf('\t');
            Item it = new Item();
            if (t > 0) { it.uri = line.substring(0, t); it.name = line.substring(t + 1); }
            else { it.uri = line; it.name = line; }
            // bozuk/adsiz girdileri at (eski deneme artiklari)
            if (it.uri == null || it.uri.isEmpty() || it.name == null || it.name.trim().isEmpty()) { cleaned = true; continue; }
            if (seen.add(it.uri)) playlist.add(it);   // tekrarlari at
            else cleaned = true;
        }
        if (cleaned) savePlaylist();                   // temizlenmis listeyi kalici yaz
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        releaseWake();
        sleepH.removeCallbacks(sleepR);
        try { if (noisy != null) unregisterReceiver(noisy); } catch (Exception ignore) {}
        if (session != null) session.release();
        if (engine != null) engine.release();
    }
}
