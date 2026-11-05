package io.github.notstirred.dasm.mod;

import java.util.*;

import com.google.gson.*;

/** Shared validation of DASM declarations on all loaders. */
public final class DasmConfigReader {
    private DasmConfigReader() {}

    public static DasmConfig parseConfig(JsonObject json, String environment, String file) {
        if (json == null)
            throw new IllegalArgumentException("Empty DASM config " + file);
        if (!json.has("dasm"))
            throw new IllegalArgumentException("Missing dasm array in " + file);
        Set<String> classes = new LinkedHashSet<>();
        addClasses(json.get("dasm"), classes, file);
        if (json.has(environment))
            addClasses(json.get(environment), classes, file);
        // The upstream generator uses requiredVersion=0.8 for its config
        // format; it is not a dependency range for the DASM engine version.
        String requiredVersion = json.has("requiredVersion") ? json.get("requiredVersion").getAsString() : null;
        return new DasmConfig(requiredVersion, List.copyOf(classes));
    }

    private static void addClasses(JsonElement element, Set<String> classes, String file) {
        if (!element.isJsonArray())
            throw new IllegalArgumentException("DASM class list must be an array in " + file);
        JsonArray entries = element.getAsJsonArray();
        for (JsonElement entry : entries) {
            if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString() || entry.getAsString().isBlank()) {
                throw new IllegalArgumentException("DASM class name must be a nonempty string in " + file);
            }
            classes.add(entry.getAsString());
        }
    }
}
