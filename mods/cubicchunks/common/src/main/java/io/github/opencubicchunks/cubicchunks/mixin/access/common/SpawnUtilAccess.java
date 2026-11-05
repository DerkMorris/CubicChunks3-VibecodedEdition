package io.github.opencubicchunks.cubicchunks.mixin.access.common;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.SpawnUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(SpawnUtil.class)
public interface SpawnUtilAccess {
    @Invoker("moveToPossibleSpawnPosition") static boolean cc_findFloor(ServerLevel level, int rangeY, BlockPos.MutableBlockPos pos, SpawnUtil.Strategy strategy) {
        throw new AssertionError();
    }
}
