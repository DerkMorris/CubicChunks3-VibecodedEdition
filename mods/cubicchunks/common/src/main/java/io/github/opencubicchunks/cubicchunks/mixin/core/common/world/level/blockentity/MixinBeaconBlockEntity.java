package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.level.blockentity;

import java.util.Arrays;
import java.util.List;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.world.level.block.entity.CubeBeaconBeam;
import io.github.opencubicchunks.cubicchunks.world.level.block.entity.CubicBeacon;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.heightmap.CubeColumnSnapshot;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.heightmap.CubeColumnSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BeaconBeamOwner;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BeaconBlockEntity.class)
public abstract class MixinBeaconBlockEntity implements CubicBeacon {
    @Unique
    private static final int CC_MAX_LEVELS = 4;
    @Shadow private int lastCheckY;
    @Shadow private int levels;
    @Shadow private List<BeaconBeamOwner.Section> beamSections;
    @Shadow private List<BeaconBeamOwner.Section> checkingBeamSections;
    @Unique
    private CubeBeaconBeam cc_beam = new CubeBeaconBeam();
    @Unique
    private int[] cc_sentBeam;
    @Unique
    private int cc_sentLevels = -1;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private static void cc_serverOwnsCubicBeam(Level level, BlockPos pos, BlockState state, BeaconBlockEntity beacon, CallbackInfo ci) {
        if (((CanBeCubic) level).cc_isCubic() && level.isClientSide()) {
            ci.cancel();
        }
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getHeight(Lnet/minecraft/world/level/levelgen/Heightmap$Types;II)I"))
    private static int cc_scanSparseColumn(
            Level receiver, Heightmap.Types type, int x, int z, Operation<Integer> original, Level level, BlockPos pos, BlockState state,
            BeaconBlockEntity beacon
    ) {
        return ((CanBeCubic) level).cc_isCubic() ? ((CubicBeacon) beacon).cc_prepareBeam(level, pos) : original.call(receiver, type, x, z);
    }

    @Override public int cc_prepareBeam(Level level, BlockPos pos) {
        CubeColumnSnapshot column = null;
        if (cc_beam.needsColumn() && level instanceof ServerLevel server) {
            column = ((CubeColumnSource) server.getChunkSource().chunkMap).cc_column(pos.getX(), pos.getZ());
        }
        var result = cc_beam.advance(column, pos);
        // Native tick has already chosen its scan cursor at or above the beacon. This virtual endpoint skips only that
        // column-based loop; its base validation, effects, sounds, advancement and publication branches remain native.
        int endpoint = pos.getY() - 1;
        lastCheckY = result == null ? endpoint - 1 : endpoint;
        if (result != null) {
            checkingBeamSections = result;
        }
        return endpoint;
    }

    @WrapOperation(method = { "tick", "updateBase" }, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMinY()I"))
    private static int cc_beaconMinimum(Level level, Operation<Integer> original) {
        return ((CanBeCubic) level).cc_isCubic() ? CubicBlockBounds.MIN : original.call(level);
    }

    @WrapOperation(method = "setLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMinY()I"))
    private int cc_initialMinimum(Level level, Operation<Integer> original) {
        return cc_beaconMinimum(level, original);
    }

    @WrapOperation(method = "applyEffects", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/AABB;expandTowards(DDD)Lnet/minecraft/world/phys/AABB;"))
    private static AABB cc_effectsReachCubicCeiling(
            AABB area, double x, double y, double z, Operation<AABB> original, Level level, BlockPos pos, int levels, Holder<MobEffect> primary,
            Holder<MobEffect> secondary
    ) {
        if (!((CanBeCubic) level).cc_isCubic()) {
            return original.call(area, x, y, z);
        }
        // Preserve the native base radius and lower boundary. Cubic entity queries visit visible entities, not the Y interval.
        return new AABB(area.minX, area.minY, area.minZ, area.maxX, CubicBlockBounds.MAX + 1.0, area.maxZ);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private static void cc_syncBeam(Level level, BlockPos pos, BlockState state, BeaconBlockEntity beacon, CallbackInfo ci) {
        if (((CanBeCubic) level).cc_isCubic() && !level.isClientSide()) {
            ((CubicBeacon) beacon).cc_publishBeam(level, pos, state);
        }
    }

    @Override public void cc_publishBeam(Level level, BlockPos pos, BlockState state) {
        int[] current = CubeBeaconBeam.encode(beamSections);
        if (levels != cc_sentLevels || !Arrays.equals(current, cc_sentBeam)) {
            cc_sentLevels = levels;
            cc_sentBeam = current;
            level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
        }
    }

    @Inject(method = "getUpdateTag", at = @At("RETURN"))
    private void cc_writeBeam(HolderLookup.Provider provider, CallbackInfoReturnable<CompoundTag> cir) {
        Level level = ((BlockEntity) (Object) this).getLevel();
        if (level != null && ((CanBeCubic) level).cc_isCubic()) {
            cir.getReturnValue().putIntArray("CubicBeam", CubeBeaconBeam.encode(beamSections));
            cir.getReturnValue().putInt("CubicBeamLevels", levels);
        }
    }

    @Inject(method = "loadAdditional", at = @At("TAIL"))
    private void cc_readBeam(ValueInput input, CallbackInfo ci) {
        input.getIntArray("CubicBeam").ifPresent(encoded -> {
            beamSections = CubeBeaconBeam.decode(encoded);
            levels = Math.clamp(input.getIntOr("CubicBeamLevels", 0), 0, CC_MAX_LEVELS);
        });
    }
}
