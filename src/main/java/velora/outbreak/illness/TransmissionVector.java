package velora.outbreak.illness;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

import java.util.Locale;

public enum TransmissionVector implements StringRepresentable {
    AIRBORNE, FOODBORNE, NONE;

    public static final Codec<TransmissionVector> CODEC = StringRepresentable.fromEnum(TransmissionVector::values);

    @Override
    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }
}