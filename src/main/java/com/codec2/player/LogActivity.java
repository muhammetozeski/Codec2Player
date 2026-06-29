package com.codec2.player;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

/** Canli gunluk/konsol ekrani. Log2'yi dinler, otomatik alta kaydirir. */
public class LogActivity extends Activity implements Log2.Listener {

    private TextView text;
    private ScrollView scroll;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_log);
        text = (TextView) findViewById(R.id.text);
        scroll = (ScrollView) findViewById(R.id.scroll);
        ((Button) findViewById(R.id.clear)).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { Log2.clear(); }
        });
        ((Button) findViewById(R.id.back)).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log2.setListener(this);
        onLog(Log2.all());
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log2.setListener(null);
    }

    @Override
    public void onLog(String full) {
        text.setText(full.isEmpty() ? getString(R.string.log_empty) : full);
        scroll.post(new Runnable() {
            @Override public void run() { scroll.fullScroll(View.FOCUS_DOWN); }
        });
    }
}
