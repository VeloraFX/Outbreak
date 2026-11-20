package velora.outbreak.diagnosis;

import java.util.UUID;

/** Deterministic focus target per sample and zoom. Not secret: it only drives blur and the sharpness check. */
public final class Optics {
    public static final int TOLERANCE = 6;

    private Optics() {}

    public static int target(UUID sampleId, int zoom) {
        long h = sampleId.getLeastSignificantBits() * 31L + sampleId.getMostSignificantBits() + zoom * 7919L;
        h ^= (h >>> 29);
        h *= 0x9E3779B97F4A7C15L;
        h ^= (h >>> 32);
        return 20 + (int) ((h & 0x7fffffffL) % 61);
    }

    public static boolean isSharp(int target, int focus) {
        return Math.abs(target - focus) <= TOLERANCE;
    }
}