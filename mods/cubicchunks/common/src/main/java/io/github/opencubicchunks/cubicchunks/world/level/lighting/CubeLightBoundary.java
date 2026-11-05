package io.github.opencubicchunks.cubicchunks.world.level.lighting;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/** Explicit external observations for a detached solve. Missing interior cubes always remain unknown. */
@FunctionalInterface
public interface CubeLightBoundary {
    CubeLightBoundary UNKNOWN = (externalSource, sourceToTarget) -> Optional.empty();

    Optional<BoundaryLight> sample(BlockPos externalSource, Direction sourceToTarget);

    record BoundaryLight(BlockState state, int block, int sky) {
        public BoundaryLight {
            Objects.requireNonNull(state, "state");
            new LightRange(block, block);
            new LightRange(sky, sky);
        }
    }
}
