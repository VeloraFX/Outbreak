package velora.outbreak.illness;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Built-ins are always present as a fallback; loaded JSON overlays them on every data reload.
 * KEEP EACH BUILT-IN IN SYNC with data/outbreak/outbreak/illness/<id>.json.
 */
public final class IllnessRegistry {

    public static final IllnessDefinition COMMON_COLD = new IllnessDefinition(
            "common_cold", TransmissionVector.AIRBORNE, true,
            2400, 72000, 6000, 6000, 96000, 300,
            IllnessDefinition.TREATMENT_SUPPORTIVE, 3, 6,
            new SpreadParams(6.0, 0.002, 200, 0.0, 1.0, 0.2, 0.1), true,
            List.of("sneeze", "cough"),
            Optional.of(new OnsetParams(0.0003, 2.5, 1.0, 1.0, 2.0, OnsetParams.DEFAULT_COLD_BELOW,
                    1.0, OnsetParams.DEFAULT_WARM_FROM, 1.0, 1.0, OnsetParams.BiomeFilter.EMPTY, false)));

    /** Not contagious: its spread block is ignored (IllnessDefinition.canSpread() is false). */
    public static final IllnessDefinition SEASONAL_ALLERGIES = new IllnessDefinition(
            "seasonal_allergies", TransmissionVector.NONE, false,
            200, 72000, 4000, 3000, 6000, 500,
            IllnessDefinition.TREATMENT_ANTIHISTAMINE, 3, 6,
            SpreadParams.DEFAULT, false,
            IllnessDefinition.DEFAULT_SYMPTOMS,
            Optional.of(new OnsetParams(0.00015, 0.5, 0.5, 1.5, 0.25, OnsetParams.DEFAULT_COLD_BELOW,
                    1.0, OnsetParams.DEFAULT_WARM_FROM, 1.0, 2.0,
                    OnsetParams.BiomeFilter.parse(List.of("minecraft:plains", "minecraft:sunflower_plains",
                            "minecraft:meadow", "minecraft:flower_forest", "minecraft:cherry_grove")),
                    false)));

    /** Lasts longer than the cold, reaches further and spreads a little before symptoms appear. */
    public static final IllnessDefinition INFLUENZA = new IllnessDefinition(
            "influenza", TransmissionVector.AIRBORNE, true,
            1800, 96000, 9000, 9000, 120000, 200,
            IllnessDefinition.TREATMENT_SUPPORTIVE, 3, 4,
            new SpreadParams(8.0, 0.001, 200, 0.1, 1.0, 0.25, 0.15), true,
            List.of("cough", "shiver", "sneeze"),
            Optional.of(new OnsetParams(0.00005, 1.25, 1.5, 1.0, 1.5, OnsetParams.DEFAULT_COLD_BELOW,
                    1.0, OnsetParams.DEFAULT_WARM_FROM, 1.0, 1.0, OnsetParams.BiomeFilter.EMPTY, false)));

    /** Spreads through shared food: short range, and only to villagers carrying food. */
    public static final IllnessDefinition FOOD_POISONING = new IllnessDefinition(
            "food_poisoning", TransmissionVector.FOODBORNE, true,
            1200, 48000, 4000, 3000, 24000, 250,
            IllnessDefinition.TREATMENT_REHYDRATION, 3, 6,
            new SpreadParams(4.0, 0.012, 200, 0.0, 1.0, 0.1, 0.05), true,
            List.of("stomach_clutch", "shiver"),
            Optional.of(new OnsetParams(0.0002, 1.0, 1.0, 1.0, 1.0, OnsetParams.DEFAULT_COLD_BELOW,
                    1.5, OnsetParams.DEFAULT_WARM_FROM, 1.0, 1.0, OnsetParams.BiomeFilter.EMPTY, true)));

    private static final Map<String, IllnessDefinition> BUILT_INS = builtIns();

    /** Replaced wholesale (never mutated), so readers on any thread see a consistent map. */
    private static volatile Map<String, IllnessDefinition> current = BUILT_INS;

    /** Authoring warnings per illness id from the last data load (see IllnessDefinition.warnings()). */
    private static volatile Map<String, List<String>> warnings = Map.of();

    private static Map<String, IllnessDefinition> builtIns() {
        Map<String, IllnessDefinition> m = new LinkedHashMap<>();
        m.put(COMMON_COLD.id(), COMMON_COLD);
        m.put(SEASONAL_ALLERGIES.id(), SEASONAL_ALLERGIES);
        m.put(INFLUENZA.id(), INFLUENZA);
        m.put(FOOD_POISONING.id(), FOOD_POISONING);
        return Collections.unmodifiableMap(m);
    }

    public static IllnessDefinition get(String id) { return current.get(id); }
    public static Collection<String> ids() { return current.keySet(); }
    public static Collection<IllnessDefinition> all() { return current.values(); }
    public static List<String> warnings(String id) { return warnings.getOrDefault(id, List.of()); }

    /** Called by IllnessLoader after a data reload. Built-ins fill any gaps. */
    public static void replaceLoaded(Map<String, IllnessDefinition> loaded, Map<String, List<String>> loadWarnings) {
        warnings = Map.copyOf(loadWarnings);
        Map<String, IllnessDefinition> merged = new LinkedHashMap<>(BUILT_INS);
        merged.putAll(loaded);
        current = Collections.unmodifiableMap(merged);
    }
}