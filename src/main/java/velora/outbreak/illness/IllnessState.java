package velora.outbreak.illness;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;
import java.util.Locale;

public enum IllnessState implements StringRepresentable {
    INCUBATING, SYMPTOMATIC, UNDER_TREATMENT, RECOVERING, RECOVERED;

    public static final Codec<IllnessState> CODEC = StringRepresentable.fromEnum(IllnessState::values);

    @Override
    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static IllnessState byName(String s) {
        for (IllnessState v : values()) if (v.getSerializedName().equals(s)) return v;
        return null;
    }
}