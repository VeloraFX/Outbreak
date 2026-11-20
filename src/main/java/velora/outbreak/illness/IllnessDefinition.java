package velora.outbreak.illness;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Data-driven illness. The id is NOT part of the JSON; it comes from the file name
 * (the codec fills in "" and the loader calls withId).
 *
 * naturalRecoveryTicks: how long SYMPTOMATIC lasts untreated before slowly recovering.
 *                       0 = never self-resolves, stays symptomatic until treated.
 * treatment:            class of treatment item that works ("supportive", "antihistamine", "rehydration").
 * treatedSymptomFactor / recoveringSymptomFactor: symptom interval multipliers while UNDER_TREATMENT /
 *                       RECOVERING (SYMPTOMATIC is always x1). Higher = fewer visible symptoms.
 * spread:               optional "spread" object, see SpreadParams. Only used if canSpread().
 * spreadDeclared:       true if the JSON actually contained a "spread" object (only used for warnings).
 * symptoms:             optional list of symptom keys (see Symptom); default ["sneeze"]. Unknown keys are
 *                       ignored with a warning; if none are valid the villager sneezes.
 * onset:                optional "natural_onset" object, see OnsetParams. Empty = never starts on its own.
 *                       (Optional rather than a default + "declared" flag like spread: an absent block has
 *                       no meaningful default, so callers cannot accidentally use one.)
 */
