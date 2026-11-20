package velora.outbreak.illness;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.npc.villager.Villager;

import java.util.List;

/**
 * Villager-to-villager spread. Server thread only.
 *
 * Cost model (all bounds in SpreadConfig):
 *  - Sources come from IllnessTracker (loaded ill villagers only); no entity scans of the world.
 *  - One pass per second. Each source is "due" in exactly one pass per its interval, chosen by its UUID
 *    hash, so the work is spread evenly across passes and no per-villager timer has to be stored
 *    (nothing to save, nothing to repair after a reload).
 *  - At most MAX_SOURCES_PER_PASS sources run per pass, each does one small bounding-box query and looks
 *    at no more than MAX_CANDIDATES_PER_SOURCE villagers, and a pass infects at most
 *    MAX_INFECTIONS_PER_PASS villagers (and a source at most one per attempt).
 *
 * Who spreads: the source's own state sets how contagious it is (SpreadParams.factorFor). A villager
 * that was just infected is INCUBATING, which is 0 by default, so it cannot pass the illness on
 * until it shows symptoms. Unloaded or non-entity-ticking villagers neither spread nor catch anything.
 */
public final class SpreadManager {
    private SpreadManager() {}

    /** Result of one pass (for /outbreak spread status and /outbreak spread pass). */
    public record PassStats(long tick, boolean forced, int dueSources, int processedSources,
                            int skippedByCap, int candidatesExamined, int infections) {}

    private static PassStats last = null;
    private static long totalInfections = 0;

    public static PassStats lastPass() { return last; }
    public static long totalInfections() { return totalInfections; }

    public static void init() {
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            SpreadConfig.resetOverrides();
            last = null;
            totalInfections = 0;
        });
    }

    /** END_SERVER_TICK listener. Cheap early-outs first. */
    public static void tick(MinecraftServer server) {
        if (!SpreadConfig.enabled()) return;
        if (server.getTickCount() % SpreadConfig.passIntervalTicks() != SpreadConfig.passTickOffset()) return;
        runPass(server, false);
    }

    /**
     * One spread pass. forceAll (debug) ignores the per-source schedule so every eligible source
     * makes an attempt now; all the other caps still apply.
     */
    public static PassStats runPass(MinecraftServer server, boolean forceAll) {
        long now = server.getTickCount();
        int slot = server.getTickCount() / SpreadConfig.passIntervalTicks();
        int due = 0, processed = 0, skipped = 0, infections = 0;
        int[] candidates = {0};

        for (Villager v : IllnessTracker.snapshot()) {
            if (infections >= SpreadConfig.maxInfectionsPerPass()) break;
            if (v.isRemoved() || !v.isAlive()) continue;
            if (!(v.level() instanceof ServerLevel level)) continue;

            VillagerIllnessData d = v.getAttached(OutbreakAttachments.ILLNESS);
            if (d == null) continue;
            IllnessDefinition def = IllnessRegistry.get(d.illnessId());
            if (def == null || !def.canSpread()) continue; // unknown illness, or may never spread
            SpreadParams sp = def.spread();
            double factor = sp.factorFor(d.state());
            if (factor <= 0.0) continue;

            if (!forceAll) {
                int slots = Math.max(1, sp.intervalTicks() / SpreadConfig.passIntervalTicks());
                if (Math.floorMod(slot + v.getUUID().hashCode(), slots) != 0) continue;
            }
            if (!level.isPositionEntityTicking(v.blockPosition())) continue;

            due++;
            if (processed >= SpreadConfig.maxSourcesPerPass()) {
                skipped++;
                continue;
            }
            processed++;
            infections += expose(level, v, def, sp, factor, level.getGameTime(), candidates);
        }

        PassStats stats = new PassStats(now, forceAll, due, processed, skipped, candidates[0], infections);
        last = stats;
        totalInfections += infections;
        return stats;
    }

    /** One exposure attempt of one source. @return 1 if it infected a neighbour, else 0. */
    private static int expose(ServerLevel level, Villager source, IllnessDefinition def, SpreadParams sp,
                              double factor, long gameTime, int[] candidates) {
        double range = Math.min(SpreadConfig.maxEffectiveRange(), sp.rangeBlocks() * SpreadConfig.rangeMultiplier());
        double rangeSq = range * range;
        List<Villager> near = level.getEntitiesOfClass(Villager.class, source.getBoundingBox().inflate(range));
        int n = near.size();
        if (n <= 1) return 0; // only the source itself

        RandomSource random = level.getRandom();
        int start = random.nextInt(n); // random start so no neighbour is always favoured when the cap bites
        int limit = Math.min(n, SpreadConfig.maxCandidatesPerSource());
        double base = sp.chance() * SpreadConfig.chanceMultiplier() * factor;

        for (int k = 0; k < limit; k++) {
            Villager c = near.get((start + k) % n);
            candidates[0]++;
            if (c == source || c.isRemoved() || !c.isAlive()) continue;
            if (c.hasAttached(OutbreakAttachments.ILLNESS)) continue; // ill or immune: nothing to catch
            double distSq = source.distanceToSqr(c);
            if (distSq > rangeSq) continue;
            if (!level.isPositionEntityTicking(c.blockPosition())) continue;

            // Closer contact is riskier: weight 1.0 right next to the source, EDGE weight at the range limit.
            double weight = 1.0 - (1.0 - SpreadConfig.edgeProximityWeight()) * (Math.sqrt(distSq) / range);
            double p = Math.min(1.0, base * weight);
            if (random.nextDouble() >= p) continue;

            if (IllnessManager.transmit(c, source, def, gameTime)) return 1; // at most one per attempt
        }
        return 0;
    }
}