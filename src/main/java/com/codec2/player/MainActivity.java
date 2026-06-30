package com.codec2.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public class MainActivity extends Activity implements PlaybackService.Callback {

    private static final int REQ_FILES = 11;
    private static final int REQ_FOLDER = 12;
    private static final int REQ_CONVERT = 13;
    private static final int REQ_PERM = 21;
    private static final int HZ = 8000;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout root;
    private WaveformView wave;
    private GlowButton playBtn;
    private TextView nowPlaying, elapsed, total, listHeader;
    private ListView list;
    private Button shuffleMini, repeatMini;
    private Adapter adapter;

    private PlaybackService svc;
    private boolean bound = false, resumed = false;
    private final ArrayList<Uri> pendingOpen = new ArrayList<>();
    private volatile boolean scanning = false;
    private int tintColor = 0xFF14202E;   // arka plana karisan, moda gore renk
    private String appliedLang;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            svc = ((PlaybackService.LocalBinder) b).get();
            bound = true;
            svc.setCallback(MainActivity.this);
            if (resumed) svc.setUiVisible(true);
            refreshControls();
            adapter.notifyDataSetChanged();
            if (svc.getCurrent() >= 0) loadWaveform();
            processPendingOpen();
            scanDurations();
        }
        @Override public void onServiceDisconnected(ComponentName n) { bound = false; svc = null; }
    };

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        appliedLang = LocaleHelper.getLang(this);
        setContentView(R.layout.activity_main);

        root = (LinearLayout) findViewById(R.id.root);
        wave = (WaveformView) findViewById(R.id.wave);
        playBtn = (GlowButton) findViewById(R.id.playBtn);
        nowPlaying = (TextView) findViewById(R.id.nowPlaying);
        elapsed = (TextView) findViewById(R.id.elapsed);
        total = (TextView) findViewById(R.id.total);
        listHeader = (TextView) findViewById(R.id.listHeader);
        list = (ListView) findViewById(R.id.list);
        TextView emptyHint = (TextView) findViewById(R.id.emptyHint);
        emptyHint.setText(R.string.empty_hint);
        list.setEmptyView(emptyHint);
        nowPlaying.setSelected(true);   // uzun ad icin marquee kaysin
        setupTitleShimmer();
        android.graphics.drawable.GradientDrawable wavePanel = new android.graphics.drawable.GradientDrawable();
        wavePanel.setColor(0x09FFFFFF);
        wavePanel.setCornerRadius(dp(14));
        wave.setBackground(wavePanel);

        startBackgroundAnimation();
        for (int id : new int[]{R.id.prev, R.id.next, R.id.settings, R.id.addFiles, R.id.addFolder, R.id.convert})
            styleButton((Button) findViewById(id));
        shuffleMini = (Button) findViewById(R.id.shuffleMini);
        repeatMini = (Button) findViewById(R.id.repeatMini);
        styleMini(shuffleMini);
        styleMini(repeatMini);
        shuffleMini.setOnClickListener(v -> { if (svc != null) { svc.setShuffle(!svc.isShuffle()); refreshControls(); } });
        repeatMini.setOnClickListener(v -> { if (svc != null) { svc.cycleRepeat(); refreshControls(); } });

        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, idv) -> { if (svc != null) svc.playIndex(pos); });
        list.setOnItemLongClickListener((p, v, pos, idv) -> { showItemMenu(pos); return true; });

        playBtn.setOnClickListener(v -> { if (svc != null) svc.toggle(); });
        findViewById(R.id.prev).setOnClickListener(v -> {
            if (svc == null) return;
            if (svc.positionSamples() > 24000) svc.seekFraction(0f);   // 3 sn'den ilerideyse basa sar
            else svc.playIndex(svc.neighbor(-1));
        });
        findViewById(R.id.next).setOnClickListener(v -> { if (svc != null) svc.playIndex(svc.neighbor(+1)); });
        findViewById(R.id.prev).setOnLongClickListener(v -> { if (svc != null) svc.seekRelative(-10); return true; });
        findViewById(R.id.next).setOnLongClickListener(v -> { if (svc != null) svc.seekRelative(10); return true; });
        findViewById(R.id.settings).setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.addFiles).setOnClickListener(v -> pickFiles());
        findViewById(R.id.addFolder).setOnClickListener(v -> pickFolder());
        findViewById(R.id.convert).setOnClickListener(v -> pickConvert());
        wave.setSeekListener(f -> { if (svc != null) svc.seekFraction(f); });

        handleOpenIntent(getIntent());
        showCrashIfAny();
    }

    /** Onceki acilista yakalanan cokme raporu varsa kullaniciya goster (sessiz cokme yok). */
    private void showCrashIfAny() {
        final java.io.File f = new java.io.File(getFilesDir(), App.CRASH_FILE);
        if (!f.exists()) return;
        String txt;
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[4096]; int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            in.close();
            txt = new String(bo.toByteArray(), "UTF-8");
        } catch (Exception e) { f.delete(); return; }
        final String report = txt;

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(report);
        tv.setTextIsSelectable(true);
        tv.setTextColor(0xFFE6EEF8);
        tv.setTextSize(12);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        int pad = dp(16);
        tv.setPadding(pad, pad, pad, pad);
        sv.addView(tv);

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.crash_title)
                .setView(sv)
                .setPositiveButton(R.string.ok, null)
                .setNeutralButton(R.string.crash_share, (d, w) -> {
                    Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain");
                    send.putExtra(Intent.EXTRA_TEXT, report);
                    startActivity(Intent.createChooser(send, getString(R.string.crash_share)));
                })
                .setOnDismissListener(d -> f.delete())
                .show();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleOpenIntent(intent);
    }

    /** Disaridan "ac" / "paylas" ile gelen .c2 dosyalarini listeye al ve cal. */
    private void handleOpenIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (action == null) return;
        ArrayList<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_VIEW.equals(action)) {
            if (intent.getData() != null) uris.add(intent.getData());
        } else if (Intent.ACTION_SEND.equals(action)) {
            Object p = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (p instanceof Uri) uris.add((Uri) p);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<? extends android.os.Parcelable> ps = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (ps != null) for (android.os.Parcelable p : ps) if (p instanceof Uri) uris.add((Uri) p);
        }
        if (uris.isEmpty()) return;
        pendingOpen.addAll(uris);
        if (svc != null) processPendingOpen();
    }

    private void processPendingOpen() {
        if (svc == null || pendingOpen.isEmpty()) return;
        ArrayList<Item> items = new ArrayList<>();
        for (Uri u : pendingOpen) { persist(u); items.add(new Item(u.toString(), queryName(u))); }
        String firstUri = items.isEmpty() ? null : items.get(0).uri;
        pendingOpen.clear();
        svc.addItems(items);
        int idx = svc.indexOfUri(firstUri);
        if (idx >= 0) svc.playIndex(idx);
        toast(getString(R.string.files_opened, items.size()));
    }

    // ---------- uzun bas menüsü (silme YOK) ----------

    private void showItemMenu(final int pos) {
        if (svc == null) return;
        final ArrayList<Item> pl = svc.getPlaylist();
        if (pos < 0 || pos >= pl.size()) return;
        final Item it = pl.get(pos);
        final int size = pl.size();
        final String[] opts = {getString(R.string.m_play_here), getString(R.string.m_move_up),
                getString(R.string.m_move_down), getString(R.string.m_to_top), getString(R.string.m_to_bottom),
                getString(R.string.m_share_c2), getString(R.string.m_share_wav), getString(R.string.m_info)};
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(it.name)
                .setItems(opts, (d, w) -> {
                    switch (w) {
                        case 0: svc.playIndex(pos); break;
                        case 1: svc.moveItem(pos, Math.max(0, pos - 1)); break;
                        case 2: svc.moveItem(pos, Math.min(size - 1, pos + 1)); break;
                        case 3: svc.moveItem(pos, 0); break;
                        case 4: svc.moveItem(pos, size - 1); break;
                        case 5: shareAsC2(it); break;
                        case 6: shareAsWav(it); break;
                        case 7: showInfo(it); break;
                    }
                })
                .show();
    }

    private void shareAsC2(final Item it) {
        new Thread(() -> {
            try {
                byte[] data = readAll(Uri.parse(it.uri));
                java.io.File dir = new java.io.File(getFilesDir(), "share");
                dir.mkdirs();
                java.io.File[] old = dir.listFiles();
                if (old != null) for (java.io.File f : old) f.delete();
                String nm = (it.name == null || it.name.isEmpty()) ? "ses.c2" : it.name;
                if (!nm.toLowerCase().endsWith(".c2")) nm = nm + ".c2";
                final java.io.File out = new java.io.File(dir, nm);
                java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
                fos.write(data); fos.close();
                final Uri share = new Uri.Builder().scheme("content")
                        .authority(ShareProvider.AUTHORITY).appendPath(out.getName()).build();
                post(() -> {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("application/octet-stream");
                    send.putExtra(Intent.EXTRA_STREAM, share);
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(send, getString(R.string.share_c2_title)));
                });
            } catch (Exception e) { post(() -> toast(getString(R.string.error_msg, e.getMessage()))); }
        }).start();
    }

    private void shareAsWav(final Item it) {
        toast(getString(R.string.wav_preparing));
        new Thread(() -> {
            try {
                byte[] data = readAll(Uri.parse(it.uri));
                Decoder.Result r = Decoder.decode(data, Codec2.MODE_1300);
                if (r == null || r.pcm == null) { post(() -> toast(getString(R.string.conv_no_decode))); return; }
                java.io.File dir = new java.io.File(getFilesDir(), "share");
                dir.mkdirs();
                java.io.File[] old = dir.listFiles();   // eski paylasim wav'larini temizle
                if (old != null) for (java.io.File f : old) f.delete();
                String nm = (it.name == null) ? "ses" : it.name;
                String base = nm.toLowerCase().endsWith(".c2") ? nm.substring(0, nm.length() - 3) : nm;
                java.io.File out = new java.io.File(dir, base + ".wav");
                Decoder.writeWav(out, r.pcm, r.sampleRate);
                final Uri share = new Uri.Builder().scheme("content")
                        .authority(ShareProvider.AUTHORITY).appendPath(out.getName()).build();
                post(() -> {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("audio/wav");
                    send.putExtra(Intent.EXTRA_STREAM, share);
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(send, getString(R.string.share_wav_title)));
                });
            } catch (Exception e) { post(() -> toast(getString(R.string.error_msg, e.getMessage()))); }
        }).start();
    }

    private void showInfo(Item it) {
        long size = -1;
        try { size = querySize(Uri.parse(it.uri)); } catch (Exception ignore) {}
        String msg = getString(R.string.info_body, it.name,
                (it.mode >= 0 ? modeLabel(it.mode) : "?"),
                (it.durSec >= 0 ? fmt(it.durSec) : "?"),
                humanSize(size), it.uri);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.info_title)
                .setMessage(msg)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private static String humanSize(long b) {
        if (b < 0) return "?";
        if (b < 1024) return b + " byte";
        String[] u = {"KB", "MB", "GB", "TB"};
        double s = b / 1024.0;
        int i = 0;
        while (s >= 1024 && i < u.length - 1) { s /= 1024; i++; }
        return String.format(java.util.Locale.forLanguageTag("tr"), "%.2f %s", s, u[i]);
    }

    @Override
    protected void onStart() {
        super.onStart();
        // bindService arka planda da izinli; startService DEGIL (API 26+ background'da coker)
        bindService(new Intent(this, PlaybackService.class), conn, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (appliedLang != null && !appliedLang.equals(LocaleHelper.getLang(this))) { recreate(); return; }
        resumed = true;
        // on planda oldugumuzdan emin -> startService burada guvenli (servisi kalici yap)
        try { startService(new Intent(this, PlaybackService.class)); } catch (Exception ignore) {}
        if (bound && svc != null) svc.setUiVisible(true);
        ui.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        ui.removeCallbacks(ticker);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (bound && svc != null) {
            svc.setUiVisible(false);
            svc.setCallback(null);
            unbindService(conn);
            bound = false;
        }
        del(getCacheDir()); del(getCodeCacheDir());
    }

    // ---------- Service.Callback ----------

    @Override public void onTrackChanged(int index) {
        post(() -> {
            updateTint();
            nowPlaying.setAlpha(0f);
            refreshNowPlaying();
            nowPlaying.animate().alpha(1f).setDuration(260).start();
            loadWaveform();
            adapter.notifyDataSetChanged();
            updateListHeader();
        });
    }

    private void updateTint() {
        if (svc == null) return;
        int c = svc.getCurrent();
        ArrayList<Item> pl = svc.getPlaylist();
        if (c >= 0 && c < pl.size() && pl.get(c).mode >= 0)
            tintColor = darkTint(modeColor(pl.get(c).mode));
    }

    private static int darkTint(int color) {
        float[] h = new float[3];
        android.graphics.Color.colorToHSV(color, h);
        h[1] *= 0.85f; h[2] = 0.22f;
        return android.graphics.Color.HSVToColor(h);
    }
    @Override public void onStateChanged(boolean playing) {
        post(() -> {
            playBtn.setPlaying(playing);
            if (playing) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
    }
    @Override public void onPlaylistChanged() { post(() -> { adapter.notifyDataSetChanged(); refreshNowPlaying(); updateListHeader(); scanDurations(); }); }
    @Override public void onError(String msg) { post(() -> toast(msg)); }

    private void refreshControls() {
        if (svc == null) return;
        playBtn.setPlaying(svc.isPlaying());
        boolean sh = svc.isShuffle();
        shuffleMini.setTextColor(sh ? 0xFF8FE3FF : 0xFF7C8DA6);
        int rm = svc.getRepeatMode();
        repeatMini.setText(rm == 0 ? getString(R.string.repeat)
                : (rm == 1 ? getString(R.string.repeat_label_all) : getString(R.string.repeat_label_one)));
        repeatMini.setTextColor(rm == 0 ? 0xFF7C8DA6 : 0xFF8FE3FF);
        updateTint();
        refreshNowPlaying();
        updateListHeader();
    }

    private void updateListHeader() {
        if (listHeader == null) return;
        if (svc == null) { listHeader.setText(R.string.playlist); return; }
        ArrayList<Item> pl = svc.getPlaylist();
        int known = 0;
        for (Item it : pl) if (it.durSec > 0) known += it.durSec;
        listHeader.setText(known > 0
                ? getString(R.string.playlist_count_dur, pl.size(), fmt(known))
                : getString(R.string.playlist_count, pl.size()));
    }

    private void refreshNowPlaying() {
        if (svc == null) return;
        int c = svc.getCurrent();
        ArrayList<Item> pl = svc.getPlaylist();
        if (c >= 0 && c < pl.size()) {
            Item it = pl.get(c);
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
            sb.append(it.name);
            if (it.mode >= 0) {
                dimSep(sb);
                int s = sb.length();
                sb.append(modeLabel(it.mode));
                sb.setSpan(new android.text.style.ForegroundColorSpan(modeColor(it.mode)), s, sb.length(), 0);
            }
            if (it.durSec >= 0) { dimSep(sb); sb.append(fmt(it.durSec)); }
            nowPlaying.setText(sb);
        } else {
            nowPlaying.setText(getString(pl.isEmpty() ? R.string.pick_or_add : R.string.ready));
        }
    }

    private void dimSep(android.text.SpannableStringBuilder sb) {
        int s = sb.length();
        sb.append("   ·   ");
        sb.setSpan(new android.text.style.ForegroundColorSpan(0xFF46566E), s, sb.length(), 0);
    }

    private static int modeColor(int mode) {
        switch (mode) {
            case 0: return 0xFF7FE0FF; case 1: return 0xFF6FD0FF; case 2: return 0xFF8FB0FF;
            case 3: return 0xFFA89AFF; case 4: return 0xFFB890F0; case 5: return 0xFFCE8AE0;
            case 8: return 0xFF80E0B0; case 10: return 0xFFE0A860;
            default: return 0xFF9FB3CC;
        }
    }

    private float[] pendingPeaks;
    private void loadWaveform() {
        if (svc == null) return;
        // dalga formunu mevcut parcadan cikar (arka plan thread)
        final int c = svc.getCurrent();
        final ArrayList<Item> pl = svc.getPlaylist();
        if (c < 0 || c >= pl.size()) { wave.setPeaks(new float[0]); return; }
        final Uri uri = Uri.parse(pl.get(c).uri);
        new Thread(() -> {
            try {
                byte[] head = readHead(uri, Codec2.HEADER_SIZE);
                float[] pk;
                if (Codec2.headerMode(head) >= 0) {                 // codec2 .c2 (kucuk; tam coz)
                    Decoder.Result r = Decoder.decode(readAll(uri), Codec2.MODE_1300);
                    pk = (r != null && r.pcm != null) ? Decoder.peaks(r.pcm, 420) : new float[0];
                } else {                                            // normal ses streaming caliyor; tam cozme YOK (OOM riski)
                    pk = new float[0];
                }
                final float[] fpk = pk;
                post(() -> wave.setPeaks(fpk));
            } catch (Throwable ignore) {}
        }, "wave").start();
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (svc != null) {
                int pos = svc.positionSamples(), tot = svc.totalSamples();
                float frac = tot > 0 ? pos / (float) tot : 0f;
                float lvl = svc.level() / 32768f;
                wave.setProgress(frac);
                wave.setLevel(lvl);
                wave.invalidate();
                playBtn.setProgress(frac);
                int sr = svc.sampleRate();
                elapsed.setText(fmt(pos / sr));
                if (tot > 0) total.setText(fmt(tot / sr));
                playBtn.setPlaying(svc.isPlaying());
            }
            ui.postDelayed(this, 40);
        }
    };

    // ---------- dosya/klasor ----------

    private void pickFiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_FILES);
    }

    private void pickFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_FOLDER);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || svc == null) return;

        if (req == REQ_FILES) {
            ArrayList<Item> items = new ArrayList<>();
            ArrayList<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null)
                for (int k = 0; k < data.getClipData().getItemCount(); k++)
                    uris.add(data.getClipData().getItemAt(k).getUri());
            else if (data.getData() != null) uris.add(data.getData());
            for (Uri u : uris) { persist(u); items.add(new Item(u.toString(), queryName(u))); }
            svc.addItems(items);
            toast(getString(R.string.files_added, items.size()));

        } else if (req == REQ_FOLDER) {
            final Uri tree = data.getData();
            persist(tree);
            nowPlaying.setText(getString(R.string.scanning_folder));
            new Thread(() -> {
                final ArrayList<Item> found = scanTree(tree);
                post(() -> { svc.addItems(found); toast(getString(R.string.c2_found, found.size())); refreshNowPlaying(); });
            }, "scan").start();

        } else if (req == REQ_CONVERT) {
            ArrayList<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null)
                for (int k = 0; k < data.getClipData().getItemCount(); k++)
                    uris.add(data.getClipData().getItemAt(k).getUri());
            else if (data.getData() != null) uris.add(data.getData());
            if (!uris.isEmpty()) showConvertDialog(uris);
        }
    }

    // ---------- ses dosyasini c2'ye cevir ----------

    private void pickConvert() {
        if (android.os.Build.VERSION.SDK_INT >= 23 && android.os.Build.VERSION.SDK_INT <= 28
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERM);
        } else {
            launchConvertPicker();
        }
    }

    private void launchConvertPicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);   // toplu donusturme
        startActivityForResult(i, REQ_CONVERT);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == REQ_PERM) launchConvertPicker();
    }

    private void showConvertDialog(final ArrayList<Uri> uris) {
        new Thread(() -> {
            long us = 0;
            for (Uri u : uris) us += AudioDecoder.durationUs(MainActivity.this, u);
            final int sec = (int) (us / 1000000L);
            post(() -> showConvertDialogReady(uris, sec));
        }, "dur").start();
    }

    private void showConvertDialogReady(final ArrayList<Uri> uris, int totalSec) {
        final int[] modes = {0, 4, 8, 10};
        final int[] bps = {3200, 1300, 700, 450};
        final int[] baseStr = {R.string.conv_mode_3200, R.string.conv_mode_1300, R.string.conv_mode_700c, R.string.conv_mode_450};
        String[] labels = new String[4];
        for (int i = 0; i < 4; i++) {
            int kb = (int) ((long) bps[i] * totalSec / 8 / 1024);
            labels[i] = getString(baseStr[i]) + (totalSec > 0 ? getString(R.string.conv_size_suffix, kb) : "");
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(getString(R.string.conv_title, uris.size(), fmt(totalSec)))
                .setItems(labels, (d, w) -> convertAll(uris, modes[w]))
                .show();
    }

    private void convertAll(final ArrayList<Uri> uris, final int mode) {
        Log2.clear();
        Log2.add(getString(R.string.conv_header, uris.size(), modeLabel(mode)));
        startActivity(new Intent(this, LogActivity.class));   // canli gunlugu ac
        new Thread(() -> {
            int ok = 0;
            for (int i = 0; i < uris.size(); i++) {
                Log2.add("");
                Log2.add(getString(R.string.conv_item, i + 1, uris.size(), queryName(uris.get(i))));
                if (convertOne(uris.get(i), mode)) ok++;
            }
            Log2.add("");
            Log2.add(getString(R.string.conv_done, ok, uris.size()));
        }, "convert").start();
    }

    /** Tek dosyayi cevirir; basariliysa true. Arka plan thread'inde cagrilir. */
    private boolean convertOne(Uri u, int mode) {
        try {
            Log2.add(getString(R.string.conv_decoding));
            long t0 = android.os.SystemClock.elapsedRealtime();
            short[] pcm = AudioDecoder.decodeTo8kMono(MainActivity.this, u);
            if (pcm == null || pcm.length == 0) { Log2.add(getString(R.string.conv_no_decode)); return false; }
            Log2.add(getString(R.string.conv_pcm, pcm.length, pcm.length / 8000));
            byte[] c2 = Encoder.encodeToC2(pcm, mode);
            if (c2 == null) { Log2.add(getString(R.string.conv_no_encode)); return false; }
            String base = queryName(u);
            int dot = base.lastIndexOf('.');
            if (dot > 0) base = base.substring(0, dot);
            java.io.File dir = resolveDir(u);
            if (dir != null && dir.canWrite()) Log2.add(getString(R.string.conv_folder_orig, dir.getAbsolutePath()));
            else { dir = new java.io.File(getExternalFilesDir(null), "converted"); dir.mkdirs(); Log2.add(getString(R.string.conv_folder_app, dir.getAbsolutePath())); }
            java.io.File out = new java.io.File(dir, base + ".c2");
            int kk = 1;
            while (out.exists()) { out = new java.io.File(dir, base + " (" + kk + ").c2"); kk++; }
            final java.io.File outF = out;
            java.io.FileOutputStream fos = new java.io.FileOutputStream(outF);
            fos.write(c2); fos.close();
            long dt = android.os.SystemClock.elapsedRealtime() - t0;
            Log2.add(getString(R.string.conv_written, outF.getName(), Math.max(1, c2.length / 1024), (int) dt));
            post(() -> {
                if (svc != null) {
                    ArrayList<Item> items = new ArrayList<>();
                    items.add(new Item(Uri.fromFile(outF).toString(), outF.getName()));
                    svc.addItems(items);
                }
            });
            return true;
        } catch (Exception e) { Log2.add(getString(R.string.conv_error, String.valueOf(e))); return false; }
    }

    /** Kaynak uri'nin gercek klasoru (orijinalin yani). Cozulemezse null. */
    private java.io.File resolveDir(Uri u) {
        try {
            if ("file".equals(u.getScheme()) && u.getPath() != null)
                return new java.io.File(u.getPath()).getParentFile();
            if (android.provider.DocumentsContract.isDocumentUri(this, u)) {
                String docId = android.provider.DocumentsContract.getDocumentId(u);
                int c = docId.indexOf(':');
                if (c > 0 && "primary".equalsIgnoreCase(docId.substring(0, c))) {
                    java.io.File f = new java.io.File(android.os.Environment.getExternalStorageDirectory(),
                            docId.substring(c + 1));
                    return f.getParentFile();
                }
            }
        } catch (Exception ignore) {}
        android.database.Cursor cur = null;
        try {
            cur = getContentResolver().query(u, new String[]{"_data"}, null, null, null);
            if (cur != null && cur.moveToFirst()) {
                String p = cur.getString(0);
                if (p != null) return new java.io.File(p).getParentFile();
            }
        } catch (Exception ignore) {
        } finally { if (cur != null) cur.close(); }
        return null;
    }

    private ArrayList<Item> scanTree(Uri tree) {
        ArrayList<Item> out = new ArrayList<>();
        ContentResolver cr = getContentResolver();
        String root = DocumentsContract.getTreeDocumentId(tree);
        Deque<String> stack = new ArrayDeque<>();
        stack.push(root);
        int guard = 0;
        while (!stack.isEmpty() && guard < 5000) {
            String parent = stack.pop(); guard++;
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent);
            Cursor c = null;
            try {
                c = cr.query(children, new String[]{
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null);
                while (c != null && c.moveToNext()) {
                    String id = c.getString(0), name = c.getString(1), mime = c.getString(2);
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) stack.push(id);
                    else if (name != null && name.toLowerCase().endsWith(".c2")) {
                        Uri du = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                        out.add(new Item(du.toString(), name));
                    }
                }
            } catch (Exception ignore) {
            } finally { if (c != null) c.close(); }
        }
        return out;
    }

    private void persist(Uri u) {
        try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (Exception ignore) {}
    }

    private String queryName(Uri u) {
        Cursor c = null;
        try {
            c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) { String n = c.getString(0); if (n != null) return n; }
        } catch (Exception ignore) { } finally { if (c != null) c.close(); }
        String last = u.getLastPathSegment();
        return last != null ? last : u.toString();
    }

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

    // ---------- sure on-hesaplama (tam cozmeden) ----------

    private void scanDurations() {
        if (svc == null || scanning) return;
        scanning = true;
        new Thread(() -> {
            try {
                ArrayList<Item> pl = svc.getPlaylist();
                boolean any = false;
                for (int i = 0; i < pl.size(); i++) {
                    Item it = pl.get(i);
                    if (it.durSec >= 0 && it.mode != -1) continue;   // mode -1 = tur henuz belli degil, yeniden tara
                    try {
                        Uri u = Uri.parse(it.uri);
                        byte[] head = readHead(u, 7);
                        int hm = Codec2.headerMode(head);
                        if (hm >= 0) {                                  // codec2 .c2
                            int d = Decoder.quickDuration(head, querySize(u));
                            if (d >= 0) { it.durSec = d; any = true; }
                            if (it.mode < 0) { it.mode = hm; any = true; }
                        } else {                                        // normal ses
                            long us = AudioDecoder.durationUs(MainActivity.this, u);
                            if (us > 0) { it.durSec = (int) (us / 1000000L); any = true; }
                            if (it.mode != Item.MODE_AUDIO) { it.mode = Item.MODE_AUDIO; any = true; }
                        }
                    } catch (Exception ignore) {}
                    if (any && (i % 6 == 0)) post(() -> { adapter.notifyDataSetChanged(); updateListHeader(); });
                }
                post(() -> { adapter.notifyDataSetChanged(); updateListHeader(); if (svc != null) svc.persist(); });
            } finally { scanning = false; }
        }, "dur-scan").start();
    }

    private byte[] readHead(Uri u, int n) throws Exception {
        InputStream in = getContentResolver().openInputStream(u);
        if (in == null) return new byte[0];
        try {
            byte[] b = new byte[n]; int got = 0, r;
            while (got < n && (r = in.read(b, got, n - got)) > 0) got += r;
            return (got == n) ? b : java.util.Arrays.copyOf(b, got);
        } finally { in.close(); }
    }

    private long querySize(Uri u) {
        try {
            android.content.res.AssetFileDescriptor afd = getContentResolver().openAssetFileDescriptor(u, "r");
            if (afd != null) {
                long len = afd.getLength();
                afd.close();
                if (len != android.content.res.AssetFileDescriptor.UNKNOWN_LENGTH) return len;
            }
        } catch (Exception ignore) {}
        return -1;
    }

    // ---------- liste ----------

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return svc == null ? 0 : svc.getPlaylist().size(); }
        @Override public Object getItem(int p) { return svc.getPlaylist().get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int p, View cv, ViewGroup parent) {
            LinearLayout row;
            if (cv instanceof LinearLayout) row = (LinearLayout) cv;
            else {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                View bar = new View(MainActivity.this);
                bar.setLayoutParams(new LinearLayout.LayoutParams(dp(3), LinearLayout.LayoutParams.MATCH_PARENT));
                row.addView(bar);
                LinearLayout col = new LinearLayout(MainActivity.this);
                col.setOrientation(LinearLayout.VERTICAL);
                int pad = dp(12);
                col.setPadding(pad, dp(8), pad, dp(8));
                TextView t1 = new TextView(MainActivity.this); t1.setId(android.R.id.text1);
                t1.setTextSize(15); t1.setSingleLine(true);
                t1.setEllipsize(android.text.TextUtils.TruncateAt.END);
                t1.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
                TextView t2 = new TextView(MainActivity.this); t2.setId(android.R.id.text2);
                t2.setTextSize(12); t2.setTextColor(0xFF8597AD);
                col.addView(t1); col.addView(t2);
                row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            }
            ArrayList<Item> pl = svc.getPlaylist();
            Item it = pl.get(p);
            boolean cur = (p == svc.getCurrent());
            TextView t1 = (TextView) row.findViewById(android.R.id.text1);
            TextView t2 = (TextView) row.findViewById(android.R.id.text2);
            String nm = (it.name == null || it.name.isEmpty()) ? getString(R.string.unnamed) : it.name;
            t1.setText((cur ? ">  " : "") + nm);
            t1.setTextColor(cur ? 0xFF8FE3FF : 0xFFE6EEF8);
            android.text.SpannableStringBuilder sub = new android.text.SpannableStringBuilder();
            sub.append((p + 1) + " / " + pl.size());
            if (it.mode >= 0) {
                sub.append("   ·   ");
                int s = sub.length();
                sub.append(modeLabel(it.mode));
                sub.setSpan(new android.text.style.ForegroundColorSpan(modeColor(it.mode)), s, sub.length(), 0);
            } else if (it.mode == Item.MODE_AUDIO) {
                sub.append("   ·   ");
                int s = sub.length();
                sub.append(audioLabel(nm));
                sub.setSpan(new android.text.style.ForegroundColorSpan(0xFF9FD8B0), s, sub.length(), 0);
            }
            if (it.durSec >= 0) { sub.append("   ·   "); sub.append(fmt(it.durSec)); }
            t2.setText(sub);
            row.setBackgroundColor(cur ? 0x223A6EA5 : 0x00000000);
            row.getChildAt(0).setBackgroundColor(cur ? modeColor(it.mode >= 0 ? it.mode : 4) : 0x00000000);
            return row;
        }
    }

    // ---------- gorsel ----------

    private void setupTitleShimmer() {
        final TextView title = (TextView) findViewById(R.id.title);
        title.post(() -> {
            float w = title.getPaint().measureText(title.getText().toString());
            if (w <= 1) w = title.getWidth();
            if (w <= 1) return;
            final float tw = w;
            final android.graphics.LinearGradient lg = new android.graphics.LinearGradient(
                    0, 0, tw, 0,
                    new int[]{0xFFEAF2FF, 0xFFFFFFFF, 0xFF9FD8FF, 0xFFEAF2FF},
                    new float[]{0f, 0.45f, 0.6f, 1f}, android.graphics.Shader.TileMode.CLAMP);
            title.getPaint().setShader(lg);
            final android.graphics.Matrix m = new android.graphics.Matrix();
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(-tw, tw * 2f);
            a.setDuration(3000);
            a.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            a.addUpdateListener(v -> {
                m.setTranslate((float) v.getAnimatedValue(), 0);
                lg.setLocalMatrix(m);
                title.invalidate();
            });
            a.start();
        });
    }

    private void startBackgroundAnimation() {
        final GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{0xFF0B1118, 0xFF101A26, 0xFF0B1118});
        root.setBackground(g);
        final android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(9000);
        va.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        va.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        va.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            int b1 = blend(0xFF0B1118, 0xFF132034, f);
            int b2 = blend(0xFF161226, 0xFF0E2233, f);
            g.setColors(new int[]{ blend(b1, tintColor, 0.12f), blend(b2, tintColor, 0.08f), 0xFF0A0F16 });
        });
        va.start();
    }

    private void addPressScale(final View v) {
        v.setOnTouchListener((vv, e) -> {
            int a = e.getActionMasked();
            if (a == android.view.MotionEvent.ACTION_DOWN)
                vv.animate().scaleX(0.95f).scaleY(0.95f).setDuration(80).start();
            else if (a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL)
                vv.animate().scaleX(1f).scaleY(1f).setDuration(130).start();
            return false;
        });
    }

    private void styleMini(Button b) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(12));
        g.setColor(0x10FFFFFF);
        g.setStroke(dp(1), 0x2AA8C8FF);
        b.setBackground(g);
        b.setAllCaps(false);
        b.setPadding(dp(16), dp(6), dp(16), dp(6));
        if (b.getLayoutParams() instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) b.getLayoutParams();
            lp.setMargins(dp(6), 0, dp(6), 0);
            b.setLayoutParams(lp);
        }
        addPressScale(b);
    }

    private void styleButton(Button b) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(14));
        g.setColor(0x14FFFFFF);
        g.setStroke(dp(1), 0x33A8C8FF);
        int m = dp(4);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) b.getLayoutParams();
        if (lp != null) { lp.setMargins(m, m, m, m); b.setLayoutParams(lp); }
        b.setBackground(g);
        b.setTextColor(0xFFD6E6FB);
        b.setAllCaps(false);
        addPressScale(b);
    }

    private static int blend(int a, int b, float t) {
        int ar = (a >> 16) & 0xff, ag = (a >> 8) & 0xff, ab = a & 0xff;
        int br = (b >> 16) & 0xff, bg = (b >> 8) & 0xff, bb = b & 0xff;
        return 0xFF000000 | ((int)(ar+(br-ar)*t) << 16) | ((int)(ag+(bg-ag)*t) << 8) | (int)(ab+(bb-ab)*t);
    }

    private static String modeLabel(int mode) {
        switch (mode) {
            case 0: return "3200"; case 1: return "2400"; case 2: return "1600";
            case 3: return "1400"; case 4: return "1300"; case 5: return "1200";
            case 8: return "700C"; case 10: return "450";
            default: return "mod " + mode;
        }
    }

    /** Normal ses dosyasi icin etiket: uzantinin buyuk hali (MP3/OGG/...), yoksa AUDIO. */
    private static String audioLabel(String name) {
        if (name != null) {
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && dot < name.length() - 1) {
                String ext = name.substring(dot + 1).toUpperCase();
                if (ext.length() >= 1 && ext.length() <= 5) return ext;
            }
        }
        return "AUDIO";
    }

    private static String fmt(int sec) {
        if (sec < 0) sec = 0;
        int m = sec / 60, s = sec % 60;
        return m + ":" + (s < 10 ? "0" + s : "" + s);
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private void post(Runnable r) { ui.post(r); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private static void del(java.io.File f) {
        if (f == null) return;
        java.io.File[] k = f.listFiles();
        if (k != null) for (java.io.File c : k) del(c);
        f.delete();
    }
}
