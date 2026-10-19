package com.compwave.tach;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.Locale;

/** Dependency-free, adaptive FFT chart. Y axis shows dB re 1 m/s², NOT acoustic dB. */
public final class SpectrumView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private double[] freq = new double[0], db = new double[0];
    private double markerHz = Double.NaN;
    private final int grid = Color.rgb(52, 74, 101);
    private final int muted = Color.rgb(168, 187, 208);
    private final int accent = Color.rgb(65, 218, 184);

    public SpectrumView(Context context) { super(context); }
    public void show(RpmAnalyzer.Result result) {
        freq = result.spectrumHz;
        db = result.spectrumDb;
        markerHz = result.dominantPeakHz;
        invalidate();
    }

    public void clear() {
        freq = new double[0];
        db = new double[0];
        markerHz = Double.NaN;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left = dp(48), right = getWidth() - dp(14), top = dp(22), bottom = getHeight() - dp(30);
        if (right <= left || bottom <= top) return;
        paint.setTextSize(dp(11));
        paint.setStrokeWidth(dp(1));
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(grid);
        for (int i = 0; i <= 4; i++) {
            float y = top + (bottom - top) * i / 4f;
            canvas.drawLine(left, y, right, y, paint);
        }
        for (int i = 0; i <= 4; i++) {
            float x = left + (right - left) * i / 4f;
            canvas.drawLine(x, top, x, bottom, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(muted);
        canvas.drawText("振动频谱  dB re 1 m/s²", left, dp(14), paint);
        if (freq.length < 2) {
            canvas.drawText("等待振动数据…", left + dp(12), top + (bottom-top)/2, paint);
            return;
        }
        double minF = freq[0], maxF = freq[freq.length - 1];
        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (double v : db) { minY = Math.min(minY, v); maxY = Math.max(maxY, v); }
        maxY = Math.ceil((maxY + 5) / 10.0) * 10.0;
        minY = Math.min(minY, maxY - 35);
        minY = Math.floor((minY - 5) / 10.0) * 10.0;
        if (maxY - minY < 20) minY = maxY - 20;
        paint.setColor(muted);
        for (int i = 0; i <= 4; i++) {
            float y = top + (bottom - top) * i / 4f;
            canvas.drawText(String.format(Locale.US, "%.0f", maxY - (maxY-minY)*i/4), dp(4), y+dp(4), paint);
            float x = left + (right - left) * i / 4f;
            canvas.drawText(String.format(Locale.US, "%.0f", minF+(maxF-minF)*i/4),
                    x - dp(8), bottom + dp(17), paint);
        }
        canvas.drawText("Hz", right - dp(9), bottom + dp(27), paint);
        Path path = new Path();
        for (int i = 0; i < freq.length; i++) {
            float x = left+(float)((freq[i]-minF)/(maxF-minF))*(right-left);
            float y = bottom - (float)((db[i]-minY)/(maxY-minY))*(bottom-top);
            y = Math.max(top, Math.min(bottom, y));
            if (i == 0) path.moveTo(x,y); else path.lineTo(x,y);
        }
        paint.setColor(accent);
        paint.setStrokeWidth(dp(1.8f));
        paint.setStyle(Paint.Style.STROKE);
        canvas.drawPath(path, paint);
        if (Double.isFinite(markerHz) && markerHz >= minF && markerHz <= maxF) {
            float x = left + (float)((markerHz-minF)/(maxF-minF))*(right-left);
            paint.setColor(Color.rgb(245, 186, 95));
            paint.setStrokeWidth(dp(1));
            canvas.drawLine(x, top, x, bottom, paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawText(String.format(Locale.US,"主峰 %.1f Hz",markerHz),
                    Math.min(x + dp(4), right - dp(76)), top + dp(12), paint);
        }
    }
    private float dp(float px) { return getResources().getDisplayMetrics().density * px; }
}
