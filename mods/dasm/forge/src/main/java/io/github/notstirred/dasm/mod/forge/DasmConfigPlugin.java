package io.github.notstirred.dasm.mod.forge;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.notstirred.dasm.mod.DasmConfig;
import io.github.notstirred.dasm.mod.DasmConfigReader;
import io.github.notstirred.dasm.mod.OfficialConfigPlugin;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.LoadingModList;

/** Reads top-level dasm = ["mod.dasm.json"] declarations before game classes are loaded. */
public final class DasmConfigPlugin extends OfficialConfigPlugin {
    @Override protected List<DasmConfig> dasmConfigs() {
        List<DasmConfig> result = new ArrayList<>();
        String environment = FMLLoader.getDist().isClient() ? "client" : "server";
        for (var mod : LoadingModList.getModFiles()) {
            Object declaration = mod.getConfig().getConfigElement("dasm").orElse(null);
            if (declaration == null)
                continue;
            if (!(declaration instanceof List<?> entries)) {
                throw new IllegalArgumentException("dasm must be an array in " + mod.getFile().getFileName());
            }
            for (Object entry : entries) {
                if (!(entry instanceof String file) || file.isBlank() || file.startsWith("/") || file.contains("..")) {
                    throw new IllegalArgumentException("Invalid DASM config path in " + mod.getFile().getFileName());
                }
                try (var reader = java.nio.file.Files.newBufferedReader(mod.getFile().findResource(file), java.nio.charset.StandardCharsets.UTF_8)) {
                    result.add(DasmConfigReader.parseConfig(new Gson().fromJson(reader, JsonObject.class), environment, file));
                } catch (IOException | RuntimeException exception) {
                    throw new IllegalStateException("Cannot load DASM config " + file + " from " + mod.getFile().getFileName(), exception);
                }
            }
        }
        return result;
    }
}
