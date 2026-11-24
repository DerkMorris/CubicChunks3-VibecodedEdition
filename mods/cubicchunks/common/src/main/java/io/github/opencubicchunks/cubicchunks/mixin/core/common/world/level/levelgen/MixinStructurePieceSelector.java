package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.level.levelgen;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.DeepStructureRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.structures.StrongholdPieces;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(StructurePiece.class)
public abstract class MixinStructurePieceSelector {
    /** A shared selector stores its chosen block between next() and getNext(); protect that pair across generation workers. */
    @WrapMethod(method = "generateBox(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/levelgen/structure/BoundingBox;IIIIIIZLnet/minecraft/util/RandomSource;Lnet/minecraft/world/level/levelgen/structure/StructurePiece$BlockSelector;)V")
    private void cc_serialSelector(WorldGenLevel level, BoundingBox bounds, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
            boolean skipAir, RandomSource random, StructurePiece.BlockSelector selector, Operation<Void> original) {
        // Vanilla skips cave air when building the shell. Deep caverns can otherwise erase entire rooms;
        // the piece's subsequent doorway calls still carve their original openings through this shell.
        boolean completeShell = level instanceof DeepStructureRegion && (Object) this instanceof StrongholdPieces.StrongholdPiece;
        synchronized (selector) {
            original.call(level, bounds, minX, minY, minZ, maxX, maxY, maxZ, skipAir && !completeShell, random, selector);
        }
    }
}
