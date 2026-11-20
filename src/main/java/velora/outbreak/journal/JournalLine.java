package velora.outbreak.journal;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import velora.outbreak.diagnosis.JournalEntry;
import velora.outbreak.diagnosis.Outcome;

import java.util.Optional;

/**
 * What the Medical Journal screen receives for ONE of the player's own journal entries. Deliberately smaller
 * than JournalEntry: no villager UUID, no full sample UUID, no height. "where" is the already-rounded
 * (8-block grid) x/z of a CONFIRMED diagnosis only. Nothing about live villagers or unexamined samples.
 */
public record JournalLine(String sample, long gameTime, String choice, Outcome outcome,
                          Optional<Where> where, long sampledTick) {

    public static final int MAX_LINES = 40;

    public record Where(String dimension, int x, int z) {}

    public static JournalLine of(JournalEntry e) {
        Optional<Where> where = e.where().map(p -> new Where(p.dimension(), p.x(), p.z()));
        return new JournalLine(e.shortSample(), e.gameTime(), e.choice(), e.outcome(), where, e.sampledTick());
    }

    private static final StreamCodec<ByteBuf, Where> WHERE_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, Where::dimension,
            ByteBufCodecs.VAR_INT, Where::x,
            ByteBufCodecs.VAR_INT, Where::z,
            Where::new);

    private static final StreamCodec<ByteBuf, Optional<Where>> OPTIONAL_WHERE = ByteBufCodecs.optional(WHERE_CODEC);

    public static final StreamCodec<ByteBuf, JournalLine> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, JournalLine::sample,
            ByteBufCodecs.VAR_LONG, JournalLine::gameTime,
            ByteBufCodecs.STRING_UTF8, JournalLine::choice,
            ByteBufCodecs.STRING_UTF8.map(Outcome::byName, Outcome::getSerializedName), JournalLine::outcome,
            OPTIONAL_WHERE, JournalLine::where,
            ByteBufCodecs.VAR_LONG, JournalLine::sampledTick,
            JournalLine::new);
}