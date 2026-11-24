package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.level;

import java.util.Map;
import java.util.function.Predicate;

import io.github.opencubicchunks.cubicchunks.world.level.entity.CubeStructureQueries;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(StructureManager.class)
public class MixinStructureManager {
    @Shadow @Final private LevelAccessor level;

    @Inject(method = "getStructureAt(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/levelgen/structure/Structure;)"
            + "Lnet/minecraft/world/level/levelgen/structure/StructureStart;", at = @At("HEAD"), cancellable = true)
    private void cc_structureAt(BlockPos pos, Structure type, CallbackInfoReturnable<StructureStart> cir) {
        if (CubeStructureQueries.handles(level, pos.getY())) {
            cir.setReturnValue(CubeStructureQueries.at((ServerLevel) level, pos, structure -> structure == type, false));
        }
    }

    @Inject(method = "getStructureAt(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/HolderSet;)"
            + "Lnet/minecraft/world/level/levelgen/structure/StructureStart;", at = @At("HEAD"), cancellable = true)
    private void cc_structureSetAt(BlockPos pos, HolderSet<Structure> types, CallbackInfoReturnable<StructureStart> cir) {
        if (CubeStructureQueries.handles(level, pos.getY())) {
            ServerLevel server = (ServerLevel) level;
            cir.setReturnValue(CubeStructureQueries.at(server, pos, CubeStructureQueries.matching(server, types::contains), false));
        }
    }

    // The tag and HolderSet overloads delegate here, preserving vanilla holder filtering.
    @Inject(method = "getStructureWithPieceAt(Lnet/minecraft/core/BlockPos;Ljava/util/function/Predicate;)"
            + "Lnet/minecraft/world/level/levelgen/structure/StructureStart;", at = @At("HEAD"), cancellable = true)
    private void cc_structurePieceAt(BlockPos pos, Predicate<Holder<Structure>> filter, CallbackInfoReturnable<StructureStart> cir) {
        if (CubeStructureQueries.handles(level, pos.getY())) {
            ServerLevel server = (ServerLevel) level;
            cir.setReturnValue(CubeStructureQueries.at(server, pos, CubeStructureQueries.matching(server, filter), true));
        }
    }

    @Inject(method = "getStructureWithPieceAt(IIILnet/minecraft/world/level/levelgen/structure/Structure;)"
            + "Lnet/minecraft/world/level/levelgen/structure/StructureStart;", at = @At("HEAD"), cancellable = true)
    private void cc_structurePieceAt(int x, int y, int z, Structure type, CallbackInfoReturnable<StructureStart> cir) {
        if (CubeStructureQueries.handles(level, y)) {
            cir.setReturnValue(CubeStructureQueries.at((ServerLevel) level, new BlockPos(x, y, z), structure -> structure == type, true));
        }
    }

    @Inject(method = "getAllStructuresAt(III)Ljava/util/Map;", at = @At("HEAD"), cancellable = true)
    private void cc_structureReferencesAt(int x, int y, int z, CallbackInfoReturnable<Map<Structure, LongSet>> cir) {
        if (CubeStructureQueries.handles(level, y)) {
            cir.setReturnValue(CubeStructureQueries.references((ServerLevel) level, new BlockPos(x, y, z)));
        }
    }
}
