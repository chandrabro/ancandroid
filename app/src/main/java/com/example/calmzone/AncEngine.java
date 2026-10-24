package com.example.calmzone;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Process;

import java.util.Random;

/**
 * Owns the microphone + speaker streams and the real-time loop.
 * Flow: [calibrate once] -> analyse surrounding noise (silent, 1 s) -> cancel.
 */
public final class AncEngine {
    public interface Listener {
        void onStatus(String message, float levelDb, float reductionDb, boolean running);
    }

    public static final int SR = 48000;
    private static final int BLOCK = 96;              // 2 ms
    private static final int LAG_MAX = 4800;          // search up to 100 ms
    private static final int CAL_SAMPLES = SR * 3 / 2;
    private static final float CAL_LEVEL = 0.25f;
    private static final int PATH_TAPS = 48;
    private static final int PATH_PRE = 4;

    private static AncEngine instance;

    public static synchronized AncEngine get() {
        if (instance == null) instance = new AncEngine();
        return instance;
    }

    private volatile boolean running;
    private volatile boolean stopRequested;
    private volatile float strength = 0.8f;
    private volatile Listener listener;
    private volatile Runnable onFinished;
    private volatile String lastMessage = "Off";
    private volatile float lastLevel = -120f, lastReduction = 0f;

    private boolean thenCancel, forceCal;
    private Context ctx;

    // calibration result
    private boolean calibrated;
    private float[] sHat;
    private int sDelay, horizon;
    private float gainComp;

    private AncEngine() {}

    public boolean isRunning() { return running; }
    public boolean isCalibrated() { return calibrated; }
    public String lastMessage() { return lastMessage; }
    public float lastLevel() { return lastLevel; }
    public float lastReduction() { return lastReduction; }
    public void setListener(Listener l) { listener = l; }
    public void setOnFinished(Runnable r) { onFinished = r; }
    public void setStrength(float s) { strength = Math.max(0f, Math.min(1f, s)); }
    public void stop() { stopRequested = true; }

    public synchronized void start(Context context, boolean thenCancel, boolean forceCalibration) {
        if (running) return;
        this.ctx = context.getApplicationContext();
        this.thenCancel = thenCancel;
        this.forceCal = forceCalibration;
        this.stopRequested = false;
        this.running = true;
        if (!calibrated) loadCalibration();
        Thread t = new Thread(this::run, "anc-audio");
        t.start();
    }

    // ------------------------------------------------------------------------------------------

    private void post(String msg, float levelDb, float reductionDb) {
        lastMessage = msg;
        lastLevel = levelDb;
        lastReduction = reductionDb;
        Listener l = listener;
        if (l != null) l.onStatus(msg, levelDb, reductionDb, running);
    }

