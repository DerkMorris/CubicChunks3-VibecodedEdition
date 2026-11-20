package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.level.portal;

import java.util.Comparator;
import java.util.Optional;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.world.level.poi.CubicPoiManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.portal.PortalForcer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keep the native frame search/construction, with a finite cubic search centred on the destination. */
@Mixin(PortalForcer.class)
public abstract class MixinPortalForcer {
    @Unique
    private static final int CC_VERTICAL_SEARCH_RADIUS = 128;
    @Unique
    private static final int CC_NETHER_SEARCH_RADIUS = 16;
    @Shadow @Final private ServerLevel level;

    @Inject(method = "findClosestPortalPosition", at = @At("HEAD"), cancellable = true)
    private void cc_nearestPortalFirst(BlockPos origin, boolean toNether, WorldBorder border, CallbackInfoReturnable<Optional<BlockPos>> cir) {
        var storage = ((CubicPoiManager) level.getPoiManager()).cc_cubePoi();
        if (storage != null) {
            cir.setReturnValue(storage.portalsInHorizontalSquare(origin, toNether ? CC_NETHER_SEARCH_RADIUS : CC_VERTICAL_SEARCH_RADIUS)
                    .map(PoiRecord::getPos).filter(border::isWithinBounds)
                    .sorted(Comparator.<BlockPos>comparingDouble(pos -> pos.distSqr(origin)).thenComparingInt(BlockPos::getY))
                    .filter(pos -> level.getBlockState(pos).hasProperty(BlockStateProperties.HORIZONTAL_AXIS)).findFirst());
        }
    }

    @Inject(method = "canHostFrame", at = @At("HEAD"), cancellable = true)
    private void cc_keepFrameInsideWorld(
            BlockPos origin, BlockPos.MutableBlockPos mutable, Direction direction, int offset, CallbackInfoReturnable<Boolean> cir
    ) {
        if (((CanBeCubic) level).cc_isCubic() && origin.getY() <= CubicBlockBounds.MIN) {
            cir.setReturnValue(false);
        }
    }

    @WrapOperation(method = "createPortal", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getMinY()I"))
    private int cc_searchMinimum(ServerLevel world, Operation<Integer> original, @Local(argsOnly = true) BlockPos origin) {
        return ((CanBeCubic) world).cc_isCubic() ? Math.max(CubicBlockBounds.MIN, origin.getY() - CC_VERTICAL_SEARCH_RADIUS) : original.call(world);
    }

    @WrapOperation(method = "createPortal", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getMaxY()I"))
    private int cc_searchMaximum(ServerLevel world, Operation<Integer> original, @Local(argsOnly = true) BlockPos origin) {
        return ((CanBeCubic) world).cc_isCubic() ? Math.min(CubicBlockBounds.MAX, origin.getY() + CC_VERTICAL_SEARCH_RADIUS) : original.call(world);
    }

    @WrapOperation(method = "createPortal", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getLogicalHeight()I"))
    private int cc_searchHeight(ServerLevel world, Operation<Integer> original, @Local(argsOnly = true) BlockPos origin) {
        return ((CanBeCubic) world).cc_isCubic()
                ? Math.min(CubicBlockBounds.MAX, origin.getY() + CC_VERTICAL_SEARCH_RADIUS)
                        - Math.max(CubicBlockBounds.MIN, origin.getY() - CC_VERTICAL_SEARCH_RADIUS) + 1
                : original.call(world);
    }

    @WrapOperation(method = "createPortal", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getHeight(Lnet/minecraft/world/level/levelgen/Heightmap$Types;II)I"))
    private int cc_startAtSearchTop(
            ServerLevel world, Heightmap.Types type, int x, int z, Operation<Integer> original, @Local(argsOnly = true) BlockPos origin
    ) {
        return ((CanBeCubic) world).cc_isCubic() ? Math.min(CubicBlockBounds.MAX, origin.getY() + CC_VERTICAL_SEARCH_RADIUS)
                : original.call(world, type, x, z);
    }

    @WrapOperation(method = "createPortal", at = @At(value = "INVOKE", target = "Ljava/lang/Math;max(II)I"))
    private int cc_fallbackMayBeBelowSeaLevel(int minimum, int seaLevel, Operation<Integer> original) {
        return ((CanBeCubic) level).cc_isCubic() ? minimum : original.call(minimum, seaLevel);
    }
}
