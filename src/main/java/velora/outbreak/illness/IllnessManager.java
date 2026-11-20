package velora.outbreak.illness;

import velora.outbreak.OutbreakMod;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import velora.outbreak.config.OutbreakConfig;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class IllnessManager {
    /** Ill villagers are processed every INTERVAL ticks. */
    public static final int INTERVAL = 20;

    /** Symptomatic villagers sneeze at the base interval; treated/recovering factors come from the definition. */
    private static final int SLOWDOWN_SYMPTOMATIC = 1;

    private static final int MAX_SYMPTOM_BASE_TICKS = 6_000_000;

    private static final Set<UUID> WARNED_UNKNOWN = new HashSet<>();

    private IllnessManager() {}

    public static void resetWarnings() { WARNED_UNKNOWN.clear(); }

    /** Command / script infection: no source villager. Same rules as every other infection. */
    public static boolean infect(Villager v, IllnessDefinition def) {
        return start(v, def, "", 0L, 0);
    }

    /**
     * Villager-to-villager infection (SpreadManager only). Hard rules, enforced here and not only in data
     * (see TransmissionRules): the illness must be able to spread, and a foodborne one only reaches a
     * villager that carries food.
     */
    public static boolean transmit(Villager target, Villager source, IllnessDefinition def, long gameTime) {
        if (!TransmissionRules.canTransmitTo(target, def)) return false;
        VillagerIllnessData sd = source.getAttached(OutbreakAttachments.ILLNESS);
        int generation = sd == null ? 1 : Math.min(sd.generation() + 1, VillagerIllnessData.MAX_GENERATION);
        return start(target, def, source.getUUID().toString(), gameTime, generation);
    }

    /** Source recorded for a natural (OnsetManager) case. */
    public static final String SOURCE_NATURAL = "natural";

    /**
     * Natural onset (OnsetManager only): no source villager, generation 0. Hard rules, enforced here and not
     * only in data: the illness must declare natural_onset, and a FOODBORNE illness (or one with
     * requires_food) only starts in a villager that carries food.
     */
    public static boolean onset(Villager v, IllnessDefinition def, long gameTime) {
        OnsetParams op = def.onset().orElse(null);
        if (op == null) return false;
        boolean needsFood = def.vector() == TransmissionVector.FOODBORNE || op.requiresFood();
        if (needsFood && !TransmissionRules.holdsFood(v)) return false;
        return start(v, def, SOURCE_NATURAL, gameTime, 0);
    }

    /** The only place that writes a NEW infection. New infections always start INCUBATING. */
    private static boolean start(Villager v, IllnessDefinition def, String source, long gameTime, int generation) {
        if (v.hasAttached(OutbreakAttachments.ILLNESS)) return false; // ill, or inside the immunity window
        v.setAttached(OutbreakAttachments.ILLNESS, new VillagerIllnessData(
                def.id(), IllnessState.INCUBATING, def.incubationTicks(), 0, source, gameTime, generation));
        IllnessTracker.track(v);
        return true;
    }

    /** @return true if the villager had illness data. */
    public static boolean cure(Villager v) {
        boolean had = v.removeAttached(OutbreakAttachments.ILLNESS) != null;
        IllnessTracker.untrack(v);
        SymptomPause.release(v);
        return had;
    }

    /** Debug: force a state with that state's full duration. */
    public static boolean setStage(Villager v, IllnessState state) {
        SymptomPause.release(v);
        VillagerIllnessData d = v.getAttached(OutbreakAttachments.ILLNESS);
        if (d == null) return false;
        IllnessDefinition def = IllnessRegistry.get(d.illnessId());
        if (def == null) return false;
        VillagerIllnessData next = d.with(state, durationFor(def, state), 0);
        // Incubating and recovered villagers must never carry an examinable symptom record.
        if (state == IllnessState.INCUBATING || state == IllnessState.RECOVERED) next = next.withRecent(List.of());
        v.setAttached(OutbreakAttachments.ILLNESS, next);
        return true;
    }

    /** Debug entry point (/outbreak treat): no treatment item recorded. */
    public static boolean beginTreatment(Villager v) {
        return beginTreatment(v, "", 0L);
    }

    /**
     * SYMPTOMATIC -> UNDER_TREATMENT. Treatment items call this after server-side validation.
     * Records which treatment class was used and when, and quiets the next symptom roll.
     * @return false if the villager is not currently SYMPTOMATIC.
     */
    public static boolean beginTreatment(Villager v, String treatment, long gameTime) {
        VillagerIllnessData d = v.getAttached(OutbreakAttachments.ILLNESS);
        if (d == null || d.state() != IllnessState.SYMPTOMATIC) return false;
        IllnessDefinition def = IllnessRegistry.get(d.illnessId());
        if (def == null) return false;
        // The treated villager does not sneeze again straight away: at least half a treated interval of calm.
        int calm = Math.max(d.ticksToSymptom(), def.symptomIntervalTicks() * def.treatedSymptomFactor() / 2);
        v.setAttached(OutbreakAttachments.ILLNESS,
                d.with(IllnessState.UNDER_TREATMENT, def.treatmentTicks(), calm).withTreatment(treatment, gameTime));
        return true;
    }

    private static int durationFor(IllnessDefinition def, IllnessState s) {
        return switch (s) {
            case INCUBATING -> def.incubationTicks();
            case SYMPTOMATIC -> def.naturalRecoveryTicks();
            case UNDER_TREATMENT -> def.treatmentTicks();
            case RECOVERING -> def.recoveryTicks();
            case RECOVERED -> def.immunityTicks();
        };
    }

    public static void tick(Villager v, ServerLevel level) {
        VillagerIllnessData d = v.getAttached(OutbreakAttachments.ILLNESS);
        if (d == null) {
            IllnessTracker.untrack(v);
            return;
        }

        IllnessDefinition def = IllnessRegistry.get(d.illnessId());
        if (def == null) {
            // Never delete data we don't understand (a datapack may be temporarily missing). Pause and warn once.
            if (WARNED_UNKNOWN.add(v.getUUID())) {
                OutbreakMod.LOGGER.warn("Villager {} has illness '{}' which is not currently defined; "
                        + "keeping its data and pausing it.", v.getUUID(), d.illnessId());
            }
            return;
        }

        int left = Math.max(0, d.ticksLeft() - INTERVAL);

        switch (d.state()) {
            case INCUBATING -> {
                if (left > 0) set(v, d.with(IllnessState.INCUBATING, left, 0));
                else set(v, d.with(IllnessState.SYMPTOMATIC, def.naturalRecoveryTicks(), 0));
            }
            case SYMPTOMATIC -> {
                VillagerIllnessData r = rollSymptoms(v, level, def, d, SLOWDOWN_SYMPTOMATIC);
                int next = r.ticksToSymptom();
                if (def.naturalRecoveryTicks() == 0) {
                    set(v, r.with(IllnessState.SYMPTOMATIC, 0, next)); // chronic: only treatment ends it
                } else if (left > 0) {
                    set(v, r.with(IllnessState.SYMPTOMATIC, left, next));
                } else {
                    set(v, r.with(IllnessState.RECOVERING, def.recoveryTicks(), next)); // slow natural recovery
                }
            }
            case UNDER_TREATMENT -> {
                VillagerIllnessData r = rollSymptoms(v, level, def, d, def.treatedSymptomFactor());
                int next = r.ticksToSymptom();
                if (left > 0) set(v, r.with(IllnessState.UNDER_TREATMENT, left, next));
                else set(v, r.with(IllnessState.RECOVERING, def.recoveryTicks(), next));
            }
            case RECOVERING -> {
                VillagerIllnessData r = rollSymptoms(v, level, def, d, def.recoveringSymptomFactor());
                int next = r.ticksToSymptom();
                if (left > 0) set(v, r.with(IllnessState.RECOVERING, left, next));
                else {
                    // Recovered villagers show nothing to the Examination tool: drop the symptom record.
                    set(v, r.with(IllnessState.RECOVERED, def.immunityTicks(), 0).withRecent(List.of()));
                    SymptomManager.recovered(v, level);
                }
            }
            case RECOVERED -> {
                if (left > 0) {
                    set(v, d.with(IllnessState.RECOVERED, left, 0));
                } else {
                    v.removeAttached(OutbreakAttachments.ILLNESS);
                    IllnessTracker.untrack(v);
                }
            }
        }
    }

    private static void set(Villager v, VillagerIllnessData data) {
        v.setAttached(OutbreakAttachments.ILLNESS, data);
    }

    /**
     * Counts down to the next symptom; shows one and re-rolls when it reaches zero. Returns the villager's
     * data with the new timer and, if a symptom was really shown, that symptom added to the recent log
     * (a symptom skipped by the per-tick budget is not logged: nobody could have seen it).
     */
    private static VillagerIllnessData rollSymptoms(Villager v, ServerLevel level, IllnessDefinition def,
                                                    VillagerIllnessData d, int slowdown) {
        int remaining = Math.max(0, d.ticksToSymptom() - INTERVAL);
        if (remaining > 0) return d.with(d.state(), d.ticksLeft(), remaining);
        Symptom shown = SymptomManager.symptom(v, level, def);
        // Global frequency multiplier from the config (>1 = more often). Long math and a cap keep the next
        // timer (up to 1.5 x base) inside the 10,000,000-tick limit of the saved data.
        double scaled = (double) def.symptomIntervalTicks() * slowdown / OutbreakConfig.symptoms().frequencyMultiplier();
        int base = (int) Math.min(MAX_SYMPTOM_BASE_TICKS, Math.max(INTERVAL, Math.round(scaled)));
        int next = base / 2 + level.getRandom().nextInt(Math.max(1, base));
        return shown == null ? d.with(d.state(), d.ticksLeft(), next) : d.withSymptomShown(shown, level.getGameTime(), next);
    }
}