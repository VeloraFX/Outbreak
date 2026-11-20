package velora.outbreak.diagnosis;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import velora.outbreak.OutbreakContent;
import velora.outbreak.block.MicroscopeBlockEntity;
import velora.outbreak.hud.HudService;
import velora.outbreak.illness.OutbreakAttachments;
import velora.outbreak.net.OutbreakNetworking.AnalyzeIntent;
import velora.outbreak.net.OutbreakNetworking.CloseScreen;
import velora.outbreak.net.OutbreakNetworking.DiagnosisResult;
import velora.outbreak.net.OutbreakNetworking.Observation;
import velora.outbreak.net.OutbreakNetworking.OpenMicroscope;
import velora.outbreak.net.OutbreakNetworking.SubmitIntent;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Server-side logic for sampling, microscope sessions and diagnosis. Server thread only. */
public final class DiagnosisService {
    public static final long SAMPLE_VALID_TICKS = 72_000L; // 3 in-game days
    private static final double MAX_SWAB_DIST_SQ = 3.5 * 3.5;
    private static final double MAX_SCOPE_DIST_SQ = 8.0 * 8.0;
    private static final int MAX_JOURNAL = 40;
    private static final int SENT_JOURNAL = 6;

    private static final class Session {
        final BlockPos pos;
        final UUID sampleId;
        final EnumSet<Feature> examined = EnumSet.noneOf(Feature.class);

        Session(BlockPos pos, UUID sampleId) {
            this.pos = pos;
            this.sampleId = sampleId;
        }
    }

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private DiagnosisService() {}

