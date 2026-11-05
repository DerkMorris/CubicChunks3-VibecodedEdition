package io.github.opencubicchunks.cubicchunks.mixin.test.common.server.level;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import io.github.opencubicchunks.cc_core.world.level.CloPos;
import io.github.opencubicchunks.cubicchunks.testutils.GenerationTestScope;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = SimpleRegionStorage.class, priority = 900)
public abstract class GenerationStorageTestMixin {
    @Dynamic("Added by the production MixinChunkStorage") @Inject(method = "cc_read", at = @At("HEAD"), cancellable = true, remap = false)
    private void cc_testRead(CloPos pos, CallbackInfoReturnable<CompletableFuture<Optional<CompoundTag>>> cir) {
        GenerationTestScope scope = GenerationTestScope.storageScope(this);
        if (scope != null && pos.isCube()) {
            cir.setReturnValue(CompletableFuture.completedFuture(scope.read(pos)));
        }
    }

    @Dynamic("Added by the production MixinChunkStorage") @Inject(method = "cc_write(Lio/github/opencubicchunks/cc_core/world/level/CloPos;Ljava/util/function/Supplier;)Ljava/util/concurrent/CompletableFuture;", at = @At("HEAD"), cancellable = true, remap = false)
    private void cc_testWriteSupplier(CloPos pos, Supplier<CompoundTag> data, CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        GenerationTestScope scope = GenerationTestScope.storageScope(this);
        if (scope != null && pos.isCube()) {
            scope.write(pos, data.get());
            cir.setReturnValue(CompletableFuture.completedFuture(null));
        }
    }

    @Dynamic("Added by the production MixinChunkStorage") @Inject(method = "cc_write(Lio/github/opencubicchunks/cc_core/world/level/CloPos;Lnet/minecraft/nbt/CompoundTag;)Ljava/util/concurrent/CompletableFuture;", at = @At("HEAD"), cancellable = true, remap = false)
    private void cc_testWrite(CloPos pos, CompoundTag data, CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        GenerationTestScope scope = GenerationTestScope.storageScope(this);
        if (scope != null && pos.isCube()) {
            scope.write(pos, data);
            cir.setReturnValue(CompletableFuture.completedFuture(null));
        }
    }
}
