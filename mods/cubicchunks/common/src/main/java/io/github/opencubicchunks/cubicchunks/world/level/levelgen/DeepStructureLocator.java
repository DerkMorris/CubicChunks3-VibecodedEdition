package io.github.opencubicchunks.cubicchunks.world.level.levelgen;

import javax.annotation.Nullable;

import com.mojang.datafixers.util.Pair;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.server.level.CubicServerLevel;
import io.github.opencubicchunks.cubicchunks.world.level.CubicLevelReader;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.Structure;

/** Below the native floor, locate commands, explorer maps and eyes search the surrounding three-dimensional volume. */
public final class DeepStructureLocator {
    private DeepStructureLocator() {}

    public static @Nullable Pair<BlockPos, Holder<Structure>> find(ServerLevel level, HolderSet<Structure> requested, BlockPos origin,
            int radius, boolean skipKnown) {
        if (!((CanBeCubic) level).cc_isCubic() || origin.getY() >= level.getMinY()) {
            return null;
        }
        var layout = ((CubicServerLevel) level).cc_nativeBiomeTerrain().deepStructures();
        if (layout == null) {
            return null;
        }
        var start = layout.findNearestStart(requested, origin, radius, candidate -> !skipKnown || canReference(level, candidate));
        if (start == null || skipKnown && !reserve(level, start)) {
            return null;
        }
        return Pair.of(start.bounds().getCenter(), level.registryAccess().lookupOrThrow(Registries.STRUCTURE).wrapAsHolder(start.structure()));
    }

    private static boolean canReference(ServerLevel level, DeepStructureLayout.Start start) {
        var owner = owner(level, start);
        var persisted = owner == null ? null : owner.getStartForStructure(start.structure());
        return persisted != null && persisted.isValid() && persisted.canBeReferenced();
    }

    private static boolean reserve(ServerLevel level, DeepStructureLayout.Start start) {
        var owner = owner(level, start);
        if (owner == null) {
            return false;
        }
        var persisted = owner.getStartForStructure(start.structure());
        if (persisted == null || !persisted.canBeReferenced()) {
            return false;
        }
        // StructureManager.addReference updates a two-dimensional column index, which cannot represent this owner.
        persisted.addReference();
        owner.markUnsaved();
        return true;
    }

    private static @Nullable CubeAccess owner(ServerLevel level, DeepStructureLayout.Start start) {
        var pos = start.owner();
        return ((CubicLevelReader) level).cc_getCube(pos.getX(), pos.getY(), pos.getZ(), ChunkStatus.STRUCTURE_STARTS, true);
    }
}
