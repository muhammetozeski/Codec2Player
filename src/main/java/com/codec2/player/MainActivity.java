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
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_main);

        root = (LinearLayout) findViewById(R.id.root);
        wave = (WaveformView) findViewById(R.id.wave);
        playBtn = (GlowButton) findViewById(R.id.playBtn);
        nowPlaying = (TextView) findViewById(R.id.nowPlaying);
        elapsed = (TextView) findViewById(R.id.elapsed);
        total = (TextView) findViewById(R.id.total);
        listHeader = (TextView) findViewById(R.id.listHeader);
        list = (ListView) findViewById(R.id.list);

        startBackgroundAnimation();
        for (int id : new int[]{R.id.prev, R.id.next, R.id.settings, R.id.addFiles, R.id.addFolder})
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
        findViewById(R.id.prev).setOnClickListener(v -> { if (svc != null) svc.playIndex(svc.neighbor(-1)); });
        findViewById(R.id.next).setOnClickListener(v -> { if (svc != null) svc.playIndex(svc.neighbor(+1)); });
        findViewById(R.id.settings).setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.addFiles).setOnClickListener(v -> pickFiles());
        findViewById(R.id.addFolder).setOnClickListener(v -> pickFolder());
        wave.setSeekListener(f -> { if (svc != null) svc.seekFraction(f); });

        handleOpenIntent(getIntent());
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
        toast(items.size() + " dosya açıldı");
    }

    // ---------- uzun bas menüsü (silme YOK) ----------

    private void showItemMenu(final int pos) {
        if (svc == null) return;
        final ArrayList<Item> pl = svc.getPlaylist();
        if (pos < 0 || pos >= pl.size()) return;
        final Item it = pl.get(pos);
        final int size = pl.size();
        final String[] opts = {"Buradan oynat", "Yukarı taşı", "Aşağı taşı", "Başa al", "Sona al", "Bilgi"};
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(it.name)
                .setItems(opts, (d, w) -> {
                    switch (w) {
                        case 0: svc.playIndex(pos); break;
                        case 1: svc.moveItem(pos, Math.max(0, pos - 1)); break;
                        case 2: svc.moveItem(pos, Math.min(size - 1, pos + 1)); break;
                        case 3: svc.moveItem(pos, 0); break;
                        case 4: svc.moveItem(pos, size - 1); break;
                        case 5: showInfo(it); break;
                    }
                })
                .show();
    }

    private void showInfo(Item it) {
        String msg = "Ad: " + it.name
                + "\nMod: " + (it.mode >= 0 ? modeLabel(it.mode) : "?")
                + "\nSüre: " + (it.durSec >= 0 ? fmt(it.durSec) : "?")
                + "\nKaynak: " + it.uri;
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Bilgi")
                .setMessage(msg)
                .setPositiveButton("Tamam", null)
                .show();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Intent i = new Intent(this, PlaybackService.class);
        startService(i);
        bindService(i, conn, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
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

    @Override public void onTrackChanged(int index) { post(() -> { refreshNowPlaying(); loadWaveform(); adapter.notifyDataSetChanged(); updateListHeader(); }); }
    @Override public void onStateChanged(boolean playing) {
        post(() -> {
            playBtn.setPlaying(playing);
            if (playing) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
    }
    @Override public void onPlaylistChanged() { post(() -> { adapter.notifyDataSetChanged(); refreshNowPlaying(); updateListHeader(); scanDurations(); }); }

    private void refreshControls() {
        if (svc == null) return;
        playBtn.setPlaying(svc.isPlaying());
        boolean sh = svc.isShuffle();
        shuffleMini.setTextColor(sh ? 0xFF8FE3FF : 0xFF7C8DA6);
        int rm = svc.getRepeatMode();
        repeatMini.setText(rm == 0 ? "Tekrar" : (rm == 1 ? "Tekrar: Tümü" : "Tekrar: Tekli"));
        repeatMini.setTextColor(rm == 0 ? 0xFF7C8DA6 : 0xFF8FE3FF);
        refreshNowPlaying();
        updateListHeader();
    }

    private void updateListHeader() {
        if (listHeader == null) return;
        if (svc == null) { listHeader.setText("Çalma listesi"); return; }
        ArrayList<Item> pl = svc.getPlaylist();
        int known = 0;
        for (Item it : pl) if (it.durSec > 0) known += it.durSec;
        String t = "Çalma listesi   ·   " + pl.size() + " parça";
        if (known > 0) t += "   ·   ~" + fmt(known);
        listHeader.setText(t);
    }

    private void refreshNowPlaying() {
        if (svc == null) return;
        int c = svc.getCurrent();
        ArrayList<Item> pl = svc.getPlaylist();
        if (c >= 0 && c < pl.size()) {
            Item it = pl.get(c);
            ArrayList<String> parts = new ArrayList<>();
            parts.add(it.name);
            if (it.mode >= 0) parts.add(modeLabel(it.mode));
            if (it.durSec >= 0) parts.add(fmt(it.durSec));
            nowPlaying.setText(joinDim(parts));
        } else {
            nowPlaying.setText(pl.isEmpty() ? "Bir dosya seç ya da ekle" : "Hazır");
        }
    }

    /** parcalari soluk ortanokta ayiricilarla birlestir. */
    private CharSequence joinDim(ArrayList<String> parts) {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                int s = sb.length();
                sb.append("   ·   ");
                sb.setSpan(new android.text.style.ForegroundColorSpan(0xFF46566E), s, sb.length(), 0);
            }
            sb.append(parts.get(i));
        }
        return sb;
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
                byte[] data = readAll(uri);
                Decoder.Result r = Decoder.decode(data, Codec2.MODE_1300);
                final float[] pk = (r != null && r.pcm != null) ? Decoder.peaks(r.pcm, 420) : new float[0];
                post(() -> wave.setPeaks(pk));
            } catch (Exception ignore) {}
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
                elapsed.setText(fmt(pos / HZ));
                if (tot > 0) total.setText(fmt(tot / HZ));
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
            toast(items.size() + " dosya eklendi");

        } else if (req == REQ_FOLDER) {
            final Uri tree = data.getData();
            persist(tree);
            nowPlaying.setText("Klasör taranıyor...");
            new Thread(() -> {
                final ArrayList<Item> found = scanTree(tree);
                post(() -> { svc.addItems(found); toast(found.size() + " c2 dosyası bulundu"); refreshNowPlaying(); });
            }, "scan").start();
        }
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
                    if (it.durSec >= 0) continue;
                    try {
                        Uri u = Uri.parse(it.uri);
                        int d = Decoder.quickDuration(readHead(u, 7), querySize(u));
                        if (d >= 0) { it.durSec = d; any = true; }
                    } catch (Exception ignore) {}
                    if (any && (i % 6 == 0)) post(() -> { adapter.notifyDataSetChanged(); updateListHeader(); });
                }
                post(() -> { adapter.notifyDataSetChanged(); updateListHeader(); });
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
                row.setOrientation(LinearLayout.VERTICAL);
                int pad = dp(12);
                row.setPadding(pad, dp(8), pad, dp(8));
                TextView t1 = new TextView(MainActivity.this); t1.setId(android.R.id.text1);
                t1.setTextSize(15); t1.setSingleLine(true);
                TextView t2 = new TextView(MainActivity.this); t2.setId(android.R.id.text2);
                t2.setTextSize(12); t2.setTextColor(0xFF8597AD);
                row.addView(t1); row.addView(t2);
            }
            ArrayList<Item> pl = svc.getPlaylist();
            Item it = pl.get(p);
            boolean cur = (p == svc.getCurrent());
            TextView t1 = (TextView) row.findViewById(android.R.id.text1);
            TextView t2 = (TextView) row.findViewById(android.R.id.text2);
            String nm = (it.name == null || it.name.isEmpty()) ? "(adsız)" : it.name;
            t1.setText((cur ? ">  " : "") + nm);
            t1.setTextColor(cur ? 0xFF8FE3FF : 0xFFE6EEF8);
            String sub = (p + 1) + " / " + pl.size();
            if (it.mode >= 0) sub += "   |   " + modeLabel(it.mode);
            if (it.durSec >= 0) sub += "   |   " + fmt(it.durSec);
            t2.setText(sub);
            row.setBackgroundColor(cur ? 0x223A6EA5 : 0x00000000);
            return row;
        }
    }

    // ---------- gorsel ----------

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
            g.setColors(new int[]{ blend(0xFF0B1118, 0xFF132034, f), blend(0xFF161226, 0xFF0E2233, f), 0xFF0A0F16 });
        });
        va.start();
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
