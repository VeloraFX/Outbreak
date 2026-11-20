package velora.outbreak.illness;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Loaded villagers that carry illness data (including the RECOVERED immunity window).
 * Server thread only. Entries are removed on unload, so nothing here keeps villagers alive.
 */
public final class IllnessTracker {
    private static final Set<Villager> ILL = Collections.newSetFromMap(new IdentityHashMap<>());

    private IllnessTracker() {}

    public static void init() {
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof Villager v && v.hasAttached(OutbreakAttachments.ILLNESS)) ILL.add(v);
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            if (entity instanceof Villager v) ILL.remove(v);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            ILL.clear();
            IllnessManager.resetWarnings();
        });
    }

    public static void track(Villager v) { ILL.add(v); }
    public static void untrack(Villager v) { ILL.remove(v); }
    public static List<Villager> snapshot() { return new ArrayList<>(ILL); }
    public static int size() { return ILL.size(); }

    /** Called every server tick; does work only every IllnessManager.INTERVAL ticks. */
    public static void tick(MinecraftServer server) {
        if (ILL.isEmpty() || server.getTickCount() % IllnessManager.INTERVAL != 0) return;
        for (Villager v : snapshot()) { // snapshot: ticking may untrack
            if (v.isRemoved() || !(v.level() instanceof ServerLevel level)) {
                ILL.remove(v);
                continue;
            }
            if (!v.isAlive()) continue; // dying; will be unloaded shortly
            IllnessManager.tick(v, level);
        }
    }

    /** Debug/safety net: full scan, rebuilds the set. Returns {before, after}. */
    public static int[] rescan(MinecraftServer server) {
        int before = ILL.size();
        Set<Villager> found = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity e : level.getAllEntities()) {
                if (e instanceof Villager v && v.hasAttached(OutbreakAttachments.ILLNESS)) found.add(v);
            }
        }
        ILL.clear();
        ILL.addAll(found);
        return new int[]{before, ILL.size()};
    }
}