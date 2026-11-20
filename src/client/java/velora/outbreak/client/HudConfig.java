package velora.outbreak.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.GsonHelper;
import velora.outbreak.OutbreakMod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Client-only preferences, config/outbreak-client.json (section "hud"). Same rules as OutbreakConfig: a missing
 * file is written once and never overwritten; wrong type -> default, out of range -> clamped, unknown key ->
 * ignored, each logged once; an unreadable file means all defaults. The values are an immutable snapshot.
 * Loaded once at client start (restart the game to apply changes).
 */
public final class HudConfig {
    private HudConfig() {}

    public static final String FILE_NAME = "outbreak-client.json";

    public record Values(boolean enabled, int maxLines, int nearbyRadiusBlocks, int ageOutDays) {}

    public static final Values DEFAULTS = new Values(true, 3, 128, 7);

    private static final Set<String> HUD_KEYS = Set.of("enabled", "max_lines", "nearby_radius_blocks", "age_out_days");

    private static volatile Values current = DEFAULTS;

    public static Values get() { return current; }

    /** Never throws. */
    public static void load() {
        List<String> warnings = new ArrayList<>();
        Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        Values values = DEFAULTS;
        boolean wrote = false;
        try {
            if (!Files.isRegularFile(path)) {
                try {
                    Files.createDirectories(path.getParent());
                    Files.writeString(path, defaultFileText(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                    wrote = true;
                } catch (IOException | RuntimeException e) {
                    warnings.add("could not write the default file: " + e);
                }
            } else {
                String text = Files.readString(path, StandardCharsets.UTF_8);
                if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
                JsonObject root = GsonHelper.parse(text);
                if (root == null) warnings.add("file is empty; using defaults");
                else values = parse(root, warnings);
            }
        } catch (IOException | RuntimeException e) {
            warnings.add("could not read or parse " + FILE_NAME + " (" + e.getMessage()
                    + "); using ALL defaults. The file was left untouched.");
            values = DEFAULTS;
        }
        current = values;
        for (String w : warnings) OutbreakMod.LOGGER.warn("[hud config] {}", w);
        OutbreakMod.LOGGER.info("[hud config] {} ({} warning(s))",
                wrote ? "wrote default " + FILE_NAME : "loaded " + FILE_NAME, warnings.size());
    }

    private static Values parse(JsonObject root, List<String> w) {
        for (Map.Entry<String, JsonElement> e : root.entrySet()) {
            if (!e.getKey().equals("hud")) w.add("unknown key '" + e.getKey() + "' (ignored)");
        }
        JsonObject hud = null;
        if (root.has("hud")) {
            if (root.get("hud").isJsonObject()) hud = root.getAsJsonObject("hud");
            else w.add("'hud' must be an object; using defaults");
        }
        if (hud == null) return DEFAULTS;
        for (Map.Entry<String, JsonElement> e : hud.entrySet()) {
            if (!HUD_KEYS.contains(e.getKey())) w.add("unknown key 'hud." + e.getKey() + "' (ignored)");
        }
        return new Values(
                bool(hud, "enabled", DEFAULTS.enabled(), w),
                integer(hud, "max_lines", DEFAULTS.maxLines(), 1, 8, w),
                integer(hud, "nearby_radius_blocks", DEFAULTS.nearbyRadiusBlocks(), 16, 1024, w),
                integer(hud, "age_out_days", DEFAULTS.ageOutDays(), 1, 60, w));
    }

    private static boolean bool(JsonObject o, String key, boolean def, List<String> w) {
        if (!o.has(key)) return def;
        JsonElement el = o.get(key);
        if (!GsonHelper.isBooleanValue(el)) {
            w.add("'hud." + key + "' must be true or false; using default " + def);
            return def;
        }
        return el.getAsBoolean();
    }

    private static int integer(JsonObject o, String key, int def, int min, int max, List<String> w) {
        if (!o.has(key)) return def;
        JsonElement el = o.get(key);
        if (!GsonHelper.isNumberValue(el) || !Double.isFinite(el.getAsDouble())) {
            w.add("'hud." + key + "' must be a whole number; using default " + def);
            return def;
        }
        double v = el.getAsDouble();
        long r = Math.round(v);
        if (r != v) w.add("'hud." + key + "' = " + v + " is not a whole number; rounded to " + r);
        long c = Math.max(min, Math.min(max, r));
        if (c != r) w.add("'hud." + key + "' = " + r + " is outside " + min + ".." + max + "; clamped to " + c);
        return (int) c;
    }

    static String defaultFileText() {
        Values d = DEFAULTS;
        return "{\n"
                + "  \"hud\": {\n"
                + "    \"enabled\": " + d.enabled() + ",\n"
                + "    \"max_lines\": " + d.maxLines() + ",\n"
                + "    \"nearby_radius_blocks\": " + d.nearbyRadiusBlocks() + ",\n"
                + "    \"age_out_days\": " + d.ageOutDays() + "\n"
                + "  }\n"
                + "}\n";
    }
}