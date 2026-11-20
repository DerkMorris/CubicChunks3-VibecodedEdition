package io.github.opencubicchunks.cubicchunks.mixin.core.client.multiplayer;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientWeatherColumns;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.CubeWeatherClient;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeSource;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ClientLevel.class)
public class MixinClientLevelWeather implements CubeWeatherClient {
    @Unique
    private ClientWeatherColumns cc_weatherColumns;

    @Override public ClientWeatherColumns cc_weatherColumns() {
        if (cc_weatherColumns == null) {
            cc_weatherColumns = new ClientWeatherColumns(((ClientLevel) (Object) this).dimension().identifier());
        }
        return cc_weatherColumns;
    }

    @WrapOperation(method = "tickWeatherEffects", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getHeightmapPos(Lnet/minecraft/world/level/levelgen/Heightmap$Types;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/BlockPos;"))
    private BlockPos cc_weatherSplashAndSoundHeight(ClientLevel level, Heightmap.Types type, BlockPos sample, Operation<BlockPos> original) {
        return ((CanBeCubic) level).cc_isCubic() ? sample.atY(cc_weatherColumns().height(sample.getX(), sample.getZ()))
                : original.call(level, type, sample);
    }

    @WrapOperation(method = "tickWeatherEffects", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getMinY()I"))
    private int cc_weatherParticleMinimum(ClientLevel level, Operation<Integer> original) {
        return ((CanBeCubic) level).cc_isCubic() ? CubicBlockBounds.MIN : original.call(level);
    }

    @WrapOperation(method = "getPrecipitationAt", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientChunkCache;hasChunk(II)Z"))
    private boolean cc_weatherCubeBiome(ClientChunkCache source, int x, int z, Operation<Boolean> original, @Local(argsOnly = true) BlockPos sample) {
        if (!((CanBeCubic) this).cc_isCubic()) {
            return original.call(source, x, z);
        }
        CubePos pos = CubePos.from(sample);
        return CubicBlockBounds.contains(sample) && ((CubeSource) source).cc_getCubeNow(pos.getX(), pos.getY(), pos.getZ()) != null;
    }
}
