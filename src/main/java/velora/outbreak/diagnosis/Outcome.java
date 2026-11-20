package velora.outbreak.diagnosis;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

import java.util.Locale;

public enum Outcome implements StringRepresentable {
    CORRECT, MISMATCH, INSUFFICIENT;

    public static final Codec<Outcome> CODEC = StringRepresentable.fromEnum(Outcome::values);

    @Override
    public String getSerializedName() { return name().toLowerCase(Locale.ROOT); }

    public static Outcome byName(String s) {
        for (Outcome o : values()) if (o.getSerializedName().equals(s)) return o;
        return MISMATCH;
    }
}