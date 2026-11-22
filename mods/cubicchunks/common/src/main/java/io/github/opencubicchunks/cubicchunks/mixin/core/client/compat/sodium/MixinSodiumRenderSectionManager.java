package io.github.opencubicchunks.cubicchunks.mixin.core.client.compat.sodium;

import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.world.level.CubicLevel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager", remap = false)
public abstract class MixinSodiumRenderSectionManager {
    @Shadow @Final private ClientLevel level;

    @Redirect(method = "isOutOfGraph", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getMinSectionY()I"))
    private int cc_minSection(ClientLevel world) {
        return ((CanBeCubic) world).cc_isCubic() ? SectionPos.blockToSectionCoord(CubicBlockBounds.MIN) : world.getMinSectionY();
    }

    @Redirect(method = "isOutOfGraph", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getMaxSectionY()I"))
    private int cc_maxSection(ClientLevel world) {
        return ((CanBeCubic) world).cc_isCubic() ? SectionPos.blockToSectionCoord(CubicBlockBounds.MAX) : world.getMaxSectionY();
    }

    /** Cubic worlds are populated by individual cube events, never by scanning the full world height. */
    @Inject(method = { "onChunkAdded", "onChunkRemoved" }, at = @At("HEAD"), cancellable = true)
    private void cc_skipColumnEvents(int x, int z, CallbackInfo ci) {
        if (((CanBeCubic) level).cc_isCubic()) {
            ci.cancel();
        }
    }

    @Redirect(method = "onSectionAdded", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkAccess;getSections()[Lnet/minecraft/world/level/chunk/LevelChunkSection;"))
    private LevelChunkSection[] cc_cubeSections(ChunkAccess column, int x, int y, int z) {
        if (((CanBeCubic) level).cc_isCubic()) {
            return ((CubicLevel) level).cc_getCube(Coords.sectionToCube(x), Coords.sectionToCube(y), Coords.sectionToCube(z)).getSections();
        }
        return column.getSections();
    }

    @Redirect(method = "onSectionAdded", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getSectionIndexFromSectionY(I)I"))
    private int cc_cubeSectionIndex(ClientLevel world, int sectionY, int x, int y, int z) {
        return ((CanBeCubic) world).cc_isCubic() ? Coords.sectionToIndex(x, y, z) : world.getSectionIndexFromSectionY(sectionY);
    }
}
