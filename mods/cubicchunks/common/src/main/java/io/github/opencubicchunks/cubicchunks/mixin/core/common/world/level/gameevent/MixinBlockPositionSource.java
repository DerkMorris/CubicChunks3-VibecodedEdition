package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.level.gameevent;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import io.github.opencubicchunks.cubicchunks.network.CubePositionSourceCodec;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.gameevent.BlockPositionSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(BlockPositionSource.class)
public class MixinBlockPositionSource {
    @ModifyExpressionValue(method = "<clinit>", at = @At(value = "FIELD", target = "Lnet/minecraft/core/BlockPos;STREAM_CODEC:Lnet/minecraft/network/codec/StreamCodec;"))
    private static StreamCodec<ByteBuf, BlockPos> cc_fullHeightDestination(StreamCodec<ByteBuf, BlockPos> original) {
        return CubePositionSourceCodec.BLOCK_POSITION;
    }
}
