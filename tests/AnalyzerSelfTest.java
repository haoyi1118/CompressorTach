import com.compwave.tach.RpmAnalyzer;
import com.compwave.tach.VibrationSample;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class AnalyzerSelfTest {
    static List<VibrationSample> mock(double fs, double hz, double a1, double a2, double a3, double noise) {
        List<VibrationSample> list = new ArrayList<>();
        Random random = new Random(12345);
        for (int i = 0; i < fs * 6.0; i++) {
            double t = i / fs;
            double jitterNs = i % 7 == 0 ? 200000 : 0;
            long ns = (long)(t * 1e9 + jitterNs);
            double v = a1*Math.sin(2*Math.PI*hz*t) + a2*Math.sin(2*Math.PI*2*hz*t)
                    + a3*Math.sin(2*Math.PI*3*hz*t) + random.nextGaussian()*noise;
            list.add(new VibrationSample(ns, 0.15*v + 9.81, 0.4*v, v));
        }
        return list;
    }
    static void check(String name, RpmAnalyzer.Result r, double rpm, double tol) {
        if (!r.valid || Math.abs(r.rpm-rpm)>tol) throw new AssertionError(name + ": " + r.note + ", rpm=" + r.rpm);
        System.out.printf("PASS %-21s %.2f rpm (expected %.2f) fs=%.1fHz confidence=%s %s%n",
                name, r.rpm, rpm, r.sampleRateHz, r.confidence, r.axisLabel);
    }
    public static void main(String[] args) {
        check("3000rpm 2x stronger", RpmAnalyzer.analyze(mock(400,50,0.12,0.7,0.3,0.02),1200,4800,-1),3000,40);
        check("2400rpm mixed", RpmAnalyzer.analyze(mock(250,40,0.18,0.42,0.21,0.03),900,4800,-1),2400,40);
        check("1800rpm mixed", RpmAnalyzer.analyze(mock(200,30,0.3,0.2,0.1,0.04),900,4800,2),1800,40);
        check("4500rpm only 1x available", RpmAnalyzer.analyze(mock(200,75,0.5,0.3,0.3,0.01),3000,4800,2),4500,40);
        RpmAnalyzer.Result low = RpmAnalyzer.analyze(mock(120,45,0.1,0.8,0.0,0.04),900,4800,-1);
        if (!low.confidence.equals("较低")) throw new AssertionError("Low sample alias was not flagged: " + low.confidence);
        RpmAnalyzer.Result aliased = RpmAnalyzer.analyze(mock(200,75,0.5,0.3,0.3,0.01),1200,4800,-1);
        if (aliased.valid && !aliased.confidence.equals("较低"))
            throw new AssertionError("Harmonic alias was incorrectly presented as reliable: " + aliased.confidence);
        System.out.printf("Alias ambiguity flagged: %.1f rpm, confidence=%s%n", aliased.rpm, aliased.confidence);
        System.out.printf("Low sampling example: valid=%s rpm=%s warning=%s%n",low.valid,low.rpm,low.note);
    }
}
