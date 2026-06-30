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
        void onError(String msg);
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

    // Normal ses dosyalari (mp3/ogg/m4a/wav...) STREAMING olarak MediaPlayer ile calar
    // (bellege tum dosyayi almaz -> uzun sarkilarda OOM yok). codec2 .c2 -> PlayerEngine.
    private android.media.MediaPlayer mp;
    private boolean audioMode = false;
    private float mpSpeed = 1f;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private final TestHooks testHooks = new TestHooks();   // SADECE test; TestHooks.ON=false ile kapanir

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("c2player", MODE_PRIVATE);
        engine = new PlayerEngine(this);
        shuffle = prefs.getBoolean("shuffle", false);
        repeatMode = prefs.getInt("repeatMode", 0);
        engine.setSpeed(prefs.getFloat("speed", 1f));
        mpSpeed = prefs.getFloat("speed", 1f);
        engine.setGainDb(prefs.getFloat("gainDb", 0f));
        loadPlaylist();
        int ri = prefs.getInt("curIdx", -1);
        if (ri >= 0 && ri < playlist.size()) prepareResume(ri, prefs.getInt("curPos", 0));
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
        testHooks.install(this);
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
    public boolean isPlaying() { return audioMode ? safeMpPlaying() : engine.isPlaying(); }
    public int positionSamples() { return audioMode ? safeMpPos() : engine.positionSamples(); }
    public int totalSamples() { return audioMode ? safeMpDur() : engine.totalSamples(); }
    public int sampleRate() { return audioMode ? 1000 : engine.sampleRate(); }   // ses icin pos/total milisaniye
    public int level() { return audioMode ? 0 : engine.level(); }
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
        mpSpeed = ns; applyMpSpeed();
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

    public float getGainDb() { return engine.getGainDb(); }
    public void setGainDb(float db) {
        engine.setGainDb(db);
        prefs.edit().putFloat("gainDb", db).apply();
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
        } else if (isPlaying()) {
            startForeground(NID, buildNotification());  // arka planda calarken: bildirim
        } else {
            stopForeground(true);
            stopSelfIfIdle();
        }
    }

    public void toggle() { if (isPlaying()) pause(); else play(); }

    public void play() {
        if (current < 0 && !playlist.isEmpty()) { playIndex(0); return; }
        if (audioMode) {
            if (mp != null) { try { mp.start(); } catch (Throwable e) {} mpStateChanged(safeMpPlaying()); }
            else if (current >= 0) playIndex(current);
        } else {
            if (engine.totalSamples() == 0 && current >= 0) { playIndex(current); return; }
            engine.play();
        }
    }

    public void pause() {
        if (audioMode) { if (mp != null) { try { mp.pause(); } catch (Throwable e) {} } mpStateChanged(false); }
        else engine.pause();
        saveResume();
    }

    private void saveResume() {
        prefs.edit().putInt("curIdx", current).putInt("curPos", positionSamples()).apply();
    }

    private void prepareResume(final int idx, final int posSamples) {
        current = idx;
        loadAndPlay(playlist.get(idx), posSamples, false);   // resume: yukle + konuma git, otomatik calma
    }

    public void playIndex(final int idx) {
        if (idx < 0 || idx >= playlist.size()) return;
        current = idx;
        prefs.edit().putInt("curIdx", idx).apply();
        final Item it = playlist.get(idx);
        if (cb != null) cb.onTrackChanged(idx);
        loadAndPlay(it, 0, true);
    }

    /** Item'i turune gore yukler: .c2 basligi -> codec2 (PlayerEngine, tam PCM, kucuk);
     *  diger ses -> MediaPlayer (STREAMING, belleğe almaz -> her uzunlukta OOM yok).
     *  resumePos: codec2 icin ornek sayisi, ses icin milisaniye. */
    private void loadAndPlay(final Item it, final int resumePos, final boolean autoplay) {
        new Thread(new Runnable() {
            @Override public void run() {
                boolean isC2;
                try { isC2 = Codec2.headerMode(readHead(Uri.parse(it.uri), Codec2.HEADER_SIZE)) >= 0; }
                catch (Throwable e) { isC2 = false; }
                if (isC2) {
                    try {
                        byte[] bytes = readAll(Uri.parse(it.uri));
                        final Decoder.Result r = Decoder.decode(bytes, Codec2.MODE_1300);
                        if (r == null || r.pcm == null) throw new Exception("codec2 cozulemedi");
                        main.post(new Runnable() { @Override public void run() {
                            releaseMp(); audioMode = false;
                            it.mode = r.mode; it.durSec = r.pcm.length / HZ;
                            engine.setTrack(r.pcm, HZ);
                            if (resumePos > 0 && resumePos < r.pcm.length) engine.seekFraction(resumePos / (float) r.pcm.length);
                            if (autoplay) engine.play();
                            postNotificationAndState();
                            if (cb != null) cb.onPlaylistChanged();
                        }});
                    } catch (Throwable e) { reportError(it, e); }
                } else {
                    main.post(new Runnable() { @Override public void run() { startAudio(it, resumePos, autoplay); } });
                }
            }
        }, "svc-load").start();
    }

    /** Normal ses dosyasini MediaPlayer ile STREAMING calar (mp3/ogg/m4a/wav/flac...). */
    private void startAudio(final Item it, final int seekMs, final boolean autoplay) {
        releaseMp();
        audioMode = true;
        engine.pause();
        try {
            mp = new android.media.MediaPlayer();
            mp.setAudioStreamType(android.media.AudioManager.STREAM_MUSIC);
            mp.setOnPreparedListener(new android.media.MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(android.media.MediaPlayer p) {
                    it.mode = Item.MODE_AUDIO;
                    int d = safeMpDur();
                    if (d > 0) it.durSec = d / 1000;
                    if (seekMs > 0 && (d <= 0 || seekMs < d)) { try { p.seekTo(seekMs); } catch (Throwable e) {} }
                    applyMpSpeed();
                    if (autoplay) { try { p.start(); } catch (Throwable e) {} }
                    mpStateChanged(safeMpPlaying());
                    if (cb != null) cb.onPlaylistChanged();
                }
            });
            mp.setOnCompletionListener(new android.media.MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(android.media.MediaPlayer p) { mpStateChanged(false); onCompleted(); }
            });
            mp.setOnErrorListener(new android.media.MediaPlayer.OnErrorListener() {
                @Override public boolean onError(android.media.MediaPlayer p, int what, int extra) {
                    reportError(it, new Exception("MediaPlayer hata " + what + "/" + extra)); return true;
                }
            });
            mp.setDataSource(this, Uri.parse(it.uri));
            mp.prepareAsync();
        } catch (Throwable e) { reportError(it, e); }
    }

    private void applyMpSpeed() {
        if (mp == null || Build.VERSION.SDK_INT < 23) return;
        try {
            boolean wasPlaying = safeMpPlaying();
            mp.setPlaybackParams(mp.getPlaybackParams().setSpeed(mpSpeed <= 0 ? 1f : mpSpeed));
            if (!wasPlaying && safeMpPlaying()) mp.pause();   // setPlaybackParams calistirmis olabilir
        } catch (Throwable ignore) {}
    }

    private void mpStateChanged(boolean playing) {
        if (playing) acquireWake(); else releaseWake();
        postNotificationAndState();
        if (cb != null) cb.onStateChanged(playing);
    }

    private void releaseMp() {
        if (mp != null) {
            try { mp.reset(); } catch (Throwable ignore) {}
            try { mp.release(); } catch (Throwable ignore) {}
            mp = null;
        }
    }

    private void reportError(final Item it, final Throwable e) {
        try { Log2.add("Çalma hatası: " + it.name + " -> " + e); } catch (Throwable ignore) {}
        main.post(new Runnable() { @Override public void run() {
            if (cb != null) cb.onError(getString(R.string.play_failed, it.name));
        }});
    }

    private boolean safeMpPlaying() { try { return mp != null && mp.isPlaying(); } catch (Throwable e) { return false; } }
    private int safeMpPos() { try { return mp != null ? mp.getCurrentPosition() : 0; } catch (Throwable e) { return 0; } }
    private int safeMpDur() { try { int d = mp != null ? mp.getDuration() : 0; return d > 0 ? d : 0; } catch (Throwable e) { return 0; } }

    private byte[] readHead(Uri uri, int n) throws Exception {
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) throw new Exception("akis yok");
        try {
            byte[] b = new byte[n];
            int off = 0, r;
            while (off < n && (r = in.read(b, off, n - off)) > 0) off += r;
            if (off == n) return b;
            byte[] s = new byte[off]; System.arraycopy(b, 0, s, 0, off); return s;
        } finally { in.close(); }
    }

    public void seekFraction(float f) {
        if (audioMode) { int d = safeMpDur(); if (d > 0) { try { mp.seekTo((int) (f * d)); } catch (Throwable e) {} } }
        else engine.seekFraction(f);
        updatePlaybackState();
    }

    public void seekRelative(int seconds) {
        if (audioMode) {
            int d = safeMpDur(); if (d <= 0) return;
            int np = safeMpPos() + seconds * 1000;
            if (np < 0) np = 0; if (np >= d) np = d - 1;
            try { mp.seekTo(np); } catch (Throwable e) {}
        } else {
            int tot = engine.totalSamples();
            if (tot <= 0) return;
            int np = engine.positionSamples() + seconds * engine.sampleRate();
            if (np < 0) np = 0; if (np >= tot) np = tot - 1;
            engine.seekFraction(np / (float) tot);
        }
        updatePlaybackState();
    }

    public void stopPlayback() {
        if (mp != null) { try { mp.pause(); } catch (Throwable e) {} }
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
        if (repeatMode == 2) {                              // tekli tekrar
            if (audioMode) { if (mp != null) { try { mp.seekTo(0); mp.start(); } catch (Throwable e) {} } mpStateChanged(true); }
            else { engine.seekFraction(0f); engine.play(); }
            return;
        }
        if (shuffle) { playIndex(rnd.nextInt(Math.max(1, playlist.size()))); return; }
        int n = current + 1;
        if (n >= playlist.size()) {
            if (repeatMode == 1) n = 0;                     // tumu: basa don
            else {                                          // kapali: liste sonunda dur
                if (audioMode) { if (mp != null) { try { mp.pause(); } catch (Throwable e) {} } mpStateChanged(false); }
                else engine.pause();
                return;
            }
        }
        playIndex(n);
    }

    // ---------- bildirim / oturum ----------

    private void postNotificationAndState() {
        updatePlaybackState();
        if (!uiVisible) {
            if (isPlaying()) startForeground(NID, buildNotification());
            else {
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                stopForeground(false);
                nm.notify(NID, buildNotification());  // duraklatinca da bildirim kalsin (arka planda)
            }
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, getString(R.string.notif_channel),
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
                if (audioMode) { if (mp != null) { try { mp.seekTo((int) ms); } catch (Throwable e) {} } }
                else { int tot = engine.totalSamples(); if (tot > 0) engine.seekFraction((ms / 1000f * engine.sampleRate()) / tot); }
                updatePlaybackState();
            }
        });
        session.setActive(true);
    }

    private void updatePlaybackState() {
        if (session == null) return;
        long pos = audioMode ? safeMpPos() : engine.positionSamples() * 1000L / engine.sampleRate();
        int st = isPlaying() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        PlaybackState ps = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO
                        | PlaybackState.ACTION_STOP)
                .setState(st, pos, isPlaying() ? 1f : 0f)
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
        boolean playing = isPlaying();

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

        b.addAction(new Notification.Action.Builder(android.R.drawable.ic_media_previous, getString(R.string.notif_prev), svcPI(ACT_PREV, 1)).build());
        b.addAction(new Notification.Action.Builder(
                playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                getString(playing ? R.string.notif_pause : R.string.notif_play), svcPI(ACT_TOGGLE, 2)).build());
        b.addAction(new Notification.Action.Builder(android.R.drawable.ic_media_next, getString(R.string.notif_next), svcPI(ACT_NEXT, 3)).build());

        if (Build.VERSION.SDK_INT >= 21) {
            Notification.MediaStyle style = new Notification.MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2);
            if (session != null) style.setMediaSession(session.getSessionToken());
            b.setStyle(style);
        }
        return b.build();
    }

    private void stopSelfIfIdle() {
        if (!isPlaying() && !uiVisible) stopSelf();
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

    public void persist() { savePlaylist(); }

    private void savePlaylist() {
        StringBuilder sb = new StringBuilder();
        for (Item it : playlist)
            sb.append(it.uri).append('\t').append(it.name).append('\t')
              .append(it.mode).append('\t').append(it.durSec).append('\n');
        prefs.edit().putString("list", sb.toString()).apply();
    }

    private static int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private void loadPlaylist() {
        String s = prefs.getString("list", "");
        if (s.isEmpty()) return;
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        boolean cleaned = false;
        for (String line : s.split("\n")) {
            if (line.isEmpty()) continue;
            String[] f = line.split("\t", -1);
            Item it = new Item();
            it.uri = f[0];
            it.name = (f.length > 1 && !f[1].isEmpty()) ? f[1] : f[0];
            if (f.length >= 4) { it.mode = parseIntSafe(f[2], -1); it.durSec = parseIntSafe(f[3], -1); }
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
        saveResume();
        releaseWake();
        sleepH.removeCallbacks(sleepR);
        try { if (noisy != null) unregisterReceiver(noisy); } catch (Exception ignore) {}
        testHooks.uninstall(this);
        if (session != null) session.release();
        releaseMp();
        if (engine != null) engine.release();
    }
}