    private void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        AudioRecord rec = null;
        AudioTrack track = null;
        try {
            rec = openRecord();
            track = openTrack();
            if (rec == null || track == null) {
                post("Could not open the microphone / speaker. Is another app using it?", -120f, 0f);
                return;
            }
            rec.startRecording();
            track.write(new float[BLOCK * 4], 0, BLOCK * 4, AudioTrack.WRITE_BLOCKING);
            track.play();

            if (forceCal || !calibrated) {
                if (!calibrate(rec, track)) return;
                if (!thenCancel) {
                    post("Calibration done. Turn noise cancelling on.", -120f, 0f);
                    return;
                }
            }
            cancelLoop(rec, track);
        } catch (Throwable t) {
            post("Audio error: " + t.getMessage(), -120f, 0f);
        } finally {
            try { if (rec != null) { rec.stop(); rec.release(); } } catch (Throwable ignored) {}
            try { if (track != null) { track.stop(); track.release(); } } catch (Throwable ignored) {}
            running = false;
            Listener l = listener;
            if (l != null) l.onStatus(lastMessage, lastLevel, lastReduction, false);
            Runnable f = onFinished;
            if (f != null) f.run();
        }
    }

    private AudioRecord openRecord() {
        int[] sources = {MediaRecorder.AudioSource.UNPROCESSED,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC};
        int minBytes = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT);
        int bytes = Math.max(minBytes, BLOCK * 4 * 4);
        for (int src : sources) {
            try {
                AudioRecord r = new AudioRecord.Builder()
                        .setAudioSource(src)
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                                .setSampleRate(SR)
                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                .build())
                        .setBufferSizeInBytes(bytes)
                        .build();
                if (r.getState() == AudioRecord.STATE_INITIALIZED) return r;
                r.release();
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private AudioTrack openTrack() {
        try {
            int minBytes = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT);
            AudioTrack t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(SR)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(Math.max(minBytes, BLOCK * 4 * 2))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build();
            // The phone's own mic can only "hear" its own speaker, so always use the built-in speaker.
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                    t.setPreferredDevice(d);
                    break;
                }
            }
            return t.getState() == AudioTrack.STATE_INITIALIZED ? t : null;
        } catch (Throwable e) {
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Calibration: play white noise, measure how it arrives at the mic (delay + shape + gain).

    private boolean calibrate(AudioRecord rec, AudioTrack track) {
        post("Calibrating: playing a short hiss. Keep the room as quiet as you can...", -120f, 0f);
        float[] y = new float[CAL_SAMPLES];
        float[] e = new float[CAL_SAMPLES];
        float[] in = new float[BLOCK];
        float[] out = new float[BLOCK];
        Random rnd = new Random(7);
        int pos = 0;
        while (pos < CAL_SAMPLES) {
            if (stopRequested) return false;
            int n = Math.min(BLOCK, CAL_SAMPLES - pos);
            for (int i = 0; i < n; i++) {
                out[i] = CAL_LEVEL * (rnd.nextFloat() * 2f - 1f);
                y[pos + i] = out[i];
            }
            track.write(out, 0, n, AudioTrack.WRITE_BLOCKING);
            int r = rec.read(in, 0, n, AudioRecord.READ_BLOCKING);
            if (r != n) {
                post("Microphone read failed during calibration.", -120f, 0f);
                return false;
            }
            System.arraycopy(in, 0, e, pos, n);
            pos += n;
        }
        // remove DC from mic
        double mean = 0;
        for (float v : e) mean += v;
        mean /= e.length;
        for (int i = 0; i < e.length; i++) e[i] -= (float) mean;

        double energy = 0;
        for (float v : y) energy += (double) v * v;

        int usable = CAL_SAMPLES - LAG_MAX;
        float[] c = new float[LAG_MAX];
        for (int k = 0; k < LAG_MAX; k++) {
            if ((k & 255) == 0 && stopRequested) return false;
            double acc = 0;
            for (int n = 0; n < usable; n++) acc += (double) y[n] * e[n + k];
            c[k] = (float) (acc / (energy * usable / CAL_SAMPLES));
        }
        int peak = 0;
        for (int k = 1; k < LAG_MAX; k++) if (Math.abs(c[k]) > Math.abs(c[peak])) peak = k;

        // noise floor of the correlation, away from the peak
        double fl = 0;
        int cnt = 0;
        for (int k = 0; k < LAG_MAX; k++) {
            if (Math.abs(k - peak) > 300) { fl += (double) c[k] * c[k]; cnt++; }
        }
        double floor = Math.sqrt(fl / Math.max(1, cnt));
        float pk = c[peak];

        if (Math.abs(pk) < 5 * floor || Math.abs(pk) < 0.002f) {
            post("Calibration failed: the mic did not hear the speaker. Raise the media volume, "
                    + "move to a quieter spot, and keep the phone's mic and speaker uncovered.", -120f, 0f);
            return false;
        }
        if (peak < 8 || peak > LAG_MAX - 600) {
            post("Calibration failed: unusual speaker-to-mic delay (" + (peak * 1000 / SR) + " ms).", -120f, 0f);
            return false;
        }

        sDelay = peak - PATH_PRE;
        horizon = peak;
        sHat = new float[PATH_TAPS];
        for (int i = 0; i < PATH_TAPS && sDelay + i < LAG_MAX; i++) sHat[i] = c[sDelay + i];
        float g = sHat[PATH_PRE];
        float mag = Math.max(0.25f, Math.min(4f, 1f / Math.abs(g)));
        gainComp = Math.signum(g) * mag;
        calibrated = true;
        saveCalibration();
        post(String.format("Calibrated. Speaker-to-mic delay %.1f ms.", peak * 1000f / SR), -120f, 0f);
        return true;
    }

    // ------------------------------------------------------------------------------------------

    private void cancelLoop(AudioRecord rec, AudioTrack track) {
        AncCore core = new AncCore(sHat, sDelay, horizon, gainComp, SR);
        float[] in = new float[BLOCK];
        float[] out = new float[BLOCK];
        final int analyseBlocks = SR / BLOCK;      // 1 s
        int blocks = 0;
        boolean analysing = true;
        double baseAcc = 0;
        int baseCnt = 0;
        float basePower = 0f;
        int sinceCancel = 0;
        int tick = 0;
        float latencyMs = horizon * 1000f / SR;

        post("Analysing the surrounding noise...", -120f, 0f);
        while (!stopRequested) {
            int r = rec.read(in, 0, BLOCK, AudioRecord.READ_BLOCKING);
            if (r != BLOCK) { post("Microphone stopped delivering audio.", -120f, 0f); break; }

            core.setTarget(analysing ? 0f : strength);
            for (int i = 0; i < BLOCK; i++) {
                out[i] = core.process(in[i]);
                if (analysing && blocks >= analyseBlocks / 2) { baseAcc += (double) in[i] * in[i]; baseCnt++; }
            }
            track.write(out, 0, BLOCK, AudioTrack.WRITE_BLOCKING);
            blocks++;

            if (analysing) {
                if (blocks >= analyseBlocks) {
                    basePower = (float) (baseAcc / Math.max(1, baseCnt));
                    analysing = false;
                    sinceCancel = 0;
                }
            } else {
                sinceCancel++;
                // Safety: if the sound at the mic gets much louder than during analysis, stop and re-learn.
                if (sinceCancel > SR / BLOCK / 2 && core.residualPower() > 3f * basePower + 1e-9f) {
                    core.reset();
                    analysing = true;
                    blocks = 0;
                    baseAcc = 0;
                    baseCnt = 0;
                    post("Noise changed. Re-analysing...", lastLevel, 0f);
                }
            }

            if (++tick % 50 == 0) { // ~ every 100 ms
                float pe = Math.max(core.residualPower(), 1e-12f);
                float pd = Math.max(core.noisePower(), 1e-12f);
                float level = 10f * (float) Math.log10(pe);
                float red = 10f * (float) Math.log10(pd / pe);
                String msg;
                if (analysing) msg = "Analysing the surrounding noise...";
                else if (basePower < 1e-7f) msg = "It's already very quiet here.";
                else msg = String.format("Cancelling  (loop delay %.0f ms)", latencyMs);
                post(msg, level, analysing ? 0f : Math.max(-3f, red));
            }
        }
    }

    // ------------------------------------------------------------------------------------------

    private void saveCalibration() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(sDelay).append(';').append(horizon).append(';').append(gainComp).append(';');
            for (int i = 0; i < sHat.length; i++) { if (i > 0) sb.append(','); sb.append(sHat[i]); }
            ctx.getSharedPreferences("anc", Context.MODE_PRIVATE).edit().putString("cal", sb.toString()).apply();
        } catch (Throwable ignored) {}
    }

    private void loadCalibration() {
        try {
            SharedPreferences p = ctx.getSharedPreferences("anc", Context.MODE_PRIVATE);
            String s = p.getString("cal", null);
            if (s == null) return;
            String[] a = s.split(";");
            String[] t = a[3].split(",");
            float[] h = new float[t.length];
            for (int i = 0; i < t.length; i++) h[i] = Float.parseFloat(t[i]);
            sDelay = Integer.parseInt(a[0]);
            horizon = Integer.parseInt(a[1]);
            gainComp = Float.parseFloat(a[2]);
            sHat = h;
            calibrated = h.length == PATH_TAPS;
        } catch (Throwable ignored) {
            calibrated = false;
        }
    }
}
