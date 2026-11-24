package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.level.levelgen;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import net.minecraft.world.level.levelgen.structure.structures.StrongholdPieces;
import net.minecraft.world.level.levelgen.structure.structures.StrongholdStructure;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(StrongholdStructure.class)
public abstract class MixinStrongholdStructure {
    /** Vanilla's piece weights are static mutable state, also shared with native-band generation. */
    @WrapMethod(method = "generatePieces")
    private static void cc_serialLayout(StructurePiecesBuilder builder, Structure.GenerationContext context, Operation<Void> original) {
        synchronized (StrongholdPieces.class) {
            original.call(builder, context);
        }
    }
}
