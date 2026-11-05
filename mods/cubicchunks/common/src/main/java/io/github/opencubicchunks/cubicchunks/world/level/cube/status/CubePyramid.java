package io.github.opencubicchunks.cubicchunks.world.level.cube.status;

import com.google.common.collect.ImmutableList;
import io.github.notstirred.dasm.api.annotations.Dasm;
import io.github.notstirred.dasm.api.annotations.redirect.redirects.AddFieldToSets;
import io.github.notstirred.dasm.api.annotations.selector.Ref;
import io.github.notstirred.dasm.api.annotations.transform.TransformFromClass;
import io.github.notstirred.dasm.api.annotations.transform.TransformFromMethod;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.exception.DasmFailedToApply;
import io.github.opencubicchunks.cubicchunks.mixin.dasmsets.ChunkToCubeSet;
import io.github.opencubicchunks.cubicchunks.mixin.dasmsets.GlobalSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;

/**
 * {@link ChunkPyramid} represents a chain of {@link ChunkStep}s for either generating or loading a chunk. This class is the equivalent for cubes.
 */
@Dasm(GlobalSet.class)
public record CubePyramid(ImmutableList<CubeStep> steps) {
    private static final int MAX_PACKED_CUBE_COORDINATE = (1 << 20) - 1;
    // Assigned by the copied vanilla initializer, which DASM emits as a method.
    private static int SAFETY_MARGIN_CHUNKS;
    public static int MAX_CHUNK_COORDINATE_VALUE;

    public CubeStep getStepTo(ChunkStatus status) {
        return this.steps.get(status.getIndex());
    }

    @AddFieldToSets(containers = ChunkToCubeSet.ChunkPyramid_to_CubePyramid_redirects.class, field = "GENERATION_PYRAMID:Lnet/minecraft/world/level/chunk/status/ChunkPyramid;")
    public static CubePyramid CC_GENERATION_PYRAMID_CUBES;
    @AddFieldToSets(containers = ChunkToCubeSet.ChunkPyramid_to_CubePyramid_redirects.class, field = "LOADING_PYRAMID:Lnet/minecraft/world/level/chunk/status/ChunkPyramid;")
    public static CubePyramid CC_LOADING_PYRAMID_CUBES;

    @TransformFromMethod(useRedirectSets = ChunkToCubeSet.class, owner = @Ref(ChunkPyramid.class), value = "<clinit>")
    static void initCubePyramids() {
        throw new DasmFailedToApply();
    }

    static {
        initCubePyramids();
        CC_GENERATION_PYRAMID_CUBES = independentTerrainPyramid(CC_GENERATION_PYRAMID_CUBES);
        CC_LOADING_PYRAMID_CUBES = independentTerrainPyramid(CC_LOADING_PYRAMID_CUBES);
        // The copied initializer converts blocks to sections. Cube positions instead use cube units and 21 signed bits per axis.
        MAX_CHUNK_COORDINATE_VALUE = Math.min(Coords.blockToCube(BlockPos.MAX_HORIZONTAL_COORDINATE), MAX_PACKED_CUBE_COORDINATE)
                - SAFETY_MARGIN_CHUNKS;
    }

    /** Native terrain and decoration share a private region. Publication writes only the requested cube. */
    private static CubePyramid independentTerrainPyramid(CubePyramid source) {
        ImmutableList.Builder<CubeStep> steps = ImmutableList.builder();
        CubeStep parent = null;
        for (CubeStep original : source.steps()) {
            CubeStep.Builder builder = parent == null ? new CubeStep.Builder(original.targetStatus())
                    : new CubeStep.Builder(original.targetStatus(), parent);
            builder.setTask(original.task());
            if (original.targetStatus() == ChunkStatus.FULL) {
                // Accessible holders still need one ring for promotion and subsequent ticking.
                builder.addRequirement(ChunkStatus.EMPTY, io.github.opencubicchunks.cc_core.api.CubicConstants.DIAMETER_IN_SECTIONS);
            }
            if (original.blockStateWriteRadius() >= 0) {
                builder.blockStateWriteRadius(0);
            }
            parent = builder.build();
            steps.add(parent);
        }
        return new CubePyramid(steps.build());
    }

    @Dasm(ChunkToCubeSet.class)
    @TransformFromClass(sets = ChunkToCubeSet.class, value = @Ref(ChunkPyramid.Builder.class))
    public static class Builder {}
}
