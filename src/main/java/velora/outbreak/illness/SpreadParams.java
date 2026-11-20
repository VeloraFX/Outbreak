package velora.outbreak.illness;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;

/**
 * Per-illness spread rules (the optional "spread" object in an illness JSON). Only used when the
 * illness is contagious AND airborne (see IllnessDefinition.canSpread()).
 *
 * rangeBlocks:  how close (in blocks) a healthy villager must be to be exposed.
 * chance:       probability that one exposure attempt (one source, one neighbour, once per interval)
 *               infects, for a fully contagious source standing right next to the neighbour.
 * intervalTicks: how often each source makes an exposure attempt. Rounded down to a multiple of 20.
 * *Factor:      multiplier on the chance by the SOURCE's state. RECOVERED is always 0 (not configurable).
 */
public record SpreadParams(double rangeBlocks, double chance, int intervalTicks,
                           double incubatingFactor, double symptomaticFactor,
                           double treatedFactor, double recoveringFactor) {

    public static final double DEFAULT_RANGE = 6.0;
    public static final double DEFAULT_CHANCE = 0.02;
    public static final int DEFAULT_INTERVAL = 200;
    public static final double DEFAULT_INCUBATING = 0.0;
    public static final double DEFAULT_SYMPTOMATIC = 1.0;
    public static final double DEFAULT_TREATED = 0.2;
    public static final double DEFAULT_RECOVERING = 0.1;

    public static final SpreadParams DEFAULT = new SpreadParams(DEFAULT_RANGE, DEFAULT_CHANCE, DEFAULT_INTERVAL,
            DEFAULT_INCUBATING, DEFAULT_SYMPTOMATIC, DEFAULT_TREATED, DEFAULT_RECOVERING);

    private static final Codec<Double> UNIT = Codec.doubleRange(0.0, 1.0);
    private static final Codec<Double> RANGE = Codec.doubleRange(1.0, 16.0);
    private static final Codec<Integer> INTERVAL = Codec.intRange(20, 24_000);

    /** Every JSON key CODEC reads inside "spread", in codec order (IllnessLoader's unknown-key warning). */
    public static final List<String> JSON_KEYS = List.of(
            "range_blocks", "chance", "interval_ticks", "incubating_factor", "symptomatic_factor",
            "treated_factor", "recovering_factor");

    public static final Codec<SpreadParams> CODEC = RecordCodecBuilder.create(i -> i.group(
            RANGE.optionalFieldOf("range_blocks", DEFAULT_RANGE).forGetter(SpreadParams::rangeBlocks),
            UNIT.optionalFieldOf("chance", DEFAULT_CHANCE).forGetter(SpreadParams::chance),
            INTERVAL.optionalFieldOf("interval_ticks", DEFAULT_INTERVAL).forGetter(SpreadParams::intervalTicks),
            UNIT.optionalFieldOf("incubating_factor", DEFAULT_INCUBATING).forGetter(SpreadParams::incubatingFactor),
            UNIT.optionalFieldOf("symptomatic_factor", DEFAULT_SYMPTOMATIC).forGetter(SpreadParams::symptomaticFactor),
            UNIT.optionalFieldOf("treated_factor", DEFAULT_TREATED).forGetter(SpreadParams::treatedFactor),
            UNIT.optionalFieldOf("recovering_factor", DEFAULT_RECOVERING).forGetter(SpreadParams::recoveringFactor)
    ).apply(i, SpreadParams::new));

    /** How contagious a source in this state is (0 = not at all). RECOVERED (immune) never spreads. */
    public double factorFor(IllnessState state) {
        return switch (state) {
            case INCUBATING -> incubatingFactor;
            case SYMPTOMATIC -> symptomaticFactor;
            case UNDER_TREATMENT -> treatedFactor;
            case RECOVERING -> recoveringFactor;
            case RECOVERED -> 0.0;
        };
    }
}