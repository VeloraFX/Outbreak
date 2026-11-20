package velora.outbreak.journal;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import velora.outbreak.diagnosis.JournalEntry;
import velora.outbreak.illness.OutbreakAttachments;
import velora.outbreak.net.OutbreakNetworking.JournalData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Sends the player's OWN journal to their client. Server thread only. */
public final class JournalService {
    /** Minimum server ticks between two answered requests from the same player. */
    private static final int MIN_GAP_TICKS = 10;

    private static final Map<UUID, Integer> LAST_REQUEST = new HashMap<>();

    private JournalService() {}

    public static void init() {
        ServerPlayConnectionEvents.DISCONNECT.register((listener, server) -> LAST_REQUEST.remove(listener.getPlayer().getUUID()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> LAST_REQUEST.clear());
    }

    /** Opens the journal on the player's screen (item use). */
    public static void send(ServerPlayer player) {
        List<JournalEntry> all = player.getAttachedOrElse(OutbreakAttachments.JOURNAL, List.of());
        List<JournalLine> lines = new ArrayList<>();
        for (int i = Math.max(0, all.size() - JournalLine.MAX_LINES); i < all.size(); i++) {
            lines.add(JournalLine.of(all.get(i)));
        }
        ServerPlayNetworking.send(player, new JournalData(lines));
    }

    /** Client intent "send me my journal" (microscope screen button). Rate-limited per player. */
    public static void onRequest(ServerPlayer player) {
        int now = player.level().getServer().getTickCount();
        Integer last = LAST_REQUEST.get(player.getUUID());
        if (last != null && now - last < MIN_GAP_TICKS) return;
        LAST_REQUEST.put(player.getUUID(), now);
        send(player);
    }
}