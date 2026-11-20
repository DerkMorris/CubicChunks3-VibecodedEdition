package io.github.opencubicchunks.cubicchunks.mixin.core.common.core;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import io.github.opencubicchunks.cubicchunks.network.CubePositionSourceCodec;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.codec.StreamCodec;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Lodestone components and the native login/respawn death position share this stream codec. */
@Mixin(GlobalPos.class)
public class MixinGlobalPos {
    @ModifyExpressionValue(method = "<clinit>", at = @At(value = "FIELD", target = "Lnet/minecraft/core/BlockPos;STREAM_CODEC:Lnet/minecraft/network/codec/StreamCodec;"))
    private static StreamCodec<ByteBuf, BlockPos> cc_fullHeightGlobalPosition(StreamCodec<ByteBuf, BlockPos> original) {
        return CubePositionSourceCodec.BLOCK_POSITION;
    }
}
