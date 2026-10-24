package com.example.calmzone;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;

public class MainActivity extends Activity implements AncEngine.Listener {
    private static final int REQ_PERMS = 10;

    private Switch sw;
    private TextView status, level, reduction;
    private SeekBar strengthBar;
    private Button calBtn;
    private boolean ignoreSwitch;
    private boolean pendingCal;

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        final int bg = Color.parseColor("#0F1720");
        final int fg = Color.parseColor("#E8EEF4");
        final int dim = Color.parseColor("#93A4B5");
        final int accent = Color.parseColor("#4FD1C5");

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(bg);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(36), dp(20), dp(28));
        scroll.addView(col, new ViewGroup.LayoutParams(-1, -2));

        col.addView(text("Calm Zone", 30, fg, true));
        col.addView(text("Listens to the noise around you and plays the opposite sound, "
                + "making a quieter pocket around your phone.", 14, dim, false));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(24), 0, dp(8));
        TextView lbl = text("Noise cancelling", 20, fg, true);
        row.addView(lbl, new LinearLayout.LayoutParams(0, -2, 1f));
        sw = new Switch(this);
        row.addView(sw);
        col.addView(row);

        status = text("Off", 16, accent, false);
        col.addView(status);
        level = text("", 15, fg, false);
        col.addView(level);
        reduction = text("", 28, accent, true);
        col.addView(reduction);

        col.addView(text("Strength", 14, dim, false));
        strengthBar = new SeekBar(this);
        strengthBar.setMax(100);
        strengthBar.setProgress(80);
        col.addView(strengthBar);

        calBtn = new Button(this);
        calBtn.setText("Calibrate speaker and mic");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(16);
        col.addView(calBtn, lp);

        col.addView(text("\nHow to get the best result", 15, fg, true));
        col.addView(text("1. Put the phone on a table near you, speaker and mic uncovered.\n"
                + "2. Set media volume to about 60%, then press Calibrate once in a quiet moment "
                + "(re-calibrate if you move to another room).\n"
                + "3. Switch noise cancelling on. The big number shows how many dB quieter it is at "
                + "the phone's microphone.\n\n"
                + "Works on steady, humming sounds: fans, air conditioners, engines, appliances. "
                + "It cannot cancel voices, traffic or sudden sounds, and it only quiets the area "
                + "close to the phone. A phone is not a pair of ANC headphones.", 13, dim, false));

        setContentView(scroll);

        sw.setOnCheckedChangeListener((v, on) -> {
            if (ignoreSwitch) return;
            if (on) {
                pendingCal = false;
                if (!ensurePermissions()) return;
                AncService.start(this, true, false);
            } else {
                AncService.stop(this);
            }
        });
        calBtn.setOnClickListener(v -> {
            pendingCal = true;
            if (!ensurePermissions()) return;
            pendingCal = false;
            AncService.start(this, sw.isChecked(), true);
        });
        strengthBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) { AncEngine.get().setStrength(p / 100f); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
    }

    private boolean ensurePermissions() {
        ArrayList<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        if (need.isEmpty()) return true;
        requestPermissions(need.toArray(new String[0]), REQ_PERMS);
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code != REQ_PERMS) return;
        boolean micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        if (!micOk) {
            setSwitch(false);
            status.setText("Microphone permission is needed to hear the noise around you.");
            return;
        }
        if (pendingCal) {
            pendingCal = false;
            AncService.start(this, sw.isChecked(), true);
        } else if (sw.isChecked()) {
            AncService.start(this, true, false);
        }
    }

    private void setSwitch(boolean on) {
        ignoreSwitch = true;
        sw.setChecked(on);
        ignoreSwitch = false;
    }

    @Override
    protected void onStart() {
        super.onStart();
        AncEngine e = AncEngine.get();
        e.setListener(this);
        setSwitch(e.isRunning());
        render(e.isRunning() ? e.lastMessage() : "Off", e.lastLevel(), e.lastReduction(), e.isRunning());
    }

    @Override
    protected void onStop() {
        AncEngine.get().setListener(null);
        super.onStop();
    }

    @Override
    public void onStatus(String message, float levelDb, float reductionDb, boolean running) {
        runOnUiThread(() -> render(message, levelDb, reductionDb, running));
    }

    private void render(String message, float levelDb, float reductionDb, boolean running) {
        status.setText(message);
        if (running && levelDb > -119f) {
            level.setText(String.format("Sound at the mic: %.0f dBFS", levelDb));
        } else {
            level.setText("");
        }
        if (running && message.startsWith("Cancelling")) {
            reduction.setText(String.format("%.1f dB quieter", Math.max(0f, reductionDb)));
        } else {
            reduction.setText("");
        }
        if (!running && AncEngine.get().isRunning() == false && message.startsWith("Cancelling")) {
            status.setText("Off");
        }
        if (!running) setSwitch(false);
    }
}
