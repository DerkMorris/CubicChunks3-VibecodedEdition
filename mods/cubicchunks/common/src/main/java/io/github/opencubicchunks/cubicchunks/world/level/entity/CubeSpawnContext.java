package io.github.opencubicchunks.cubicchunks.world.level.entity;

import javax.annotation.Nullable;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightProvider;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.AABB;

/** Keeps native spawn predicates from loading terrain or accepting unknown light. Scopes must close on the server thread. */
public final class CubeSpawnContext implements AutoCloseable {
    private static final ThreadLocal<CubeSpawnContext> CURRENT = new ThreadLocal<>();
    private final Level level;
    private final @Nullable CubeSpawnContext previous;
    private boolean known = true;

    public CubeSpawnContext(Level level) {
        this.level = level;
        previous = CURRENT.get();
        CURRENT.set(this);
    }

    public static @Nullable CubeSpawnContext current(Object level) {
        CubeSpawnContext scope = CURRENT.get();
        return scope != null && scope.level == level ? scope : null;
    }

    public @Nullable LevelCube cube(BlockPos pos) {
        LevelCube cube = CubeMobBlockView.loadedCube(level, pos);
        if (cube == null) {
            reject();
        }
        return cube;
    }

    public int brightness(LightLayer layer, BlockPos pos) {
        CubeLightView view = light(pos);
        return view == null ? 0 : view.sample(layer, pos).orElseThrow().lower();
    }

    public int rawBrightness(BlockPos pos, int darkening) {
        CubeLightView view = light(pos);
        return view == null ? 0 : view.rawBrightness(pos, darkening).orElseThrow();
    }

    public boolean containsLiquid(AABB box) {
        if (Math.max(box.getXsize(), Math.max(box.getYsize(), box.getZsize())) > CubicConstants.DIAMETER_IN_BLOCKS) {
            reject();
            return true;
        }
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.getMinPosition()), BlockPos.containing(box.getMaxPosition()))) {
            LevelCube cube = cube(pos);
            if (cube == null || !cube.getFluidState(pos).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private @Nullable CubeLightView light(BlockPos pos) {
        if (cube(pos) == null) {
            return null;
        }
        CubeLightView view = ((CubeLightProvider) level.getChunkSource()).cc_getCubeLightView(CubePos.from(pos));
        if (view == null || !view.isReady() || !view.pos().equals(CubePos.from(pos))) {
            reject();
            return null;
        }
        return view;
    }

    public void reject() {
        known = false;
    }

    public boolean isKnown() {
        return known;
    }

    @Override public void close() {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}
