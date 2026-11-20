package velora.outbreak.illness;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.behavior.VillagerPanicTrigger;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.schedule.Activity;
import velora.outbreak.OutbreakMod;
import velora.outbreak.config.OutbreakConfig;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Brief "stand still" pause while a villager shows a symptom, so the symptom reads clearly in world.
 *
 * Mechanism: a TRANSIENT movement-speed modifier (x0). The brain is not touched, so the villager keeps its
 * plans and resumes them when the pause ends. Transient modifiers are never saved (AttributeInstance.pack
 * keeps only permanent ones), are not copied by mob conversion, and are dropped by dimension change, so a
 * pause can never persist on disk or into a zombie villager.
 *
 * Always ends: hard per-pause limit (never extended), and released at once on panic, hostiles nearby, hurt,
 * trading, sleeping, riding, water/lava, death, removal, unload, cure, stage change, config or data reload
 * and server stop. Server thread only. At most MAX_ENTRIES villagers (paused or cooling down) at once.
 */
public final class SymptomPause {
    /** Paused plus cooling-down villagers. When full, symptoms still play but nobody new pauses. */
    public static final int MAX_ENTRIES = 32;

    private static final Identifier MODIFIER_ID = Identifier.fromNamespaceAndPath(OutbreakMod.MOD_ID, "symptom_pause");
    private static final AttributeModifier FREEZE =
            new AttributeModifier(MODIFIER_ID, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);

    private static final class Entry {
        final long releaseAt;     // server tick at which the pause ends at the latest
        final long cooldownUntil; // no new pause for this villager before this tick
        boolean frozen = true;

        Entry(long releaseAt, long cooldownUntil) {
            this.releaseAt = releaseAt;
            this.cooldownUntil = cooldownUntil;
        }
    }

    private static final Map<Villager, Entry> ENTRIES = new IdentityHashMap<>();

    private SymptomPause() {}

    public static void init() {
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            if (entity instanceof Villager v) release(v);
        });
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) -> releaseAll());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> releaseAll());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> ENTRIES.clear());
    }

    /** Called when a symptom is really shown. Does nothing if off, busy, already paused or cooling down. */
    public static void start(Villager v, ServerLevel level) {
        int ticks = Math.min(OutbreakConfig.MAX_PAUSE_TICKS, OutbreakConfig.symptoms().pauseTicks());
        if (ticks <= 0) return;
        if (v.isRemoved() || !v.isAlive()) return;
        if (ENTRIES.containsKey(v) || ENTRIES.size() >= MAX_ENTRIES) return; // never extend a pause
        if (mustRelease(v)) return;
        AttributeInstance speed = v.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) return;
        speed.addOrUpdateTransientModifier(FREEZE);
        long now = level.getServer().getTickCount();
        ENTRIES.put(v, new Entry(now + ticks, now + 2L * ticks));
    }

    /** END_SERVER_TICK listener. O(entries), no world access. */
    public static void tick(MinecraftServer server) {
        if (ENTRIES.isEmpty()) return;
        long now = server.getTickCount();
        Iterator<Map.Entry<Villager, Entry>> it = ENTRIES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Villager, Entry> me = it.next();
            Villager v = me.getKey();
            Entry e = me.getValue();
            if (v.isRemoved() || !v.isAlive()) {
                unfreeze(v);
                it.remove();
                continue;
            }
            if (e.frozen && (now >= e.releaseAt || mustRelease(v))) {
                unfreeze(v);
                e.frozen = false;
            }
            if (!e.frozen && now >= e.cooldownUntil) it.remove();
        }
    }

    /** Ends this villager's pause (if any) and forgets it. Safe to call for any villager. */
    public static void release(Villager v) {
        if (ENTRIES.remove(v) != null) unfreeze(v);
    }

    /** Ends every pause (config reload, data reload, server stop). */
    public static void releaseAll() {
        for (Villager v : ENTRIES.keySet()) unfreeze(v);
        ENTRIES.clear();
    }

    /** Never pause, or stop pausing, while the villager needs to move or is busy with something vanilla. */
    private static boolean mustRelease(Villager v) {
        if (v.hurtTime > 0 || v.isSleeping() || v.isTrading() || v.isPassenger() || v.isInWater() || v.isInLava()) {
            return true;
        }
        if (VillagerPanicTrigger.isHurt(v) || VillagerPanicTrigger.hasHostile(v)) return true;
        Brain<Villager> brain = v.getBrain();
        return brain.isActive(Activity.PANIC) || brain.isActive(Activity.HIDE)
                || brain.isActive(Activity.RAID) || brain.isActive(Activity.PRE_RAID);
    }

    private static void unfreeze(Villager v) {
        AttributeInstance speed = v.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) speed.removeModifier(MODIFIER_ID);
    }
}