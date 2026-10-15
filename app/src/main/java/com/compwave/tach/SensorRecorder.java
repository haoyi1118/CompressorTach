package com.compwave.tach;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Owns a separate sensor callback thread; caps memory to about 30 seconds at 400Hz. */
public final class SensorRecorder implements SensorEventListener {
    private static final int MAX_SAMPLES = 16000;
    private final SensorManager manager;
    private final Sensor sensor;
    private final ArrayDeque<VibrationSample> samples = new ArrayDeque<>();
    private HandlerThread thread;
    private volatile boolean recording;

    public SensorRecorder(Context context) {
        manager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        sensor = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    }

    public boolean available() { return sensor != null; }
    public String sensorName() { return sensor == null ? "无加速度传感器" : sensor.getName(); }
    public double nominalMaxHz() {
        if (sensor == null) return 0;
        return sensor.getMinDelay() > 0 ? 1e6 / sensor.getMinDelay() : 0;
    }

    public synchronized boolean start() {
        if (sensor == null) return false;
        stop();
        samples.clear();
        thread = new HandlerThread("CompressorTachSensor");
        thread.start();
        recording = true;
        // 5000 us requests ~200Hz; platform may return lower or higher actual rate.
        boolean ok = manager.registerListener(this, sensor, 5000, new Handler(thread.getLooper()));
        if (!ok) stop();
        return ok;
    }

    public synchronized void stop() {
        recording = false;
        manager.unregisterListener(this);
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
    }

    public synchronized List<VibrationSample> snapshot() { return new ArrayList<>(samples); }
    public synchronized int sampleCount() { return samples.size(); }

    @Override public void onSensorChanged(SensorEvent event) {
        if (!recording || event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;
        VibrationSample observation = new VibrationSample(event.timestamp,
                event.values[0], event.values[1], event.values[2]);
        synchronized (this) {
            if (samples.size() >= MAX_SAMPLES) samples.removeFirst();
            samples.addLast(observation);
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
}
