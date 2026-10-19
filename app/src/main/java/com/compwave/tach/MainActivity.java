package com.compwave.tach;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** On-device vibration RPM analysis. Does not use network, microphone or cloud. */
public final class MainActivity extends Activity {
    private static final int EXPORT_REQUEST = 2201;
    private static final int NAVY = Color.rgb(12, 24, 41);
    private static final int CARD = Color.rgb(23, 41, 62);
    private static final int WHITE = Color.rgb(235, 247, 255);
    private static final int SECONDARY = Color.rgb(167, 189, 212);
    private static final int GREEN = Color.rgb(66, 220, 176);
    private static final int AMBER = Color.rgb(255, 190, 98);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean analyzing = new AtomicBoolean(false);
    private SensorRecorder recorder;
    private boolean running;
    private int minRpm = 900, maxRpm = 4800, selectedAxis = -1;
    private TextView rpmView, confidenceView, detailView, noteView, statusView, sensorView;
    private TextView samplingView, alternativesView;
    private EditText minInput, maxInput;
    private Spinner axisSpinner;
    private Button runButton, exportButton;
    private SpectrumView chart;
    private RpmAnalyzer.Result lastResult;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (analyzing.compareAndSet(false, true)) {
                List<VibrationSample> captured = recorder.snapshot();
                final int min = minRpm, max = maxRpm, axis = selectedAxis;
                worker.execute(() -> {
                    RpmAnalyzer.Result result;
                    try { result = RpmAnalyzer.analyze(captured, min, max, axis); }
                    catch (Exception e) {
                        android.util.Log.e("CompressorTach", "Analysis error", e);
                        result = null;
                    }
                    final RpmAnalyzer.Result ready = result;
                    ui.post(() -> {
                        analyzing.set(false);
                        if (running && ready != null) render(ready);
                    });
                });
            }
            ui.postDelayed(this, 950);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(NAVY);
        getWindow().setNavigationBarColor(NAVY);
        recorder = new SensorRecorder(this);
        buildUi();
        sensorView.setText(recorder.sensorName() + "  |  标称最快约 " +
                String.format(Locale.US,"%.0f Hz",recorder.nominalMaxHz()));
        if (!recorder.available()) {
            statusView.setText("此手机没有可用加速度计");
            runButton.setEnabled(false);
        }
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(NAVY);
        LinearLayout root = vertical();
        root.setPadding(dp(18), dp(22), dp(18), dp(34));
        scroll.addView(root);
        setContentView(scroll);

        TextView eyebrow = label("COMPRESSOR  /  NVH TOOL", 11, GREEN, true);
        root.addView(eyebrow);
        TextView title = label("压缩机振动转速仪", 27, WHITE, true);
        title.setPadding(0, dp(7), 0, dp(4));
        root.addView(title);
        TextView subtitle = label("单缸往复式压缩机 · 三轴加速度 · 最大谱峰优先", 12, SECONDARY, false);
        root.addView(subtitle);

        LinearLayout rpmCard = card();
        LinearLayout.LayoutParams cardParam = margins(dp(0),dp(18));
        root.addView(rpmCard, cardParam);
        rpmCard.addView(label("估算曲轴转速", 13, SECONDARY, false));
        LinearLayout numberLine = horizontal();
        numberLine.setGravity(Gravity.BOTTOM);
        rpmCard.addView(numberLine);
        rpmView = label("—", 60, WHITE, true);
        rpmView.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        numberLine.addView(rpmView);
        TextView unit = label("RPM", 15, GREEN, true);
        unit.setPadding(dp(9), 0, 0, dp(11));
        numberLine.addView(unit);
        confidenceView = label("可信度：等待数据", 12, AMBER, false);
        confidenceView.setPadding(0, dp(6), 0, dp(10));
        rpmCard.addView(confidenceView);
        statusView = label("尚未开始测量", 13, SECONDARY, false);
        rpmCard.addView(statusView);
        samplingView = label("实际采样率：等待采集", 12, GREEN, false);
        samplingView.setPadding(0, dp(9), 0, 0);
        rpmCard.addView(samplingView);
        alternativesView = label("其他可能转速：等待频谱分析", 12, AMBER, false);
        alternativesView.setPadding(0, dp(8), 0, 0);
        rpmCard.addView(alternativesView);

