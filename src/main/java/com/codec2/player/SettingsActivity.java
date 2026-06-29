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
    private Button shuffleBtn, repeatBtn, speedBtn, sleepBtn, gainBtn;

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
        gainBtn = (Button) findViewById(R.id.gain);
        for (int id : new int[]{R.id.shuffle, R.id.repeat, R.id.speed, R.id.sleep, R.id.gain, R.id.back})
            styleButton((Button) findViewById(id));

        shuffleBtn.setOnClickListener(v -> { if (svc != null) { svc.setShuffle(!svc.isShuffle()); refresh(); } });
        repeatBtn.setOnClickListener(v -> { if (svc != null) { svc.cycleRepeat(); refresh(); } });
        speedBtn.setOnClickListener(v -> { if (svc != null) { svc.cycleSpeed(); refresh(); } });
        sleepBtn.setOnClickListener(v -> { if (svc != null) { svc.cycleSleep(); refresh(); } });
        gainBtn.setOnClickListener(v -> { if (svc != null) { svc.cycleGain(); refresh(); } });
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
        int mb = svc.getGainMb();
        gainBtn.setText("Ses yükselt: " + (mb == 0 ? "Kapalı" : "+" + (mb / 100) + " dB"));
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
