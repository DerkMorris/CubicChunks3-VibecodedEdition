package io.github.opencubicchunks.cubicchunks.mixin.core.common.server;

import java.time.Duration;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.datafixers.util.Pair;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.ResourceOrTagKeyArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.commands.LocateCommand;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(LocateCommand.class)
public abstract class MixinLocateCommand {
    @WrapMethod(method = "showLocateResult(Lnet/minecraft/commands/CommandSourceStack;Lnet/minecraft/commands/arguments/ResourceOrTagKeyArgument$Result;"
            + "Lnet/minecraft/core/BlockPos;Lcom/mojang/datafixers/util/Pair;Ljava/lang/String;ZLjava/time/Duration;)I")
    private static int cc_showDepth(CommandSourceStack source, ResourceOrTagKeyArgument.Result<?> requested, BlockPos origin,
            Pair<BlockPos, ? extends Holder<?>> result, String message, boolean showY, Duration duration, Operation<Integer> original) {
        boolean underground = ((CanBeCubic) source.getLevel()).cc_isCubic() && result.getFirst().getY() < source.getLevel().getMinY();
        return original.call(source, requested, origin, result, message, showY || underground, duration);
    }
}