        LinearLayout spectrumCard = card();
        root.addView(spectrumCard, margins(0, dp(14)));
        spectrumCard.addView(label("实时频谱", 15, WHITE, true));
        chart = new SpectrumView(this);
        spectrumCard.addView(chart, new LinearLayout.LayoutParams(-1, dp(235)));
        detailView = label("频率分辨率 / 选中轴 / RMS", 12, SECONDARY, false);
        spectrumCard.addView(detailView);
        noteView = label("结果基于 5 秒振动信号，每约 1 秒更新。请将手机可靠固定在静止、隔热的测试支架上；勿把手机直接贴在高温压缩机壳体或运动部件上。", 12, AMBER, false);
        noteView.setPadding(0, dp(12), 0, 0);
        spectrumCard.addView(noteView);
        LinearLayout cfg = card();
        root.addView(cfg, margins(0, dp(14)));
        cfg.addView(label("测量设置", 15, WHITE, true));
        LinearLayout ranges = horizontal();
        ranges.setPadding(0, dp(8), 0, dp(5));
        cfg.addView(ranges);
        LinearLayout lo = vertical(), hi = vertical();
        ranges.addView(lo,new LinearLayout.LayoutParams(0, -2,1));
        ranges.addView(hi,new LinearLayout.LayoutParams(0, -2,1));
        lo.addView(label("最低转速  rpm", 11, SECONDARY, false));
        hi.addView(label("最高转速  rpm", 11, SECONDARY, false));
        minInput = edit("900"); maxInput = edit("4800");
        lo.addView(minInput); hi.addView(maxInput);
        cfg.addView(label("分析轴", 11, SECONDARY, false));
        axisSpinner = new Spinner(this);
        String[] names = {"自动选择有效振动轴", "X 轴", "Y 轴", "Z 轴"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names);
        axisSpinner.setAdapter(adapter);
        axisSpinner.setBackgroundColor(Color.rgb(41, 62, 84));
        cfg.addView(axisSpinner);
        sensorView = label("读取传感器…", 11, SECONDARY, false);
        sensorView.setPadding(0, dp(13), 0, 0);
        cfg.addView(sensorView);

        LinearLayout buttons = horizontal();
        buttons.setPadding(0, dp(12), 0, dp(12));
        root.addView(buttons);
        runButton = new Button(this);
        runButton.setText("开始测量");
        runButton.setAllCaps(false);
        runButton.setTextColor(NAVY);
        runButton.setBackground(round(GREEN, 12));
        LinearLayout.LayoutParams grow = new LinearLayout.LayoutParams(0, dp(52), 1.6f);
        grow.setMargins(0, 0, dp(9), 0);
        buttons.addView(runButton, grow);
        runButton.setOnClickListener(v -> { if (running) stopMeasure(); else startMeasure(); });

        exportButton = new Button(this);
        exportButton.setText("导出 CSV");
        exportButton.setAllCaps(false);
        exportButton.setTextColor(WHITE);
        exportButton.setBackground(round(Color.rgb(39, 65, 89), 12));
        exportButton.setEnabled(false);
        buttons.addView(exportButton, new LinearLayout.LayoutParams(0, dp(52), 1));
        exportButton.setOnClickListener(v -> exportCsv());

