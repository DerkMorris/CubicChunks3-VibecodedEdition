package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.level.chunk;

import java.util.Collection;
import java.util.Map;

import io.github.opencubicchunks.cc_core.world.level.CloPos;
import io.github.opencubicchunks.cubicchunks.world.level.chunklike.CloAccess;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.heightmap.CloHeightmap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.blending.BlendingData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkAccess.class)
public abstract class MixinChunkAccess implements CloAccess {
    private CloPos cc_cloPos;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void cc_onInit(
            ChunkPos chunkPos, UpgradeData upgradeData, LevelHeightAccessor levelHeightAccessor, PalettedContainerFactory paletteFactory,
            long inhabitedTime, LevelChunkSection[] sections, BlendingData blendingData, CallbackInfo ci
    ) {
        cc_cloPos = CloPos.chunk(chunkPos);
    }

    public CloPos cc_getCloPos() {
        return cc_cloPos;
    }

    @Override public Collection<Map.Entry<Heightmap.Types, CloHeightmap>> cc_getHeightmaps() {
        return ((ChunkAccess) (Object) this).getHeightmaps().stream().map(entry -> Map.entry(entry.getKey(), (CloHeightmap) entry.getValue()))
                .toList();
    }

    @Override public CloHeightmap cc_getOrCreateHeightmapUnprimed(Heightmap.Types type) {
        return (CloHeightmap) ((ChunkAccess) (Object) this).getOrCreateHeightmapUnprimed(type);
    }
}
