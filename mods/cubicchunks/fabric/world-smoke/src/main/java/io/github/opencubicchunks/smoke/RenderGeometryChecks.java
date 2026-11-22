package io.github.opencubicchunks.smoke;

import java.lang.reflect.Field;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/** Geometry assertions for the active renderer; optional Sodium classes are resolved only when it is installed. */
final class RenderGeometryChecks {
    private RenderGeometryChecks() {}

    static boolean hasVisibleGeometryNear(Minecraft client, int blockY, int radius) {
        if (FabricLoader.getInstance().isModLoaded("sodium")) {
            return Sodium.hasVisibleGeometryNear(blockY, radius);
        }
        return client.levelRenderer.visibleSections().stream()
                .anyMatch(section -> Math.abs((long) section.getRenderOrigin().getY() - blockY) < radius
                        && section.getSectionMesh().hasRenderableLayers());
    }

    private static final class Sodium {
        private static final Field MANAGER = managerField();

        private static boolean hasVisibleGeometryNear(int blockY, int radius) {
            var renderer = net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer.instanceNullable();
            if (renderer == null) return false;
            try {
                var manager = (net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager) MANAGER.get(renderer);
                if (manager == null) return false;
                var lists = manager.getRenderLists().iterator();
                while (lists.hasNext()) {
                    var list = lists.next();
                    var sections = list.sectionsWithGeometryIterator(false);
                    if (sections == null) continue;
                    while (sections.hasNext()) {
                        int localY = net.caffeinemc.mods.sodium.client.render.chunk.LocalSectionIndex.unpackY(sections.nextByteAsInt());
                        int originY = (list.getRegion().getChunkY() + localY) << 4;
                        if (Math.abs((long) originY - blockY) < radius) return true;
                    }
                }
                return false;
            } catch (IllegalAccessException failure) {
                throw new IllegalStateException("Cannot inspect Sodium render geometry", failure);
            }
        }

        private static Field managerField() {
            try {
                var field = net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer.class.getDeclaredField("renderSectionManager");
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }
    }
}
