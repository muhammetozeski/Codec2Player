package com.codec2.player;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.Gravity;
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
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Random;

public class MainActivity extends Activity implements PlayerEngine.Listener {

    private static final int REQ_FILES = 11;
    private static final int REQ_FOLDER = 12;
    private static final int FALLBACK_MODE = Codec2.MODE_1300; // basliksiz ham dosya icin
    private static final int HZ = 8000;

    static final class Item {
        String uri, name;
        int mode = -1, durSec = -1;
    }

    private final ArrayList<Item> playlist = new ArrayList<>();
    private int current = -1;
    private boolean shuffle = false, repeat = false;
    private final Random rnd = new Random(1234);

    private PlayerEngine engine;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout root;
    private WaveformView wave;
    private GlowButton playBtn;
    private TextView nowPlaying, elapsed, total;
    private ListView list;
    private Button shuffleBtn, repeatBtn;
    private Adapter adapter;

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("c2player", MODE_PRIVATE);

        root = (LinearLayout) findViewById(R.id.root);
        wave = (WaveformView) findViewById(R.id.wave);
        playBtn = (GlowButton) findViewById(R.id.playBtn);
        nowPlaying = (TextView) findViewById(R.id.nowPlaying);
        elapsed = (TextView) findViewById(R.id.elapsed);
        total = (TextView) findViewById(R.id.total);
        list = (ListView) findViewById(R.id.list);
        shuffleBtn = (Button) findViewById(R.id.shuffle);
        repeatBtn = (Button) findViewById(R.id.repeat);

        startBackgroundAnimation();
        for (int id : new int[]{R.id.prev, R.id.next, R.id.shuffle, R.id.repeat, R.id.addFiles, R.id.addFolder})
            styleButton((Button) findViewById(id));

