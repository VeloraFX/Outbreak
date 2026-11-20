package velora.outbreak.illness;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.npc.villager.Villager;
import velora.outbreak.config.OutbreakConfig;

import java.util.List;

/**
 * Visible symptoms. Everything here uses vanilla-synced effects only (head shake, particles, sounds),
 * so no client code is needed. Effects are bounded: a few particles and one quiet sound each.
 */
public final class SymptomManager {
    private static final DustParticleOptions SNEEZE_GREEN = new DustParticleOptions(0x7FB82E, 1.0f);

    /** Per-server-tick effect budget (limit from the config). Excess effects are skipped, never queued. */
    private static final class Budget {
        private long tick = -1;
        private int used = 0;

        boolean take(long now, int limit) {
            if (now != tick) {
                tick = now;
                used = 0;
            }
            if (used >= limit) return false;
            used++;
            return true;
        }
    }

    private static final Budget SYMPTOMS = new Budget();
    private static final Budget RECOVERY = new Budget();

    /**
     * The illness state machine's symptom effect: budgeted per tick. Picks one of the illness' symptoms
     * at random. Server-only.
     */
    public static Symptom symptom(Villager v, ServerLevel level, IllnessDefinition def) {
        if (!SYMPTOMS.take(level.getServer().getTickCount(), OutbreakConfig.symptoms().maxSymptomEffectsPerTick())) return null;
        List<Symptom> list = def.symptomList();
        Symptom s = list.size() == 1 ? list.get(0) : list.get(level.getRandom().nextInt(list.size()));
        show(v, level, s);
        return s;
    }

    /** Not budgeted (used directly by /outbreak sneeze). */
    public static void sneeze(Villager v, ServerLevel level) { show(v, level, Symptom.SNEEZE); }

    private static void show(Villager v, ServerLevel level, Symptom s) {
        switch (s) {
            case SNEEZE -> {
                v.setUnhappyCounter(30); // vanilla head shake, synced by the game, expires on its own
                level.playSound(null, v.blockPosition(), SoundEvents.PANDA_SNEEZE, SoundSource.NEUTRAL, 0.6f, 1.4f);
                level.sendParticles(SNEEZE_GREEN, v.getX(), v.getEyeY(), v.getZ(), 8, 0.2, 0.1, 0.2, 0.0);
            }
            case COUGH -> {
                v.setUnhappyCounter(20);
                level.playSound(null, v.blockPosition(), SoundEvents.VILLAGER_NO, SoundSource.NEUTRAL, 0.35f, 1.6f);
                level.sendParticles(ParticleTypes.POOF, v.getX(), v.getEyeY() - 0.1, v.getZ(), 3, 0.1, 0.05, 0.1, 0.01);
            }
            case SHIVER -> // visual only: a few cold flakes around the body
                    level.sendParticles(ParticleTypes.SNOWFLAKE, v.getX(), v.getY() + 1.0, v.getZ(), 5, 0.3, 0.4, 0.3, 0.02);
            case STOMACH_CLUTCH -> {
                level.playSound(null, v.blockPosition(), SoundEvents.VILLAGER_HURT, SoundSource.NEUTRAL, 0.4f, 0.6f);
                level.sendParticles(ParticleTypes.ITEM_SLIME, v.getX(), v.getY() + 0.8, v.getZ(), 4, 0.25, 0.2, 0.25, 0.0);
            }
        }
        SymptomPause.start(v, level); // brief stand-still so the symptom reads clearly; always ends on its own
    }

    /** Player-triggered, so never budgeted: a few happy particles and a soft trade sound. */
    public static void treatmentStarted(Villager v, ServerLevel level) {
        level.playSound(null, v.blockPosition(), SoundEvents.VILLAGER_TRADE, SoundSource.NEUTRAL, 0.8f, 1.0f);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, v.getX(), v.getY() + 1.2, v.getZ(), 5, 0.3, 0.4, 0.3, 0.0);
    }

    /** Full recovery (RECOVERING -> RECOVERED). Bounded per server tick. */
    public static void recovered(Villager v, ServerLevel level) {
        if (!RECOVERY.take(level.getServer().getTickCount(), OutbreakConfig.symptoms().maxRecoveryEffectsPerTick())) return;
        level.playSound(null, v.blockPosition(), SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 0.7f, 1.2f);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, v.getX(), v.getY() + 1.2, v.getZ(), 6, 0.35, 0.5, 0.35, 0.0);
    }

    private SymptomManager() {}
}