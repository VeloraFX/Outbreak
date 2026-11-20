package velora.outbreak.diagnosis;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** A microscope observation. Each has levels 0..2 and is revealed at one zoom stage (1..3). */
public enum Feature {
    CELL_DENSITY(1),
    CELL_TYPE(2),
    PARTICLES(3),
    INFLAMMATION(3);

    public static final int MIN_ZOOM = 1;
    public static final int MAX_ZOOM = 3;

    public final int zoom;

    Feature(int zoom) { this.zoom = zoom; }

    public String key() { return name().toLowerCase(Locale.ROOT); }

    /** Order is the enum order; server and client rely on it for parallel level lists. */
    public static List<Feature> atZoom(int zoom) {
        List<Feature> out = new ArrayList<>();
        for (Feature f : values()) if (f.zoom == zoom) out.add(f);
        return out;
    }
}