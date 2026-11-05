package io.github.notstirred.dasm.mod.fabric;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.notstirred.dasm.api.provider.MappingsProvider;
import io.github.notstirred.dasm.mod.BaseConfigPlugin;
import io.github.notstirred.dasm.mod.DasmConfig;
import io.github.notstirred.dasm.util.CachingClassProvider;
import io.github.notstirred.dasm.util.ClassNodeProvider;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.CustomValue;
import org.spongepowered.asm.service.MixinService;

/**
 * Uses official names throughout: this adapter deliberately targets the
 * unobfuscated 26.3 release, not intermediary-mapped Minecraft releases.
 * A consuming mod declares {@code "custom":{"dasm":["mod.dasm.json"]}}.
 * Each config accepts common "dasm" and optional "client"/"server" arrays.
 */
public final class DasmConfigPlugin extends BaseConfigPlugin {
    @Override protected MappingsProvider mappingsProvider() {
        return MappingsProvider.IDENTITY;
    }

    @Override protected ClassNodeProvider classNodeProvider() {
        return new CachingClassProvider(name -> {
            String resource = name.replace('.', '/') + ".class";
            try (InputStream stream = MixinService.getService().getResourceAsStream(resource)) {
                return stream == null ? Optional.empty() : Optional.of(stream.readAllBytes());
            } catch (IOException exception) {
                throw new IllegalStateException("Cannot read DASM class " + name, exception);
            }
        });
    }

    @Override protected List<DasmConfig> dasmConfigs() {
        List<DasmConfig> result = new ArrayList<>();
        FabricLoader loader = FabricLoader.getInstance();
        String environment = loader.getEnvironmentType() == EnvType.CLIENT ? "client" : "server";
        for (ModContainer mod : loader.getAllMods()) {
            CustomValue declaration = mod.getMetadata().getCustomValue("dasm");
            if (declaration == null)
                continue;
            if (declaration.getType() != CustomValue.CvType.ARRAY) {
                throw new IllegalArgumentException("custom.dasm must be an array in " + mod.getMetadata().getId());
            }
            for (CustomValue entry : declaration.getAsArray()) {
                if (entry.getType() != CustomValue.CvType.STRING) {
                    throw new IllegalArgumentException("custom.dasm entries must be strings in " + mod.getMetadata().getId());
                }
                String file = entry.getAsString();
                Path path = mod.findPath(file)
                        .orElseThrow(() -> new IllegalArgumentException("Missing DASM config " + file + " in " + mod.getMetadata().getId()));
                try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    result.add(parseConfig(new Gson().fromJson(reader, JsonObject.class), environment, file));
                } catch (IOException | RuntimeException exception) {
                    throw new IllegalStateException("Cannot load DASM config " + file + " from " + mod.getMetadata().getId(), exception);
                }
            }
        }
        return result;
    }

    static DasmConfig parseConfig(JsonObject json, String environment, String file) {
        return io.github.notstirred.dasm.mod.DasmConfigReader.parseConfig(json, environment, file);
    }
}
