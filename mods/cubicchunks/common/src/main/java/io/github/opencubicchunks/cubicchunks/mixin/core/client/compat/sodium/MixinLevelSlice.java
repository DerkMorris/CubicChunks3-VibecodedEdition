package io.github.opencubicchunks.cubicchunks.mixin.core.client.compat.sodium;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.world.level.CubicLevel;
import net.caffeinemc.mods.sodium.client.world.cloned.ClonedChunkSectionCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Keeps Sodium's snapshot preparation and mesh appenders, sourcing its emptiness check from the cube. */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.world.LevelSlice", remap = false)
public abstract class MixinLevelSlice {
    @Shadow @Final private ClientLevel level;

    @ModifyReturnValue(method = "getMinY", at = @At("RETURN"))
    private int cc_fullHeightMinimum(int original) {
        return ((CanBeCubic) level).cc_isCubic() ? CubicBlockBounds.MIN : original;
    }

    @ModifyReturnValue(method = "getHeight", at = @At("RETURN"))
    private int cc_fullHeightRange(int original) {
        return ((CanBeCubic) level).cc_isCubic() ? CubicBlockBounds.MAX - CubicBlockBounds.MIN + 1 : original;
    }

    @WrapOperation(method = "prepare", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunk;getSections()[Lnet/minecraft/world/level/chunk/LevelChunkSection;"))
    private static LevelChunkSection[] cc_cubeSections(
            LevelChunk chunk, Operation<LevelChunkSection[]> original, Level level, SectionPos pos, ClonedChunkSectionCache cache
    ) {
        if (!((CanBeCubic) level).cc_isCubic()) {
            return original.call(chunk);
        }
        return ((CubicLevel) level).cc_getCube(Coords.sectionToCube(pos.x()), Coords.sectionToCube(pos.y()), Coords.sectionToCube(pos.z()))
                .getSections();
    }

    @WrapOperation(method = "prepare", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getSectionIndexFromSectionY(I)I"))
    private static int cc_cubeSectionIndex(Level level, int sectionY, Operation<Integer> original, Level unusedLevel, SectionPos pos,
            ClonedChunkSectionCache cache) {
        return ((CanBeCubic) level).cc_isCubic() ? Coords.sectionToIndex(pos.x(), sectionY, pos.z()) : original.call(level, sectionY);
    }
}
