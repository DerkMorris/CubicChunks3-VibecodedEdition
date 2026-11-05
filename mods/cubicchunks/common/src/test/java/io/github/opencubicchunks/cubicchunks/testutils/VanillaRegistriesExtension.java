package io.github.opencubicchunks.cubicchunks.testutils;

import java.util.List;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.tags.TagLoader;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

/** Loads real vanilla world-generation registries for tests that previously started an entire server just to obtain them. */
public final class VanillaRegistriesExtension implements ParameterResolver {
    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(VanillaRegistriesExtension.class);
    private static RegistryAccess.Frozen sharedRegistries;

    @Override public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
        return parameter.getParameter().getType() == RegistryAccess.Frozen.class;
    }

    @Override public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
        return context.getRoot().getStore(NAMESPACE).getOrComputeIfAbsent("registries", ignored -> shared(), RegistryAccess.Frozen.class);
    }

    /** Shared only after the test launcher has bootstrapped vanilla registries. */
    public static synchronized RegistryAccess.Frozen shared() {
        if (sharedRegistries == null) {
            sharedRegistries = load();
        }
        return sharedRegistries;
    }

    public static RegistryAccess.Frozen load() {
        var packs = ServerPacksSource.createVanillaTrustedRepository();
        packs.reload();
        packs.setSelected(List.of("vanilla"));
        try (var resources = new MultiPackResourceManager(PackType.SERVER_DATA, packs.openAllSelected())) {
            var layers = RegistryLayer.createRegistryAccess();
            var tags = TagLoader.loadTagsForExistingRegistries(resources, layers.getLayer(RegistryLayer.STATIC));
            var lookups = TagLoader.buildUpdatedLookups(layers.getAccessForLoading(RegistryLayer.WORLD), tags);
            var world = RegistryDataLoader.load(resources, lookups, RegistryDataLoader.WORLD_REGISTRIES, Runnable::run).join();
            tags.forEach(pending -> pending.apply());
            var registries = layers.replaceFrom(RegistryLayer.WORLD, world).compositeAccess();
            // 26.3 binds item/entity defaults after registry loading, not during Bootstrap.bootStrap.
            // This is the same initializer and application step used by ReloadableServerResources.
            BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(registries).forEach(pending -> pending.apply());
            return registries;
        }
    }
}
