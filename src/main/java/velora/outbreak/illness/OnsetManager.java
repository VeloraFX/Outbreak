package velora.outbreak.illness;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.AABB;
import velora.outbreak.config.OutbreakConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Natural onset: villagers near players sometimes fall ill on their own. Server thread only.
 *
 * Once per onset.check_interval_ticks (counted on the server tick counter, so startup_delay_ticks counts
 * from server start), after the early-outs (disabled, grace period on the overworld game time, startup
 * delay, global cooldown, global ceiling, no illness with natural_onset, no players):
 *  - up to max_players_per_check online non-spectator players, round-robin from an in-memory cursor;
 *  - a player within radius_blocks (horizontal, same level) of a player already handled this check is
 *    skipped (one village = one set of rolls);
 *  - an area within area_cooldown_radius of an active area cooldown is skipped;
 *  - one capped box query (+-radius_blocks, +-vertical_blocks, at most max_candidates_per_player villagers);
 *    the area is skipped if max_ill_nearby or more of them carry illness data (ill or immune);
 *  - each healthy, entity-ticking candidate (random start) gets one roll per illness with natural_onset
 *    (random illness order), at OnsetParams.effectiveChance; the area stops at its first case.
 * A case arms the global cooldown and adds an area cooldown; the check stops at max_new_cases_per_check.
 *
 * Silent by design: no chat, no HUD, no log line per case. Nothing is saved; all state below is in memory,
 * cleared on SERVER_STOPPED, and holds no villager references between checks.
 */
public final class OnsetManager {
    private OnsetManager() {}

    /** Upper bound on remembered area cooldowns; expired ones are pruned first, then the oldest is dropped. */
    public static final int MAX_AREA_COOLDOWNS = 64;

    /** Why a whole check did nothing (NONE = it ran its areas). */
    public enum Skip { NONE, DISABLED, GRACE, STARTUP_DELAY, GLOBAL_COOLDOWN, GLOBAL_CEILING, NO_ONSET_ILLNESSES, NO_PLAYERS }

    /**
     * Result of one check (for /outbreak onset status|check). tick = server tick count,
     * gameTime = overworld game time. rejected = rolls that succeeded but IllnessManager.onset refused
     * (e.g. a foodborne illness and no food).
     */
    public record CheckStats(long tick, long gameTime, boolean forced, Skip skip,
                             int playersEligible, int playersConsidered, int areas,
                             int skippedDeduped, int skippedCeiling, int skippedCooldown,
                             int candidates, int rolls, int rejected, int cases) {}

    /** An active area cooldown: plain values only (level key and position), never an entity. */
    public record AreaCooldown(ResourceKey<Level> level, double x, double z, long expiresAt) {}

    private static long nextCheckTick = 0;
    private static int playerCursor = 0;
    private static long globalCooldownUntil = 0L;
    private static final List<AreaCooldown> AREA_COOLDOWNS = new ArrayList<>();
    private static CheckStats last = null;
    private static long totalCases = 0;

