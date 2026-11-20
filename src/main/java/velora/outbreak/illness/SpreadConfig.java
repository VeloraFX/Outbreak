package velora.outbreak.illness;

import velora.outbreak.config.OutbreakConfig;

/**
 * The spread system's view of the settings: config file values (OutbreakConfig) with the runtime
 * overrides of /outbreak spread ... layered on top. Overrides are NOT saved and reset when the server stops.
 *
 *  enabled:   an override REPLACES the config value (so an admin can switch spread on or off for a test).
 *  chance / range: an override MULTIPLIES the config value (1.0 = no change); the result is clamped to the
 *             limits in the config file.
 * Server thread only (volatile for safety).
 */
public final class SpreadConfig {
    private SpreadConfig() {}

    private static volatile Boolean enabledOverride = null;
    private static volatile double chanceOverride = 1.0;
    private static volatile double rangeOverride = 1.0;

    public static void resetOverrides() {
        enabledOverride = null;
        chanceOverride = 1.0;
        rangeOverride = 1.0;
    }

    // ---- effective values ----
    public static boolean enabled() {
        Boolean o = enabledOverride;
        return o != null ? o : OutbreakConfig.spread().enabled();
    }

    public static double chanceMultiplier() {
        OutbreakConfig.Spread c = OutbreakConfig.spread();
        return clamp(c.chanceMultiplier() * chanceOverride, 0.0, c.maxChanceMultiplier());
    }

    public static double rangeMultiplier() {
        OutbreakConfig.Spread c = OutbreakConfig.spread();
        return clamp(c.rangeMultiplier() * rangeOverride, c.minRangeMultiplier(), c.maxRangeMultiplier());
    }

    public static int passIntervalTicks() { return OutbreakConfig.spread().passIntervalTicks(); }
    public static int passTickOffset() { return OutbreakConfig.spread().passTickOffset(); }
    public static int maxSourcesPerPass() { return OutbreakConfig.spread().maxSourcesPerPass(); }
    public static int maxCandidatesPerSource() { return OutbreakConfig.spread().maxCandidatesPerSource(); }
    public static int maxInfectionsPerPass() { return OutbreakConfig.spread().maxInfectionsPerPass(); }
    public static double edgeProximityWeight() { return OutbreakConfig.spread().edgeProximityWeight(); }
    public static double maxEffectiveRange() { return OutbreakConfig.spread().maxEffectiveRange(); }

    // ---- overrides ----
    public static Boolean enabledOverride() { return enabledOverride; }
    public static double chanceOverride() { return chanceOverride; }
    public static double rangeOverride() { return rangeOverride; }

    public static void setEnabledOverride(boolean value) { enabledOverride = value; }

    /** Clamped to the configured limits; returns the value actually stored. */
    public static double setChanceOverride(double m) {
        chanceOverride = clamp(m, 0.0, OutbreakConfig.spread().maxChanceMultiplier());
        return chanceOverride;
    }

    public static double setRangeOverride(double m) {
        OutbreakConfig.Spread c = OutbreakConfig.spread();
        rangeOverride = clamp(m, c.minRangeMultiplier(), c.maxRangeMultiplier());
        return rangeOverride;
    }

    private static double clamp(double v, double min, double max) { return Math.max(min, Math.min(max, v)); }
}