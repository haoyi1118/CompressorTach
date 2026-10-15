package com.compwave.tach;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Pure Java RPM estimator, independent of Android.
 *
 * IMPORTANT: one-axis acceleration can contain weak/missing 1x and strong 2x/3x.
 * Results are estimates, NOT a certified shaft-speed measurement. The algorithm
 * reports low confidence rather than claiming a unique RPM when evidence is weak.
 */
public final class RpmAnalyzer {
    public static final double WINDOW_SECONDS = 5.0;
    public static final int MIN_SAMPLES = 180;
    private static final double EPS = 1e-12;

    private RpmAnalyzer() {}

    public static final class Result {
        public final boolean valid;
        public final double rpm, fundamentalHz, sampleRateHz, resolutionHz;
        public final double rmsMs2, snrDb;
        public final String confidence, note, axisLabel;
        public final double[] spectrumHz, spectrumDb;
        public final int supportedHarmonics;
        public final double durationSeconds;

        private Result(boolean valid, double rpm, double fundamentalHz, double sampleRateHz,
                double resolutionHz, double rmsMs2, double snrDb, String confidence,
                String note, String axisLabel, double[] spectrumHz, double[] spectrumDb,
                int supportedHarmonics, double durationSeconds) {
            this.valid = valid;
            this.rpm = rpm;
            this.fundamentalHz = fundamentalHz;
            this.sampleRateHz = sampleRateHz;
            this.resolutionHz = resolutionHz;
            this.rmsMs2 = rmsMs2;
            this.snrDb = snrDb;
            this.confidence = confidence;
            this.note = note;
            this.axisLabel = axisLabel;
            this.spectrumHz = spectrumHz;
            this.spectrumDb = spectrumDb;
            this.supportedHarmonics = supportedHarmonics;
            this.durationSeconds = durationSeconds;
        }
    }

    private static Result invalid(String msg, double fs, double duration) {
        return new Result(false, Double.NaN, Double.NaN, fs, duration > 0 ? 1.0 / duration : 0,
                0, 0, "不足", msg, "—", new double[0], new double[0], 0, duration);
    }

