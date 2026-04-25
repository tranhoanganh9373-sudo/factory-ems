package com.ems.mockdata.timeseries;

import java.util.Random;

/**
 * Adds per-minute Gaussian noise and injects fault patterns:
 * - ±5% Gaussian noise on each minute value
 * - 1-3 missing windows per meter per day (value set to -1 as sentinel)
 * - 1% of meters get 1-hour zero-stuck periods (set to 0 for that hour)
 */
public class NoiseInjector {

    private final Random rng;
    private final double noisePct;

    public NoiseInjector(Random rng) {
        this.rng = rng;
        this.noisePct = 0.05;
    }

    /** Apply ±noisePct Gaussian noise. */
    public double addNoise(double base) {
        double noise = rng.nextGaussian() * noisePct;
        return Math.max(0.0, base * (1.0 + noise));
    }

    /**
     * Returns true if this minute (0-1439) should be marked as missing.
     * Deterministic per (dayHash, meterHash): 1-3 windows of 5-20 minutes each.
     */
    public boolean isMissing(int minuteOfDay, long dayHash, long meterHash) {
        long seed = dayHash * 31L + meterHash;
        Random r = new Random(seed);
        int windowCount = 1 + r.nextInt(3); // 1-3 windows
        for (int w = 0; w < windowCount; w++) {
            int start = r.nextInt(1440);
            int len = 5 + r.nextInt(16); // 5-20 minutes
            if (minuteOfDay >= start && minuteOfDay < start + len) return true;
        }
        return false;
    }

    /**
     * Returns true if this meter (by index) gets the zero-stuck fault
     * and the hour matches the injected stuck hour.
     * Affects ~1% of meters.
     */
    public boolean isZeroStuck(int meterIdx, int totalMeters, int hourOfDay, long dayHash) {
        // deterministic selection: meter is "stuck" if its index lands in ~1%
        long seed = dayHash * 1000003L + meterIdx;
        Random r = new Random(seed);
        if (r.nextDouble() > 0.01) return false;
        int stuckHour = r.nextInt(24);
        return hourOfDay == stuckHour;
    }
}
