package velora.outbreak.hud;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * One CONFIRMED case taken from the player's own journal, in the smallest form the HUD needs.
 * No villager UUID, no sample id, no height, no choice other than a real illness id.
 *
 * @param illness   illness id, e.g. "common_cold"
 * @param dimension dimension id, e.g. "minecraft:overworld"
 * @param x         x of the sampled villager, already rounded to the 8-block grid (JournalEntry.Place)
 * @param z         z, same rounding
 * @param tick      game time at which the diagnosis was confirmed
 */
public record HudCase(String illness, String dimension, int x, int z, long tick) {
    /** Hard cap on cases per HudSync; also the decoder's limit. */
    public static final int MAX_CASES = 16;

    public static final StreamCodec<ByteBuf, HudCase> CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(64), HudCase::illness,
            ByteBufCodecs.stringUtf8(64), HudCase::dimension,
            ByteBufCodecs.VAR_INT, HudCase::x,
            ByteBufCodecs.VAR_INT, HudCase::z,
            ByteBufCodecs.VAR_LONG, HudCase::tick,
            HudCase::new);
}