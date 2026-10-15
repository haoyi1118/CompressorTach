package com.compwave.tach;

/** Accelerometer observation, SI units m/s², monotonic timestamp in nanoseconds. */
public final class VibrationSample {
    public final long timestampNs;
    public final double x, y, z;

    public VibrationSample(long timestampNs, double x, double y, double z) {
        this.timestampNs = timestampNs;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public double axis(int axis) {
        return axis == 0 ? x : (axis == 1 ? y : z);
    }
}
