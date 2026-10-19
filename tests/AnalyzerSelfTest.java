import com.compwave.tach.RpmAnalyzer;
import com.compwave.tach.VibrationSample;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Deterministic tests for maximum-peak-first RPM interpretation. */
public final class AnalyzerSelfTest {
    static List<VibrationSample> mock(double fs, double hz, double a1, double a2, double a3, double noise) {
        List<VibrationSample> list = new ArrayList<>();
        Random random = new Random(12345);
        for (int i = 0; i < fs * 6.0; i++) {
            double t = i / fs;
            long ns = (long)(t * 1e9 + (i % 7 == 0 ? 200000 : 0));
            double v = a1 * Math.sin(2 * Math.PI * hz * t)
                    + a2 * Math.sin(2 * Math.PI * 2 * hz * t)
                    + a3 * Math.sin(2 * Math.PI * 3 * hz * t)
                    + random.nextGaussian() * noise;
            list.add(new VibrationSample(ns, 0.15 * v + 9.81, 0.4 * v, v));
        }
        return list;
    }

    static void close(String title, double actual, double expected, double tolerance) {
        if (!Double.isFinite(actual) || Math.abs(actual - expected) > tolerance)
            throw new AssertionError(title + ": expected=" + expected + ", actual=" + actual);
    }

    static void primary(String name, RpmAnalyzer.Result r, double peakHz, double rpm,
                        int order, int expectedPossibilities) {
        if (!r.valid) throw new AssertionError(name + " rejected: " + r.note);
        close(name + " peak", r.dominantPeakHz, peakHz, 0.6);
        close(name + " primary rpm", r.rpm, rpm, 40);
        if (r.primaryOrder != order)
            throw new AssertionError(name + ": primary harmonic order=" + r.primaryOrder);
        if (r.possibleRpm.length != expectedPossibilities)
            throw new AssertionError(name + ": interpretations=" + r.possibleRpm.length);
        close(name + " physical bin resolution", r.resolutionHz, 0.2, 0.02);
        System.out.printf("PASS %-30s peak=%5.1f Hz primary=%7.1f RPM (%dx) candidates=%d fs=%6.1f Hz confidence=%s%n",
                name, r.dominantPeakHz, r.rpm, r.primaryOrder,
                r.possibleRpm.length, r.sampleRateHz, r.confidence);
    }

    public static void main(String[] args) {
        // Most powerful 2x peak is ABOVE the configured 4800-RPM 1x range.
        RpmAnalyzer.Result r1 = RpmAnalyzer.analyze(mock(400,50,0.12,0.7,0.3,0.02),1200,4800,-1);
        primary("3000RPM, strongest 2x above range", r1, 100, 3000, 2, 2);
        close("3000 alternate", r1.possibleRpm[1], 2000, 40);

        // Under max-peak-first, 80 Hz wins over the true (but weaker) 40-Hz fundamental.
        RpmAnalyzer.Result r2 = RpmAnalyzer.analyze(mock(250,40,0.18,0.42,0.21,0.03),900,4800,-1);
        primary("2400RPM physical, 2x chosen as 1x", r2, 80, 4800, 1, 3);
        close("2400 alternate", r2.possibleRpm[1], 2400, 40);
        close("1600 alternate", r2.possibleRpm[2], 1600, 40);
        if (r2.confidence.equals("较高")) throw new AssertionError("Ambiguous peak given high confidence");

        RpmAnalyzer.Result r3 = RpmAnalyzer.analyze(mock(200,30,0.3,0.2,0.1,0.04),900,4800,2);
        primary("1800RPM 1x strongest", r3, 30, 1800, 1, 2);

        RpmAnalyzer.Result r4 = RpmAnalyzer.analyze(mock(200,75,0.5,0.3,0.3,0.01),3000,4800,2);
        primary("4500RPM narrow range", r4, 75, 4500, 1, 1);

        RpmAnalyzer.Result low = RpmAnalyzer.analyze(mock(120,45,0.1,0.8,0.0,0.04),900,4800,-1);
        if (low.valid && !low.confidence.equals("较低"))
            throw new AssertionError("Aliasing risk was not reflected in confidence: " + low.confidence);
        System.out.printf("PASS low-sample-rate warning: valid=%s sampling=%.1f%n",low.valid,low.sampleRateHz);
        // Bad timestamp or incomplete acquisition should fail safely.
        RpmAnalyzer.Result tooShort = RpmAnalyzer.analyze(mock(200,50,0.4,0,0,0.01).subList(0, 200),900,4800,-1);
        if (tooShort.valid) throw new AssertionError("Premature valid result for 1-s capture");
        System.out.println("PASS incomplete-window guard");
    }
}
