package io.github.opencubicchunks.cubicchunks.mixin.core.common.server.commands;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.commands.SummonCommand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(SummonCommand.class)
public abstract class MixinSummonCommand {
    @WrapOperation(method = "createEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;isInSpawnableBounds(Lnet/minecraft/core/BlockPos;)Z"))
    private static boolean cc_spawnableBounds(BlockPos pos, Operation<Boolean> original, @Local(argsOnly = true) CommandSourceStack source) {
        if (((CanBeCubic) source.getLevel()).cc_isCubic()) {
            // Reuse native X/Z bounds; only the destination world's vertical limit changes.
            return CubicBlockBounds.contains(pos.getY()) && original.call(pos.atY(0));
        }
        return original.call(pos);
    }
}