    /** axis=-1 automatic selection, 0 X, 1 Y, 2 Z. */
    public static Result analyze(List<VibrationSample> data, int minRpm, int maxRpm, int axis) {
        if (data == null || data.size() < MIN_SAMPLES) {
            return invalid("正在采集数据，请保持压缩机稳定运行约 5 秒。", 0, 0);
        }
        if (minRpm < 60 || maxRpm <= minRpm || maxRpm > 30000) {
            return invalid("转速范围配置不正确。", 0, 0);
        }

        // Work from the latest five seconds only. SensorEvent.timestamp is monotonic.
        int endIndex = data.size() - 1;
        long lastNs = data.get(endIndex).timestampNs;
        long earliest = lastNs - (long)(WINDOW_SECONDS * 1e9);
        int firstIndex = endIndex;
        while (firstIndex > 0 && data.get(firstIndex - 1).timestampNs >= earliest) firstIndex--;
        int rawN = endIndex - firstIndex + 1;
        double duration = (lastNs - data.get(firstIndex).timestampNs) * 1e-9;
        if (rawN < MIN_SAMPLES || duration < 4.5) {
            return invalid("采样窗口尚未满 5 秒，请稍候。", rawN / Math.max(duration, 0.001), duration);
        }

        int nonMonotonic = 0;
        long maxGap = 0;
        for (int i = 1; i < rawN; i++) {
            long delta = data.get(firstIndex + i).timestampNs - data.get(firstIndex + i - 1).timestampNs;
            if (delta <= 0) nonMonotonic++;
            maxGap = Math.max(delta, maxGap);
        }
        if (nonMonotonic > 0) return invalid("传感器时间戳异常；请重新开始测量。", 0, duration);
        double fs = (rawN - 1) / duration;
        if (fs < 55) return invalid("实际采样率低于 55 Hz，无法可靠测量常见压缩机转速。", fs, duration);
        if (maxGap > 0.15e9) return invalid("传感器采样存在超过 150 ms 的中断，请保持手机静止。", fs, duration);

        double minHz = minRpm / 60.0;
        double maxHz = Math.min(maxRpm / 60.0, fs * 0.45);
        if (maxHz <= minHz + 0.5) {
            return invalid("手机传感器采样率不足，无法覆盖设置的转速范围。", fs, duration);
        }

        // Linearly resample onto an evenly-spaced time grid. Never assume Android
        // sensor events arrive at exact uniform intervals.
        int uniformCount = rawN;
        double[][] signal = new double[3][uniformCount];
        double firstT = data.get(firstIndex).timestampNs;
        double dtNs = (lastNs - firstT) / (uniformCount - 1.0);
        int cursor = firstIndex;
        for (int i = 0; i < uniformCount; i++) {
            double target = firstT + i * dtNs;
            while (cursor < endIndex - 1 && data.get(cursor + 1).timestampNs < target) cursor++;
            VibrationSample a = data.get(cursor);
            VibrationSample b = data.get(Math.min(cursor + 1, endIndex));
            double gap = b.timestampNs - a.timestampNs;
            double alpha = gap <= 0 ? 0 : Math.max(0, Math.min(1, (target - a.timestampNs) / gap));
            for (int c = 0; c < 3; c++) {
                signal[c][i] = a.axis(c) * (1 - alpha) + b.axis(c) * alpha;
            }
        }

        int fftN = 1;
        while (fftN < uniformCount * 2) fftN <<= 1; // Zero padding aids peak interpolation, not physical resolution.
        AxisResult best = null;
        for (int c = 0; c < 3; c++) {
            if (axis >= 0 && c != axis) continue;
            AxisResult r = analyzeAxis(c, signal[c], fftN, fs, duration, minHz, maxHz);
            if (best == null || r.score > best.score) best = r;
        }
        if (best == null || best.peak == null) {
            return invalid("未发现足够明显的周期振动峰；尝试切换 X/Y/Z 轴或改善振动耦合。", fs, duration);
        }
        Peak candidate = best.peak;
        double peakRatio = Math.max(best.peakMainAmp / best.noiseAmp, 1.0);
        double snr = 20 * Math.log10(peakRatio);
        boolean fundamentalSeen = best.amp1 > best.noiseAmp * 3.5;
        int count = 0;
        int accessible = 0;
        for (int h = 1; h <= 3; h++) {
            if (candidate.freq * h >= fs * 0.47) continue;
            accessible++;
            if (peakAmplitude(best.amplitudes, candidate.freq * h, fftN / fs, fs) > best.noiseAmp * 3.5) count++;
        }

        // Refine frequency with the strongest verified harmonic's parabolic peak,
        // but require at least some 1x evidence before making a high-confidence claim.
        double weightedHz = 0, weightTotal = 0;
        for (int h = 1; h <= 3; h++) {
            double hz = candidate.freq * h;
            if (hz >= fs * 0.47) continue;
            int k = findPeakIndex(best.amplitudes, hz * fftN / fs, 2);
            double amp = best.amplitudes[k];
            if (amp < best.noiseAmp * 3.5) continue;
            double refined = interpolatePeakFrequency(best.amplitudes, k, fs / fftN) / h;
            double weight = Math.sqrt(amp / best.noiseAmp) / h;
            weightedHz += refined * weight;
            weightTotal += weight;
        }
        double estimatedHz = weightTotal > 0 ? weightedHz / weightTotal : candidate.freq;
        double rpm = estimatedHz * 60.0;
        if (rpm < minRpm * 0.97 || rpm > maxRpm * 1.03) {
            return invalid("候选转频超出所选范围，请检查转速上下限。", fs, duration);
        }
        String confidence;
        if (snr >= 14 && count >= 2 && fundamentalSeen && accessible >= 2) confidence = "较高";
        else if (snr >= 9 && count >= 2 && fundamentalSeen) confidence = "中等";
        else confidence = "较低";

        String note = "曲轴转频为基于振动谱的估计值，尚未经转速基准校准。";
        if (count < 2) note = "仅检测到单个明显谱峰，存在将 2×/3× 倍频误判为转频的风险。";
        else if (!fundamentalSeen) note = "一阶转频不明显，无法排除倍频误判。";
        else if (accessible < 2) note = "采样率不足以观察二阶谐波，估计值需与基准转速核对。";
        if (fs * 0.45 < maxRpm / 60.0) {
            note += " 当前采样率不足以覆盖整个设定转速上限，存在混叠风险。";
            confidence = "较低";
        } else if (fs < (maxRpm / 60.0) * 2.5) {
            note += " 高频段接近奈奎斯特频率，可能发生倍频混叠；请缩小转速范围校验。";
            if (confidence.equals("较高")) confidence = "中等";
        }
        // An apparent 1x/2x/3x harmonic comb can itself be aliasing from a
        // faster shaft (example: 75 Hz sampled at 200 Hz, aliases at 25/50/75).
        // Never claim high confidence for such an ambiguous pattern.
        double firstAmp = peakAmplitude(best.amplitudes, candidate.freq, fftN / fs, fs);
        double thirdAmp = peakAmplitude(best.amplitudes, candidate.freq * 3.0, fftN / fs, fs);
        boolean foldAmbiguity = candidate.freq * 3.0 <= maxHz &&
                fs < 6.0 * maxHz && thirdAmp > firstAmp * 1.15;
        if (foldAmbiguity) {
            confidence = "较低";
            note += " 三阶谱峰强于候选一阶，可能是更高转速振动分量混叠成的假谐波组。";
        }
        if (best.rms < 0.025) {
            note += " 有效振动幅值偏小，建议改善固定和耦合。";
            if (confidence.equals("较高")) confidence = "中等";
        }

        int plotEnd = Math.min(fftN / 2 - 1, (int)Math.ceil(Math.min(fs * 0.49, maxRpm / 60.0 * 3) * fftN / fs));
        int plotStart = Math.max(1, (int)Math.floor(Math.max(2, minHz * 0.5) * fftN / fs));
        // Keep chart lightweight by decimating when needed.
        int step = Math.max(1, (plotEnd - plotStart) / 400);
        List<Double> x = new ArrayList<>(), y = new ArrayList<>();
        for (int k = plotStart; k <= plotEnd; k += step) {
            x.add(k * fs / fftN);
            y.add(20.0 * Math.log10(Math.max(best.amplitudes[k], 1e-7)));
        }
        double[] fx = new double[x.size()], sy = new double[y.size()];
        for (int i = 0; i < x.size(); i++) { fx[i] = x.get(i); sy[i] = y.get(i); }
        return new Result(true, rpm, estimatedHz, fs, 1.0 / duration,
                best.rms, snr, confidence, note, "XYZ".substring(best.axis, best.axis + 1),
                fx, sy, count, duration);
    }

