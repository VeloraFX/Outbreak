package velora.outbreak.illness;

import java.util.ArrayList;
import java.util.List;

/** Visible symptoms an illness can show. The JSON "symptoms" list uses the keys below. */
public enum Symptom {
    SNEEZE("sneeze"),
    COUGH("cough"),
    SHIVER("shiver"),
    STOMACH_CLUTCH("stomach_clutch");

    private final String key;

    Symptom(String key) { this.key = key; }

    public String key() { return key; }

    /** @return the symptom with this key, or null if unknown. */
    public static Symptom byKey(String key) {
        for (Symptom s : values()) if (s.key.equals(key)) return s;
        return null;
    }

    public static boolean isKnown(String key) { return byKey(key) != null; }

    public static String knownNames() {
        StringBuilder sb = new StringBuilder();
        for (Symptom s : values()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(s.key);
        }
        return sb.toString();
    }

    /** Known symptoms, in order, without duplicates. Never empty: falls back to sneeze only. */
    public static List<Symptom> parse(List<String> keys) {
        List<Symptom> out = new ArrayList<>();
        for (String k : keys) {
            Symptom s = byKey(k);
            if (s != null && !out.contains(s)) out.add(s);
        }
        if (out.isEmpty()) out.add(SNEEZE);
        return List.copyOf(out);
    }
}