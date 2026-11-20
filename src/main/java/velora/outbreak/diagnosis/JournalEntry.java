package velora.outbreak.diagnosis;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * One diagnosis attempt in the player's journal (persistent player attachment).
 *
 * The first four fields are the original Phase 2 schema. Phase 5a adds three OPTIONAL fields, filled only for
 * a CONFIRMED diagnosis (so old saves and mismatches simply have them empty):
 *  villager:    the sampled villager (UUID).
 *  where:       dimension + position of the villager when the sample was taken, ROUNDED to a coarse grid.
 *  sampledTick: game time the sample was taken (gameTime above is when the diagnosis was made); -1 = unknown.
 * Phase 6e adds one more OPTIONAL field:
 *  treatedTick: game time THIS player started the correct treatment on that villager; -1 = not treated by them.
 *               Set by HudService.markTreated; a treated entry no longer shows on the HUD. Old saves load as -1.
 * The network codec below deliberately still carries only the first four: the microscope screen needs no more.
 */
public record JournalEntry(UUID sampleId, long gameTime, String choice, Outcome outcome,
                           Optional<UUID> villager, Optional<Place> where, long sampledTick, long treatedTick) {

    /** Rounded positions snap to the centre of a POS_GRID-sized cell: enough for "nearby", not an exact spot. */
    public static final int POS_GRID = 8;

    public record Place(String dimension, int x, int y, int z) {
        public static final Codec<Place> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("dim").forGetter(Place::dimension),
                Codec.INT.fieldOf("x").forGetter(Place::x),
                Codec.INT.fieldOf("y").forGetter(Place::y),
                Codec.INT.fieldOf("z").forGetter(Place::z)
        ).apply(i, Place::new));

        /** @param dimension e.g. "minecraft:overworld" */
        public static Place of(String dimension, BlockPos pos) {
            return new Place(dimension, round(pos.getX()), round(pos.getY()), round(pos.getZ()));
        }

        private static int round(int v) { return Math.floorDiv(v, POS_GRID) * POS_GRID + POS_GRID / 2; }
    }

    public static final Codec<JournalEntry> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("sample").forGetter(JournalEntry::sampleId),
            Codec.LONG.fieldOf("time").forGetter(JournalEntry::gameTime),
            Codec.STRING.fieldOf("choice").forGetter(JournalEntry::choice),
            Outcome.CODEC.fieldOf("outcome").forGetter(JournalEntry::outcome),
            UUIDUtil.CODEC.optionalFieldOf("villager").forGetter(JournalEntry::villager),
            Place.CODEC.optionalFieldOf("where").forGetter(JournalEntry::where),
            Codec.LONG.optionalFieldOf("sampled", -1L).forGetter(JournalEntry::sampledTick),
            Codec.LONG.optionalFieldOf("treated", -1L).forGetter(JournalEntry::treatedTick)
    ).apply(i, JournalEntry::new));

    public static final Codec<List<JournalEntry>> LIST_CODEC = CODEC.listOf();

    /** Original four fields only (client screen, mismatches, old data). */
    public JournalEntry(UUID sampleId, long gameTime, String choice, Outcome outcome) {
        this(sampleId, gameTime, choice, outcome, Optional.empty(), Optional.empty(), -1L, -1L);
    }

    /** A confirmed diagnosis with its origin. */
    public static JournalEntry confirmed(UUID sampleId, long gameTime, String choice, UUID villager,
                                         Optional<Place> where, long sampledTick) {
        return new JournalEntry(sampleId, gameTime, choice, Outcome.CORRECT,
                Optional.of(villager), where, sampledTick, -1L);
    }

    /** True once this player has started the correct treatment on the entry's villager. */
    public boolean treated() { return treatedTick >= 0; }

    /** A copy marked as treated at the given game time. */
    public JournalEntry withTreated(long gameTime) {
        return new JournalEntry(sampleId, this.gameTime, choice, outcome, villager, where, sampledTick, Math.max(0L, gameTime));
    }

    public static final StreamCodec<ByteBuf, JournalEntry> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, JournalEntry::sampleId,
            ByteBufCodecs.VAR_LONG, JournalEntry::gameTime,
            ByteBufCodecs.STRING_UTF8, JournalEntry::choice,
            ByteBufCodecs.STRING_UTF8.map(Outcome::byName, Outcome::getSerializedName), JournalEntry::outcome,
            JournalEntry::new);

    public static final StreamCodec<ByteBuf, List<JournalEntry>> LIST_STREAM_CODEC =
            STREAM_CODEC.apply(ByteBufCodecs.list());

    public String shortSample() { return sampleId.toString().substring(0, 8); }
}