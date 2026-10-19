package com.compwave.tach;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
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
    private TextView rpmView, confidenceView, detailView, statusView;
    private TextView samplingView, alternativesView;
    private Button runButton, exportButton, settingsButton;
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
        SharedPreferences prefs = getSharedPreferences("measurement", MODE_PRIVATE);
        minRpm = prefs.getInt("minRpm", 900);
        maxRpm = prefs.getInt("maxRpm", 4800);
        selectedAxis = prefs.getInt("axis", -1);
        if (minRpm < 60 || maxRpm > 30000 || maxRpm <= minRpm || selectedAxis < -1 || selectedAxis > 2) {
            minRpm = 900; maxRpm = 4800; selectedAxis = -1;
        }
        buildUi();
        if (!recorder.available()) {
            statusView.setText("此手机没有可用加速度计");
            runButton.setEnabled(false);
        }
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(NAVY);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout root = vertical();
        root.setPadding(dp(13), dp(8), dp(13), dp(8));
        scroll.addView(root);
        setContentView(scroll);

        // One-line title / upper-right settings gear. No in-page settings panel.
        LinearLayout header = horizontal();
        header.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(header);
        LinearLayout branding = vertical();
        header.addView(branding, new LinearLayout.LayoutParams(0, -2, 1));
        branding.addView(label("压缩机振动转速仪", 20, WHITE, true));
        TextView tagline = label("CompressorTach  ·  三轴加速度  ·  V0.3", 10, SECONDARY, false);
        tagline.setPadding(0, dp(2), 0, 0);
        branding.addView(tagline);
        settingsButton = new Button(this);
        settingsButton.setText("⚙");
        settingsButton.setTextSize(24);
        settingsButton.setAllCaps(false);
        settingsButton.setTextColor(WHITE);
        settingsButton.setContentDescription("测量设置");
        settingsButton.setPadding(0, 0, 0, 0);
        settingsButton.setMinHeight(0);
        settingsButton.setMinimumHeight(0);
        settingsButton.setMinWidth(0);
        settingsButton.setMinimumWidth(0);
        settingsButton.setBackground(round(Color.rgb(35, 57, 78), 12));
        header.addView(settingsButton, new LinearLayout.LayoutParams(dp(46), dp(46)));
        settingsButton.setOnClickListener(v -> openSettings());

        LinearLayout rpmCard = compactCard();
        root.addView(rpmCard, margins(0, dp(7)));
        LinearLayout rpmHeading = horizontal();
        rpmHeading.setGravity(Gravity.CENTER_VERTICAL);
        rpmCard.addView(rpmHeading);
        rpmHeading.addView(label("估算曲轴转速", 13, SECONDARY, false),
                new LinearLayout.LayoutParams(0, -2, 1));
        samplingView = label("采样率：等待", 11, GREEN, false);
        samplingView.setGravity(Gravity.END);
        rpmHeading.addView(samplingView);

        LinearLayout numberLine = horizontal();
        numberLine.setGravity(Gravity.BOTTOM);
        rpmCard.addView(numberLine);
        rpmView = label("—", 46, WHITE, true);
        rpmView.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        rpmView.setIncludeFontPadding(false);
        numberLine.addView(rpmView);
        TextView unit = label("RPM", 14, GREEN, true);
        unit.setPadding(dp(7), 0, 0, dp(7));
        numberLine.addView(unit);

        statusView = label("尚未开始测量", 12, SECONDARY, false);
        statusView.setMaxLines(2);
        statusView.setEllipsize(TextUtils.TruncateAt.END);
        rpmCard.addView(statusView);
        confidenceView = label("可信度：等待数据", 11, AMBER, false);
        confidenceView.setPadding(0, dp(2), 0, 0);
        rpmCard.addView(confidenceView);
        alternativesView = label("潜在转速：等待分析", 11, AMBER, false);
        alternativesView.setPadding(0, dp(3), 0, 0);
        alternativesView.setMaxLines(2);
        alternativesView.setEllipsize(TextUtils.TruncateAt.END);
        rpmCard.addView(alternativesView);

        // Compact chart and diagnostic line; buttons immediately follow the chart.
        LinearLayout spectrumCard = compactCard();
        root.addView(spectrumCard, margins(0, dp(7)));
        spectrumCard.addView(label("实时频谱", 14, WHITE, true));
        chart = new SpectrumView(this);
        int availableDp = Math.round(getResources().getDisplayMetrics().heightPixels /
                getResources().getDisplayMetrics().density);
        int chartDp = Math.max(136, Math.min(208, Math.round(availableDp * 0.225f)));
        spectrumCard.addView(chart, new LinearLayout.LayoutParams(-1, dp(chartDp)));
        detailView = label("频率分辨率 / 分析轴 / 振动 RMS", 11, SECONDARY, false);
        detailView.setMaxLines(2);
        spectrumCard.addView(detailView);

        LinearLayout buttons = horizontal();
        buttons.setPadding(0, dp(8), 0, 0);
        root.addView(buttons);
        runButton = new Button(this);
        runButton.setText("开始测量");
        runButton.setAllCaps(false);
        runButton.setTextSize(15);
        runButton.setTextColor(NAVY);
        runButton.setPadding(0, 0, 0, 0);
        runButton.setBackground(round(GREEN, 12));
        LinearLayout.LayoutParams grow = new LinearLayout.LayoutParams(0, dp(48), 1.5f);
        grow.setMargins(0, 0, dp(8), 0);
        buttons.addView(runButton, grow);
        runButton.setOnClickListener(v -> { if (running) stopMeasure(); else startMeasure(); });

        exportButton = new Button(this);
        exportButton.setText("导出 CSV");
        exportButton.setAllCaps(false);
        exportButton.setTextSize(15);
        exportButton.setPadding(0, 0, 0, 0);
        exportButton.setTextColor(WHITE);
        exportButton.setBackground(round(Color.rgb(39, 65, 89), 12));
        exportButton.setEnabled(false);
        buttons.addView(exportButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        exportButton.setOnClickListener(v -> exportCsv());
    }

    /** Settings are applied when a new measurement starts; no hidden in-measure changes. */
    private void openSettings() {
        LinearLayout body = vertical();
        body.setPadding(dp(18), dp(8), dp(18), dp(3));
        body.addView(label("搜索转速范围（RPM）", 13, WHITE, true));
        LinearLayout ranges = horizontal();
        ranges.setPadding(0, dp(5), 0, dp(9));
        body.addView(ranges);
        LinearLayout left = vertical(), right = vertical();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.setMargins(0, 0, dp(10), 0);
        ranges.addView(left, lp);
        ranges.addView(right, new LinearLayout.LayoutParams(0, -2, 1));
        left.addView(label("最低转速", 11, SECONDARY, false));
        right.addView(label("最高转速", 11, SECONDARY, false));
        EditText minField = edit(String.valueOf(minRpm));
        EditText maxField = edit(String.valueOf(maxRpm));
        left.addView(minField);
        right.addView(maxField);
        body.addView(label("分析轴", 12, WHITE, true));
        Spinner axisField = new Spinner(this);
        String[] axes = {"自动选择有效振动轴", "X 轴", "Y 轴", "Z 轴"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, axes);
        axisField.setAdapter(adapter);
        axisField.setSelection(selectedAxis + 1);
        body.addView(axisField);
        TextView sensorInfo = label(recorder.sensorName() + "  |  标称上限约 " +
                String.format(Locale.US, "%.0f Hz", recorder.nominalMaxHz()),
                11, SECONDARY, false);
        sensorInfo.setPadding(0, dp(10), 0, 0);
        body.addView(sensorInfo);
        TextView tip = label("手机须固定在静止、隔热的试验支架上；不得直接接触高温压缩机壳体。实际采样率以测量卡片显示为准。", 11, AMBER, false);
        tip.setPadding(0, dp(12), 0, 0);
        body.addView(tip);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("测量设置")
                .setView(body)
                .setNegativeButton("取消", (d, w) -> {})
                .setPositiveButton("保存", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    int low, high;
                    try {
                        low = Integer.parseInt(minField.getText().toString().trim());
                        high = Integer.parseInt(maxField.getText().toString().trim());
                    } catch (NumberFormatException ex) {
                        toast("请输入有效整数转速"); return;
                    }
                    if (low < 60 || high > 30000 || high <= low) {
                        toast("最低 ≥60，最高 ≤30000，且最高必须大于最低"); return;
                    }
                    if (running) stopMeasure();
                    minRpm = low;
                    maxRpm = high;
                    selectedAxis = axisField.getSelectedItemPosition() - 1;
                    getSharedPreferences("measurement", MODE_PRIVATE).edit()
                            .putInt("minRpm", minRpm)
                            .putInt("maxRpm", maxRpm)
                            .putInt("axis", selectedAxis)
                            .apply();
                    toast("设置已保存，下一次测量生效");
                    dialog.dismiss();
                }));
        dialog.show();
    }

    private void startMeasure() {
        if (!recorder.start()) { toast("无法启动加速度传感器"); return; }
        running = true;
        lastResult = null;
        rpmView.setText("—");
        statusView.setText("正在采集 5 秒振动数据…");
        confidenceView.setText("可信度：等待数据");
        samplingView.setText("采样率：采集中…");
        alternativesView.setText("潜在转速：等待频谱分析");
        chart.clear();
        detailView.setText("频率分辨率 / 分析轴 / 振动 RMS");
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
        statusView.setText("已停止，保留最后一次估算结果");
    }

    private void render(RpmAnalyzer.Result result) {
        lastResult = result;
        samplingView.setText(result.sampleRateHz > 0
                ? String.format(Locale.US, "实际采样率 %.1f Hz", result.sampleRateHz)
                : "采样率：等待数据");
        if (result.valid) {
            rpmView.setText(String.format(Locale.US, "%.0f", result.rpm));
            confidenceView.setText("可信度：" + result.confidence
                    + "  ·  相关谱线 " + result.supportedHarmonics + " 条");
            confidenceView.setTextColor(result.confidence.equals("较高") ? GREEN : AMBER);
            statusView.setText(String.format(Locale.US,
                    "最大峰值 %.2f Hz  ·  按 %d× 换算",
                    result.dominantPeakHz, result.primaryOrder));
            if (result.possibleRpm.length <= 1) {
                alternativesView.setText("潜在转速：当前范围内无其它阶次解释");
            } else {
                StringBuilder b = new StringBuilder("潜在转速：");
                for (int i = 1; i < result.possibleRpm.length; i++) {
                    if (i > 1) b.append("  ·  ");
                    b.append(String.format(Locale.US, "%d× %.0f RPM",
                            result.possibleOrders[i], result.possibleRpm[i]));
                }
                alternativesView.setText(b.toString());
            }
            detailView.setText(String.format(Locale.US,
                    "Δf ≈ %.2f Hz  ·  轴 %s  ·  RMS %.4f m/s²\n主峰 SNR %.1f dB",
                    result.resolutionHz, result.axisLabel, result.rmsMs2, result.snrDb));
            chart.show(result);
        } else {
            rpmView.setText("—");
            confidenceView.setText("可信度：不足");
            confidenceView.setTextColor(AMBER);
            statusView.setText(result.note);
            alternativesView.setText("潜在转速：等待有效谱峰");
            detailView.setText(result.sampleRateHz > 0
                    ? String.format(Locale.US, "已采集 %.1f 秒数据", result.durationSeconds)
                    : "等待传感器数据");
            chart.clear();
        }
        // Detailed diagnostic messages are available in the status line and log;
        // the safety warning lives in the settings dialog to preserve screen space.
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
    private LinearLayout compactCard() {
        LinearLayout l = vertical();
        l.setPadding(dp(12), dp(9), dp(12), dp(10));
        l.setBackground(round(CARD, 14));
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
