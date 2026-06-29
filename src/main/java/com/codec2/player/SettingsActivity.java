package com.codec2.player;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

/** Ayarlar sayfasi: karistir / tekrar / hiz / uyku. Servise baglanir. */
public class SettingsActivity extends Activity {

    private PlaybackService svc;
    private boolean bound = false;
    private Button shuffleBtn, repeatBtn, speedBtn, sleepBtn;
    private android.widget.EditText gainVal;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            svc = ((PlaybackService.LocalBinder) b).get();
            bound = true;
            refresh();
        }
        @Override public void onServiceDisconnected(ComponentName n) { bound = false; svc = null; }
    };

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_settings);

        shuffleBtn = (Button) findViewById(R.id.shuffle);
        repeatBtn = (Button) findViewById(R.id.repeat);
        speedBtn = (Button) findViewById(R.id.speed);
        sleepBtn = (Button) findViewById(R.id.sleep);
        gainVal = (android.widget.EditText) findViewById(R.id.gainVal);
        for (int id : new int[]{R.id.shuffle, R.id.repeat, R.id.speed, R.id.sleep, R.id.log, R.id.back,
                R.id.gainM1, R.id.gainM5, R.id.gainP5, R.id.gainP1})
            styleButton((Button) findViewById(id));
        findViewById(R.id.log).setOnClickListener(v -> startActivity(new Intent(this, LogActivity.class)));

        shuffleBtn.setOnClickListener(v -> { if (svc != null) { svc.setShuffle(!svc.isShuffle()); refresh(); } });
        repeatBtn.setOnClickListener(v -> { if (svc != null) { svc.cycleRepeat(); refresh(); } });
        speedBtn.setOnClickListener(v -> { if (svc != null) { svc.cycleSpeed(); refresh(); } });
        sleepBtn.setOnClickListener(v -> { if (svc != null) { svc.cycleSleep(); refresh(); } });
        findViewById(R.id.gainM1).setOnClickListener(v -> nudgeGain(-1));
        findViewById(R.id.gainM5).setOnClickListener(v -> nudgeGain(-5));
        findViewById(R.id.gainP5).setOnClickListener(v -> nudgeGain(5));
        findViewById(R.id.gainP1).setOnClickListener(v -> nudgeGain(1));
        gainVal.setOnEditorActionListener((tv, a, e) -> { applyGain(readGain()); return false; });
        gainVal.setOnFocusChangeListener((vv, h) -> { if (!h) applyGain(readGain()); });
        findViewById(R.id.back).setOnClickListener(v -> finish());

        TextView info = (TextView) findViewById(R.id.info);
        info.setText("Codec2 Oynatıcı  ·  sürüm 1.0\n"
                + "Saf Android, kütüphane yok\n"
                + "Tek ABI (armeabi-v7a), kurulu boyut ~285 KB");
    }

    @Override protected void onStart() {
        super.onStart();
        bindService(new Intent(this, PlaybackService.class), conn, Context.BIND_AUTO_CREATE);
    }

    @Override protected void onStop() {
        super.onStop();
        if (bound) { unbindService(conn); bound = false; }
    }

    private void refresh() {
        if (svc == null) return;
        shuffleBtn.setText("Karıştır: " + (svc.isShuffle() ? "Açık" : "Kapalı"));
        String[] rl = {"Kapalı", "Tümü", "Tekli"};
        repeatBtn.setText("Tekrar: " + rl[svc.getRepeatMode()]);
        speedBtn.setText("Hız: " + String.valueOf(svc.getSpeed()) + "x");
        int sm = svc.getSleepMin();
        sleepBtn.setText("Uyku: " + (sm == 0 ? "Kapalı" : sm + " dk"));
        if (!gainVal.hasFocus()) gainVal.setText(fmtGain(svc.getGainDb()));
    }

    private float readGain() {
        try { return Float.parseFloat(gainVal.getText().toString().trim().replace(',', '.')); }
        catch (Exception e) { return (svc != null) ? svc.getGainDb() : 0f; }
    }

    private void applyGain(float db) {
        if (svc != null) svc.setGainDb(db);
        gainVal.setText(fmtGain(db));
        gainVal.clearFocus();
    }

    private void nudgeGain(float d) { applyGain(readGain() + d); }

    private static String fmtGain(float d) {
        if (d == Math.rint(d)) return String.valueOf((int) d);
        return String.format(java.util.Locale.US, "%.1f", d);
    }

    private void styleButton(Button b) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(14));
        g.setColor(0x14FFFFFF);
        g.setStroke(dp(1), 0x33A8C8FF);
        int m = dp(5);
        if (b.getLayoutParams() instanceof android.widget.LinearLayout.LayoutParams) {
            android.widget.LinearLayout.LayoutParams lp = (android.widget.LinearLayout.LayoutParams) b.getLayoutParams();
            lp.setMargins(0, m, 0, m);
            b.setLayoutParams(lp);
        }
        b.setBackground(g);
        b.setTextColor(0xFFD6E6FB);
        b.setAllCaps(false);
        b.setPadding(dp(16), dp(12), dp(16), dp(12));
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