        TextView foot = label("离线运行 · 不使用麦克风/网络 · 不保证计量级精度", 11, SECONDARY, false);
        foot.setGravity(Gravity.CENTER);
        root.addView(foot, margins(0, dp(15)));
    }

    private void startMeasure() {
        try {
            minRpm = Integer.parseInt(minInput.getText().toString().trim());
            maxRpm = Integer.parseInt(maxInput.getText().toString().trim());
        } catch (NumberFormatException e) { toast("请输入有效的整数转速范围"); return; }
        if (minRpm < 60 || maxRpm > 30000 || maxRpm <= minRpm) {
            toast("转速范围错误：下限 ≥60，上限 ≤30000 且大于下限"); return;
        }
        selectedAxis = axisSpinner.getSelectedItemPosition() - 1;
        if (!recorder.start()) { toast("无法启动加速度传感器"); return; }
        running = true;
        lastResult = null;
        rpmView.setText("—");
        statusView.setText("正在采集 5 秒振动数据…");
        confidenceView.setText("可信度：等待数据");
        samplingView.setText("实际采样率：采集中…");
        alternativesView.setText("其他可能转速：等待频谱分析");
        chart.clear();
        detailView.setText("频率分辨率 / 选中轴 / RMS");
        minInput.setEnabled(false);
        maxInput.setEnabled(false);
        axisSpinner.setEnabled(false);
        runButton.setText("停止测量");
        exportButton.setEnabled(true);
        ui.postDelayed(refresh, 1200);
    }

    private void stopMeasure() {
        if (!running) return;
        running = false;
        recorder.stop();
        ui.removeCallbacks(refresh);
        runButton.setText("重新开始");
        minInput.setEnabled(true);
        maxInput.setEnabled(true);
        axisSpinner.setEnabled(true);
        statusView.setText("已停止，当前显示上次有效分析结果");
    }

    private void render(RpmAnalyzer.Result result) {
        lastResult = result;
        samplingView.setText(result.sampleRateHz > 0
                ? String.format(Locale.US, "实际采样率：%.1f Hz", result.sampleRateHz)
                : "实际采样率：等待数据");
        if (result.valid) {
            rpmView.setText(String.format(Locale.US, "%.0f", result.rpm));
            confidenceView.setText("可信度：" + result.confidence
                    + "  |  检出相关谱线 " + result.supportedHarmonics + " 条");
            confidenceView.setTextColor(result.confidence.equals("较高") ? GREEN : AMBER);
            statusView.setText(String.format(Locale.US,
                    "最大峰值 %.2f Hz  ·  按 %d× 换算",
                    result.dominantPeakHz, result.primaryOrder));
            if (result.possibleRpm.length <= 1) {
                alternativesView.setText("其他可能转速：当前范围内无其它 1×～3× 解释");
            } else {
                StringBuilder b = new StringBuilder("其他可能转速（同一主峰的阶次解释）：");
                for (int i = 1; i < result.possibleRpm.length; i++) {
                    b.append(String.format(Locale.US, "\n%d× 对应 %.0f RPM",
                            result.possibleOrders[i], result.possibleRpm[i]));
                }
                alternativesView.setText(b.toString());
            }
            detailView.setText(String.format(Locale.US,
                    "真实频率分辨率约 %.2f Hz  ·  分析轴 %s\n振动 RMS %.4f m/s²  ·  主峰 SNR %.1f dB",
                    result.resolutionHz, result.axisLabel, result.rmsMs2, result.snrDb));
            chart.show(result);
        } else {
            rpmView.setText("—");
            confidenceView.setText("可信度：不足");
            confidenceView.setTextColor(AMBER);
            statusView.setText(result.note);
            alternativesView.setText("其他可能转速：等待有效谱峰");
            detailView.setText(result.sampleRateHz > 0
                    ? String.format(Locale.US, "已采集 %.1f 秒数据", result.durationSeconds)
                    : "等待传感器数据");
            chart.clear();
        }
        noteView.setText(result.note);
    }

    private void exportCsv() {
        if (recorder.sampleCount() == 0) { toast("无可导出的数据"); return; }
        // SAF does not require storage permissions; user chooses export location.
        if (running) stopMeasure();
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/csv");
        intent.putExtra(Intent.EXTRA_TITLE, "compressor_vibration_" +
                new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".csv");
        startActivityForResult(intent, EXPORT_REQUEST);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != EXPORT_REQUEST || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri target = data.getData();
        List<VibrationSample> snapshot = recorder.snapshot();
        RpmAnalyzer.Result analyzed = lastResult;
        worker.execute(() -> {
            try (OutputStream os = getContentResolver().openOutputStream(target);
                 BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8))) {
                writer.write("# CompressorTach vibration capture; timestamps are monotonic, not wall-clock time\n");
                writer.write("# units: t_s, ax_m_s2, ay_m_s2, az_m_s2\n");
                if (analyzed != null && analyzed.valid) {
                    writer.write(String.format(Locale.US,"# estimated_rpm=%.3f; confidence=%s; fs_Hz=%.2f; axis=%s; dominant_Hz=%.3f; dominant_order=%d\n",
                            analyzed.rpm, analyzed.confidence, analyzed.sampleRateHz,
                            analyzed.axisLabel, analyzed.dominantPeakHz, analyzed.primaryOrder));
                }
                writer.write("t_s,ax_m_s2,ay_m_s2,az_m_s2\n");
                long zero = snapshot.get(0).timestampNs;
                for (VibrationSample s : snapshot) {
                    writer.write(String.format(Locale.US,"%.8f,%.7f,%.7f,%.7f\n",
                            (s.timestampNs-zero) * 1e-9,s.x,s.y,s.z));
                }
                ui.post(() -> toast("CSV 导出成功（"+snapshot.size()+" 条）"));
            } catch (Exception e) {
                android.util.Log.e("CompressorTach", "Export error", e);
                ui.post(() -> toast("保存失败：" + e.getClass().getSimpleName()));
            }
        });
    }

    @Override protected void onPause() {
        super.onPause();
        if (running) stopMeasure(); // Never record while app is in background.
    }
    @Override protected void onDestroy() {
        running = false;
        ui.removeCallbacks(refresh);
        recorder.stop();
        worker.shutdownNow();
        super.onDestroy();
    }

    private LinearLayout vertical() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout horizontal() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); return l; }
    private LinearLayout card() {
        LinearLayout l = vertical();
        l.setPadding(dp(17),dp(16),dp(17),dp(16));
        l.setBackground(round(CARD, 17));
        return l;
    }
    private GradientDrawable round(int color, int radius) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(radius));
        return bg;
    }
    private TextView label(String title, int sp, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(title); text.setTextSize(sp); text.setTextColor(color);
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
    }
    private EditText edit(String initial) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(initial); e.setSelectAllOnFocus(true);
        e.setTextColor(WHITE); e.setTextSize(17); e.setSingleLine(true);
        return e;
    }
    private LinearLayout.LayoutParams margins(int l, int t) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2);
        p.setMargins(l,t,0,0); return p;
    }
    private int dp(float px) { return Math.round(px * getResources().getDisplayMetrics().density); }
    private void toast(String msg) { Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); }
}
