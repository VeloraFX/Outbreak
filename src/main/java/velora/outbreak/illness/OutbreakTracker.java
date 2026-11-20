package velora.outbreak.illness;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Server-side outbreak census, computed ON DEMAND from IllnessTracker (loaded villagers only, O(n)).
 * Never call it every tick; a future HUD should poll it at most every few seconds and cache.
 *
 * !! GROUND TRUTH, ADMIN/DEBUG ONLY !! The Census includes INCUBATING villagers and exact current states,
 * which the player has not earned. Nothing here is sent to clients and no payload exists for it.
 * The Phase 5 HUD must NOT be fed from this class. It must be built from records the player earned
 * (confirmed diagnoses in their journal: which illness, when, and where the sample was taken), shown as
 * "last known", never from live villager state.
 */
public final class OutbreakTracker {
    /** Clusters are built from 16x16-block cells; touching cells (8-neighbourhood) merge. */
    private static final int CELL_SHIFT = 4;

    private OutbreakTracker() {}

    public record Cluster(String dimension, int x, int y, int z, int villagers, int symptomatic) {}

    public static final class Census {
        public final String illnessId;
        /** Count per IllnessState ordinal, RECOVERED (immune window) included. */
        public final int[] byState = new int[IllnessState.values().length];
        public int maxGeneration = 0;
        public final List<Cluster> clusters = new ArrayList<>();

        Census(String illnessId) { this.illnessId = illnessId; }

        public int count(IllnessState s) { return byState[s.ordinal()]; }
        public int immune() { return count(IllnessState.RECOVERED); }
        public int ill() {
            int total = 0;
            for (IllnessState s : IllnessState.values()) if (s != IllnessState.RECOVERED) total += count(s);
            return total;
        }
    }

    private static final class Cell {
        int count, symptomatic;
        double sx, sy, sz;
    }

    private record GroupKey(String illness, String dimension) {}

    /** Per illness id, sorted by id. Only illnesses with at least one tracked villager appear. */
    public static Map<String, Census> census() {
        Map<String, Census> result = new TreeMap<>();
        Map<GroupKey, Map<Long, Cell>> cells = new LinkedHashMap<>();

        for (Villager v : IllnessTracker.snapshot()) {
            if (v.isRemoved() || !(v.level() instanceof ServerLevel level)) continue;
            VillagerIllnessData d = v.getAttached(OutbreakAttachments.ILLNESS);
            if (d == null) continue;

            Census c = result.computeIfAbsent(d.illnessId(), Census::new);
            c.byState[d.state().ordinal()]++;
            c.maxGeneration = Math.max(c.maxGeneration, d.generation());
            if (d.state() == IllnessState.RECOVERED) continue; // immune villagers do not form clusters

            BlockPos pos = v.blockPosition();
            GroupKey gk = new GroupKey(d.illnessId(), level.dimension().identifier().toString());
            Cell cell = cells.computeIfAbsent(gk, k -> new HashMap<>())
                    .computeIfAbsent(key(pos.getX() >> CELL_SHIFT, pos.getZ() >> CELL_SHIFT), k -> new Cell());
            cell.count++;
            if (d.state() == IllnessState.SYMPTOMATIC) cell.symptomatic++;
            cell.sx += pos.getX();
            cell.sy += pos.getY();
            cell.sz += pos.getZ();
        }

        for (Map.Entry<GroupKey, Map<Long, Cell>> e : cells.entrySet()) {
            Census c = result.get(e.getKey().illness());
            mergeCells(e.getKey().dimension(), e.getValue(), c.clusters);
        }
        for (Census c : result.values()) c.clusters.sort(Comparator.comparingInt(Cluster::villagers).reversed());
        return result;
    }

    private static long key(int cx, int cz) { return ((long) cx << 32) | (cz & 0xFFFFFFFFL); }

    /** Flood-fill over touching cells. */
    private static void mergeCells(String dimension, Map<Long, Cell> cells, List<Cluster> out) {
        Set<Long> seen = new HashSet<>();
        for (Long start : cells.keySet()) {
            if (!seen.add(start)) continue;
            int count = 0, symptomatic = 0;
            double sx = 0, sy = 0, sz = 0;
            ArrayDeque<Long> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                long k = queue.poll();
                Cell cell = cells.get(k);
                count += cell.count;
                symptomatic += cell.symptomatic;
                sx += cell.sx;
                sy += cell.sy;
                sz += cell.sz;
                int cx = (int) (k >> 32);
                int cz = (int) k;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        long nk = key(cx + dx, cz + dz);
                        if (cells.containsKey(nk) && seen.add(nk)) queue.add(nk);
                    }
                }
            }
            out.add(new Cluster(dimension, (int) Math.round(sx / count), (int) Math.round(sy / count),
                    (int) Math.round(sz / count), count, symptomatic));
        }
    }
}