public record IllnessDefinition(
        String id,
        TransmissionVector vector,
        boolean contagious,
        int incubationTicks,
        int naturalRecoveryTicks,
        int treatmentTicks,
        int recoveryTicks,
        int immunityTicks,
        int symptomIntervalTicks,
        String treatment,
        int treatedSymptomFactor,
        int recoveringSymptomFactor,
        SpreadParams spread,
        boolean spreadDeclared,
        List<String> symptoms,
        Optional<OnsetParams> onset) {

    public static final String TREATMENT_SUPPORTIVE = "supportive";
    public static final String TREATMENT_ANTIHISTAMINE = "antihistamine";
    public static final String TREATMENT_REHYDRATION = "rehydration";
    /** Every treatment class an item exists for. */
    public static final List<String> KNOWN_TREATMENTS =
            List.of(TREATMENT_SUPPORTIVE, TREATMENT_ANTIHISTAMINE, TREATMENT_REHYDRATION);
    public static final int DEFAULT_TREATED_FACTOR = 3;
    public static final int DEFAULT_RECOVERING_FACTOR = 6;
    public static final List<String> DEFAULT_SYMPTOMS = List.of(Symptom.SNEEZE.key());

    private static final int MAX_TICKS = 24_000_000; // 1000 in-game days
    private static final Codec<Integer> TICKS = Codec.intRange(0, MAX_TICKS);
    private static final Codec<Integer> FACTOR = Codec.intRange(1, 20);
    private static final Codec<Integer> INTERVAL = Codec.intRange(20, MAX_TICKS); // sim runs every 20 ticks

    /**
     * Every top-level JSON key CODEC reads, in codec order. Used only by IllnessLoader's unknown-key
     * warning; keep it in step with the optionalFieldOf names below.
     */
    public static final List<String> JSON_KEYS = List.of(
            "vector", "contagious", "incubation_ticks", "natural_recovery_ticks", "treatment_ticks",
            "recovery_ticks", "immunity_ticks", "symptom_interval_ticks", "treatment",
            "treated_symptom_factor", "recovering_symptom_factor", "spread", "symptoms", "natural_onset");

    public static final Codec<IllnessDefinition> CODEC = RecordCodecBuilder.create(i -> i.group(
            TransmissionVector.CODEC.optionalFieldOf("vector", TransmissionVector.NONE).forGetter(IllnessDefinition::vector),
            Codec.BOOL.optionalFieldOf("contagious", false).forGetter(IllnessDefinition::contagious),
            TICKS.optionalFieldOf("incubation_ticks", 2400).forGetter(IllnessDefinition::incubationTicks),
            TICKS.optionalFieldOf("natural_recovery_ticks", 0).forGetter(IllnessDefinition::naturalRecoveryTicks),
            TICKS.optionalFieldOf("treatment_ticks", 6000).forGetter(IllnessDefinition::treatmentTicks),
            TICKS.optionalFieldOf("recovery_ticks", 6000).forGetter(IllnessDefinition::recoveryTicks),
            TICKS.optionalFieldOf("immunity_ticks", 24000).forGetter(IllnessDefinition::immunityTicks),
            INTERVAL.optionalFieldOf("symptom_interval_ticks", 300).forGetter(IllnessDefinition::symptomIntervalTicks),
            Codec.STRING.optionalFieldOf("treatment", TREATMENT_SUPPORTIVE).forGetter(IllnessDefinition::treatment),
            FACTOR.optionalFieldOf("treated_symptom_factor", DEFAULT_TREATED_FACTOR).forGetter(IllnessDefinition::treatedSymptomFactor),
            FACTOR.optionalFieldOf("recovering_symptom_factor", DEFAULT_RECOVERING_FACTOR).forGetter(IllnessDefinition::recoveringSymptomFactor),
            SpreadParams.CODEC.optionalFieldOf("spread").forGetter(d -> d.spreadDeclared() ? Optional.of(d.spread()) : Optional.empty()),
            Codec.STRING.listOf().optionalFieldOf("symptoms", DEFAULT_SYMPTOMS).forGetter(IllnessDefinition::symptoms),
            OnsetParams.CODEC.optionalFieldOf("natural_onset").forGetter(IllnessDefinition::onset)
    ).apply(i, (vector, contagious, incubation, natural, treatmentTicks, recovery, immunity, interval,
                treatment, treatedFactor, recoveringFactor, spreadBlock, symptoms, onsetBlock) ->
            new IllnessDefinition("", vector, contagious, incubation, natural, treatmentTicks, recovery, immunity,
                    interval, treatment, treatedFactor, recoveringFactor,
                    spreadBlock.orElse(SpreadParams.DEFAULT), spreadBlock.isPresent(), List.copyOf(symptoms),
                    onsetBlock)));

    public IllnessDefinition withId(String newId) {
        return new IllnessDefinition(newId, vector, contagious, incubationTicks, naturalRecoveryTicks,
                treatmentTicks, recoveryTicks, immunityTicks, symptomIntervalTicks,
                treatment, treatedSymptomFactor, recoveringSymptomFactor, spread, spreadDeclared, symptoms, onset);
    }

    /** The symptoms this illness shows, resolved (unknown keys dropped; never empty). */
    public List<Symptom> symptomList() { return Symptom.parse(symptoms); }

    /**
     * Can this illness' VECTOR pass between villagers at all? AIRBORNE and FOODBORNE do; NONE never does.
     * (Per-vector extra conditions live in TransmissionRules.)
     */
    public boolean vectorCanSpread() {
        return vector == TransmissionVector.AIRBORNE || vector == TransmissionVector.FOODBORNE;
    }

    /**
     * The one rule that decides whether this illness can pass between villagers.
     * "contagious": false always wins, whatever the vector or spread block says.
     */
    public boolean canSpread() {
        return contagious && vectorCanSpread();
    }

    /** Problems an author should know about. Pure (no logging); the loader logs them once per data load. */
    public List<String> warnings() {
        List<String> w = new ArrayList<>();
        if (!KNOWN_TREATMENTS.contains(treatment)) {
            w.add("unknown treatment class '" + treatment + "' (known: " + String.join(", ", KNOWN_TREATMENTS)
                    + "); no item can ever treat it");
        }
        if (contagious && !vectorCanSpread()) {
            w.add("contagious is true but vector '" + vector.getSerializedName()
                    + "' cannot spread between villagers (only 'airborne' and 'foodborne' do); it will never spread");
        }
        if (!contagious && vector != TransmissionVector.NONE) {
            w.add("vector '" + vector.getSerializedName() + "' is ignored because contagious is false");
        }
        if (spreadDeclared && !canSpread()) {
            w.add("has a 'spread' block but cannot spread (needs contagious=true and a spreading vector); the block is ignored");
        }
        if (symptoms.isEmpty()) {
            w.add("'symptoms' is empty; villagers will sneeze (known: " + Symptom.knownNames() + ")");
        }
        for (String s : symptoms) {
            if (!Symptom.isKnown(s)) {
                w.add("unknown symptom '" + s + "' (known: " + Symptom.knownNames() + "); it is ignored");
            }
        }
        onset.ifPresent(o -> w.addAll(o.warnings(vector == TransmissionVector.FOODBORNE)));
        return w;
    }
}