package velora.outbreak.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.GsonHelper;
import velora.outbreak.OutbreakMod;
import velora.outbreak.illness.SymptomLog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


public final class OutbreakConfig {
    private OutbreakConfig() {}

    public static final String FILE_NAME = "outbreak.json";

    /** Absolute limits for command arguments (the configured limits are applied on top of these). */
    public static final double HARD_MAX_CHANCE = 1000.0;
    public static final double HARD_MIN_RANGE = 0.05;
    public static final double HARD_MAX_RANGE = 16.0;
    /** Upper limit for symptoms.pause_ticks (3 seconds). */
    public static final int MAX_PAUSE_TICKS = 60;

    public record Spread(boolean enabled, double chanceMultiplier, double rangeMultiplier,
                         int passIntervalTicks, int passTickOffset,
                         int maxSourcesPerPass, int maxCandidatesPerSource, int maxInfectionsPerPass,
                         double edgeProximityWeight,
                         double maxChanceMultiplier, double minRangeMultiplier, double maxRangeMultiplier,
                         double maxEffectiveRange) {}

    /** pauseTicks: how long a villager stands still while showing a symptom (0 = off). */
    public record Symptoms(double frequencyMultiplier, int maxSymptomEffectsPerTick, int maxRecoveryEffectsPerTick,
                           int pauseTicks) {}

    /** cooldownTicks: per-player item cooldown after an examination. recentWindowTicks: how far back "recently" reaches. */
    public record Examination(int cooldownTicks, int recentWindowTicks) {}

    /**
     * Natural onset (villagers falling ill on their own near players). Read once per check, so a reload applies
     * from the next check. maxIllNearby = 0 means no per-area ceiling; maxTrackedGlobal = 0 means no global
     * ceiling. A cooldown of 0 means no cooldown. Distances are in blocks, times in ticks.
     */
    public record Onset(boolean enabled, int checkIntervalTicks, double chanceMultiplier, int maxNewCasesPerCheck,
                        int maxPlayersPerCheck, int radiusBlocks, int verticalBlocks, int maxCandidatesPerPlayer,
                        int maxIllNearby, int maxTrackedGlobal, int globalCooldownTicks, int areaCooldownTicks,
                        int areaCooldownRadius, int graceTicks, int startupDelayTicks) {}

    public record Values(Spread spread, Symptoms symptoms, Examination examination, Onset onset) {}

    public record LoadResult(String path, boolean fileExisted, boolean wroteDefaults, boolean usedDefaultsOnly,
                             List<String> warnings) {}

    public static final Values DEFAULTS = new Values(
            new Spread(true, 1.0, 1.0, 20, 10, 32, 12, 4, 0.5, 100.0, 0.25, 4.0, 32.0),
            new Symptoms(1.0, 16, 6, 40),
            new Examination(20, 3600),
            new Onset(true, 1200, 1.0, 1, 8, 48, 16, 32, 3, 48, 2400, 24000, 64, 24000, 6000));

    private static volatile Values current = DEFAULTS;
    private static volatile LoadResult last = new LoadResult("", false, false, true, List.of());

    public static Values get() { return current; }
    public static Spread spread() { return current.spread(); }
    public static Symptoms symptoms() { return current.symptoms(); }
    public static Examination examination() { return current.examination(); }
    public static Onset onset() { return current.onset(); }
    public static LoadResult lastResult() { return last; }

