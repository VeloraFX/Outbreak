package velora.outbreak.illness;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.resources.Identifier;
import velora.outbreak.OutbreakMod;
import velora.outbreak.diagnosis.JournalEntry;
import velora.outbreak.diagnosis.SampleStore;

import java.util.List;

public final class OutbreakAttachments {
    public static final AttachmentType<VillagerIllnessData> ILLNESS = AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath(OutbreakMod.MOD_ID, "villager_illness"),
            builder -> builder
                    .persistent(VillagerIllnessData.CODEC));

    /** Server-wide hidden sample truths. Attached to the server's global attachments; never sent to clients. */
    public static final AttachmentType<SampleStore> SAMPLES = AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath(OutbreakMod.MOD_ID, "sample_truths"),
            builder -> builder.persistent(SampleStore.CODEC));

    /** Per-player diagnosis journal. */
    public static final AttachmentType<List<JournalEntry>> JOURNAL = AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath(OutbreakMod.MOD_ID, "journal"),
            builder -> builder
                    .persistent(JournalEntry.LIST_CODEC)
                    .copyOnDeath());

    /** Call once from onInitialize to force class loading. */
    public static void init() {}
}