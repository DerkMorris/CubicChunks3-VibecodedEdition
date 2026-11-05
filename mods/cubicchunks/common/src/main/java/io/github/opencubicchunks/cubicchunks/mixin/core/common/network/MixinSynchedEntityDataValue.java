package io.github.opencubicchunks.cubicchunks.mixin.core.common.network;

import java.util.Optional;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import io.github.opencubicchunks.cubicchunks.network.CubeEntityDataSerializers;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SynchedEntityData.DataValue.class)
public abstract class MixinSynchedEntityDataValue {
    @Inject(method = "write", at = @At("HEAD"), cancellable = true)
    private void cc_writePosition(RegistryFriendlyByteBuf buffer, CallbackInfo ci) {
        var data = (SynchedEntityData.DataValue<?>) (Object) this;
        if (data.serializer() == EntityDataSerializers.OPTIONAL_BLOCK_POS) {
            var value = (Optional<BlockPos>) data.value();
            if (value.isPresent() && !BlockPos.of(value.get().asLong()).equals(value.get())) {
                new SynchedEntityData.DataValue<>(data.id(), CubeEntityDataSerializers.OPTIONAL_POSITION, value).write(buffer);
                ci.cancel();
            }
        }
    }

    @ModifyReturnValue(method = "read(Lnet/minecraft/network/RegistryFriendlyByteBuf;I)Lnet/minecraft/network/syncher/SynchedEntityData$DataValue;", at = @At("RETURN"))
    private static SynchedEntityData.DataValue<?> cc_restoreNativeAccessor(SynchedEntityData.DataValue<?> value) {
        return value.serializer() == CubeEntityDataSerializers.OPTIONAL_POSITION
                ? new SynchedEntityData.DataValue<>(value.id(), EntityDataSerializers.OPTIONAL_BLOCK_POS, (Optional<BlockPos>) value.value())
                : value;
    }
}
