package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.entity.raid;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.world.level.CubicLevelReader;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubeMobBlockView;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Keep the native wave distribution and validity checks, but search the village's vertical neighborhood. */
@Mixin(Raid.class)
public abstract class MixinRaid {
    @Unique
    private static final int CC_VERTICAL_SEARCH_RADIUS = 96;
    @Unique
    private static final int CC_WAVE_CLEARANCE = 3;
    @Shadow private BlockPos center;

    @WrapOperation(method = "findRandomSpawnPos", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getHeight(Lnet/minecraft/world/level/levelgen/Heightmap$Types;II)I"))
    private int cc_localWaveSurface(ServerLevel level, Heightmap.Types type, int x, int z, Operation<Integer> original) {
        if (!((CanBeCubic) level).cc_isCubic()) {
            return original.call(level, type, x, z);
        }
        // Native raids already restrict waves to 96 blocks vertically. Search only that bounded, loaded area.
        for (int distance = 0; distance <= CC_VERTICAL_SEARCH_RADIUS; distance++) {
            int above = center.getY() + distance;
            if (cc_validSurface(level, new BlockPos(x, above, z))) {
                return above;
            }
            int below = center.getY() - distance;
            if (distance != 0 && cc_validSurface(level, new BlockPos(x, below, z))) {
                return below;
            }
        }
        // The existing native distance check rejects this candidate without loading a missing column.
        return center.getY() + CC_VERTICAL_SEARCH_RADIUS + 1;
    }

    @Unique
    private static boolean cc_validSurface(ServerLevel level, BlockPos pos) {
        if (CubeMobBlockView.loadedCube(level, pos.below()) == null || CubeMobBlockView.loadedCube(level, pos) == null
                || CubeMobBlockView.loadedCube(level, pos.above()) == null) {
            return false;
        }
        return Raid.RAVAGER_SPAWN_PLACEMENT_TYPE.isSpawnPositionOk(level, pos, EntityTypes.RAVAGER)
                || level.getBlockState(pos.below()).is(Blocks.SNOW) && level.getBlockState(pos).isAir();
    }

    @WrapOperation(method = "findRandomSpawnPos", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;hasChunksAt(IIII)Z"))
    private boolean cc_loadedWaveArea(
            ServerLevel level, int x0, int z0, int x1, int z1, Operation<Boolean> original, @Local BlockPos.MutableBlockPos spawnPos
    ) {
        if (!((CanBeCubic) level).cc_isCubic()) {
            return original.call(level, x0, z0, x1, z1);
        }
        return ((CubicLevelReader) level).cc_hasCubesAt(x0, spawnPos.getY() - 1, z0, x1, spawnPos.getY() + CC_WAVE_CLEARANCE, z1);
    }
}
