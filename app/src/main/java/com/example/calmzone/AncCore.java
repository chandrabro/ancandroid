package com.example.calmzone;

/**
 * Single-microphone, predictive feedback active noise control (pure Java, no Android classes).
 *
 * How it works (per audio sample):
 *  1. The mic hears  e(n) = d(n) + (anti-noise we played, after travelling speaker -> air -> mic).
 *  2. Using the calibrated speaker->mic path "sHat" we subtract our own anti-noise from e(n),
 *     leaving an estimate of the ORIGINAL ambient noise  d(n).
 *  3. An adaptive NLMS filter learns to predict d(n) from its own recent past, H samples ahead
 *     (H = the speaker->mic delay). Steady / periodic noise (hum, fans, engines) is predictable.
 *  4. We play the NEGATIVE of that prediction, so that it arrives at the mic exactly as the real
 *     noise does and the two cancel.
 *
 * Limits (physics, not bugs): unpredictable noise (speech, clatter) cannot be predicted H samples
 * ahead, so it is not cancelled; and only the area around the microphone gets quieter.
 */
public final class AncCore {
    public static final int PRED_TAPS = 64;
    private static final int RING = 8192;
    private static final int MASK = RING - 1;

    private final int sDelay;
    private final int horizon;
    private final float[] s;
    private final float gainComp;
    private final float[] w = new float[PRED_TAPS];
    private final float[] yRing = new float[RING]; // what we played
    private final float[] dRing = new float[RING]; // estimated ambient noise
    private final float mu;
    private final float leak;
    private final float limit;
    private final float rampStep;
    private final float alpha;

    private int n = 0;
    private float gain = 0f;
    private float target = 0f;
    private float pe = 0f; // smoothed power of the mic signal (residual)
    private float pd = 0f; // smoothed power of the estimated ambient noise

    /**
     * @param sHat      speaker->mic impulse response taps, starting at lag sDelay
     * @param sDelay    lag (samples) of sHat[0]
     * @param horizon   lag (samples) of the main peak of the speaker->mic path
     * @param gainComp  1 / (gain at the peak); also carries polarity
     */
    public AncCore(float[] sHat, int sDelay, int horizon, float gainComp, int sampleRate) {
        if (horizon < 1 || sDelay < 0 || sDelay + sHat.length >= RING || horizon + PRED_TAPS >= RING) {
            throw new IllegalArgumentException("path too long or invalid");
        }
        this.s = sHat.clone();
        this.sDelay = sDelay;
        this.horizon = horizon;
        this.gainComp = gainComp;
        this.mu = 0.02f;
        this.leak = 2e-6f;
        this.limit = 0.35f;                         // never drive the speaker harder than this
        this.rampStep = 1f / (0.25f * sampleRate);  // 0.25 s fade in / out
        this.alpha = 1f / (0.2f * sampleRate);      // 0.2 s power smoothing
    }

    /** 0 = observe only (learn, but stay silent); 0..1 = cancellation strength. */
    public void setTarget(float t) {
        target = Math.max(0f, Math.min(1f, t));
    }

    public float residualPower() { return pe; }
    public float noisePower() { return pd; }
    public float currentGain() { return gain; }

    public void reset() {
        java.util.Arrays.fill(w, 0f);
        java.util.Arrays.fill(yRing, 0f);
        java.util.Arrays.fill(dRing, 0f);
        gain = 0f;
        pe = 0f;
        pd = 0f;
    }

    /** Feed one mic sample, get the next speaker sample. */
    public float process(float e) {
        // 1-2: remove our own anti-noise from what the mic heard
        float sh = 0f;
        int base = n - sDelay;
        for (int k = 0; k < s.length; k++) {
            sh += s[k] * yRing[(base - k) & MASK];
        }
        float d = e - sh;
        dRing[n & MASK] = d;

        // 3: NLMS - predict d(n) from d(n-H) ... d(n-H-N+1) and learn from the error
        float pred = 0f;
        float en = 1e-6f;
        int b = n - horizon;
        for (int i = 0; i < PRED_TAPS; i++) {
            float u = dRing[(b - i) & MASK];
            pred += w[i] * u;
            en += u * u;
        }
        float g = mu * (d - pred) / en;
        float keep = 1f - leak;
        for (int i = 0; i < PRED_TAPS; i++) {
            w[i] = w[i] * keep + g * dRing[(b - i) & MASK];
        }

        // 4: predict d(n+H) from d(n) ... d(n-N+1) and play the negative
        float p = 0f;
        for (int i = 0; i < PRED_TAPS; i++) {
            p += w[i] * dRing[(n - i) & MASK];
        }

        if (gain < target) gain = Math.min(target, gain + rampStep);
        else gain = Math.max(target, gain - rampStep);

        float out = -gain * gainComp * p;
        if (!(Math.abs(out) < 100f)) { // NaN / blow-up guard
            reset();
            out = 0f;
        }
        if (out > limit) out = limit;
        if (out < -limit) out = -limit;
        yRing[n & MASK] = out;

        pe += alpha * (e * e - pe);
        pd += alpha * (d * d - pd);
        n++;
        return out;
    }
}
