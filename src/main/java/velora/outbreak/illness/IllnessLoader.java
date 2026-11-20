package velora.outbreak.illness;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import velora.outbreak.OutbreakMod;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.StrictJsonParser;
import net.minecraft.util.profiling.ProfilerFiller;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Loads data/<namespace>/outbreak/illness/<id>.json.
 * Vanilla's scan logs "Couldn't parse data file" and skips files that fail the codec,
 * so a bad file can never crash a reload; the built-in with that id simply stays in place.
 *
 * The codec silently ignores keys it does not know, so after decoding, every file that ended up in use
 * is re-read as raw JSON and its keys are compared with IllnessDefinition / SpreadParams / OnsetParams
 * JSON_KEYS (top level, inside "spread", inside "natural_onset"). Each unknown key becomes an authoring
 * warning; the decoded definition is NOT changed. The re-read uses the same lister and the same strict
 * parser as vanilla's scanDirectory, so it sees exactly the files the codec decoded.
 */
public final class IllnessLoader extends SimpleJsonResourceReloadListener<IllnessDefinition> {
    public static final Identifier ID = Identifier.fromNamespaceAndPath(OutbreakMod.MOD_ID, "illnesses");
    private static final Pattern VALID_ID = Pattern.compile("[a-z0-9_]+");
    /** Shared by the codec scan (constructor) and the unknown-key re-read, so both see the same files. */
    private static final FileToIdConverter LISTER = FileToIdConverter.json("outbreak/illness");

    public IllnessLoader() {
        super(IllnessDefinition.CODEC, LISTER);
    }

    @Override
    protected void apply(Map<Identifier, IllnessDefinition> prepared, ResourceManager manager, ProfilerFiller profiler) {
        Map<String, IllnessDefinition> loaded = new LinkedHashMap<>();
        Map<String, Identifier> loadedFiles = new HashMap<>(); // id -> the file (converted id) that won

        // Sorted so duplicate handling is deterministic.
        for (Map.Entry<Identifier, IllnessDefinition> e : new TreeMap<>(prepared).entrySet()) {
            Identifier file = e.getKey();
            String id = file.getPath();
            if (!VALID_ID.matcher(id).matches()) {
                OutbreakMod.LOGGER.error("Ignoring illness file '{}': id must match [a-z0-9_]+ (no sub-folders).", file);
                continue;
            }
            if (loaded.containsKey(id)) {
                OutbreakMod.LOGGER.warn("Illness id '{}' is defined by more than one namespace; ignoring '{}'.", id, file);
                continue;
            }
            loaded.put(id, e.getValue().withId(id));
            loadedFiles.put(id, file);
        }

        Map<String, List<String>> keyWarnings = unknownKeyWarnings(manager, loadedFiles);

        // Authoring warnings: logged once per data load, never fatal.
        Map<String, List<String>> warnings = new LinkedHashMap<>();
        for (IllnessDefinition def : loaded.values()) {
            List<String> w = new ArrayList<>(def.warnings());
            w.addAll(keyWarnings.getOrDefault(def.id(), List.of()));
            if (w.isEmpty()) continue;
            warnings.put(def.id(), List.copyOf(w));
            for (String msg : w) OutbreakMod.LOGGER.warn("Illness '{}': {}", def.id(), msg);
        }

        IllnessRegistry.replaceLoaded(loaded, warnings);
        IllnessManager.resetWarnings();
        OutbreakMod.LOGGER.info("Illness data reloaded: {} from data, {} available in total (built-ins fill gaps), {} with warnings.",
                loaded.size(), IllnessRegistry.ids().size(), warnings.size());
    }

    /**
     * Unknown-key warnings per illness id, for the files in use only (invalid ids, duplicate-namespace
     * losers and files that failed the codec are not in loadedFiles; vanilla already logged the latter).
     * Never throws: a file that cannot be re-read is logged once and skipped.
     */
    private static Map<String, List<String>> unknownKeyWarnings(ResourceManager manager, Map<String, Identifier> loadedFiles) {
        Map<String, List<String>> out = new HashMap<>();
        if (loadedFiles.isEmpty()) return out;

        Map<Identifier, Resource> byId = new HashMap<>();
        try {
            for (Map.Entry<Identifier, Resource> e : LISTER.listMatchingResources(manager).entrySet()) {
                byId.put(LISTER.fileToId(e.getKey()), e.getValue());
            }
        } catch (RuntimeException ex) {
            OutbreakMod.LOGGER.warn("Illness unknown-key check skipped: could not list illness files ({}).", ex.toString());
            return out;
        }

        for (Map.Entry<String, Identifier> e : loadedFiles.entrySet()) {
            Identifier file = e.getValue();
            Resource resource = byId.get(file);
            if (resource == null) {
                OutbreakMod.LOGGER.debug("Illness unknown-key check: '{}' not found on re-read; skipped.", file);
                continue;
            }
            JsonElement root;
            try (Reader reader = resource.openAsReader()) {
                root = StrictJsonParser.parse(reader);
            } catch (IOException | RuntimeException ex) { // JsonParseException is a RuntimeException
                OutbreakMod.LOGGER.warn("Illness unknown-key check skipped for '{}' from '{}': {}",
                        file, resource.sourcePackId(), ex.toString());
                continue;
            }
            if (root == null || !root.isJsonObject()) continue; // the codec would have rejected it

            List<String> w = new ArrayList<>();
            JsonObject top = root.getAsJsonObject();
            checkKeys(top, "at the top level", IllnessDefinition.JSON_KEYS, w);
            checkNested(top, "spread", SpreadParams.JSON_KEYS, w);
            checkNested(top, "natural_onset", OnsetParams.JSON_KEYS, w);
            if (!w.isEmpty()) out.put(e.getKey(), w);
        }
        return out;
    }

    /** Checks a nested block; a block that is absent or not an object is skipped (the codec handles it). */
    private static void checkNested(JsonObject top, String key, List<String> known, List<String> out) {
        JsonElement block = top.get(key);
        if (block == null || !block.isJsonObject()) return;
        checkKeys(block.getAsJsonObject(), "in '" + key + "'", known, out);
    }

    private static void checkKeys(JsonObject obj, String where, List<String> known, List<String> out) {
        for (String key : new TreeSet<>(obj.keySet())) { // sorted: deterministic warnings
            if (known.contains(key)) continue;
            out.add("unknown key '" + key + "' " + where + " (known: " + String.join(", ", known) + "); it is ignored");
        }
    }
}