        engine = new PlayerEngine(this);

        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, idv) -> playIndex(pos));

        playBtn.setOnClickListener(v -> { if (current < 0 && !playlist.isEmpty()) playIndex(0); else engine.toggle(); });
        findViewById(R.id.prev).setOnClickListener(v -> playIndex(neighbor(-1)));
        findViewById(R.id.next).setOnClickListener(v -> playIndex(neighbor(+1)));
        shuffleBtn.setOnClickListener(v -> { shuffle = !shuffle; shuffleBtn.setText("Karistir: " + (shuffle ? "Acik" : "Kapali")); });
        repeatBtn.setOnClickListener(v -> { repeat = !repeat; repeatBtn.setText("Tekrar: " + (repeat ? "Acik" : "Kapali")); });
        findViewById(R.id.addFiles).setOnClickListener(v -> pickFiles());
        findViewById(R.id.addFolder).setOnClickListener(v -> pickFolder());

        wave.setSeekListener(f -> engine.seekFraction(f));

        loadPlaylist();
        adapter.notifyDataSetChanged();
    }

    // ---------------- oynatma ----------------

    private int neighbor(int dir) {
        if (playlist.isEmpty()) return -1;
        if (shuffle) return rnd.nextInt(playlist.size());
        if (current < 0) return 0;
        int n = current + dir;
        if (n < 0) n = playlist.size() - 1;
        if (n >= playlist.size()) n = 0;
        return n;
    }

    private void playIndex(final int idx) {
        if (idx < 0 || idx >= playlist.size()) return;
        current = idx;
        final Item it = playlist.get(idx);
        nowPlaying.setText("Cozuluyor... " + it.name);
        adapter.notifyDataSetChanged();

        new Thread(() -> {
            byte[] bytes;
            try { bytes = readAll(Uri.parse(it.uri)); }
            catch (Exception e) { post(() -> nowPlaying.setText("Okunamadi: " + it.name)); return; }
            final Decoder.Result r = Decoder.decode(bytes, FALLBACK_MODE);
            if (r == null || r.pcm == null) { post(() -> nowPlaying.setText("Cozulemedi: " + it.name)); return; }
            final float[] pk = Decoder.peaks(r.pcm, 420);
            it.mode = r.mode;
            it.durSec = r.pcm.length / HZ;
            post(() -> {
                engine.setTrack(r.pcm);
                wave.setPeaks(pk);
                nowPlaying.setText(it.name + "   |   " + modeLabel(r.mode) + "   |   " + fmt(it.durSec));
                total.setText(fmt(it.durSec));
                adapter.notifyDataSetChanged();
                engine.play();
            });
        }, "decode").start();
    }

    @Override public void onProgress(int pos, int tot) { /* UI ticker poluyor */ }
    @Override public void onStateChanged(boolean playing) { post(() -> playBtn.setPlaying(playing)); }
    @Override public void onCompleted() {
        post(() -> {
            if (repeat) { engine.seekFraction(0f); engine.play(); }
            else playIndex(neighbor(+1));
        });
    }

    // ---------------- UI ticker ----------------

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            int pos = engine.positionSamples(), tot = engine.totalSamples();
            wave.setProgress(tot > 0 ? pos / (float) tot : 0f);
            wave.setLevel(engine.level() / 32768f);
            wave.invalidate();
            elapsed.setText(fmt(pos / HZ));
            if (tot > 0) total.setText(fmt(tot / HZ));
            playBtn.setPlaying(engine.isPlaying());
            ui.postDelayed(this, 40);
        }
    };

    @Override protected void onResume() { super.onResume(); ui.post(ticker); }
    @Override protected void onPause() { super.onPause(); ui.removeCallbacks(ticker); }

    // ---------------- dosya/klasor ekleme ----------------

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
        if (res != RESULT_OK || data == null) return;

        if (req == REQ_FILES) {
            ArrayList<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null) {
                for (int k = 0; k < data.getClipData().getItemCount(); k++)
                    uris.add(data.getClipData().getItemAt(k).getUri());
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            int added = 0;
            for (Uri u : uris) {
                persist(u);
                Item it = new Item(); it.uri = u.toString(); it.name = queryName(u);
                playlist.add(it); added++;
            }
            savePlaylist();
            adapter.notifyDataSetChanged();
            toast(added + " dosya eklendi");

        } else if (req == REQ_FOLDER) {
            final Uri tree = data.getData();
            persist(tree);
            nowPlaying.setText("Klasor taraniyor...");
            new Thread(() -> {
                final List<Item> found = scanTree(tree);
                post(() -> {
                    playlist.addAll(found);
                    savePlaylist();
                    adapter.notifyDataSetChanged();
                    toast(found.size() + " c2 dosyasi bulundu");
                    nowPlaying.setText(found.isEmpty() ? "Klasorde c2 yok" : "Hazir");
                });
            }, "scan").start();
        }
    }

    private List<Item> scanTree(Uri tree) {
        List<Item> out = new ArrayList<>();
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
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                        stack.push(id);
                    } else if (name != null && name.toLowerCase().endsWith(".c2")) {
                        Uri du = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                        Item it = new Item(); it.uri = du.toString(); it.name = name;
                        out.add(it);
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
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null) return n;
            }
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

    // ---------------- kalicilik ----------------

    private void savePlaylist() {
        StringBuilder sb = new StringBuilder();
        for (Item it : playlist) sb.append(it.uri).append('\t').append(it.name).append('\n');
        prefs.edit().putString("list", sb.toString()).apply();
    }

    private void loadPlaylist() {
        String s = prefs.getString("list", "");
        if (s.isEmpty()) return;
        for (String line : s.split("\n")) {
            if (line.isEmpty()) continue;
            int t = line.indexOf('\t');
            Item it = new Item();
            if (t > 0) { it.uri = line.substring(0, t); it.name = line.substring(t + 1); }
            else { it.uri = line; it.name = line; }
            playlist.add(it);
        }
    }

    // ---------------- liste adaptoru ----------------

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return playlist.size(); }
        @Override public Object getItem(int p) { return playlist.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int p, View cv, ViewGroup parent) {
            LinearLayout row;
            if (cv instanceof LinearLayout) row = (LinearLayout) cv;
            else {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                int pad = dp(12);
                row.setPadding(pad, dp(10), pad, dp(10));
                TextView t1 = new TextView(MainActivity.this);
                t1.setId(android.R.id.text1);
                t1.setTextSize(15);
                t1.setSingleLine(true);
                TextView t2 = new TextView(MainActivity.this);
                t2.setId(android.R.id.text2);
                t2.setTextSize(12);
                t2.setTextColor(0xFF8597AD);
                row.addView(t1);
                row.addView(t2);
            }
            Item it = playlist.get(p);
            TextView t1 = (TextView) row.findViewById(android.R.id.text1);
            TextView t2 = (TextView) row.findViewById(android.R.id.text2);
            boolean cur = (p == current);
            t1.setText((cur ? ">  " : "") + it.name);
            t1.setTextColor(cur ? 0xFF8FE3FF : 0xFFE6EEF8);
            String sub = (p + 1) + " / " + playlist.size();
            if (it.mode >= 0) sub += "   |   " + modeLabel(it.mode);
            if (it.durSec >= 0) sub += "   |   " + fmt(it.durSec);
            t2.setText(sub);
            row.setBackgroundColor(cur ? 0x223A6EA5 : 0x00000000);
            return row;
        }
    }

    // ---------------- gorsel ----------------

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
            int c1 = blend(0xFF0B1118, 0xFF132034, f);
            int c2 = blend(0xFF161226, 0xFF0E2233, f);
            g.setColors(new int[]{c1, c2, 0xFF0A0F16});
        });
        va.start();
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
        int r = (int) (ar + (br - ar) * t), gg = (int) (ag + (bg - ag) * t), bl = (int) (ab + (bb - ab) * t);
        return 0xFF000000 | (r << 16) | (gg << 8) | bl;
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

    @Override
    protected void onStop() {
        super.onStop();
        del(getCacheDir()); del(getCodeCacheDir());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (engine != null) engine.release();
    }

    private static void del(java.io.File f) {
        if (f == null) return;
        java.io.File[] k = f.listFiles();
        if (k != null) for (java.io.File c : k) del(c);
        f.delete();
    }
}
