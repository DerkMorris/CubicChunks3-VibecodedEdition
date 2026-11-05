package io.github.notstirred.dasm.mod.fabric;

import com.google.gson.JsonParser;
import io.github.notstirred.dasm.mod.DasmConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DasmConfigPluginTest {
    @Test
    void excludesClientTransformsOnDedicatedServerAndDeduplicatesCommonEntries() {
        var json = JsonParser.parseString("""
                {"dasm":["common.Transform"],"client":["client.Transform"],
                 "server":["common.Transform","server.Transform"]}
                """).getAsJsonObject();
        DasmConfig config = DasmConfigPlugin.parseConfig(json, "server", "test.json");
        assertEquals(List.of("common.Transform", "server.Transform"), config.dasmClasses);
    }

    @Test
    void includesCommonAndClientTransformsOnClient() {
        var json = JsonParser.parseString("""
                {"dasm":["common.Transform"],"client":["client.Transform"],"server":["server.Transform"]}
                """).getAsJsonObject();
        assertEquals(List.of("common.Transform", "client.Transform"),
                DasmConfigPlugin.parseConfig(json, "client", "test.json").dasmClasses);
    }

    @Test
    void malformedConfigFailsInsteadOfSilentlyDisablingTransformations() {
        for (String malformed : List.of("{}", "{\"dasm\":42}", "{\"dasm\":[null]}", "{\"dasm\":[\"\"]}")) {
            var json = JsonParser.parseString(malformed).getAsJsonObject();
            assertThrows(IllegalArgumentException.class, () -> DasmConfigPlugin.parseConfig(json, "server", "test.json"));
        }
    }
}
