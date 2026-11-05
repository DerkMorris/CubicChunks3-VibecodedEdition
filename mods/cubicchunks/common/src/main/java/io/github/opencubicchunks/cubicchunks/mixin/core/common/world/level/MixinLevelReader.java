package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.level;

import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.world.level.CubicLevelReader;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubeMobBlockView;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubeSpawnContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Cube equivalents of LevelReader chunk methods are implemented as defaults in CubicLevelReader
@Mixin(LevelReader.class)
public interface MixinLevelReader extends CubicLevelReader {
    @Inject(method = "containsAnyLiquid", at = @At("HEAD"), cancellable = true)
    private void cc_spawnLiquid(AABB box, CallbackInfoReturnable<Boolean> cir) {
        var context = CubeSpawnContext.current(this);
        if (context != null) {
            cir.setReturnValue(context.containsLiquid(box));
        }
    }

    @Inject(method = "getNoiseBiome", at = @At("HEAD"), cancellable = true)
    private void cc_cubeBiome(int x, int y, int z, CallbackInfoReturnable<Holder<Biome>> cir) {
        if (this instanceof Level level && ((CanBeCubic) level).cc_isCubic()) {
            BlockPos pos = new BlockPos(QuartPos.toBlock(x), QuartPos.toBlock(y), QuartPos.toBlock(z));
            var context = CubeSpawnContext.current(this);
            var cube = context == null ? CubeMobBlockView.loadedCube(level, pos) : context.cube(pos);
            cir.setReturnValue(cube == null ? level.getUncachedNoiseBiome(x, y, z) : cube.getNoiseBiome(x, y, z));
        }
    }

    @Inject(method = "hasChunkAt(Lnet/minecraft/core/BlockPos;)Z", at = @At("HEAD"), cancellable = true)
    private void cc_hasCubeAtPosition(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (this instanceof CanBeCubic cubic && cubic.cc_isCubic()) {
            cir.setReturnValue(CubicBlockBounds.contains(pos) && cc_hasCubeAt(pos));
        }
    }

    @Inject(method = "hasChunksAt(IIIIII)Z", at = @At("HEAD"), cancellable = true)
    private void cc_hasCubeArea(int x0, int y0, int z0, int x1, int y1, int z1, CallbackInfoReturnable<Boolean> cir) {
        if (this instanceof CanBeCubic cubic && cubic.cc_isCubic()) {
            cir.setReturnValue(CubicBlockBounds.contains(new BlockPos(x0, y0, z0)) && CubicBlockBounds.contains(new BlockPos(x1, y1, z1)) && x0 <= x1
                    && y0 <= y1 && z0 <= z1 && cc_hasCubesAt(x0, y0, z0, x1, y1, z1));
        }
    }
}
