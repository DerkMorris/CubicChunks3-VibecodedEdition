package io.github.opencubicchunks.cubicchunks.world.level.lighting;

import java.util.Objects;

import javax.annotation.Nullable;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.utils.Coords;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LayerLightEventListener;

/** Read-only adapter. Client display retains accepted light during updates; current readiness remains separate. */
public final class CubeLayerLightListener implements LayerLightEventListener {
    private final CubeLightProvider provider;
    private final LightLayer layer;

    public CubeLayerLightListener(CubeLightProvider provider, LightLayer layer) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.layer = Objects.requireNonNull(layer, "layer");
    }

    @Override public @Nullable DataLayer getDataLayerData(SectionPos pos) {
        CubePos cube = CubePos.of(Coords.sectionToCube(pos.x()), Coords.sectionToCube(pos.y()), Coords.sectionToCube(pos.z()));
        CubeLightView view = this.provider.cc_getVisibleCubeLightView(cube);
        return view == null || !cube.equals(view.pos()) ? null
                : view.sectionData(this.layer, Coords.sectionToIndex(pos.x(), pos.y(), pos.z())).orElse(null);
    }

    @Override public int getLightValue(BlockPos pos) {
        CubePos cube = CubePos.of(Coords.blockToCube(pos.getX()), Coords.blockToCube(pos.getY()), Coords.blockToCube(pos.getZ()));
        CubeLightView view = this.provider.cc_getVisibleCubeLightView(cube);
        return view == null ? 0 : view.sample(this.layer, pos).map(range -> range.exactValue().orElse(0)).orElse(0);
    }

    @Override public void checkBlock(BlockPos pos) {}

    @Override public boolean hasLightWork() {
        return false;
    }

    @Override public int runLightUpdates() {
        return 0;
    }

    @Override public void updateSectionStatus(SectionPos pos, boolean sectionEmpty) {}

    @Override public void setLightEnabled(ChunkPos pos, boolean enable) {}

    @Override public void propagateLightSources(ChunkPos pos) {}
}
