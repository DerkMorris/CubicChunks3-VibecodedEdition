package io.github.opencubicchunks.cubicchunks.mixin.core.common.server.level;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.server.level.CubeWeatherServer;
import io.github.opencubicchunks.cubicchunks.server.level.CubeWeatherTicks;
import io.github.opencubicchunks.cubicchunks.server.level.WeatherColumnPublisher;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public class MixinServerLevelWeather implements CubeWeatherServer {
    @Unique
    private WeatherColumnPublisher cc_weatherPublisher;

    @Inject(method = "tick", at = @At("RETURN"))
    private void cc_sendWeatherSurfaces(CallbackInfo ci) {
        ServerLevel level = (ServerLevel) (Object) this;
        if (((CanBeCubic) level).cc_isCubic()) {
            if (cc_weatherPublisher == null) {
                cc_weatherPublisher = new WeatherColumnPublisher(level);
            }
            cc_weatherPublisher.tick();
        }
    }

    @Override public void cc_weatherColumnChanged(CubePos cube) {
        if (cc_weatherPublisher != null) {
            cc_weatherPublisher.changed(cube);
        }
    }

    @WrapOperation(method = "tickPrecipitation", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getHeightmapPos(Lnet/minecraft/world/level/levelgen/Heightmap$Types;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/BlockPos;"))
    private BlockPos cc_weatherSurface(ServerLevel level, Heightmap.Types type, BlockPos sample, Operation<BlockPos> original) {
        BlockPos surface = CubeWeatherTicks.precipitationSurface(level);
        return surface == null ? original.call(level, type, sample) : surface;
    }
}
