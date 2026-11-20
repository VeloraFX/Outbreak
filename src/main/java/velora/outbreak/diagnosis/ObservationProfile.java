package velora.outbreak.diagnosis;

import velora.outbreak.illness.IllnessDefinition;
import velora.outbreak.illness.IllnessRegistry;
import velora.outbreak.illness.TransmissionVector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * What a sample looks like per feature, and which evidence a given diagnosis requires.
 * New illnesses plug in by adding a KNOWN entry.
 * Unknown illness ids fall back to allergy-like (not contagious), food-like (foodborne) or cold-like.
 */
public final class ObservationProfile {
    public static final String HEALTHY_CHOICE = "healthy";
    public static final String INCONCLUSIVE_CHOICE = "inconclusive";

    private final int[] levels = new int[Feature.values().length];

    private ObservationProfile(int density, int cellType, int particles, int inflammation) {
        levels[Feature.CELL_DENSITY.ordinal()] = density;
        levels[Feature.CELL_TYPE.ordinal()] = cellType;
        levels[Feature.PARTICLES.ordinal()] = particles;
        levels[Feature.INFLAMMATION.ordinal()] = inflammation;
    }

    public int level(Feature f) { return levels[f.ordinal()]; }

    private static final ObservationProfile HEALTHY = new ObservationProfile(0, 0, 0, 0);
    private static final ObservationProfile TOO_EARLY = new ObservationProfile(1, 0, 0, 0);
    private static final ObservationProfile COLD = new ObservationProfile(1, 1, 1, 1);
    private static final ObservationProfile FLU = new ObservationProfile(2, 1, 1, 2);
    private static final ObservationProfile ALLERGY = new ObservationProfile(1, 2, 2, 0);
    /** Bacterial: neutrophils (cell type 3) and rod-shaped bacteria (particles 3). */
    private static final ObservationProfile FOOD = new ObservationProfile(2, 3, 3, 1);

    private static final Map<String, ObservationProfile> KNOWN = Map.of(
            "common_cold", COLD,
            "seasonal_allergies", ALLERGY,
            "influenza", FLU,
            "food_poisoning", FOOD);

    public static ObservationProfile forTruth(SampleTruth t) {
        return switch (t.kind()) {
            case HEALTHY -> HEALTHY;
            case TOO_EARLY -> TOO_EARLY;
            case ILL -> forIllness(t.illnessId());
        };
    }

    private static ObservationProfile forIllness(String id) {
        ObservationProfile known = KNOWN.get(id);
        if (known != null) return known;
        IllnessDefinition def = IllnessRegistry.get(id);
        if (def == null) return COLD;
        if (!def.contagious()) return ALLERGY;
        return def.vector() == TransmissionVector.FOODBORNE ? FOOD : COLD;
    }

    /**
     * Evidence needed before a diagnosis can be submitted. Depends only on the CHOSEN diagnosis,
     * never on the truth, so the server's reply cannot be used to probe the answer.
     */
    public static EnumSet<Feature> requiredFor(String choice) {
        if (HEALTHY_CHOICE.equals(choice)) {
            return EnumSet.of(Feature.CELL_DENSITY, Feature.CELL_TYPE, Feature.PARTICLES);
        }
        if (INCONCLUSIVE_CHOICE.equals(choice)) return EnumSet.allOf(Feature.class);
        ObservationProfile p = forIllness(choice);
        return (p == ALLERGY || p == FOOD)
                ? EnumSet.of(Feature.CELL_TYPE, Feature.PARTICLES)
                : EnumSet.of(Feature.CELL_TYPE, Feature.INFLAMMATION); // cold vs flu needs the zoom-3 marker
    }

    public static String expectedChoice(SampleTruth t) {
        return switch (t.kind()) {
            case HEALTHY -> HEALTHY_CHOICE;
            case TOO_EARLY -> INCONCLUSIVE_CHOICE;
            case ILL -> t.illnessId();
        };
    }

    public static List<String> availableChoices() {
        List<String> ids = new ArrayList<>(IllnessRegistry.ids());
        Collections.sort(ids);
        List<String> out = new ArrayList<>();
        out.add(HEALTHY_CHOICE);
        out.addAll(ids);
        out.add(INCONCLUSIVE_CHOICE);
        return out;
    }

    public static boolean isValidChoice(String choice) {
        return choice != null && availableChoices().contains(choice);
    }
}