package io.github.opencubicchunks.cubicchunks.mixin.core.common.server.network;

import java.util.Objects;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.server.network.CubeBlockEntityEditing;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Preserve reach, spawn protection, spectator/adventure rules and pending teleport checks in vanilla handlers. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class MixinServerGamePacketListenerImpl {
    @Shadow public ServerPlayer player;

    @WrapOperation(method = { "handlePlayerPositionChange",
        "handleMoveVehicle" }, at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;clampVertical(D)D"))
    private double cc_movementHeight(double y, Operation<Double> original) {
        return ((CanBeCubic) player.level()).cc_isCubic() ? Math.clamp(y, CubicBlockBounds.MIN, CubicBlockBounds.MAX) : original.call(y);
    }

    // Keep vanilla's mode-change logic, replacing only its column lookup and entity reinsertion.
    @WrapOperation(method = "handleSetCommandBlock", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getChunkAt(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/chunk/LevelChunk;"))
    private LevelChunk cc_commandCube(
            ServerLevel level, BlockPos pos, Operation<LevelChunk> original, @Share("commandCube") LocalRef<LevelCube> cube
    ) {
        if (((CanBeCubic) level).cc_isCubic()) {
            cube.set(Objects.requireNonNull(CubeBlockEntityEditing.loadedCube(this.player, pos), "Command cube is no longer available"));
            return null;
        }
        return original.call(level, pos);
    }

    @WrapOperation(method = "handleSetCommandBlock", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunk;setBlockEntity(Lnet/minecraft/world/level/block/entity/BlockEntity;)V"))
    private void cc_restoreCommandEntity(
            LevelChunk chunk, BlockEntity entity, Operation<Void> original, @Share("commandCube") LocalRef<LevelCube> cube
    ) {
        if (cube.get() != null) {
            cube.get().setBlockEntity(entity);
        } else {
            original.call(chunk, entity);
        }
    }

    @WrapOperation(method = { "handlePlayerAction",
        "handleUseItemOn" }, at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getMaxY()I"))
    private int cc_actionMaxY(ServerLevel level, Operation<Integer> original) {
        return ((CanBeCubic) level).cc_isCubic() ? CubicBlockBounds.MAX : original.call(level);
    }

    @WrapOperation(method = "handleUseItemOn", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getMinY()I"))
    private int cc_actionMinY(ServerLevel level, Operation<Integer> original) {
        return ((CanBeCubic) level).cc_isCubic() ? CubicBlockBounds.MIN : original.call(level);
    }
}
