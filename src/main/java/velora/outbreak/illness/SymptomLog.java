package velora.outbreak.illness;

import com.mojang.serialization.Codec;

import java.util.ArrayList;
import java.util.List;

/**
 * Tiny record of the VISIBLE symptoms a villager showed recently (server-side, never sent to clients).
 * Each event is one long: (gameTime << 3) | symptom ordinal. At most MAX_PER_SYMPTOM events are kept
 * per symptom (the newest), so the list never exceeds MAX_PER_SYMPTOM x symptom count entries.
 * Only symptoms that were really shown are logged (budget-skipped ones are not).
 */
public final class SymptomLog {
    public static final int MAX_PER_SYMPTOM = 6;
    /** Hard retention and the largest examination window the config may ask for (7 in-game days). */
    public static final int MAX_WINDOW_TICKS = 168_000;

    private static final int BITS = 3;
    private static final int MASK = (1 << BITS) - 1;

    /** Decoding sanitises: bad ordinals, negative values and over-long lists are dropped, never an error. */
    public static final Codec<List<Long>> CODEC = Codec.LONG.listOf().xmap(SymptomLog::sanitize, l -> l);

    private SymptomLog() {}

    /** Keeps only valid events, newest MAX_PER_SYMPTOM per symptom, in the original order. */
    static List<Long> sanitize(List<Long> in) {
        int kinds = Symptom.values().length;
        int[] kept = new int[kinds];
        ArrayList<Long> rev = new ArrayList<>();
        for (int i = in.size() - 1; i >= 0; i--) {
            long e = in.get(i);
            int ord = (int) (e & MASK);
            if (e < 0 || ord >= kinds || kept[ord] >= MAX_PER_SYMPTOM) continue;
            kept[ord]++;
            rev.add(e);
        }
        java.util.Collections.reverse(rev);
        return List.copyOf(rev);
    }

    /** A new list with the event appended; events older than the retention (or in the future) are dropped. */
    public static List<Long> add(List<Long> log, Symptom s, long now) {
        ArrayList<Long> next = new ArrayList<>(log.size() + 1);
        for (long e : log) {
            long age = now - (e >> BITS);
            if (age >= 0 && age <= MAX_WINDOW_TICKS) next.add(e);
        }
        next.add((now << BITS) | s.ordinal());
        return sanitize(next);
    }

    /** Events per symptom (indexed by Symptom.ordinal()) that happened within the last windowTicks. */
    public static int[] counts(List<Long> log, long now, long windowTicks) {
        int[] c = new int[Symptom.values().length];
        for (long e : log) {
            long age = now - (e >> BITS);
            int ord = (int) (e & MASK);
            if (age >= 0 && age <= windowTicks && ord < c.length) c[ord]++;
        }
        return c;
    }

    /** 0 = not noticed, 1 = now and then, 2 = often, 3 = very often. */
    public static int bucket(int count) {
        if (count <= 0) return 0;
        if (count <= 2) return 1;
        if (count <= 4) return 2;
        return 3;
    }

    /** Debug text for /outbreak inspect. */
    public static String describe(List<Long> log, long now) {
        if (log.isEmpty()) return "none";
        StringBuilder sb = new StringBuilder();
        for (long e : log) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(Symptom.values()[(int) (e & MASK)].key()).append('@').append(now - (e >> BITS)).append("t-ago");
        }
        return sb.toString();
    }
}