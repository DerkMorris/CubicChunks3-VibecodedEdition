package io.github.notstirred.dasm.mod;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

import io.github.notstirred.dasm.api.provider.MappingsProvider;
import io.github.notstirred.dasm.util.CachingClassProvider;
import io.github.notstirred.dasm.util.ClassNodeProvider;
import org.spongepowered.asm.service.MixinService;

/** Minecraft 26.3 ships official names on all supported loaders. */
public abstract class OfficialConfigPlugin extends BaseConfigPlugin {
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

}