    public static void init() {
        ServerPlayConnectionEvents.DISCONNECT.register((listener, server) -> SESSIONS.remove(listener.getPlayer().getUUID()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> SESSIONS.clear());
    }

    // ---------------------------------------------------------------- sampling

    /** Offhand first, then the inventory. Returns the live stack (shrink it), or null. */
    public static ItemStack findStack(Player player, Item item) {
        ItemStack off = player.getOffhandItem();
        if (!off.isEmpty() && off.getItem() == item) return off;
        for (ItemStack s : player.getInventory().getNonEquipmentItems()) {
            if (!s.isEmpty() && s.getItem() == item) return s;
        }
        return null;
    }

    /** All validation is here. Returns true if a sample was taken. */
    public static boolean collect(ServerPlayer player, ServerLevel level, Villager villager, ItemStack swab) {
        if (swab.getItem() != OutbreakContent.STERILE_SWAB) return false;
        if (!villager.isAlive() || player.distanceToSqr(villager) > MAX_SWAB_DIST_SQ) {
            player.sendOverlayMessage(Component.translatable("message.outbreak.swab.too_far"));
            return false;
        }
        ItemStack dish = findStack(player, OutbreakContent.EMPTY_PETRI_DISH);
        if (dish == null) {
            player.sendOverlayMessage(Component.translatable("message.outbreak.swab.no_dish"));
            return false;
        }
        SampleData data = newSample(level.getServer(), villager, true);
        swab.shrink(1);
        dish.shrink(1);
        player.getInventory().placeItemBackInInventory(OutbreakContent.preparedDish(data));
        level.playSound(null, villager.blockPosition(), SoundEvents.ITEM_FRAME_ADD_ITEM, net.minecraft.sounds.SoundSource.PLAYERS, 0.8f, 1.2f);
        return true;
    }

    /** Snapshots the villager into the hidden store and returns the item component. */
    private static SampleData newSample(MinecraftServer server, Villager villager, boolean viewable) {
        // The origin (rounded) stays in the hidden store; it only reaches the journal if the diagnosis is confirmed.
        JournalEntry.Place origin = JournalEntry.Place.of(
                villager.level().dimension().identifier().toString(), villager.blockPosition());
        SampleTruth truth = SampleTruth.snapshot(villager.getAttached(OutbreakAttachments.ILLNESS)).withOrigin(origin);
        SampleData data = new SampleData(villager.getUUID(), villager.level().getGameTime(), UUID.randomUUID(), viewable);
        SampleStore.save(server, SampleStore.load(server).with(data.sampleId(), truth));
        return data;
    }

    /** Debug command support. */
    public static SampleData giveSample(ServerPlayer player, Villager villager, boolean viewable) {
        SampleData data = newSample(player.level().getServer(), villager, viewable);
        player.getInventory().placeItemBackInInventory(OutbreakContent.preparedDish(data));
        return data;
    }

    // -------------------------------------------------------------- microscope

    private static SampleTruth usableTruth(MinecraftServer server, SampleData d, long now) {
        if (!d.viewable() || now - d.collectedTick() > SAMPLE_VALID_TICKS) return null;
        return SampleStore.load(server).find(d.sampleId());
    }

    public static void open(ServerPlayer player, BlockPos pos, MicroscopeBlockEntity be) {
        SampleData d = be.dish();
        if (d == null || be.getLevel() == null) return;
        MinecraftServer server = player.level().getServer();
        boolean usable = usableTruth(server, d, be.getLevel().getGameTime()) != null;
        SESSIONS.put(player.getUUID(), new Session(pos, d.sampleId()));
        ServerPlayNetworking.send(player, new OpenMicroscope(pos, d,
                ObservationProfile.availableChoices(), journalView(player), usable));
    }

    /** Re-validates distance, block entity and dish. On failure tells the client to close and returns null. */
    private static MicroscopeBlockEntity check(ServerPlayer player, Session s, BlockPos pos) {
        MicroscopeBlockEntity be = null;
        if (s != null && s.pos.equals(pos) && player.distanceToSqr(Vec3.atCenterOf(pos)) <= MAX_SCOPE_DIST_SQ) {
            BlockEntity raw = player.level().getBlockEntity(pos);
            if (raw instanceof MicroscopeBlockEntity m && m.dish() != null && m.dish().sampleId().equals(s.sampleId)) {
                be = m;
            }
        }
        if (be == null) {
            SESSIONS.remove(player.getUUID());
            ServerPlayNetworking.send(player, CloseScreen.INSTANCE);
        }
        return be;
    }

    public static void analyze(ServerPlayer player, AnalyzeIntent in) {
        Session s = SESSIONS.get(player.getUUID());
        MicroscopeBlockEntity be = check(player, s, in.pos());
        if (be == null) return;
        if (in.zoom() < Feature.MIN_ZOOM || in.zoom() > Feature.MAX_ZOOM) return;

        SampleData d = be.dish();
        SampleTruth truth = usableTruth(player.level().getServer(), d, player.level().getGameTime());
        if (truth == null) return; // screen is in its unusable state; nothing to analyse

        if (!Optics.isSharp(Optics.target(d.sampleId(), in.zoom()), in.focus())) {
            ServerPlayNetworking.send(player, new Observation(in.zoom(), false, List.of()));
            return;
        }
        ObservationProfile profile = ObservationProfile.forTruth(truth);
        List<Feature> features = Feature.atZoom(in.zoom());
        List<Integer> levels = new ArrayList<>();
        for (Feature f : features) levels.add(profile.level(f));
        s.examined.addAll(features);
        ServerPlayNetworking.send(player, new Observation(in.zoom(), true, levels));
    }

    public static void submit(ServerPlayer player, SubmitIntent in) {
        Session s = SESSIONS.get(player.getUUID());
        MicroscopeBlockEntity be = check(player, s, in.pos());
        if (be == null) return;
        String choice = in.choice();
        if (!ObservationProfile.isValidChoice(choice)) return;

        SampleData d = be.dish();
        MinecraftServer server = player.level().getServer();
        SampleTruth truth = usableTruth(server, d, player.level().getGameTime());
        if (truth == null) return;

        String expected = ObservationProfile.expectedChoice(truth);
        if (truth.resolved()) { // already diagnosed: just repeat the stored answer
            ServerPlayNetworking.send(player, new DiagnosisResult(Outcome.CORRECT, truth.resolution(), expected, List.of()));
            return;
        }

        EnumSet<Feature> missing = ObservationProfile.requiredFor(choice);
        missing.removeAll(s.examined);
        if (!missing.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (Feature f : missing) names.add(f.key());
            ServerPlayNetworking.send(player, new DiagnosisResult(Outcome.INSUFFICIENT, choice, "", names));
            return;
        }

        long now = player.level().getGameTime();
        if (expected.equals(choice)) {
            SampleStore.save(server, SampleStore.load(server).with(d.sampleId(), truth.resolvedAs(choice)));
            addJournal(player, JournalEntry.confirmed(d.sampleId(), now, choice, d.source(), truth.origin(), d.collectedTick()));
            ServerPlayNetworking.send(player, new DiagnosisResult(Outcome.CORRECT, choice, expected, List.of()));
        } else {
            addJournal(player, new JournalEntry(d.sampleId(), now, choice, Outcome.MISMATCH));
            ServerPlayNetworking.send(player, new DiagnosisResult(Outcome.MISMATCH, choice, "", List.of()));
        }
    }

    // ------------------------------------------------------------------ journal

    private static void addJournal(ServerPlayer player, JournalEntry entry) {
        List<JournalEntry> copy = new ArrayList<>(player.getAttachedOrElse(OutbreakAttachments.JOURNAL, List.of()));
        copy.add(entry);
        while (copy.size() > MAX_JOURNAL) copy.remove(0);
        player.setAttached(OutbreakAttachments.JOURNAL, List.copyOf(copy));
        if (entry.outcome() == Outcome.CORRECT) HudService.send(player);
    }

    private static List<JournalEntry> journalView(ServerPlayer player) {
        List<JournalEntry> all = player.getAttachedOrElse(OutbreakAttachments.JOURNAL, List.of());
        return new ArrayList<>(all.subList(Math.max(0, all.size() - SENT_JOURNAL), all.size()));
    }

    // -------------------------------------------------------------------- debug

    /**
     * Debug: resolves the Prepared Petri Dish in the main hand (else offhand) with the correct answer,
     * marks all observations as examined for an open screen, and returns a chat summary (null = no dish).
     */
    public static String forceDiagnose(ServerPlayer player) {
        ItemStack stack = player.getMainHandItem();
        if (stack.getItem() != OutbreakContent.PREPARED_PETRI_DISH) stack = player.getOffhandItem();
        if (stack.getItem() != OutbreakContent.PREPARED_PETRI_DISH) return null;
        SampleData d = stack.get(OutbreakContent.SAMPLE);
        if (d == null) return "That dish has no sample data.";

        MinecraftServer server = player.level().getServer();
        SampleStore store = SampleStore.load(server);
        SampleTruth truth = store.find(d.sampleId());
        if (truth == null) return "No hidden record for sample " + d.shortId() + " (never stored, or evicted).";

        String expected = ObservationProfile.expectedChoice(truth);
        if (!truth.resolved()) {
            SampleStore.save(server, store.with(d.sampleId(), truth.resolvedAs(expected)));
            addJournal(player, JournalEntry.confirmed(d.sampleId(), player.level().getGameTime(), expected,
                    d.source(), truth.origin(), d.collectedTick()));
        }
        Session s = SESSIONS.get(player.getUUID());
        if (s != null && s.sampleId.equals(d.sampleId())) {
            s.examined.addAll(EnumSet.allOf(Feature.class));
            ServerPlayNetworking.send(player, new DiagnosisResult(Outcome.CORRECT, expected, expected, List.of()));
        }
        return "Sample " + d.shortId() + ": " + truth.kind().getSerializedName()
                + (truth.illnessId().isEmpty() ? "" : " (" + truth.illnessId() + ")")
                + " -> correct diagnosis: " + expected;
    }
}