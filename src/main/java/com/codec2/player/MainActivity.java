package com.codec2.player;

import android.app.Activity;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

public class MainActivity extends Activity {

    private static final int REQ_PICK = 1;
    private static final int HZ = 8000; // tum codec2 ses modlari 8 kHz mono

    // Spinner sirasi -> codec2 mod degeri
    private static final String[] MODE_LABELS =
            {"3200", "2400", "1600", "1400", "1300", "1200", "700C", "450"};
    private static final int[] MODE_CODES =
            {0, 1, 2, 3, 4, 5, 8, 10};

    private Spinner modeSpinner;
    private TextView status;

    private volatile boolean playing = false;
    private Thread worker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        modeSpinner = (Spinner) findViewById(R.id.mode);
        status = (TextView) findViewById(R.id.status);

        ArrayAdapter<String> ad = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, MODE_LABELS);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modeSpinner.setAdapter(ad);
        modeSpinner.setSelection(4); // varsayilan 1300

        ((Button) findViewById(R.id.pick)).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickFile(); }
        });
        ((Button) findViewById(R.id.stop)).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { stopPlayback(); setStatus("Durduruldu"); }
        });
    }

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_PICK);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        setStatus("Okunuyor...");
        new Thread(new Runnable() {
            @Override public void run() {
                byte[] bytes;
                try {
                    bytes = readAll(uri);
                } catch (Exception e) {
                    setStatus("Dosya okunamadi: " + e.getMessage());
                    return;
                }
                play(bytes);
            }
        }).start();
    }

    private byte[] readAll(Uri uri) throws Exception {
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) throw new Exception("akis yok");
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private void play(byte[] data) {
        stopPlayback(); // varsa oncekini durdur

        int offset = 0;
        int mode = MODE_CODES[modeSpinner.getSelectedItemPosition()];
        String src = "secilen mod";
        int hm = Codec2.headerMode(data);
        if (hm >= 0) {
            mode = hm;
            offset = Codec2.HEADER_SIZE;
            src = "dosya basligi";
        }

        final long c2 = Codec2.create(mode);
        if (c2 == 0) { setStatus("Gecersiz mod: " + mode); return; }

        final int spf = Codec2.samplesPerFrame(c2);
        final int bpf = Codec2.bytesPerFrame(c2);
        if (spf <= 0 || bpf <= 0) { Codec2.destroy(c2); setStatus("codec2 baslatilamadi"); return; }

        final int dataLen = data.length;
        final int startOff = offset;
        final int frames = (dataLen - startOff) / bpf;
        final int seconds = frames * spf / HZ;
        final String label = labelFor(mode);
        setStatus("Mod " + label + " (" + src + ") | " + frames + " kare | ~" + seconds + " sn");

        final byte[] src2 = data;
        playing = true;
        worker = new Thread(new Runnable() {
            @Override public void run() {
                int min = AudioTrack.getMinBufferSize(HZ,
                        AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                int bufSize = Math.max(min, spf * 2 * 4);
                AudioTrack track = new AudioTrack(AudioManager.STREAM_MUSIC, HZ,
                        AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                        bufSize, AudioTrack.MODE_STREAM);
                track.play();
                byte[] bits = new byte[bpf];
                short[] out = new short[spf];
                int pos = startOff;
                try {
                    while (playing && pos + bpf <= dataLen) {
                        System.arraycopy(src2, pos, bits, 0, bpf);
                        Codec2.decode(c2, bits, out);
                        track.write(out, 0, spf);
                        pos += bpf;
                    }
                } catch (Throwable t) {
                    setStatus("Oynatma hatasi: " + t.getMessage());
                } finally {
                    try { track.stop(); } catch (Throwable ignore) {}
                    track.release();
                    Codec2.destroy(c2);
                    if (playing) setStatus("Bitti | Mod " + label + " | ~" + seconds + " sn");
                    playing = false;
                }
            }
        });
        worker.start();
    }

    private void stopPlayback() {
        playing = false;
        Thread t = worker;
        if (t != null) {
            try { t.join(500); } catch (InterruptedException ignore) {}
        }
        worker = null;
    }

    private static String labelFor(int mode) {
        for (int i = 0; i < MODE_CODES.length; i++)
            if (MODE_CODES[i] == mode) return MODE_LABELS[i];
        return String.valueOf(mode);
    }

    private void setStatus(final String s) {
        runOnUiThread(new Runnable() {
            @Override public void run() { status.setText(s); }
        });
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopPlayback();
        del(getCacheDir());
        del(getCodeCacheDir());
    }

    private static void del(java.io.File f) {
        if (f == null) return;
        java.io.File[] kids = f.listFiles();
        if (kids != null) for (java.io.File k : kids) del(k);
        f.delete();
    }
}
