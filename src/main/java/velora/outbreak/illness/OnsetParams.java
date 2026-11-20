package velora.outbreak.illness;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-illness natural onset rules (the optional "natural_onset" object in an illness JSON).
 * Absent block = the illness never starts on its own. Read by OnsetManager (6d-2d).
 *
 * chance:            base probability per eligible (healthy, entity-ticking, near a player) villager per
 *                    onset check, before modifiers and the config's onset.chance_multiplier.
 * *_multiplier:      applied when the matching environment condition holds (see multiplierFor):
 *                    wet   = level is raining AND the biome has precipitation (level-wide weather, so it
 *                            also applies to villagers indoors);
 *                    night = level.isDarkOutside(); day = level.isBrightOutside() (both false in fixed-time
 *                            dimensions);
 *                    cold  = biome base temperature  < cold_below_temperature;
 *                    warm  = biome base temperature >= warm_from_temperature;
 *                    baby  = villager.isBaby();
 *                    biome = the biome matches any entry of "biomes".
 * biomes:            up to 32 strings, "minecraft:plains" (biome id) or "#minecraft:is_forest" (biome tag).
 *                    Parsed once at decode time (BiomeFilter), never per check. Malformed strings are
 *                    ignored with a warning. A well-formed but UNKNOWN biome id or tag cannot be checked at
 *                    load time (the illness loader has no registry access), so it simply never matches.
 * requires_food:     only villagers carrying food (TransmissionRules.holdsFood) can fall ill. A FOODBORNE
 *                    illness ALWAYS requires food, whatever this says (hard rule in IllnessManager.onset).
 *
 * Base temperatures are floats in vanilla (0.3f is slightly above 0.3), so with the defaults a biome at
 * 0.3 (old_growth_pine_taiga) is not cold and one at 0.8 (plains, swamp) is warm.
 */
