package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.level.storage;

import java.util.UUID;

import com.mojang.serialization.Dynamic;
import io.github.opencubicchunks.cubicchunks.CubicChunks;
import io.github.opencubicchunks.cubicchunks.world.level.CubicWorldData;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.DeepCaveSettings;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.level.storage.PrimaryLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PrimaryLevelData.class)
public abstract class MixinPrimaryLevelData implements CubicWorldData {
    @Unique
    private boolean cc_cubicWorld;

    @Unique
    private DeepCaveSettings cc_deepCaves = DeepCaveSettings.CLASSIC;

    @Override public DeepCaveSettings cc_deepCaveSettings() {
        return cc_deepCaves;
    }

    @Override public void cc_setDeepCaveSettings(DeepCaveSettings settings) {
        cc_deepCaves = settings;
    }

    @Override public boolean cc_isCubicWorld() {
        return cc_cubicWorld;
    }

    @Override public void cc_setCubicWorld(boolean cubic) {
        cc_cubicWorld = cubic;
    }

    @Inject(method = "<init>(Lnet/minecraft/world/level/LevelSettings;Lnet/minecraft/world/level/storage/PrimaryLevelData$SpecialWorldProperty;Lcom/mojang/serialization/Lifecycle;)V", at = @At("RETURN"))
    private void cc_chooseNewWorldMode(CallbackInfo ci) {
        cc_cubicWorld = CubicChunks.config().shouldGenerateNewWorldsAsCC();
        if (cc_cubicWorld) {
            cc_deepCaves = CubicChunks.config().deepCaveSettings();
        }
    }

    @Inject(method = "parse", at = @At("RETURN"))
    private static void cc_readWorldMode(
            Dynamic<?> input, net.minecraft.world.level.LevelSettings settings, PrimaryLevelData.SpecialWorldProperty special,
            com.mojang.serialization.Lifecycle lifecycle, CallbackInfoReturnable<PrimaryLevelData> cir
    ) {
        int format = input.get(FORMAT_KEY).asInt(0);
        if (format != 0 && format != CURRENT_FORMAT) {
            throw new IllegalArgumentException("Unsupported Cubic Chunks world format: " + format);
        }
        var data = (CubicWorldData) cir.getReturnValue();
        data.cc_setCubicWorld(format == CURRENT_FORMAT);
        if (format == CURRENT_FORMAT) {
            data.cc_setDeepCaveSettings(input.get(DEEP_CAVES_KEY).result().map(value -> value.read(DeepCaveSettings.CODEC).getOrThrow())
                    .orElse(DeepCaveSettings.CLASSIC));
        }
    }

    @Inject(method = "setTagData", at = @At("RETURN"))
    private void cc_writeWorldMode(CompoundTag tag, UUID player, CallbackInfo ci) {
        if (cc_cubicWorld) {
            tag.putInt(FORMAT_KEY, CURRENT_FORMAT);
            tag.put(DEEP_CAVES_KEY, DeepCaveSettings.CODEC.encodeStart(NbtOps.INSTANCE, cc_deepCaves).getOrThrow());
        }
    }
}
