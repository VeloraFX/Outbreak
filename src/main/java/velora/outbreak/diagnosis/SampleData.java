package velora.outbreak.diagnosis;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.UUID;

/** Item component on the Prepared Petri Dish. Contains no diagnosis information. */
public record SampleData(UUID source, long collectedTick, UUID sampleId, boolean viewable) {

    public static final Codec<SampleData> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("source").forGetter(SampleData::source),
            Codec.LONG.fieldOf("collected").forGetter(SampleData::collectedTick),
            UUIDUtil.CODEC.fieldOf("sample_id").forGetter(SampleData::sampleId),
            Codec.BOOL.optionalFieldOf("viewable", true).forGetter(SampleData::viewable)
    ).apply(i, SampleData::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, SampleData> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, SampleData::source,
            ByteBufCodecs.VAR_LONG, SampleData::collectedTick,
            UUIDUtil.STREAM_CODEC, SampleData::sampleId,
            ByteBufCodecs.BOOL, SampleData::viewable,
            SampleData::new);

    public String shortSource() { return source.toString().substring(0, 8); }
    public String shortId() { return sampleId.toString().substring(0, 8); }
}