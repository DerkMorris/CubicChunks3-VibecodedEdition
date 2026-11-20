package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.item.consume_effects;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.consume_effects.TeleportRandomlyConsumeEffect;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Keep native consume attempts and effects while selecting destinations near a player outside ordinary dimension heights. */
@Mixin(TeleportRandomlyConsumeEffect.class)
public abstract class MixinTeleportRandomlyConsumeEffect {
    @WrapOperation(method = "apply", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMinY()I"))
    private int cc_consumeTeleportMinimum(Level level, Operation<Integer> original) {
        return ((CanBeCubic) level).cc_isCubic() ? CubicBlockBounds.MIN : original.call(level);
    }

    @WrapOperation(method = "apply", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getLogicalHeight()I"))
    private int cc_consumeTeleportHeight(ServerLevel level, Operation<Integer> original) {
        return ((CanBeCubic) level).cc_isCubic() ? CubicBlockBounds.MAX - CubicBlockBounds.MIN + 1 : original.call(level);
    }
}
