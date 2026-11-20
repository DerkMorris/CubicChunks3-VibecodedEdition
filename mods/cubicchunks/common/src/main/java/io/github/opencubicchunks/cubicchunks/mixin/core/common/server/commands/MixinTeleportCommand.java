package io.github.opencubicchunks.cubicchunks.mixin.core.common.server.commands;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.server.commands.TeleportCommand;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(TeleportCommand.class)
public abstract class MixinTeleportCommand {
    @WrapOperation(method = "performTeleport", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;isInSpawnableBounds(Lnet/minecraft/core/BlockPos;)Z"))
    private static boolean cc_spawnableBounds(BlockPos pos, Operation<Boolean> original, @Local(argsOnly = true) ServerLevel destination) {
        if (((CanBeCubic) destination).cc_isCubic()) {
            return CubicBlockBounds.contains(pos.getY()) && original.call(pos.atY(0));
        }
        return original.call(pos);
    }
}
