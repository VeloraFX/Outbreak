package velora.outbreak.diagnosis;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.StringRepresentable;
import velora.outbreak.illness.VillagerIllnessData;

import java.util.Locale;
import java.util.Optional;

/**
 * Hidden, server-side snapshot of what a sample really contains. Never sent to clients.
 * origin (Phase 5a, optional): where the sample was taken (rounded), copied into the journal on confirmation.
 */
public record SampleTruth(Kind kind, String illnessId, String resolution, Optional<JournalEntry.Place> origin) {

    public SampleTruth(Kind kind, String illnessId, String resolution) {
        this(kind, illnessId, resolution, Optional.empty());
    }

    public enum Kind implements StringRepresentable {
        HEALTHY, ILL, TOO_EARLY;

        public static final Codec<Kind> CODEC = StringRepresentable.fromEnum(Kind::values);

        @Override
        public String getSerializedName() { return name().toLowerCase(Locale.ROOT); }
    }

    public static final Codec<SampleTruth> CODEC = RecordCodecBuilder.create(i -> i.group(
            Kind.CODEC.fieldOf("kind").forGetter(SampleTruth::kind),
            Codec.STRING.optionalFieldOf("illness", "").forGetter(SampleTruth::illnessId),
            Codec.STRING.optionalFieldOf("resolution", "").forGetter(SampleTruth::resolution),
            JournalEntry.Place.CODEC.optionalFieldOf("origin").forGetter(SampleTruth::origin)
    ).apply(i, SampleTruth::new));

    /** Snapshot at sampling time. Incubating = too early to detect; recovered = no active infection. */
    public static SampleTruth snapshot(VillagerIllnessData d) {
        if (d == null) return new SampleTruth(Kind.HEALTHY, "", "");
        return switch (d.state()) {
            case INCUBATING -> new SampleTruth(Kind.TOO_EARLY, d.illnessId(), "");
            case RECOVERED -> new SampleTruth(Kind.HEALTHY, "", "");
            case SYMPTOMATIC, UNDER_TREATMENT, RECOVERING -> new SampleTruth(Kind.ILL, d.illnessId(), "");
        };
    }

    public boolean resolved() { return !resolution.isEmpty(); }

    public SampleTruth resolvedAs(String choice) { return new SampleTruth(kind, illnessId, choice, origin); }

    public SampleTruth withOrigin(JournalEntry.Place place) { return new SampleTruth(kind, illnessId, resolution, Optional.of(place)); }
}