package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.entity.ai.poi;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import com.mojang.datafixers.DataFixer;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.world.level.poi.CubePoiStorage;
import io.github.opencubicchunks.cubicchunks.world.level.poi.CubicPoiManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.util.debug.DebugPoiInfo;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.storage.ChunkIOErrorReporter;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PoiManager.class)
public abstract class MixinPoiManager implements CubicPoiManager {
    @Unique
    private LevelHeightAccessor cc_height;
    @Unique
    private CubePoiStorage cc_storage;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void cc_init(
            RegionStorageInfo info, Path folder, DataFixer fixer, boolean sync, RegistryAccess registries, ChunkIOErrorReporter errors,
            LevelHeightAccessor height, CallbackInfo ci
    ) {
        this.cc_height = height;
        this.cc_storage = new CubePoiStorage(folder.resolveSibling("cube_poi"), registries);
    }

    @Override public @Nullable CubePoiStorage cc_cubePoi() {
        return this.cc_height instanceof CanBeCubic cubic && cubic.cc_isCubic() ? this.cc_storage : null;
    }

    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void cc_add(BlockPos pos, Holder<PoiType> type, CallbackInfoReturnable<PoiRecord> cir) {
        var cubes = cc_cubePoi();
        if (cubes != null) {
            var section = cubes.section(pos);
            section.getType(pos).filter(old -> !old.equals(type)).ifPresent(old -> section.remove(pos));
            cir.setReturnValue(section.add(pos, type));
        }
    }

    @Inject(method = "remove", at = @At("HEAD"), cancellable = true)
    private void cc_remove(BlockPos pos, CallbackInfo ci) {
        var cubes = cc_cubePoi();
        if (cubes != null) {
            var section = cubes.section(pos);
            if (section.getType(pos).isPresent()) {
                section.remove(pos);
            }
            ci.cancel();
        }
    }

    @Inject(method = "getInSquare", at = @At("HEAD"), cancellable = true)
    private void cc_inSquare(
            Predicate<Holder<PoiType>> filter, BlockPos center, int radius, PoiManager.Occupancy occupancy,
            CallbackInfoReturnable<Stream<PoiRecord>> cir
    ) {
        var cubes = cc_cubePoi();
        if (cubes != null) {
            cir.setReturnValue(cubes.inBox(filter, center, radius, occupancy));
        }
    }

    @Inject(method = "getInRange", at = @At("HEAD"), cancellable = true)
    private void cc_inRange(
            Predicate<Holder<PoiType>> filter, BlockPos center, int radius, PoiManager.Occupancy occupancy,
            CallbackInfoReturnable<Stream<PoiRecord>> cir
    ) {
        var cubes = cc_cubePoi();
        if (cubes != null) {
            cir.setReturnValue(
                    cubes.inBox(filter, center, radius, occupancy).filter(record -> record.getPos().distSqr(center) <= (double) radius * radius));
        }
    }

    @Inject(method = "getInChunk", at = @At("HEAD"), cancellable = true)
    private void cc_inColumn(
            Predicate<Holder<PoiType>> filter, ChunkPos column, PoiManager.Occupancy occupancy, CallbackInfoReturnable<Stream<PoiRecord>> cir
    ) {
        var cubes = cc_cubePoi();
        if (cubes != null) {
            cir.setReturnValue(cubes.inLoadedColumn(filter, column, occupancy));
        }
    }

    @Inject(method = "release", at = @At("HEAD"), cancellable = true)
    private void cc_release(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        var cubes = cc_cubePoi();
        if (cubes != null) {
            cir.setReturnValue(cubes.section(pos).release(pos));
        }
    }

    @Inject(method = "exists", at = @At("HEAD"), cancellable = true)
    private void cc_exists(BlockPos pos, Predicate<Holder<PoiType>> filter, CallbackInfoReturnable<Boolean> cir) {
        var cubes = cc_cubePoi();
        if (cubes != null) {
            cir.setReturnValue(cubes.section(pos).exists(pos, filter));
        }
    }

    @Inject(method = "getType", at = @At("HEAD"), cancellable = true)
    private void cc_type(BlockPos pos, CallbackInfoReturnable<Optional<Holder<PoiType>>> cir) {
        var cubes = cc_cubePoi();
        if (cubes != null) {
            cir.setReturnValue(cubes.section(pos).getType(pos));
        }
    }

    @Inject(method = "getDebugPoiInfo", at = @At("HEAD"), cancellable = true)
    private void cc_debug(BlockPos pos, CallbackInfoReturnable<DebugPoiInfo> cir) {
        var cubes = cc_cubePoi();
        if (cubes != null) {
            cir.setReturnValue(cubes.section(pos).getDebugPoiInfo(pos).orElse(null));
        }
    }

    @Inject(method = "sectionsToVillage", at = @At("HEAD"), cancellable = true)
    private void cc_village(SectionPos pos, CallbackInfoReturnable<Integer> cir) {
        var cubes = cc_cubePoi();
        if (cubes != null) {
            cir.setReturnValue(cubes.sectionsToVillage(pos));
        }
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void cc_tick(BooleanSupplier time, CallbackInfo ci) {
        var cubes = cc_cubePoi();
        if (cubes != null) {
            cubes.tick(time);
            ci.cancel();
        }
    }

    @Inject(method = "checkConsistencyWithBlocks", at = @At("HEAD"), cancellable = true)
    private void cc_consistency(SectionPos pos, LevelChunkSection blocks, CallbackInfo ci) {
        var cubes = cc_cubePoi();
        if (cubes != null) {
            cubes.reconcile(pos, blocks);
            ci.cancel();
        }
    }

    @Inject(method = "ensureLoadedAndValid", at = @At("HEAD"), cancellable = true)
    private void cc_ensure(LevelReader reader, BlockPos center, int radius, CallbackInfo ci) {
        var cubes = cc_cubePoi();
        if (cubes != null) {
            cubes.ensureLoadedAndValid(reader, center, radius);
            ci.cancel();
        }
    }
}
