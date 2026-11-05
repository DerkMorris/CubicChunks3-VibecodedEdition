package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.level.block.entity;

import java.util.List;
import java.util.Optional;

import javax.annotation.Nullable;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.structure.CubeStructureCorners;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.structure.CubeTemplateArea;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.structure.CubeTemplateEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.StructureBlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Use cubic corner bounds and reject unavailable template areas before vanilla mutates a template or reports a successful load. */
@Mixin(StructureBlockEntity.class)
public abstract class MixinStructureBlockEntity {
    @Unique
    private boolean cc_templatePlaced;

    @WrapMethod(method = "saveStructure(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/resources/Identifier;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Vec3i;ZLjava/lang/String;ZLjava/util/List;)Z")
    private static boolean cc_reportEntityCaptureFailure(
            ServerLevel level, Identifier name, BlockPos origin, Vec3i size, boolean ignoreEntities, String author, boolean saveToDisk,
            List<Block> ignored, Operation<Boolean> original
    ) {
        try {
            return original.call(level, name, origin, size, ignoreEntities, author, saveToDisk, ignored);
        } catch (CubeTemplateEntities.InvalidSnapshotException failure) {
            return false;
        }
    }

    @Inject(method = "saveStructure(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/resources/Identifier;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Vec3i;ZLjava/lang/String;ZLjava/util/List;)Z", at = @At("HEAD"), cancellable = true)
    private static void cc_requireSaveArea(
            ServerLevel level, Identifier name, BlockPos origin, Vec3i size, boolean ignoreEntities, String author, boolean saveToDisk,
            List<Block> ignored, CallbackInfoReturnable<Boolean> cir
    ) {
        if (((CanBeCubic) level).cc_isCubic() && CubeTemplateArea.capture(level, origin, size).isEmpty()) {
            cir.setReturnValue(false);
        }
    }

    @WrapMethod(method = "placeStructureIfSameSize")
    private boolean cc_reportPlacementResult(ServerLevel level, Operation<Boolean> original) {
        if (!((CanBeCubic) level).cc_isCubic()) {
            return original.call(level);
        }
        boolean previous = cc_templatePlaced;
        cc_templatePlaced = false;
        try {
            return original.call(level) && cc_templatePlaced;
        } finally {
            cc_templatePlaced = previous;
        }
    }

    @WrapOperation(method = "placeStructure(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate;placeInWorld(Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructurePlaceSettings;Lnet/minecraft/util/RandomSource;I)Z"))
    private boolean cc_recordPlacementResult(
            StructureTemplate template, ServerLevelAccessor level, BlockPos origin, BlockPos reference, StructurePlaceSettings settings,
            RandomSource random, int flags, Operation<Boolean> original
    ) {
        boolean placed = original.call(template, level, origin, reference, settings, random, flags);
        cc_templatePlaced = placed;
        return placed;
    }

    @Inject(method = "getEnclosingBoundingBox", at = @At("HEAD"), cancellable = true)
    private static void cc_findCubeCorners(
            Level level, BlockPos origin, @Nullable Identifier name, CallbackInfoReturnable<Optional<BoundingBox>> cir
    ) {
        if (((CanBeCubic) level).cc_isCubic()) {
            cir.setReturnValue(CubeStructureCorners.find(level, origin, name));
        }
    }
}
