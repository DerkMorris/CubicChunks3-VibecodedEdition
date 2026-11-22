package io.github.opencubicchunks.cubicchunks.mixin.core.client.compat.sodium;

import javax.annotation.Nullable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.world.level.CubicLevel;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import it.unimi.dsi.fastutil.ints.Int2ReferenceMap;
import it.unimi.dsi.fastutil.ints.Int2ReferenceOpenHashMap;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Feeds cube block entities through Sodium's existing render-data snapshot path. */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.world.cloned.ClonedChunkSection", remap = false)
public abstract class MixinClonedChunkSection {
    @WrapOperation(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/world/cloned/ClonedChunkSection;tryCopyBlockEntities(Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/core/SectionPos;)Lit/unimi/dsi/fastutil/ints/Int2ReferenceMap;"))
    private @Nullable Int2ReferenceMap<BlockEntity> cc_copyCubeBlockEntities(
            LevelChunk chunk, SectionPos sectionPos, Operation<Int2ReferenceMap<BlockEntity>> original,
            Level level, LevelChunk unusedChunk, @Nullable LevelChunkSection section, SectionPos unusedPos
    ) {
        if (!((CanBeCubic) level).cc_isCubic()) {
            return original.call(chunk, sectionPos);
        }
        var cube = ((CubicLevel) level).cc_getCube(Coords.sectionToCube(sectionPos.x()), Coords.sectionToCube(sectionPos.y()),
                Coords.sectionToCube(sectionPos.z()), ChunkStatus.FULL, false);
        if (!(cube instanceof LevelCube levelCube)) {
            return null;
        }
        Int2ReferenceOpenHashMap<BlockEntity> entities = null;
        for (var entry : levelCube.getBlockEntities().entrySet()) {
            var pos = entry.getKey();
            if (SectionPos.blockToSectionCoord(pos.getX()) == sectionPos.x()
                    && SectionPos.blockToSectionCoord(pos.getY()) == sectionPos.y()
                    && SectionPos.blockToSectionCoord(pos.getZ()) == sectionPos.z()) {
                if (entities == null) {
                    entities = new Int2ReferenceOpenHashMap<>();
                }
                entities.put(LevelSlice.getLocalBlockIndex(SectionPos.sectionRelative(pos.getX()), SectionPos.sectionRelative(pos.getY()),
                        SectionPos.sectionRelative(pos.getZ())), entry.getValue());
            }
        }
        if (entities != null) {
            entities.trim();
        }
        return entities;
    }
}