    public static Path path() { return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME); }

    // ------------------------------------------------------------------ load

    /** Loads (or reloads) the file. Never throws. */
    public static LoadResult reload() {
        List<String> warnings = new ArrayList<>();
        Path path = path();
        Values values = DEFAULTS;
        boolean existed = false, wrote = false, defaultsOnly = true;
        try {
            existed = Files.isRegularFile(path);
            if (!existed) {
                try {
                    Files.createDirectories(path.getParent());
                    Files.writeString(path, defaultFileText(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                    wrote = true;
                } catch (IOException | RuntimeException e) {
                    warnings.add("could not write the default config file: " + e);
                }
            } else {
                String text = Files.readString(path, StandardCharsets.UTF_8);
                if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1); // editor BOM
                JsonObject root = GsonHelper.parse(text);
                if (root == null) {
                    warnings.add("file is empty; using all defaults");
                } else {
                    values = parse(root, warnings);
                    defaultsOnly = false;
                }
            }
        } catch (IOException | RuntimeException e) { // RuntimeException also covers Gson's JsonParseException
            warnings.add("could not read or parse " + path.getFileName() + " (" + e.getMessage()
                    + "); using ALL defaults. The file was left untouched: fix it and run /outbreak config reload.");
            values = DEFAULTS;
            defaultsOnly = true;
        }
        current = values;
        last = new LoadResult(path.toString(), existed, wrote, defaultsOnly, List.copyOf(warnings));
        for (String w : warnings) OutbreakMod.LOGGER.warn("[config] {}", w);
        OutbreakMod.LOGGER.info("[config] {} ({} warning(s))",
                wrote ? "wrote default " + FILE_NAME : "loaded " + FILE_NAME, warnings.size());
        return last;
    }

    // --------------------------------------------------------------- parsing

    private static Values parse(JsonObject root, List<String> w) {
        Reader top = new Reader(root, "", w);

        Reader s = top.section("spread");
        Spread d = DEFAULTS.spread();
        // Limits first: the other values are clamped to them. min <= 1 <= max by their own ranges.
        double maxChance = s.num("max_chance_multiplier", d.maxChanceMultiplier(), 1.0, HARD_MAX_CHANCE);
        double minRange = s.num("min_range_multiplier", d.minRangeMultiplier(), HARD_MIN_RANGE, 1.0);
        double maxRange = s.num("max_range_multiplier", d.maxRangeMultiplier(), 1.0, HARD_MAX_RANGE);
        double maxEffective = s.num("max_effective_range", d.maxEffectiveRange(), 1.0, 48.0);
        boolean enabled = s.bool("enabled", d.enabled());
        double chance = s.num("chance_multiplier", d.chanceMultiplier(), 0.0, maxChance);
        double range = s.num("range_multiplier", d.rangeMultiplier(), minRange, maxRange);
        int interval = s.integer("pass_interval_ticks", d.passIntervalTicks(), 10, 200);
        int offset = s.integer("pass_tick_offset", Math.min(d.passTickOffset(), interval - 1), 0, interval - 1);
        int sources = s.integer("max_sources_per_pass", d.maxSourcesPerPass(), 1, 256);
        int candidates = s.integer("max_candidates_per_source", d.maxCandidatesPerSource(), 1, 64);
        int infections = s.integer("max_infections_per_pass", d.maxInfectionsPerPass(), 1, 64);
        double edge = s.num("edge_proximity_weight", d.edgeProximityWeight(), 0.0, 1.0);
        s.finish();
        Spread spread = new Spread(enabled, chance, range, interval, offset, sources, candidates, infections,
                edge, maxChance, minRange, maxRange, maxEffective);

        Reader y = top.section("symptoms");
        Symptoms sd = DEFAULTS.symptoms();
        Symptoms symptoms = new Symptoms(
                y.num("frequency_multiplier", sd.frequencyMultiplier(), 0.1, 10.0),
                y.integer("max_symptom_effects_per_tick", sd.maxSymptomEffectsPerTick(), 1, 256),
                y.integer("max_recovery_effects_per_tick", sd.maxRecoveryEffectsPerTick(), 1, 256),
                y.integer("pause_ticks", sd.pauseTicks(), 0, MAX_PAUSE_TICKS));
        y.finish();

        Reader x = top.section("examination");
        Examination xd = DEFAULTS.examination();
        Examination examination = new Examination(
                x.integer("cooldown_ticks", xd.cooldownTicks(), 0, 1200),
                x.integer("recent_window_ticks", xd.recentWindowTicks(), 600, SymptomLog.MAX_WINDOW_TICKS));
        x.finish();

        // A missing "onset" section (every config written before 6d) silently means defaults.
        Reader o = top.section("onset");
        Onset od = DEFAULTS.onset();
        Onset onset = new Onset(
                o.bool("enabled", od.enabled()),
                o.integer("check_interval_ticks", od.checkIntervalTicks(), 200, 24000),
                o.num("chance_multiplier", od.chanceMultiplier(), 0.0, 100.0),
                o.integer("max_new_cases_per_check", od.maxNewCasesPerCheck(), 1, 8),
                o.integer("max_players_per_check", od.maxPlayersPerCheck(), 1, 64),
                o.integer("radius_blocks", od.radiusBlocks(), 16, 96),
                o.integer("vertical_blocks", od.verticalBlocks(), 4, 64),
                o.integer("max_candidates_per_player", od.maxCandidatesPerPlayer(), 1, 64),
                o.integer("max_ill_nearby", od.maxIllNearby(), 0, 64),
                o.integer("max_tracked_global", od.maxTrackedGlobal(), 0, 1024),
                o.integer("global_cooldown_ticks", od.globalCooldownTicks(), 0, 240000),
                o.integer("area_cooldown_ticks", od.areaCooldownTicks(), 0, 480000),
                o.integer("area_cooldown_radius", od.areaCooldownRadius(), 16, 256),
                o.integer("grace_ticks", od.graceTicks(), 0, 240000),
                o.integer("startup_delay_ticks", od.startupDelayTicks(), 0, 72000));
        o.finish();

        top.finish();
        return new Values(spread, symptoms, examination, onset);
    }

    /** Reads one JSON object with validation. A null object (missing/invalid section) yields defaults. */
    private static final class Reader {
        private final JsonObject obj;
        private final String prefix;
        private final List<String> warnings;
        private final Set<String> known = new HashSet<>();

        Reader(JsonObject obj, String prefix, List<String> warnings) {
            this.obj = obj;
            this.prefix = prefix;
            this.warnings = warnings;
        }

        Reader section(String name) {
            known.add(name);
            if (obj == null || !obj.has(name)) return new Reader(null, name + ".", warnings);
            JsonElement el = obj.get(name);
            if (!el.isJsonObject()) {
                warnings.add("'" + prefix + name + "' must be an object; using defaults for that section");
                return new Reader(null, name + ".", warnings);
            }
            return new Reader(el.getAsJsonObject(), prefix + name + ".", warnings);
        }

        double num(String key, double def, double min, double max) {
            known.add(key);
            if (obj == null || !obj.has(key)) return def;
            JsonElement el = obj.get(key);
            if (!GsonHelper.isNumberValue(el) || !Double.isFinite(el.getAsDouble())) {
                warnings.add("'" + prefix + key + "' must be a number; using default " + def);
                return def;
            }
            double v = el.getAsDouble();
            double c = Math.max(min, Math.min(max, v));
            if (c != v) warnings.add("'" + prefix + key + "' = " + v + " is outside " + min + ".." + max + "; clamped to " + c);
            return c;
        }

        int integer(String key, int def, int min, int max) {
            known.add(key);
            if (obj == null || !obj.has(key)) return def;
            JsonElement el = obj.get(key);
            if (!GsonHelper.isNumberValue(el) || !Double.isFinite(el.getAsDouble())) {
                warnings.add("'" + prefix + key + "' must be a whole number; using default " + def);
                return def;
            }
            double v = el.getAsDouble();
            long r = Math.round(v);
            if (r != v) warnings.add("'" + prefix + key + "' = " + v + " is not a whole number; rounded to " + r);
            long c = Math.max(min, Math.min(max, r));
            if (c != r) warnings.add("'" + prefix + key + "' = " + r + " is outside " + min + ".." + max + "; clamped to " + c);
            return (int) c;
        }

        boolean bool(String key, boolean def) {
            known.add(key);
            if (obj == null || !obj.has(key)) return def;
            JsonElement el = obj.get(key);
            if (!GsonHelper.isBooleanValue(el)) {
                warnings.add("'" + prefix + key + "' must be true or false; using default " + def);
                return def;
            }
            return el.getAsBoolean();
        }

        /** Warns about keys nobody asked for (typos). */
        void finish() {
            if (obj == null) return;
            for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                if (!known.contains(e.getKey())) warnings.add("unknown key '" + prefix + e.getKey() + "' (ignored)");
            }
        }
    }

    // ----------------------------------------------------------- default file

    static String defaultFileText() {
        Spread s = DEFAULTS.spread();
        Symptoms y = DEFAULTS.symptoms();
        Examination x = DEFAULTS.examination();
        Onset o = DEFAULTS.onset();
        return "{\n"
                + "  \"spread\": {\n"
                + "    \"enabled\": " + s.enabled() + ",\n"
                + "    \"chance_multiplier\": " + s.chanceMultiplier() + ",\n"
                + "    \"range_multiplier\": " + s.rangeMultiplier() + ",\n"
                + "    \"pass_interval_ticks\": " + s.passIntervalTicks() + ",\n"
                + "    \"pass_tick_offset\": " + s.passTickOffset() + ",\n"
                + "    \"max_sources_per_pass\": " + s.maxSourcesPerPass() + ",\n"
                + "    \"max_candidates_per_source\": " + s.maxCandidatesPerSource() + ",\n"
                + "    \"max_infections_per_pass\": " + s.maxInfectionsPerPass() + ",\n"
                + "    \"edge_proximity_weight\": " + s.edgeProximityWeight() + ",\n"
                + "    \"max_chance_multiplier\": " + s.maxChanceMultiplier() + ",\n"
                + "    \"min_range_multiplier\": " + s.minRangeMultiplier() + ",\n"
                + "    \"max_range_multiplier\": " + s.maxRangeMultiplier() + ",\n"
                + "    \"max_effective_range\": " + s.maxEffectiveRange() + "\n"
                + "  },\n"
                + "  \"symptoms\": {\n"
                + "    \"frequency_multiplier\": " + y.frequencyMultiplier() + ",\n"
                + "    \"max_symptom_effects_per_tick\": " + y.maxSymptomEffectsPerTick() + ",\n"
                + "    \"max_recovery_effects_per_tick\": " + y.maxRecoveryEffectsPerTick() + ",\n"
                + "    \"pause_ticks\": " + y.pauseTicks() + "\n"
                + "  },\n"
                + "  \"examination\": {\n"
                + "    \"cooldown_ticks\": " + x.cooldownTicks() + ",\n"
                + "    \"recent_window_ticks\": " + x.recentWindowTicks() + "\n"
                + "  },\n"
                + "  \"onset\": {\n"
                + "    \"enabled\": " + o.enabled() + ",\n"
                + "    \"check_interval_ticks\": " + o.checkIntervalTicks() + ",\n"
                + "    \"chance_multiplier\": " + o.chanceMultiplier() + ",\n"
                + "    \"max_new_cases_per_check\": " + o.maxNewCasesPerCheck() + ",\n"
                + "    \"max_players_per_check\": " + o.maxPlayersPerCheck() + ",\n"
                + "    \"radius_blocks\": " + o.radiusBlocks() + ",\n"
                + "    \"vertical_blocks\": " + o.verticalBlocks() + ",\n"
                + "    \"max_candidates_per_player\": " + o.maxCandidatesPerPlayer() + ",\n"
                + "    \"max_ill_nearby\": " + o.maxIllNearby() + ",\n"
                + "    \"max_tracked_global\": " + o.maxTrackedGlobal() + ",\n"
                + "    \"global_cooldown_ticks\": " + o.globalCooldownTicks() + ",\n"
                + "    \"area_cooldown_ticks\": " + o.areaCooldownTicks() + ",\n"
                + "    \"area_cooldown_radius\": " + o.areaCooldownRadius() + ",\n"
                + "    \"grace_ticks\": " + o.graceTicks() + ",\n"
                + "    \"startup_delay_ticks\": " + o.startupDelayTicks() + "\n"
                + "  }\n"
                + "}\n";
    }
}