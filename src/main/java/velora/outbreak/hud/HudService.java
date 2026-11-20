package velora.outbreak.hud;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import velora.outbreak.diagnosis.JournalEntry;
import velora.outbreak.diagnosis.Outcome;
import velora.outbreak.illness.IllnessRegistry;
import velora.outbreak.illness.OutbreakAttachments;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Feeds the client HUD. The ONLY input is the player's own journal: nothing from live villagers, the
 * illness tracker, the census or the hidden sample store. Sent on join and after each confirmed diagnosis;
 * never periodically. Server thread only.
 */
public final class HudService {
    private HudService() {}

    public static void init() {
        PayloadTypeRegistry.clientboundPlay().register(HudSync.TYPE, HudSync.CODEC);
        ServerPlayConnectionEvents.JOIN.register((listener, sender, server) -> send(listener.getPlayer()));
    }

    /** Sends the player's current case list (possibly empty) to that player only. */
    public static void send(ServerPlayer player) {
        List<JournalEntry> journal = player.getAttachedOrElse(OutbreakAttachments.JOURNAL, List.of());
        ServerPlayNetworking.send(player, new HudSync(build(journal)));
    }

    /**
     * Newest first. A case is a CORRECT entry that has a recorded place and whose choice is a currently known
     * illness id (this excludes "healthy", "inconclusive" and ids removed by a datapack). Several entries for the
     * same villager count once (the newest). Entries the player has since treated are left out. At most HudCase.MAX_CASES.
     */
    static List<HudCase> build(List<JournalEntry> journal) {
        List<HudCase> out = new ArrayList<>();
        Set<UUID> seenVillagers = new HashSet<>();
        for (int i = journal.size() - 1; i >= 0 && out.size() < HudCase.MAX_CASES; i--) {
            JournalEntry e = journal.get(i);
            if (e.outcome() != Outcome.CORRECT) continue;
            if (e.treated()) continue;
            if (e.where().isEmpty()) continue;
            if (IllnessRegistry.get(e.choice()) == null) continue;
            if (e.villager().isPresent() && !seenVillagers.add(e.villager().get())) continue;
            JournalEntry.Place p = e.where().get();
            out.add(new HudCase(e.choice(), p.dimension(), p.x(), p.z(), e.gameTime()));
        }
        return out;
    }

    /**
     * The player just started the correct treatment on this villager (TreatmentService): marks every confirmed,
     * not yet treated journal entry for that villager as treated and, if any changed, re-sends the HUD.
     * Only the treating player's own journal changes. A later diagnosis of the same villager is a new entry
     * and shows again.
     */
    public static void markTreated(ServerPlayer player, UUID villager, long gameTime) {
        List<JournalEntry> journal = player.getAttachedOrElse(OutbreakAttachments.JOURNAL, List.of());
        List<JournalEntry> copy = new ArrayList<>(journal.size());
        boolean changed = false;
        for (JournalEntry e : journal) {
            if (e.outcome() == Outcome.CORRECT && !e.treated()
                    && e.villager().isPresent() && e.villager().get().equals(villager)) {
                copy.add(e.withTreated(gameTime));
                changed = true;
            } else {
                copy.add(e);
            }
        }
        if (!changed) return;
        player.setAttached(OutbreakAttachments.JOURNAL, List.copyOf(copy));
        send(player);
    }
}