public record OnsetParams(double chance,
                          double wetMultiplier, double nightMultiplier, double dayMultiplier,
                          double coldMultiplier, double coldBelowTemperature,
                          double warmMultiplier, double warmFromTemperature,
                          double babyMultiplier, double biomeMultiplier,
                          BiomeFilter biomes, boolean requiresFood) {

    public static final double MAX_CHANCE = 0.01;
    /** Hard ceiling on the effective chance of one roll, after every modifier and the config multiplier. */
    public static final double MAX_EFFECTIVE_CHANCE = 0.05;
    public static final double DEFAULT_COLD_BELOW = 0.3;
    public static final double DEFAULT_WARM_FROM = 0.8;
    public static final int MAX_BIOMES = 32;

    private static final Codec<Double> CHANCE = Codec.doubleRange(0.0, MAX_CHANCE);
    private static final Codec<Double> MULT = Codec.doubleRange(0.0, 10.0);
    private static final Codec<Double> TEMP = Codec.doubleRange(-2.0, 3.0);

    /** Every JSON key CODEC reads inside "natural_onset", in codec order (IllnessLoader's unknown-key warning). */
    public static final List<String> JSON_KEYS = List.of(
            "chance", "wet_multiplier", "night_multiplier", "day_multiplier", "cold_multiplier",
            "cold_below_temperature", "warm_multiplier", "warm_from_temperature", "baby_multiplier",
            "biome_multiplier", "biomes", "requires_food");

    public static final Codec<OnsetParams> CODEC = RecordCodecBuilder.create(i -> i.group(
            CHANCE.optionalFieldOf("chance", 0.0).forGetter(OnsetParams::chance),
            MULT.optionalFieldOf("wet_multiplier", 1.0).forGetter(OnsetParams::wetMultiplier),
            MULT.optionalFieldOf("night_multiplier", 1.0).forGetter(OnsetParams::nightMultiplier),
            MULT.optionalFieldOf("day_multiplier", 1.0).forGetter(OnsetParams::dayMultiplier),
            MULT.optionalFieldOf("cold_multiplier", 1.0).forGetter(OnsetParams::coldMultiplier),
            TEMP.optionalFieldOf("cold_below_temperature", DEFAULT_COLD_BELOW).forGetter(OnsetParams::coldBelowTemperature),
            MULT.optionalFieldOf("warm_multiplier", 1.0).forGetter(OnsetParams::warmMultiplier),
            TEMP.optionalFieldOf("warm_from_temperature", DEFAULT_WARM_FROM).forGetter(OnsetParams::warmFromTemperature),
            MULT.optionalFieldOf("baby_multiplier", 1.0).forGetter(OnsetParams::babyMultiplier),
            MULT.optionalFieldOf("biome_multiplier", 1.0).forGetter(OnsetParams::biomeMultiplier),
            BiomeFilter.CODEC.optionalFieldOf("biomes", BiomeFilter.EMPTY).forGetter(OnsetParams::biomes),
            Codec.BOOL.optionalFieldOf("requires_food", false).forGetter(OnsetParams::requiresFood)
    ).apply(i, OnsetParams::new));

    /**
     * Environment values read ONCE per candidate villager by the onset manager, then shared by every
     * illness's multiplierFor. Plain values only; the manager must not keep this between checks.
     */
    public record Environment(Holder<Biome> biome, float baseTemperature,
                              boolean wet, boolean dark, boolean bright, boolean baby) {}

    /** Product of every modifier whose condition holds (1.0 if none). Pure; no world access. */
    public double multiplierFor(Environment env) {
        double m = 1.0;
        if (env.wet()) m *= wetMultiplier;
        if (env.dark()) m *= nightMultiplier;
        if (env.bright()) m *= dayMultiplier;
        if (env.baseTemperature() < coldBelowTemperature) m *= coldMultiplier;
        if (env.baseTemperature() >= warmFromTemperature) m *= warmMultiplier;
        if (env.baby()) m *= babyMultiplier;
        if (biomeMultiplier != 1.0 && biomes.matches(env.biome())) m *= biomeMultiplier;
        return m;
    }

    /** chance x modifiers x the config multiplier, clamped to [0, MAX_EFFECTIVE_CHANCE]. */
    public double effectiveChance(Environment env, double configMultiplier) {
        double c = chance * multiplierFor(env) * configMultiplier;
        if (!(c > 0.0)) return 0.0; // also catches NaN
        return Math.min(c, MAX_EFFECTIVE_CHANCE);
    }

    /** Problems an author should know about. Pure (no logging); IllnessDefinition.warnings() prefixes them. */
    public List<String> warnings(boolean foodborne) {
        List<String> w = new ArrayList<>();
        if (chance == 0.0) {
            w.add("'natural_onset' has chance 0; this illness never starts on its own");
        }
        for (String bad : biomes.malformed()) {
            w.add("natural_onset: malformed biome '" + bad
                    + "' (use \"namespace:biome\" or \"#namespace:tag\"); it is ignored");
        }
        if (biomes.dropped() > 0) {
            w.add("natural_onset: 'biomes' has more than " + MAX_BIOMES + " entries; the last "
                    + biomes.dropped() + " are ignored");
        }
        if (biomeMultiplier != 1.0 && biomes.isEmpty()) {
            w.add("natural_onset: biome_multiplier is set but no valid 'biomes' are listed; it has no effect");
        }
        if (coldBelowTemperature > warmFromTemperature) {
            w.add("natural_onset: cold_below_temperature is above warm_from_temperature; some biomes count as both cold and warm");
        }
        if (foodborne && !requiresFood) {
            w.add("natural_onset: requires_food is false, but a foodborne illness only ever starts in a villager carrying food");
        }
        return w;
    }

    /** One-line summary for /outbreak illnesses: the chance plus every modifier that is not x1. */
    public String describe() {
        StringBuilder sb = new StringBuilder("chance ").append(num(chance));
        mod(sb, "wet", wetMultiplier);
        mod(sb, "night", nightMultiplier);
        mod(sb, "day", dayMultiplier);
        mod(sb, "cold(<" + num(coldBelowTemperature) + ")", coldMultiplier);
        mod(sb, "warm(>=" + num(warmFromTemperature) + ")", warmMultiplier);
        mod(sb, "baby", babyMultiplier);
        if (!biomes.isEmpty()) mod(sb, "biomes[" + (biomes.ids().size() + biomes.tags().size()) + "]", biomeMultiplier);
        if (requiresFood) sb.append(", requires food");
        return sb.toString();
    }

    private static void mod(StringBuilder sb, String name, double value) {
        if (value != 1.0) sb.append(", ").append(name).append(" x").append(num(value));
    }

    private static String num(double d) {
        return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }

    /**
     * The parsed "biomes" list. raw keeps the original strings (for encoding); ids/tags are the parsed
     * matchers; malformed and dropped only feed warnings.
     */
    public record BiomeFilter(List<String> raw, List<Identifier> ids, List<TagKey<Biome>> tags,
                              List<String> malformed, int dropped) {

        public static final BiomeFilter EMPTY = parse(List.of());
        public static final Codec<BiomeFilter> CODEC = Codec.STRING.listOf().xmap(BiomeFilter::parse, BiomeFilter::raw);

        /** Never throws: a bad entry is recorded in malformed, entries past MAX_BIOMES are counted in dropped. */
        public static BiomeFilter parse(List<String> entries) {
            List<Identifier> ids = new ArrayList<>();
            List<TagKey<Biome>> tags = new ArrayList<>();
            List<String> malformed = new ArrayList<>();
            int dropped = 0;
            for (int n = 0; n < entries.size(); n++) {
                if (n >= MAX_BIOMES) {
                    dropped++;
                    continue;
                }
                String s = entries.get(n);
                boolean isTag = s.startsWith("#");
                String body = isTag ? s.substring(1) : s;
                Identifier id = body.isEmpty() ? null : Identifier.tryParse(body);
                if (id == null || id.getPath().isEmpty()) {
                    malformed.add(s);
                } else if (isTag) {
                    tags.add(TagKey.create(Registries.BIOME, id));
                } else {
                    ids.add(id);
                }
            }
            return new BiomeFilter(List.copyOf(entries), List.copyOf(ids), List.copyOf(tags),
                    List.copyOf(malformed), dropped);
        }

        public boolean isEmpty() { return ids.isEmpty() && tags.isEmpty(); }

        public boolean matches(Holder<Biome> biome) {
            for (Identifier id : ids) {
                if (biome.is(id)) return true;
            }
            for (TagKey<Biome> tag : tags) {
                if (biome.is(tag)) return true;
            }
            return false;
        }
    }
}