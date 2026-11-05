package io.github.opencubicchunks.cubicchunks.mixin.core.client.multiplayer;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientCubeBlockChanges;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientCubeCache;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientCubePredictions;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.CubicClientEntityManager;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.CubicClientLevel;
import io.github.opencubicchunks.cubicchunks.mixin.access.client.BlockStatePredictionHandlerAccess;
import io.github.opencubicchunks.cubicchunks.mixin.core.common.world.level.MixinLevel;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundCubeBlockChangesPacket;
import io.github.opencubicchunks.cubicchunks.network.CubeBlockEntityUpdate;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubeEntityGetter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.entity.TransientEntitySectionManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public abstract class MixinClientLevel extends MixinLevel implements CubicClientLevel {
    @Shadow @Final private ClientChunkCache chunkSource;
    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final private BlockStatePredictionHandler blockStatePredictionHandler;
    @Shadow @Final private TransientEntitySectionManager<Entity> entityStorage;
    @Unique
    private final ClientCubePredictions cc_predictions = new ClientCubePredictions();
    @Unique
    private LevelEntityGetter<Entity> cc_entityGetter;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void cc_configureClientEntityTicks(CallbackInfo ci) {
        if (cc_isCubic) {
            ((CubicClientEntityManager) entityStorage)
                    .cc_configureCubeTicks(pos -> cc_getCubeSource().cc_getCube(pos.getX(), pos.getY(), pos.getZ(), false) != null);
        }
    }

    @ModifyReturnValue(method = "getEntities", at = @At("RETURN"))
    private LevelEntityGetter<Entity> cc_fullHeightEntityQueries(LevelEntityGetter<Entity> original) {
        if (!cc_isCubic) {
            return original;
        }
        if (cc_entityGetter == null) {
            cc_entityGetter = new CubeEntityGetter<>(original);
        }
        return cc_entityGetter;
    }

    @Override public ClientCubePredictions cc_getCubePredictions() {
        return this.cc_predictions;
    }

    @Override public void cc_forgetCubePredictions(CubePos pos) {
        this.cc_predictions.forget(pos);
    }

    @WrapOperation(method = "setBlock", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/prediction/BlockStatePredictionHandler;retainKnownServerState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/client/player/LocalPlayer;)V"))
    private void cc_retainPrediction(
            BlockStatePredictionHandler handler, BlockPos pos, BlockState state, LocalPlayer player, Operation<Void> original,
            @Share("cubeEntityBeforePrediction") LocalRef<CubeBlockEntityUpdate> entity
    ) {
        if (cc_isCubic) {
            cc_predictions.retain((ClientLevel) (Object) this, pos, state, entity.get(), player, handler.currentSequence());
        } else {
            original.call(handler, pos, state, player);
        }
    }

    @WrapOperation(method = "setBlock", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z"))
    private boolean cc_capturePredictionEntity(
            ClientLevel level, BlockPos pos, BlockState state, int flags, int limit, Operation<Boolean> original,
            @Share("cubeEntityBeforePrediction") LocalRef<CubeBlockEntityUpdate> entity
    ) {
        if (cc_isCubic && blockStatePredictionHandler.isPredicting() && CubicBlockBounds.contains(pos)) {
            CubePos cubePos = CubePos.from(pos);
            LevelCube cube = cc_getCubeSource().cc_getCube(cubePos.getX(), cubePos.getY(), cubePos.getZ(), false);
            if (cube != null) {
                entity.set(CCClientboundCubeBlockChangesPacket.captureBlockEntity(cube, pos, cube.getBlockState(pos)));
            }
        }
        return original.call(level, pos, state, flags, limit);
    }

    @Inject(method = "handleBlockChangedAck", at = @At("HEAD"), cancellable = true)
    private void cc_ackPredictions(int sequence, CallbackInfo ci) {
        if (cc_isCubic) {
            cc_predictions.acknowledge((ClientLevel) (Object) this, minecraft.player, sequence,
                    ((BlockStatePredictionHandlerAccess) blockStatePredictionHandler).cc_lastTeleportSequence());
            ci.cancel();
        }
    }

    @Inject(method = "setServerVerifiedBlockState", at = @At("HEAD"), cancellable = true)
    private void cc_receiveVerifiedState(BlockPos pos, BlockState state, int flags, CallbackInfo ci) {
        if (cc_isCubic) {
            CubePos cubePos = CubePos.from(pos);
            LevelCube cube = cc_getCubeSource().cc_getCube(cubePos.getX(), cubePos.getY(), cubePos.getZ(), false);
            if (cube != null) {
                ClientCubeBlockChanges.applyServerUpdate((ClientLevel) (Object) this, cube, pos, state, null);
            }
            ci.cancel();
        }
    }

    @Override public boolean cc_hasCube(int cubeX, int cubeY, int cubeZ) {
        return true;
    }

    @Override public void cc_onCubeLoaded(CubePos cubePos) {
        var ticks = ((CubicClientEntityManager) entityStorage).cc_cubeTicks();
        if (ticks != null) {
            ticks.refresh(cubePos);
        }
    }

    @Override public void cc_onCubeUnloaded(CubePos cubePos) {
        cc_onCubeLoaded(cubePos);
    }

    @Override public void cc_refreshEntityTicks() {
        var ticks = ((CubicClientEntityManager) entityStorage).cc_cubeTicks();
        if (ticks != null) {
            ticks.refreshAll();
        }
    }

    @Override public ClientCubeCache cc_getCubeSource() {
        return ((ClientCubeCache) this.chunkSource);
    }

}
