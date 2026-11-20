package velora.outbreak.illness;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;

/**
 * Immutable. Always replace the whole value via setAttached.
 * Every field after ticksToSymptom is optional in the codec, so older saves load unchanged:
 *  treatment / treatedTick: the treatment class that was applied and the game time it was applied.
 *  source / infectedTick / generation (Phase 4): who infected this villager (UUID string, "" = none:
 *  command or index case), when, and how many hops from the index case (0 = index case).
 *  recent (Phase 5c): log of recently SHOWN symptoms, see SymptomLog. Read only by the Examination tool.
 * Server-side only; the attachment is persistent but never synced to clients.
 */
public record VillagerIllnessData(String illnessId, IllnessState state, int ticksLeft, int ticksToSymptom,
                                  String treatment, long treatedTick,
                                  String source, long infectedTick, int generation,
                                  List<Long> recent) {

    public static final int MAX_GENERATION = 1000;

    public static final Codec<VillagerIllnessData> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("illness").forGetter(VillagerIllnessData::illnessId),
            IllnessState.CODEC.fieldOf("state").forGetter(VillagerIllnessData::state),
            Codec.intRange(0, 10_000_000).fieldOf("ticks_left").forGetter(VillagerIllnessData::ticksLeft),
            Codec.intRange(0, 10_000_000).optionalFieldOf("ticks_to_symptom", 0).forGetter(VillagerIllnessData::ticksToSymptom),
            Codec.STRING.optionalFieldOf("treatment", "").forGetter(VillagerIllnessData::treatment),
            Codec.LONG.optionalFieldOf("treated_tick", 0L).forGetter(VillagerIllnessData::treatedTick),
            Codec.STRING.optionalFieldOf("source", "").forGetter(VillagerIllnessData::source),
            Codec.LONG.optionalFieldOf("infected_tick", 0L).forGetter(VillagerIllnessData::infectedTick),
            Codec.intRange(0, MAX_GENERATION).optionalFieldOf("generation", 0).forGetter(VillagerIllnessData::generation),
            SymptomLog.CODEC.optionalFieldOf("recent", List.of()).forGetter(VillagerIllnessData::recent)
    ).apply(i, VillagerIllnessData::new));

    /** Same as the full constructor with an empty symptom log. */
    public VillagerIllnessData(String illnessId, IllnessState state, int ticksLeft, int ticksToSymptom,
                               String treatment, long treatedTick,
                               String source, long infectedTick, int generation) {
        this(illnessId, state, ticksLeft, ticksToSymptom, treatment, treatedTick, source, infectedTick, generation, List.of());
    }

    /** New, untreated infection without a known source. */
    public VillagerIllnessData(String illnessId, IllnessState state, int ticksLeft, int ticksToSymptom) {
        this(illnessId, state, ticksLeft, ticksToSymptom, "", 0L, "", 0L, 0);
    }

    /** New infection with a source (used by IllnessManager only). */
    public VillagerIllnessData(String illnessId, IllnessState state, int ticksLeft, int ticksToSymptom,
                               String source, long infectedTick, int generation) {
        this(illnessId, state, ticksLeft, ticksToSymptom, "", 0L, source, infectedTick, generation);
    }

    /** Keeps the treatment, source and symptom records. */
    public VillagerIllnessData with(IllnessState s, int left, int toSymptom) {
        return new VillagerIllnessData(illnessId, s, left, toSymptom, treatment, treatedTick,
                source, infectedTick, generation, recent);
    }

    public VillagerIllnessData withTreatment(String treatmentClass, long gameTime) {
        return new VillagerIllnessData(illnessId, state, ticksLeft, ticksToSymptom, treatmentClass, gameTime,
                source, infectedTick, generation, recent);
    }

    public VillagerIllnessData withRecent(List<Long> newRecent) {
        return new VillagerIllnessData(illnessId, state, ticksLeft, ticksToSymptom, treatment, treatedTick,
                source, infectedTick, generation, newRecent);
    }

    /** Records one shown symptom and the time until the next one. */
    public VillagerIllnessData withSymptomShown(Symptom shown, long gameTime, int nextTicks) {
        return new VillagerIllnessData(illnessId, state, ticksLeft, nextTicks, treatment, treatedTick,
                source, infectedTick, generation, SymptomLog.add(recent, shown, gameTime));
    }
}