    private static class Peak {
        final double freq;
        final double value;
        Peak(double freq, double value) { this.freq = freq; this.value = value; }
    }

    private static class AxisResult {
        final int axis;
        final double[] amplitudes;
        final double noiseAmp, rms, score, amp1, peakMainAmp;
        final Peak peak;
        AxisResult(int axis, double[] amps, double noiseAmp, double rms,
                double score, double amp1, double peakMainAmp, Peak peak) {
            this.axis = axis;
            this.amplitudes = amps;
            this.noiseAmp = noiseAmp;
            this.rms = rms;
            this.score = score;
            this.amp1 = amp1;
            this.peakMainAmp = peakMainAmp;
            this.peak = peak;
        }
    }

    private static AxisResult analyzeAxis(int axis, double[] values, int fftN, double fs,
            double duration, double minHz, double maxHz) {
        int n = values.length;
        double mean = 0;
        for (double v : values) mean += v;
        mean /= n;
        double rmsSum = 0, windowSum = 0;
        double[] re = new double[fftN], im = new double[fftN];
        for (int i = 0; i < n; i++) {
            double v = values[i] - mean;
            rmsSum += v * v;
            double w = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (n - 1));
            re[i] = v * w;
            windowSum += w;
        }
        fft(re, im);
        double[] amps = new double[fftN / 2 + 1];
        for (int i = 1; i < amps.length; i++) amps[i] = 2.0 * Math.hypot(re[i], im[i]) / windowSum;
        double noise = medianNoise(amps, fs / fftN, Math.max(3, minHz / 2));
        int kLow = Math.max(2, (int)Math.ceil(minHz * fftN / fs));
        int kHigh = Math.min(amps.length - 3, (int)Math.floor(maxHz * fftN / fs));
        if (kHigh <= kLow) return new AxisResult(axis, amps, noise, Math.sqrt(rmsSum/n), -1, 0, 0, null);

