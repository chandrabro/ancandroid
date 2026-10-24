import com.example.calmzone.AncCore;
import java.util.Random;

/**
 * Offline test of AncCore: simulates a room (speaker->mic path with delay) and checks how much the
 * microphone-position noise drops for different noise types.
 * Run:  javac -d /tmp/out app/src/main/java/com/example/calmzone/AncCore.java tools/SimTest.java
 *       java -cp /tmp/out SimTest
 */
public class SimTest {
    static final int SR = 48000;

    interface Noise { float at(int n, Random r); }

    static double run(String name, Noise noise, int trueDelay, float[] truePath, float[] estPath, boolean verbose) {
        int total = SR * 12;
        Random r = new Random(1);
        int sDelay = trueDelay - 4;
        float[] sHat = new float[48];
        for (int i = 0; i < estPath.length; i++) sHat[4 + i] = estPath[i];
        AncCore core = new AncCore(sHat, sDelay, trueDelay, 1f / estPath[0], SR);
        core.setTarget(0f);
        float[] y = new float[total];
        double pOff = 0, pOn = 0;
        int offN = 0, onN = 0;
        for (int n = 0; n < total; n++) {
            if (n == SR * 2) core.setTarget(0.9f); // 2 s analysing, then cancel
            float d = noise.at(n, r);
            float e = d;
            for (int k = 0; k < truePath.length; k++) {
                int idx = n - trueDelay - k;
                if (idx >= 0) e += truePath[k] * y[idx];
            }
            y[n] = core.process(e);
            if (n >= SR && n < SR * 2) { pOff += e * e; offN++; }
            if (n >= SR * 10) { pOn += e * e; onN++; }
        }
        double red = 10 * Math.log10((pOff / offN) / (pOn / onN));
        System.out.printf("%-48s reduction at mic: %6.1f dB%n", name, red);
        return red;
    }

    static Noise tones(final double[] f, final double[] a, final float broad) {
        return (n, r) -> {
            double v = 0;
            for (int i = 0; i < f.length; i++) v += a[i] * Math.sin(2 * Math.PI * f[i] * n / SR);
            return (float) (v + broad * r.nextGaussian());
        };
    }

    public static void main(String[] args) {
        float[] path = {0.6f, 0.25f, -0.1f};
        int D = 720; // 15 ms loop delay
        run("hum 120/240/360 Hz (+ little hiss)", tones(new double[]{120, 240, 360}, new double[]{0.05, 0.03, 0.02}, 0.002f), D, path, path, true);
        run("fan whine 500/1000 Hz", tones(new double[]{500, 1000}, new double[]{0.04, 0.02}, 0.002f), D, path, path, true);
        run("hum, calibration 15% off", tones(new double[]{120, 240, 360}, new double[]{0.05, 0.03, 0.02}, 0.002f), D, path, new float[]{0.51f, 0.3f, -0.05f}, true);
        run("hum, 40 ms delay", tones(new double[]{120, 240, 360}, new double[]{0.05, 0.03, 0.02}, 0.002f), 1920, path, path, true);
        run("hum + strong broadband hiss", tones(new double[]{120, 240}, new double[]{0.03, 0.02}, 0.03f), D, path, path, true);
        run("pure white noise (should be ~0 dB)", (n, r) -> (float) (0.03 * r.nextGaussian()), D, path, path, true);
    }
}
