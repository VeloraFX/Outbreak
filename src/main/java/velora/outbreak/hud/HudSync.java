package velora.outbreak.hud;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import velora.outbreak.OutbreakContent;

import java.util.List;

/** S2C: the player's confirmed cases, newest first, at most HudCase.MAX_CASES. Journal-derived data only. */
public record HudSync(List<HudCase> cases) implements CustomPacketPayload {
    public static final Type<HudSync> TYPE = new Type<>(OutbreakContent.id("hud_sync"));
    public static final StreamCodec<RegistryFriendlyByteBuf, HudSync> CODEC = StreamCodec.composite(
            HudCase.CODEC.apply(ByteBufCodecs.list(HudCase.MAX_CASES)), HudSync::cases,
            HudSync::new);

    @Override
    public Type<HudSync> type() { return TYPE; }
}