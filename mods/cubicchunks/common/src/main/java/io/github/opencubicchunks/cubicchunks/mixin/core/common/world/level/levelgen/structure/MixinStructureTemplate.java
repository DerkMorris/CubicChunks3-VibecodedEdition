package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.level.levelgen.structure;

import java.util.List;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.structure.CubeTemplateArea;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.structure.CubeTemplateBlockMatcher;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.structure.CubeTemplateEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.blockscan.BlockMatcher;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Group;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Replace the column scanner and preflight cube coverage; retain vanilla palettes, NBT, transforms and placement callbacks. */
@Mixin(StructureTemplate.class)
public abstract class MixinStructureTemplate {
    @Shadow @Final private List<StructureTemplate.StructureEntityInfo> entityInfoList;

    @WrapOperation(method = "fillFromWorld", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate;fillEntityList(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/ProblemReporter;)V"))
    private void cc_installCapturedEntities(
            StructureTemplate template, Level level, BlockPos min, BlockPos max, ProblemReporter reporter, Operation<Void> original,
            @Share("cc_capturedEntities") LocalRef<List<StructureTemplate.StructureEntityInfo>> captured
    ) {
        if (captured.get() != null) {
            entityInfoList.clear();
            entityInfoList.addAll(captured.get());
        } else {
            original.call(template, level, min, max, reporter);
        }
    }

    @Inject(method = "fillFromWorld", at = @At("HEAD"))
    private void cc_requireCaptureArea(
            Level level, BlockPos origin, Vec3i size, boolean includeEntities, List<Block> ignored, CallbackInfo ci,
            @Share("cc_capturedEntities") LocalRef<List<StructureTemplate.StructureEntityInfo>> captured
    ) {
        if (((CanBeCubic) level).cc_isCubic() && CubeTemplateArea.capture(level, origin, size).isEmpty()) {
            // The structure block entry point returns false instead. Direct API callers must not silently retain stale palettes.
            throw new IllegalStateException("Structure capture requires a supported area of loaded cubes");
        }
        if (includeEntities && level instanceof ServerLevel server && ((CanBeCubic) server).cc_isCubic()) {
            captured.set(CubeTemplateEntities.capture(server, origin, origin.offset(size).offset(-1, -1, -1)));
        }
    }

    @WrapOperation(method = "fillFromWorld", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;findBlocksIn(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/blockscan/BlockMatcher;"))
    private BlockMatcher cc_captureCubes(Level level, BlockPos min, BlockPos max, Operation<BlockMatcher> original) {
        return ((CanBeCubic) level).cc_isCubic() ? new CubeTemplateBlockMatcher(level, min, max) : original.call(level, min, max);
    }

    @Inject(method = "placeInWorld", at = @At("HEAD"), cancellable = true)
    private void cc_requirePlacementArea(
            ServerLevelAccessor accessor, BlockPos origin, BlockPos reference, StructurePlaceSettings settings, RandomSource random, int flags,
            CallbackInfoReturnable<Boolean> cir, @Share("cc_entities") LocalRef<CubeTemplateEntities.Placement> entities
    ) {
        if (accessor instanceof Level level && ((CanBeCubic) level).cc_isCubic()
                && !CubeTemplateArea.canPlace(level, (StructureTemplate) (Object) this, origin, settings)) {
            cir.setReturnValue(false);
            return;
        }
        if (accessor instanceof ServerLevel server && ((CanBeCubic) server).cc_isCubic() && !settings.isIgnoreEntities()) {
            var prepared = CubeTemplateEntities.prepare(server, entityInfoList, origin, settings);
            if (prepared.isEmpty()) {
                cir.setReturnValue(false);
            } else {
                entities.set(prepared.get());
            }
        }
    }

    @Group(name = "cc_placeEntities", min = 1, max = 1)
    @WrapOperation(method = "placeInWorld", require = 0, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate;placeEntities(Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Mirror;Lnet/minecraft/world/level/block/Rotation;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/levelgen/structure/BoundingBox;ZLnet/minecraft/util/ProblemReporter;)V"))
    private void cc_placePreparedEntities(
            StructureTemplate template, ServerLevelAccessor level, BlockPos origin, Mirror mirror, Rotation rotation, BlockPos pivot, BoundingBox box,
            boolean finalizeEntities, ProblemReporter reporter, Operation<Void> original,
            @Share("cc_entities") LocalRef<CubeTemplateEntities.Placement> entities
    ) {
        if (entities.get() == null) {
            original.call(template, level, origin, mirror, rotation, pivot, box, finalizeEntities, reporter);
        } else {
            entities.get().place();
        }
    }

    // FML distributions pass placement settings to their entity-processor hooks.
    // Exactly one of these three descriptors must match the installed Minecraft.
    @Group(name = "cc_placeEntities", min = 1, max = 1)
    @WrapOperation(method = "placeInWorld", require = 0, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate;placeEntities(Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Mirror;Lnet/minecraft/world/level/block/Rotation;Lnet/minecraft/world/level/levelgen/structure/BoundingBox;ZLnet/minecraft/util/ProblemReporter;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructurePlaceSettings;)V"))
    private void cc_placePreparedEntitiesForge(
            StructureTemplate template, ServerLevelAccessor level, BlockPos origin, Mirror mirror, Rotation rotation, BoundingBox box,
            boolean finalizeEntities, ProblemReporter reporter, StructurePlaceSettings settings, Operation<Void> original,
            @Share("cc_entities") LocalRef<CubeTemplateEntities.Placement> entities
    ) {
        if (entities.get() == null) {
            original.call(template, level, origin, mirror, rotation, box, finalizeEntities, reporter, settings);
        } else {
            entities.get().place();
        }
    }

    @Group(name = "cc_placeEntities", min = 1, max = 1)
    @WrapOperation(method = "placeInWorld", require = 0, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate;placeEntities(Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Mirror;Lnet/minecraft/world/level/block/Rotation;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/levelgen/structure/BoundingBox;ZLnet/minecraft/util/ProblemReporter;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructurePlaceSettings;)V"))
    private void cc_placePreparedEntitiesNeoForge(
            StructureTemplate template, ServerLevelAccessor level, BlockPos origin, Mirror mirror, Rotation rotation, BlockPos pivot, BoundingBox box,
            boolean finalizeEntities, ProblemReporter reporter, StructurePlaceSettings settings, Operation<Void> original,
            @Share("cc_entities") LocalRef<CubeTemplateEntities.Placement> entities
    ) {
        if (entities.get() == null) {
            original.call(template, level, origin, mirror, rotation, pivot, box, finalizeEntities, reporter, settings);
        } else {
            entities.get().place();
        }
    }

    @Inject(method = "placeInWorld", at = @At("RETURN"), cancellable = true)
    private void cc_reportEntityFailure(
            ServerLevelAccessor level, BlockPos origin, BlockPos reference, StructurePlaceSettings settings, RandomSource random, int flags,
            CallbackInfoReturnable<Boolean> cir, @Share("cc_entities") LocalRef<CubeTemplateEntities.Placement> entities
    ) {
        if (entities.get() != null && !entities.get().successful()) {
            cir.setReturnValue(false);
        }
    }
}
