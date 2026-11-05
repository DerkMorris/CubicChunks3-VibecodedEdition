package io.github.opencubicchunks.cubicchunks.world.level.levelgen;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess;
import io.github.opencubicchunks.cubicchunks.world.level.cube.ProtoCube;
import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import it.unimi.dsi.fastutil.shorts.ShortList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.phys.Vec3;

/** Retain just the finished sections and their generation data, releasing noise, carver and region caches. */
final class NativeBiomeColumn {
    private static final int BEDROCK_LAYERS = 5;
    private final int firstSection;
    private final LevelChunkSection[] sections;
    private final ShortList[] postProcessing;
    private final ChunkAccess.PackedTicks ticks;
    private final List<CompoundTag> generatedEntities;
    private final Map<BlockPos, CompoundTag> entities = new HashMap<>();

    NativeBiomeColumn(ServerLevel level, ProtoChunk chunk) {
        firstSection = chunk.getMinSectionY();
        sections = chunk.getSections();
        postProcessing = chunk.getPostProcessing();
        ticks = chunk.getTicksForSerialization(0);
        generatedEntities = List.copyOf(chunk.getEntities());
        for (BlockPos pos : chunk.getBlockEntitiesPos()) {
            var tag = chunk.getBlockEntityNbtForSaving(pos, level.registryAccess());
            if (tag != null) {
                entities.put(pos.immutable(), tag.copy());
            }
        }
        if (level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator) {
            openBottom();
        }
    }

    // The cubic Overworld continues below the native noise volume.
    private void openBottom() {
        var bottom = sections[0];
        if (!bottom.maybeHas(state -> state.is(Blocks.BEDROCK))) {
            return;
        }
        for (int y = 0; y < BEDROCK_LAYERS; y++) {
            for (int x = 0; x < SectionPos.SECTION_SIZE; x++) {
                for (int z = 0; z < SectionPos.SECTION_SIZE; z++) {
                    if (bottom.getBlockState(x, y, z).is(Blocks.BEDROCK)) {
                        bottom.setBlockState(x, y, z, Blocks.DEEPSLATE.defaultBlockState(), false);
                    }
                }
            }
        }
    }

    LevelChunkSection section(int sy) {
        return sections[sy - firstSection];
    }

    void copyExtras(CubeAccess cube, int sy, int i) {
        var positions = postProcessing[sy - firstSection];
        if (positions != null) {
            cube.getPostProcessing()[i] = new ShortArrayList(positions);
        }
        if (cube instanceof ProtoCube proto) {
            for (CompoundTag tag : generatedEntities) {
                BlockPos pos = BlockPos.containing(tag.read("Pos", Vec3.CODEC).orElseThrow());
                if (SectionPos.blockToSectionCoord(pos.getY()) == sy) {
                    proto.addEntity(tag.copy());
                }
            }
        }
        entities.forEach((pos, tag) -> {
            if (SectionPos.blockToSectionCoord(pos.getY()) == sy) {
                cube.setBlockEntityNbt(tag.copy());
            }
        });
        ticks.blocks().stream().filter(t -> SectionPos.blockToSectionCoord(t.pos().getY()) == sy)
                .forEach(t -> cube.getBlockTicks().schedule(t.unpack(0, 0)));
        ticks.fluids().stream().filter(t -> SectionPos.blockToSectionCoord(t.pos().getY()) == sy)
                .forEach(t -> cube.getFluidTicks().schedule(t.unpack(0, 0)));
    }
}