        List<Peak> candidates = new ArrayList<>();
        for (int k = Math.max(2, (int)(minHz * fftN / fs)); k < amps.length - 2; k++) {
            if (amps[k] < noise * 3 || amps[k] < amps[k - 1] || amps[k] <= amps[k + 1]) continue;
            double peakFreq = interpolatePeakFrequency(amps, k, fs / fftN);
            if (peakFreq >= fs * 0.47) continue;
            for (int h = 1; h <= 3; h++) {
                double base = peakFreq / h;
                if (base >= minHz && base <= maxHz) candidates.add(new Peak(base, amps[k]));
            }
        }
        Peak best = null;
        double bestScore = -1, bestAmp1 = 0, bestMain = 0;
        for (Peak p : candidates) {
            double[] weights = {1.0, 0.78, 0.48, 0.24};
            double score = 0, amp1 = 0, main = 0;
            int supports = 0;
            for (int h = 1; h <= 4; h++) {
                double freq = p.freq * h;
                if (freq >= fs * 0.47) continue;
                double amp = peakAmplitude(amps, freq, fftN / fs, fs);
                if (h == 1) amp1 = amp;
                main = Math.max(main, amp);
                double ratio = amp / noise;
                if (ratio >= 3.5) {
                    supports++;
                    score += weights[h - 1] * Math.log1p(ratio);
                }
            }
            // Reward candidates supported by multiple harmonic lines, not by a single isolated peak.
            score += Math.max(0, supports - 1) * 0.9;
            if (amp1 < noise * 3.5) score -= 0.8;
            if (score > bestScore) {
                best = p;
                bestScore = score;
                bestAmp1 = amp1;
                bestMain = main;
            }
        }
        return new AxisResult(axis, amps, noise, Math.sqrt(rmsSum / n), bestScore,
                bestAmp1, bestMain, best);
    }

    private static double peakAmplitude(double[] amps, double hz, double binsPerHz, double fs) {
        if (hz <= 0 || hz >= fs * 0.48) return 0;
        return amps[findPeakIndex(amps, hz * binsPerHz, 2)];
    }

    private static int findPeakIndex(double[] amps, double exactBin, int radius) {
        int center = (int)Math.round(exactBin);
        int start = Math.max(1, center - radius), stop = Math.min(amps.length - 2, center + radius);
        int best = start;
        for (int k = start + 1; k <= stop; k++) if (amps[k] > amps[best]) best = k;
        return best;
    }

    private static double interpolatePeakFrequency(double[] amps, int k, double binHz) {
        if (k <= 0 || k >= amps.length - 1) return k * binHz;
        double a = Math.log(Math.max(amps[k - 1], EPS));
        double b = Math.log(Math.max(amps[k], EPS));
        double c = Math.log(Math.max(amps[k + 1], EPS));
        double den = a - 2 * b + c;
        double delta = Math.abs(den) > EPS ? 0.5 * (a - c) / den : 0;
        delta = Math.max(-0.5, Math.min(0.5, delta));
        return (k + delta) * binHz;
    }

    private static double medianNoise(double[] spectrum, double binHz, double minHz) {
        int from = Math.max(2, (int)(minHz / binHz));
        int until = Math.max(from + 1, (int)(spectrum.length * 0.9));
        double[] buf = new double[until - from];
        System.arraycopy(spectrum, from, buf, 0, buf.length);
        Arrays.sort(buf);
        return Math.max(buf[buf.length / 2], 1e-7);
    }

    /** Iterative radix-2 FFT. input array length must be a power of two. */
    private static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >>> 1;
            for (; (j & bit) != 0; bit >>>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double theta = -2 * Math.PI / len;
            double wLenRe = Math.cos(theta), wLenIm = Math.sin(theta);
            for (int start = 0; start < n; start += len) {
                double wr = 1, wi = 0;
                for (int j = 0; j < len / 2; j++) {
                    int i0 = start + j, i1 = i0 + len / 2;
                    double ur = re[i0], ui = im[i0];
                    double vr = re[i1] * wr - im[i1] * wi;
                    double vi = re[i1] * wi + im[i1] * wr;
                    re[i0] = ur + vr; im[i0] = ui + vi;
                    re[i1] = ur - vr; im[i1] = ui - vi;
                    double nextWr = wr * wLenRe - wi * wLenIm;
                    wi = wr * wLenIm + wi * wLenRe;
                    wr = nextWr;
                }
            }
        }
    }
}
