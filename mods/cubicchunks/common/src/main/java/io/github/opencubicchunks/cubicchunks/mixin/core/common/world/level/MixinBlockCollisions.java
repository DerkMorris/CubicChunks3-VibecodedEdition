package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.level;

import java.util.function.BiFunction;

import javax.annotation.Nullable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.world.level.CubicCollisionGetter;
import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Retain the cube collision-provider contract; other readers, including path regions, use the native iterator. */
@Mixin(BlockCollisions.class)
public class MixinBlockCollisions {
    @Shadow @Final private CollisionGetter collisionGetter;
    @Shadow private @Nullable BlockGetter cachedBlockGetter;
    @Shadow private long cachedBlockGetterPos;
    @Unique
    private boolean cc_isCubic;

    @Inject(method = "<init>(Lnet/minecraft/world/level/CollisionGetter;Lnet/minecraft/world/phys/shapes/CollisionContext;Lnet/minecraft/world/phys/AABB;ZLjava/util/function/BiFunction;)V", at = @At(value = "CTOR_HEAD", unsafe = true))
    private void cc_onInit(
            CollisionGetter getter, CollisionContext context, AABB box, boolean onlySuffocatingBlocks, BiFunction resultProvider, CallbackInfo ci
    ) {
        cc_isCubic = getter instanceof CanBeCubic cubic && cubic.cc_isCubic() && getter instanceof CubicCollisionGetter;
    }

    private @Nullable BlockGetter cc_getCube(int x, int y, int z) {
        int cubeX = Coords.blockToCube(x);
        int cubeY = Coords.blockToCube(y);
        int cubeZ = Coords.blockToCube(z);
        long key = CubePos.asLong(cubeX, cubeY, cubeZ);
        if (cachedBlockGetter == null || cachedBlockGetterPos != key) {
            cachedBlockGetter = ((CubicCollisionGetter) collisionGetter).cc_getCubeForCollisions(cubeX, cubeY, cubeZ);
            cachedBlockGetterPos = key;
        }
        return cachedBlockGetter;
    }

    @WrapOperation(method = "computeNext", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/BlockCollisions;getChunk(II)Lnet/minecraft/world/level/BlockGetter;"))
    private @Nullable BlockGetter cc_onComputeNext_getChunk(
            BlockCollisions instance, int x, int z, Operation<BlockGetter> original, @Local(ordinal = 1) int y
    ) {
        return cc_isCubic ? cc_getCube(x, y, z) : original.call(instance, x, z);
    }
}
