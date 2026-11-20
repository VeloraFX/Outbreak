package velora.outbreak.diagnosis;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import velora.outbreak.illness.OutbreakAttachments;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Hidden truth per sample, stored as a persistent Fabric global (server-wide) attachment.
 * Insertion ordered; the oldest entries are evicted beyond MAX. Immutable; replaced on every change.
 */
public record SampleStore(List<Entry> entries) {
    public static final int MAX = 2048;
    public static final SampleStore EMPTY = new SampleStore(List.of());

    public record Entry(UUID id, SampleTruth truth) {
        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("id").forGetter(Entry::id),
                SampleTruth.CODEC.fieldOf("truth").forGetter(Entry::truth)
        ).apply(i, Entry::new));
    }

    public static final Codec<SampleStore> CODEC = Entry.CODEC.listOf().xmap(SampleStore::new, SampleStore::entries);

    public SampleStore {
        entries = List.copyOf(entries);
    }

    public SampleTruth find(UUID id) {
        for (Entry e : entries) if (e.id().equals(id)) return e.truth();
        return null;
    }

    public SampleStore with(UUID id, SampleTruth truth) {
        List<Entry> copy = new ArrayList<>(entries);
        boolean replaced = false;
        for (int i = 0; i < copy.size(); i++) {
            if (copy.get(i).id().equals(id)) {
                copy.set(i, new Entry(id, truth));
                replaced = true;
                break;
            }
        }
        if (!replaced) copy.add(new Entry(id, truth));
        while (copy.size() > MAX) copy.remove(0);
        return new SampleStore(copy);
    }

    public static SampleStore load(MinecraftServer server) {
        return server.globalAttachments().getAttachedOrElse(OutbreakAttachments.SAMPLES, EMPTY);
    }

    public static void save(MinecraftServer server, SampleStore store) {
        server.globalAttachments().setAttached(OutbreakAttachments.SAMPLES, store);
    }
}