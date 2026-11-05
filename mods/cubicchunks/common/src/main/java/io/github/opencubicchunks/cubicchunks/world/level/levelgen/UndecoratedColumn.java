package io.github.opencubicchunks.cubicchunks.world.level.levelgen;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import it.unimi.dsi.fastutil.shorts.ShortList;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.ticks.ProtoChunkTicks;

/** Completed generation stage shared by overlapping decoration regions. No region ever decorates the cached sections. */
final class UndecoratedColumn {
    private final ChunkPos pos;
    private final ChunkStatus status;
    private final LevelChunkSection[] sections;
    private final Map<Heightmap.Types, long[]> heights = new EnumMap<>(Heightmap.Types.class);
    private final Map<BlockPos, CompoundTag> blockEntities = new HashMap<>();
    private final ShortList[] post;
    private final ChunkAccess.PackedTicks ticks;
    private final List<CompoundTag> entities;

    UndecoratedColumn(ServerLevel level, ProtoChunk generated) {
        pos = generated.getPos();
        status = generated.getPersistedStatus();
        sections = generated.getSections();
        post = generated.getPostProcessing();
        entities = List.copyOf(generated.getEntities());
        ticks = generated.getTicksForSerialization(0);
        generated.getHeightmaps().forEach(entry -> heights.put(entry.getKey(), entry.getValue().getRawData().clone()));
        for (BlockPos p : generated.getBlockEntitiesPos()) {
            var tag = generated.getBlockEntityNbtForSaving(p, level.registryAccess());
            if (tag != null) {
                blockEntities.put(p.immutable(), tag.copy());
            }
        }
    }

    ProtoChunk copy(ServerLevel level) {
        var body = Arrays.stream(sections).map(LevelChunkSection::copy).toArray(LevelChunkSection[]::new);
        var chunk = new ProtoChunk(pos, UpgradeData.EMPTY, body, ProtoChunkTicks.load(ticks.blocks()), ProtoChunkTicks.load(ticks.fluids()),
                LevelHeightAccessor.create(level.getMinY(), level.getHeight()), level.palettedContainerFactory(), null);
        heights.forEach((type, values) -> chunk.setHeightmap(type, values.clone()));
        blockEntities.values().forEach(tag -> chunk.setBlockEntityNbt(tag.copy()));
        entities.forEach(tag -> chunk.addEntity(tag.copy()));
        for (int i = 0; i < post.length; i++) {
            if (post[i] != null) {
                chunk.addPackedPostProcess(new ShortArrayList(post[i]), i);
            }
        }
        chunk.setPersistedStatus(status);
        return chunk;
    }
}
