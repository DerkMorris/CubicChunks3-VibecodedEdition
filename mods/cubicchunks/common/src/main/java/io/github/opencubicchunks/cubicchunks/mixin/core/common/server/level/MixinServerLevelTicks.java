package io.github.opencubicchunks.cubicchunks.mixin.core.common.server.level;

import java.util.function.LongPredicate;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.server.level.CubicServerLevel;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeSource;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.ticks.CubicLevelTicks;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class MixinServerLevelTicks {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void cc_configureScheduledTicks(CallbackInfo ci) {
        ServerLevel level = (ServerLevel) (Object) this;
        if (!((CanBeCubic) level).cc_isCubic()) {
            return;
        }
        LongPredicate ticking = key -> {
            CubePos pos = CubePos.from(key);
            LevelCube cube = ((CubeSource) level.getChunkSource()).cc_getCubeNow(pos.getX(), pos.getY(), pos.getZ());
            return cube != null && cube.getFullStatus().isOrAfter(FullChunkStatus.BLOCK_TICKING)
                    && ((CubicServerLevel) level).cc_areCubeEntitiesLoaded(pos);
        };
        LongPredicate aging = key -> {
            CubePos pos = CubePos.from(key);
            LevelCube cube = ((CubeSource) level.getChunkSource()).cc_getCubeNow(pos.getX(), pos.getY(), pos.getZ());
            return cube != null && cube.getFullStatus().isOrAfter(FullChunkStatus.FULL);
        };
        ((CubicLevelTicks<Block>) level.getBlockTicks()).cc_configure(ticking, aging);
        ((CubicLevelTicks<Fluid>) level.getFluidTicks()).cc_configure(ticking, aging);
    }
}