    public static void init() {
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> reset());
    }

    // ------------------------------------------------------------------ read accessors (/outbreak onset status)

    public static CheckStats lastCheck() { return last; }
    public static long totalCases() { return totalCases; }
    /** Server tick count at or after which the next scheduled check runs. */
    public static long nextCheckTick() { return nextCheckTick; }
    /** Remaining global cooldown in game ticks (0 if none). */
    public static long globalCooldownRemaining(long gameTime) { return Math.max(0L, globalCooldownUntil - gameTime); }
    /** Copy of the area cooldowns still active at gameTime. */
    public static List<AreaCooldown> activeAreaCooldowns(long gameTime) {
        List<AreaCooldown> out = new ArrayList<>();
        for (AreaCooldown a : AREA_COOLDOWNS) {
            if (a.expiresAt() > gameTime) out.add(a);
        }
        return out;
    }

    /** Clears cooldowns, stats, schedule and cursor (SERVER_STOPPED). */
    public static void reset() {
        nextCheckTick = 0;
        playerCursor = 0;
        clearCooldownsAndStats();
    }

    /**
     * Clears cooldowns, the last-check stats and the case total, but keeps the schedule and the player cursor
     * (/outbreak onset reset): the next scheduled check stays where it was instead of running on the next tick.
     */
    public static void clearCooldownsAndStats() {
        globalCooldownUntil = 0L;
        AREA_COOLDOWNS.clear();
        last = null;
        totalCases = 0;
    }

    // ------------------------------------------------------------------ schedule

    /** END_SERVER_TICK listener. Cheap early-outs first. */
    public static void tick(MinecraftServer server) {
        OutbreakConfig.Onset cfg = OutbreakConfig.onset();
        if (!cfg.enabled()) return;
        long now = server.getTickCount();
        // A reload may have shortened the interval: never wait longer than one interval.
        if (nextCheckTick - now > cfg.checkIntervalTicks()) nextCheckTick = now + cfg.checkIntervalTicks();
        if (now < nextCheckTick) return;
        nextCheckTick = now + cfg.checkIntervalTicks();
        runCheck(server, cfg, false);
    }

    /** One normal check now, all rules applied (/outbreak onset check). */
    public static CheckStats runCheck(MinecraftServer server) {
        return runCheck(server, OutbreakConfig.onset(), true);
    }

    // ------------------------------------------------------------------ check

    private static CheckStats runCheck(MinecraftServer server, OutbreakConfig.Onset cfg, boolean forced) {
        long tick = server.getTickCount();
        long now = server.overworld().getGameTime();

        Skip skip = Skip.NONE;
        if (!cfg.enabled()) skip = Skip.DISABLED;
        else if (now < cfg.graceTicks()) skip = Skip.GRACE;
        else if (tick < cfg.startupDelayTicks()) skip = Skip.STARTUP_DELAY;
        else if (now < globalCooldownUntil) skip = Skip.GLOBAL_COOLDOWN;
        else if (cfg.maxTrackedGlobal() > 0 && IllnessTracker.size() >= cfg.maxTrackedGlobal()) skip = Skip.GLOBAL_CEILING;

        List<IllnessDefinition> illnesses = new ArrayList<>();
        if (skip == Skip.NONE) {
            for (IllnessDefinition def : IllnessRegistry.all()) {
                OnsetParams op = def.onset().orElse(null);
                if (op != null && op.chance() > 0.0) illnesses.add(def);
            }
            if (illnesses.isEmpty()) skip = Skip.NO_ONSET_ILLNESSES;
        }

        List<ServerPlayer> players = new ArrayList<>();
        if (skip == Skip.NONE) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (!p.isSpectator()) players.add(p);
            }
            if (players.isEmpty()) skip = Skip.NO_PLAYERS;
        }
        if (skip != Skip.NONE) {
            return remember(new CheckStats(tick, now, forced, skip, players.size(), 0, 0, 0, 0, 0, 0, 0, 0, 0));
        }

        pruneExpired(now);

        int n = players.size();
        int take = Math.min(n, cfg.maxPlayersPerCheck());
        int start = Math.floorMod(playerCursor, n);
        playerCursor = (start + take) % n;

        Counters c = new Counters();
        double radius = cfg.radiusBlocks();
        double vertical = cfg.verticalBlocks();
        List<ServerPlayer> handled = new ArrayList<>(take);

        for (int k = 0; k < take; k++) {
            if (c.cases >= cfg.maxNewCasesPerCheck()) break;
            if (cfg.maxTrackedGlobal() > 0 && IllnessTracker.size() >= cfg.maxTrackedGlobal()) break;
            ServerPlayer p = players.get((start + k) % n);
            ServerLevel level = p.level();
            c.considered++;

            if (nearAny(handled, level, p.getX(), p.getZ(), radius)) {
                c.deduped++;
                continue;
            }
            handled.add(p);

            if (inAreaCooldown(level.dimension(), p.getX(), p.getZ(), cfg.areaCooldownRadius(), now)) {
                c.cooldown++;
                continue;
            }

            AABB box = new AABB(p.getX() - radius, p.getY() - vertical, p.getZ() - radius,
                    p.getX() + radius, p.getY() + vertical, p.getZ() + radius);
            List<Villager> found = new ArrayList<>();
            level.getEntities(EntityType.VILLAGER, box, v -> v.isAlive() && !v.isRemoved(), found,
                    cfg.maxCandidatesPerPlayer());

            int ill = 0;
            for (Villager v : found) {
                if (v.hasAttached(OutbreakAttachments.ILLNESS)) ill++;
            }
            if (cfg.maxIllNearby() > 0 && ill >= cfg.maxIllNearby()) {
                c.ceiling++;
                continue;
            }

            c.areas++;
            Villager hit = rollArea(level, found, illnesses, cfg.chanceMultiplier(), c);
            if (hit != null) {
                c.cases++;
                totalCases++;
                armCooldowns(level.dimension(), hit.getX(), hit.getZ(), now, cfg);
            }
        }

        return remember(new CheckStats(tick, now, forced, Skip.NONE, n, c.considered, c.areas,
                c.deduped, c.ceiling, c.cooldown, c.candidates, c.rolls, c.rejected, c.cases));
    }

    /** Rolls every healthy, entity-ticking villager of one area. @return the new case, or null. */
    private static Villager rollArea(ServerLevel level, List<Villager> found, List<IllnessDefinition> illnesses,
                                     double configMultiplier, Counters c) {
        int n = found.size();
        if (n == 0) return null;
        RandomSource random = level.getRandom();
        int start = random.nextInt(n); // the capped query favours a fixed subset; at least vary the order
        int m = illnesses.size();
        // Level-wide conditions: read once per area.
        boolean raining = level.isRaining();
        boolean dark = level.isDarkOutside();
        boolean bright = level.isBrightOutside();

        for (int k = 0; k < n; k++) {
            Villager v = found.get((start + k) % n);
            if (v.isRemoved() || !v.isAlive()) continue;
            if (v.hasAttached(OutbreakAttachments.ILLNESS)) continue; // ill or immune
            if (!level.isPositionEntityTicking(v.blockPosition())) continue;
            c.candidates++;

            Holder<Biome> biome = level.getBiome(v.blockPosition());
            Biome b = biome.value();
            OnsetParams.Environment env = new OnsetParams.Environment(biome, b.getBaseTemperature(),
                    raining && b.hasPrecipitation(), dark, bright, v.isBaby());

            int first = random.nextInt(m); // no illness always rolls first
            for (int j = 0; j < m; j++) {
                IllnessDefinition def = illnesses.get((first + j) % m);
                OnsetParams op = def.onset().orElse(null);
                if (op == null) continue;
                double p = op.effectiveChance(env, configMultiplier);
                if (p <= 0.0) continue;
                c.rolls++;
                if (random.nextDouble() >= p) continue;
                if (IllnessManager.onset(v, def, level.getGameTime())) return v;
                c.rejected++;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ trigger (admin, /outbreak onset trigger)

    /**
     * Result of trigger. On success: villager = the case's short UUID, illnessId = its illness (an admin asked
     * for this case, so the command may print both), message = how the illness was chosen. On failure:
     * message = why, the other two are empty.
     */
    public record TriggerResult(boolean success, String message, String villager, String illnessId) {
        static TriggerResult fail(String message) { return new TriggerResult(false, message, "", ""); }
    }

    /**
     * A guaranteed natural case near player. Ignores onset.enabled, chance, grace, startup delay, cooldowns and
     * ceilings, but uses the same box and capped query as a check, and IllnessManager.onset applies the hard
     * rules (food). requested == null: the illnesses with natural_onset and chance > 0, tried in an order drawn
     * by base chance (weighted, without replacement). On success arms the cooldowns like a real case and counts
     * in totalCases; never changes lastCheck.
     */
    public static TriggerResult trigger(MinecraftServer server, ServerPlayer player, IllnessDefinition requested) {
        ServerLevel level = player.level();
        RandomSource random = level.getRandom();
        List<IllnessDefinition> order = new ArrayList<>();
        if (requested != null) {
            if (requested.onset().isEmpty()) return TriggerResult.fail(requested.id() + " has no natural_onset");
            order.add(requested);
        } else {
            List<IllnessDefinition> pool = new ArrayList<>();
            for (IllnessDefinition def : IllnessRegistry.all()) {
                OnsetParams op = def.onset().orElse(null);
                if (op != null && op.chance() > 0.0) pool.add(def);
            }
            if (pool.isEmpty()) return TriggerResult.fail("no illness has natural_onset with chance > 0");
            while (!pool.isEmpty()) {
                double total = 0.0;
                for (IllnessDefinition def : pool) total += def.onset().get().chance();
                double r = random.nextDouble() * total;
                int pick = pool.size() - 1;
                for (int i = 0; i < pool.size(); i++) {
                    r -= pool.get(i).onset().get().chance();
                    if (r < 0.0) {
                        pick = i;
                        break;
                    }
                }
                order.add(pool.remove(pick));
            }
        }

        OutbreakConfig.Onset cfg = OutbreakConfig.onset();
        double radius = cfg.radiusBlocks();
        double vertical = cfg.verticalBlocks();
        AABB box = new AABB(player.getX() - radius, player.getY() - vertical, player.getZ() - radius,
                player.getX() + radius, player.getY() + vertical, player.getZ() + radius);
        List<Villager> found = new ArrayList<>();
        level.getEntities(EntityType.VILLAGER, box, v -> v.isAlive() && !v.isRemoved(), found,
                cfg.maxCandidatesPerPlayer());
        List<Villager> healthy = new ArrayList<>();
        for (Villager v : found) {
            if (v.hasAttached(OutbreakAttachments.ILLNESS)) continue; // ill or immune
            if (!level.isPositionEntityTicking(v.blockPosition())) continue;
            healthy.add(v);
        }
        String counts = "found " + found.size() + " villager(s) within +-" + cfg.radiusBlocks() + " / +-"
                + cfg.verticalBlocks() + " blocks (cap " + cfg.maxCandidatesPerPlayer() + "), "
                + healthy.size() + " healthy and entity-ticking";
        if (healthy.isEmpty()) return TriggerResult.fail("no qualifying villager: " + counts);

        int n = healthy.size();
        int start = random.nextInt(n);
        List<String> tried = new ArrayList<>();
        for (IllnessDefinition def : order) {
            for (int k = 0; k < n; k++) {
                Villager v = healthy.get((start + k) % n);
                if (!IllnessManager.onset(v, def, level.getGameTime())) continue;
                totalCases++;
                armCooldowns(level.dimension(), v.getX(), v.getZ(), server.overworld().getGameTime(), cfg);
                String how = requested != null ? "requested"
                        : tried.isEmpty() ? "picked by onset chance"
                        : "picked by onset chance; no villager qualified for " + String.join(", ", tried);
                return new TriggerResult(true, how, v.getUUID().toString().substring(0, 8), def.id());
            }
            tried.add(def.id());
        }
        return TriggerResult.fail("no villager qualified for " + String.join(", ", tried) + ": " + counts
                + " (foodborne / requires_food illnesses need a villager carrying food)");
    }

    // ------------------------------------------------------------------ cooldowns

    private static void armCooldowns(ResourceKey<Level> level, double x, double z, long now, OutbreakConfig.Onset cfg) {
        if (cfg.globalCooldownTicks() > 0) globalCooldownUntil = now + cfg.globalCooldownTicks();
        if (cfg.areaCooldownTicks() <= 0) return;
        pruneExpired(now);
        if (AREA_COOLDOWNS.size() >= MAX_AREA_COOLDOWNS) AREA_COOLDOWNS.remove(0); // oldest first
        AREA_COOLDOWNS.add(new AreaCooldown(level, x, z, now + cfg.areaCooldownTicks()));
    }

    private static void pruneExpired(long now) {
        AREA_COOLDOWNS.removeIf(a -> a.expiresAt() <= now);
    }

    private static boolean inAreaCooldown(ResourceKey<Level> level, double x, double z, double radius, long now) {
        double r2 = radius * radius;
        for (AreaCooldown a : AREA_COOLDOWNS) {
            if (a.expiresAt() <= now || !a.level().equals(level)) continue;
            double dx = a.x() - x, dz = a.z() - z;
            if (dx * dx + dz * dz <= r2) return true;
        }
        return false;
    }

    /** Horizontal distance check against players already handled in this check (same level only). */
    private static boolean nearAny(List<ServerPlayer> handled, ServerLevel level, double x, double z, double radius) {
        double r2 = radius * radius;
        for (ServerPlayer h : handled) {
            if (h.level() != level) continue;
            double dx = h.getX() - x, dz = h.getZ() - z;
            if (dx * dx + dz * dz <= r2) return true;
        }
        return false;
    }

    private static CheckStats remember(CheckStats s) {
        last = s;
        return s;
    }

    /** Per-check counters (local to one runCheck call). */
    private static final class Counters {
        int considered, areas, deduped, ceiling, cooldown, candidates, rolls, rejected, cases;
    }
}