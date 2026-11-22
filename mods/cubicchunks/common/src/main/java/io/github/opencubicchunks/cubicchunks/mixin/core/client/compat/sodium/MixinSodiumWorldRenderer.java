package io.github.opencubicchunks.cubicchunks.mixin.core.client.compat.sodium;

import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientCubeCache;
import io.github.opencubicchunks.cubicchunks.client.renderer.CubicLevelRenderer;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Feeds only resident cube sections to Sodium, including after a renderer/resource reload. */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer", remap = false)
public abstract class MixinSodiumWorldRenderer implements CubicLevelRenderer {
    @Shadow private ClientLevel level;
    @Shadow private RenderSectionManager renderSectionManager;
    @Unique private final ConcurrentLinkedQueue<Map.Entry<CubePos, Boolean>> cc_cubeChanges = new ConcurrentLinkedQueue<>();
    @Unique private final LongOpenHashSet cc_loadedCubes = new LongOpenHashSet();
    @Unique private final AtomicBoolean cc_viewChanged = new AtomicBoolean();

    @Override public void cc_onCubeReadyToRender(CubePos pos) {
        cc_cubeChanges.add(Map.entry(pos, true));
    }

    @Override public void cc_onCubeUnloaded(CubePos pos) {
        cc_cubeChanges.add(Map.entry(pos, false));
    }

    @Override public void cc_onCubeViewChanged() {
        cc_viewChanged.set(true);
    }

    @Inject(method = "processChunkEvents", at = @At("HEAD"), cancellable = true)
    private void cc_processCubeEvents(CallbackInfo ci) {
        if (!((CanBeCubic) level).cc_isCubic()) {
            return;
        }
        renderSectionManager.beforeSectionUpdates();
        cc_drainCubeChanges();
        ci.cancel();
    }

    @Inject(method = "initRenderer", at = @At("RETURN"))
    private void cc_restoreCubesAfterReload(CallbackInfo ci) {
        if (!((CanBeCubic) level).cc_isCubic()) {
            return;
        }
        renderSectionManager.beforeSectionUpdates();
        for (long key : cc_loadedCubes) {
            CubePos pos = CubePos.from(key);
            if (cc_hasCube(pos)) {
                cc_updateSections(pos, true);
            }
        }
        cc_drainCubeChanges();
    }

    @Inject(method = "unloadLevel", at = @At("HEAD"))
    private void cc_clearCubesOnWorldChange(CallbackInfo ci) {
        cc_cubeChanges.clear();
        cc_loadedCubes.clear();
        cc_viewChanged.set(false);
    }

    @Unique
    private void cc_drainCubeChanges() {
        if (cc_viewChanged.getAndSet(false)) {
            var iterator = cc_loadedCubes.iterator();
            while (iterator.hasNext()) {
                CubePos pos = CubePos.from(iterator.nextLong());
                if (!cc_hasCube(pos)) {
                    iterator.remove();
                    cc_updateSections(pos, false);
                }
            }
        }
        Map.Entry<CubePos, Boolean> change;
        while ((change = cc_cubeChanges.poll()) != null) {
            CubePos pos = change.getKey();
            boolean present = change.getValue() && cc_hasCube(pos);
            boolean changed = present ? cc_loadedCubes.add(pos.asLong()) : cc_loadedCubes.remove(pos.asLong());
            if (changed) {
                cc_updateSections(pos, present);
            }
        }
    }

    @Unique
    private boolean cc_hasCube(CubePos pos) {
        return ((ClientCubeCache) level.getChunkSource()).cc_getCube(pos.getX(), pos.getY(), pos.getZ(), false) != null;
    }

    @Unique
    private void cc_updateSections(CubePos pos, boolean added) {
        for (int dx = 0; dx < CubicConstants.DIAMETER_IN_SECTIONS; dx++) {
            for (int dy = 0; dy < CubicConstants.DIAMETER_IN_SECTIONS; dy++) {
                for (int dz = 0; dz < CubicConstants.DIAMETER_IN_SECTIONS; dz++) {
                    int x = Coords.cubeToSection(pos.getX(), dx);
                    int y = Coords.cubeToSection(pos.getY(), dy);
                    int z = Coords.cubeToSection(pos.getZ(), dz);
                    if (added) {
                        renderSectionManager.onSectionAdded(x, y, z);
                    } else {
                        renderSectionManager.onSectionRemoved(x, y, z);
                    }
                }
            }
        }
    }
}
