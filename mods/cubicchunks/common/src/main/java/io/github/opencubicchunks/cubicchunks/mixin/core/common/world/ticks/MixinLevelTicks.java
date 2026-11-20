package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.ticks;

import java.util.function.BiConsumer;
import java.util.function.LongPredicate;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.world.ticks.CubicLevelTicks;
import io.github.opencubicchunks.cubicchunks.world.ticks.CubicTickContainer;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Retains vanilla priority, deduplication, budgets and copy/clear semantics, replacing only spatial addressing. */
@Mixin(LevelTicks.class)
public abstract class MixinLevelTicks<T> implements CubicLevelTicks<T> {
    @Shadow @Final private Long2ObjectMap<LevelChunkTicks<T>> allContainers;
    @Shadow @Final private Long2LongMap nextTickForContainer;
    @Shadow @Final private BiConsumer<LevelChunkTicks<T>, ScheduledTick<T>> chunkScheduleUpdater;
    @Unique
    private LongPredicate cc_tickingCube;
    @Unique
    private LongPredicate cc_agingCube;
    @Unique
    private final LongSet cc_frozenAgingContainers = new LongOpenHashSet();
    @Unique
    private long cc_lastPersistenceTime = Long.MIN_VALUE;

    @Inject(method = "tick", at = @At("HEAD"))
    private void cc_dirtyElapsedDelays(long gameTime, int maxTicks, BiConsumer<BlockPos, T> ticker, CallbackInfo ci) {
        if (cc_tickingCube == null || gameTime == cc_lastPersistenceTime) {
            return;
        }
        cc_lastPersistenceTime = gameTime;
        // Serialization stores remaining delays. Even without a queue mutation, a previously saved delay ages while loaded.
        // Visit only queues with pending work, so an empty loaded world does not become dirty every tick.
        for (long key : nextTickForContainer.keySet()) {
            LevelChunkTicks<T> ticks = allContainers.get(key);
            if (ticks != null && ticks.peek() != null) {
                if (cc_agingCube.test(key)) {
                    cc_frozenAgingContainers.remove(key);
                    ((CubicTickContainer) ticks).cc_markUnsaved();
                } else if (cc_frozenAgingContainers.add(key)) {
                    // Capture the final remaining delay once on unload. Repeated aging dirtiness would prevent
                    // an asynchronous write from ever making an inaccessible cube clean enough to release.
                    ((CubicTickContainer) ticks).cc_markUnsaved();
                }
            }
        }
    }

    @Override public void cc_configure(LongPredicate tickingCube, LongPredicate agingCube) {
        this.cc_tickingCube = tickingCube;
        this.cc_agingCube = agingCube;
        this.cc_frozenAgingContainers.clear();
        this.cc_lastPersistenceTime = Long.MIN_VALUE;
    }

    @Override public void cc_addContainer(CubePos pos, LevelChunkTicks<T> ticks) {
        long key = pos.asLong();
        cc_frozenAgingContainers.remove(key);
        LevelChunkTicks<T> previous = allContainers.put(key, ticks);
        if (previous != null && previous != ticks) {
            previous.setOnTickAdded(null);
        }
        nextTickForContainer.remove(key);
        ScheduledTick<T> first = ticks.peek();
        if (first != null) {
            nextTickForContainer.put(key, first.triggerTick());
        }
        ticks.setOnTickAdded(chunkScheduleUpdater);
    }

    @Override public void cc_removeContainer(CubePos pos) {
        long key = pos.asLong();
        cc_frozenAgingContainers.remove(key);
        LevelChunkTicks<T> ticks = allContainers.remove(key);
        nextTickForContainer.remove(key);
        if (ticks != null) {
            ticks.setOnTickAdded(null);
        }
    }

    // Cubic worlds also load backing columns. Their queues must not collide with, or run alongside, cube queues.
    @Inject(method = "addContainer", at = @At("HEAD"), cancellable = true)
    private void cc_skipColumnRegistration(ChunkPos pos, LevelChunkTicks<T> ticks, CallbackInfo ci) {
        if (cc_tickingCube != null) {
            ci.cancel();
        }
    }

    @Inject(method = "removeContainer", at = @At("HEAD"), cancellable = true)
    private void cc_skipColumnRemoval(ChunkPos pos, CallbackInfo ci) {
        if (cc_tickingCube != null) {
            ci.cancel();
        }
    }

    @Inject(method = "schedule", at = @At("HEAD"), cancellable = true)
    private void cc_rejectUnrepresentableTick(ScheduledTick<T> tick, CallbackInfo ci) {
        if (cc_tickingCube != null && !CubicBlockBounds.contains(tick.pos())) {
            ci.cancel();
        }
    }

    @Inject(method = "hasScheduledTick", at = @At("HEAD"), cancellable = true)
    private void cc_rejectUnrepresentableQuery(BlockPos pos, T type, CallbackInfoReturnable<Boolean> cir) {
        if (cc_tickingCube != null && !CubicBlockBounds.contains(pos)) {
            cir.setReturnValue(false);
        }
    }

    @WrapOperation(method = { "schedule", "hasScheduledTick",
        "updateContainerScheduling" }, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/ChunkPos;pack(Lnet/minecraft/core/BlockPos;)J"))
    private long cc_tickPosition(BlockPos pos, Operation<Long> original) {
        return cc_tickingCube == null ? original.call(pos) : CubePos.from(pos).asLong();
    }

    @WrapOperation(method = "sortContainersToTick", at = @At(value = "INVOKE", target = "Ljava/util/function/LongPredicate;test(J)Z"))
    private boolean cc_canTick(LongPredicate predicate, long pos, Operation<Boolean> original) {
        return cc_tickingCube == null ? original.call(predicate, pos) : cc_tickingCube.test(pos);
    }

    @Inject(method = "forContainersInArea", at = @At("HEAD"), cancellable = true)
    private void cc_cubeContainersInArea(BoundingBox box, LevelTicks.PosAndContainerConsumer<T> consumer, CallbackInfo ci) {
        if (cc_tickingCube == null) {
            return;
        }
        int minX = Coords.blockToCube(box.minX());
        int minY = Coords.blockToCube(box.minY());
        int minZ = Coords.blockToCube(box.minZ());
        int maxX = Coords.blockToCube(box.maxX());
        int maxY = Coords.blockToCube(box.maxY());
        int maxZ = Coords.blockToCube(box.maxZ());
        // Iterate loaded containers, not the potentially enormous volume of an editor/clone selection.
        for (Long2ObjectMap.Entry<LevelChunkTicks<T>> entry : allContainers.long2ObjectEntrySet()) {
            CubePos pos = CubePos.from(entry.getLongKey());
            if (pos.getX() >= minX && pos.getX() <= maxX && pos.getY() >= minY && pos.getY() <= maxY && pos.getZ() >= minZ && pos.getZ() <= maxZ) {
                consumer.accept(entry.getLongKey(), entry.getValue());
            }
        }
        ci.cancel();
    }
}
