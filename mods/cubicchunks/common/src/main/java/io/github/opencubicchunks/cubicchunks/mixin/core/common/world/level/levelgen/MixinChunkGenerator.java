package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.level.levelgen;

import com.mojang.datafixers.util.Pair;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.DeepStructureLocator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkGenerator.class)
public abstract class MixinChunkGenerator {
    @Inject(method = "findNearestMapStructure", at = @At("HEAD"), cancellable = true)
    private void cc_locateDeep(ServerLevel level, HolderSet<Structure> requested, BlockPos origin, int radius, boolean skipKnown,
            CallbackInfoReturnable<Pair<BlockPos, Holder<Structure>>> cir) {
        var result = DeepStructureLocator.find(level, requested, origin, radius, skipKnown);
        if (result != null) {
            cir.setReturnValue(result);
        }
    }
}
