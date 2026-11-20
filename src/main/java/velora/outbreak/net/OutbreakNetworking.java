package velora.outbreak.net;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import velora.outbreak.OutbreakContent;
import velora.outbreak.diagnosis.DiagnosisService;
import velora.outbreak.diagnosis.JournalEntry;
import velora.outbreak.diagnosis.Outcome;
import velora.outbreak.diagnosis.SampleData;
import velora.outbreak.hud.HudService;
import velora.outbreak.journal.JournalLine;
import velora.outbreak.journal.JournalService;

import java.util.List;

/** Payload definitions (common, no client classes) and server-side registration. */
public final class OutbreakNetworking {
    private OutbreakNetworking() {}

    // ------------------------------------------------------------------- S2C

    public record OpenMicroscope(BlockPos pos, SampleData sample, List<String> choices,
                                 List<JournalEntry> journal, boolean usable) implements CustomPacketPayload {
        public static final Type<OpenMicroscope> TYPE = new Type<>(OutbreakContent.id("open_microscope"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenMicroscope> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, OpenMicroscope::pos,
                SampleData.STREAM_CODEC, OpenMicroscope::sample,
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), OpenMicroscope::choices,
                JournalEntry.LIST_STREAM_CODEC, OpenMicroscope::journal,
                ByteBufCodecs.BOOL, OpenMicroscope::usable,
                OpenMicroscope::new);

        @Override
        public Type<OpenMicroscope> type() { return TYPE; }
    }

    /** levels is parallel to Feature.atZoom(zoom); empty when not sharp. */
    public record Observation(int zoom, boolean sharp, List<Integer> levels) implements CustomPacketPayload {
        public static final Type<Observation> TYPE = new Type<>(OutbreakContent.id("observation"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Observation> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Observation::zoom,
                ByteBufCodecs.BOOL, Observation::sharp,
                ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), Observation::levels,
                Observation::new);

        @Override
        public Type<Observation> type() { return TYPE; }
    }

    /** truth is only filled on CORRECT. missing holds Feature keys on INSUFFICIENT. */
    public record DiagnosisResult(Outcome outcome, String choice, String truth,
                                  List<String> missing) implements CustomPacketPayload {
        public static final Type<DiagnosisResult> TYPE = new Type<>(OutbreakContent.id("diagnosis_result"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DiagnosisResult> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8.map(Outcome::byName, Outcome::getSerializedName), DiagnosisResult::outcome,
                ByteBufCodecs.STRING_UTF8, DiagnosisResult::choice,
                ByteBufCodecs.STRING_UTF8, DiagnosisResult::truth,
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), DiagnosisResult::missing,
                DiagnosisResult::new);

        @Override
        public Type<DiagnosisResult> type() { return TYPE; }
    }

    public record CloseScreen() implements CustomPacketPayload {
        public static final CloseScreen INSTANCE = new CloseScreen();
        public static final Type<CloseScreen> TYPE = new Type<>(OutbreakContent.id("close_screen"));
        public static final StreamCodec<RegistryFriendlyByteBuf, CloseScreen> CODEC = StreamCodec.unit(INSTANCE);

        @Override
        public Type<CloseScreen> type() { return TYPE; }
    }

    /** The player's OWN journal, newest last, at most JournalLine.MAX_LINES entries (see JournalLine for the fields). */
    public record JournalData(List<JournalLine> lines) implements CustomPacketPayload {
        public static final Type<JournalData> TYPE = new Type<>(OutbreakContent.id("journal_data"));
        public static final StreamCodec<RegistryFriendlyByteBuf, JournalData> CODEC = StreamCodec.composite(
                JournalLine.CODEC.apply(ByteBufCodecs.list(JournalLine.MAX_LINES)), JournalData::lines,
                JournalData::new);

        @Override
        public Type<JournalData> type() { return TYPE; }
    }

    // ------------------------------------------------------------------- C2S (intents only)

    /** "Send me my journal." Carries no data. */
    public record JournalRequest() implements CustomPacketPayload {
        public static final JournalRequest INSTANCE = new JournalRequest();
        public static final Type<JournalRequest> TYPE = new Type<>(OutbreakContent.id("journal_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, JournalRequest> CODEC = StreamCodec.unit(INSTANCE);

        @Override
        public Type<JournalRequest> type() { return TYPE; }
    }

    public record AnalyzeIntent(BlockPos pos, int zoom, int focus) implements CustomPacketPayload {
        public static final Type<AnalyzeIntent> TYPE = new Type<>(OutbreakContent.id("analyze"));
        public static final StreamCodec<RegistryFriendlyByteBuf, AnalyzeIntent> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, AnalyzeIntent::pos,
                ByteBufCodecs.VAR_INT, AnalyzeIntent::zoom,
                ByteBufCodecs.VAR_INT, AnalyzeIntent::focus,
                AnalyzeIntent::new);

        @Override
        public Type<AnalyzeIntent> type() { return TYPE; }
    }

    public record SubmitIntent(BlockPos pos, String choice) implements CustomPacketPayload {
        public static final Type<SubmitIntent> TYPE = new Type<>(OutbreakContent.id("submit"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SubmitIntent> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, SubmitIntent::pos,
                ByteBufCodecs.STRING_UTF8, SubmitIntent::choice,
                SubmitIntent::new);

        @Override
        public Type<SubmitIntent> type() { return TYPE; }
    }

    // ------------------------------------------------------------------- registration

    public static void init() {
        PayloadTypeRegistry.clientboundPlay().register(OpenMicroscope.TYPE, OpenMicroscope.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Observation.TYPE, Observation.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(DiagnosisResult.TYPE, DiagnosisResult.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(CloseScreen.TYPE, CloseScreen.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(JournalData.TYPE, JournalData.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(AnalyzeIntent.TYPE, AnalyzeIntent.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SubmitIntent.TYPE, SubmitIntent.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(JournalRequest.TYPE, JournalRequest.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(AnalyzeIntent.TYPE,
                (payload, context) -> DiagnosisService.analyze(context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(SubmitIntent.TYPE,
                (payload, context) -> DiagnosisService.submit(context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(JournalRequest.TYPE,
                (payload, context) -> JournalService.onRequest(context.player()));

        DiagnosisService.init();
        JournalService.init();
        HudService.init();
    }
}