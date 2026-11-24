package io.github.opencubicchunks.cubicchunks.world.level.levelgen;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess;
import io.github.opencubicchunks.cubicchunks.world.level.cube.ProtoCube;
import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.ticks.SavedTick;

/** Immutable native placement writes and generation extras, indexed without packed section or block Y. */
final class DeepStructureResult {
    static final DeepStructureResult EMPTY = new DeepStructureResult(Map.of());
    private final Map<SectionPos, Delta> sections;

    private DeepStructureResult(Map<SectionPos, Delta> sections) {
        this.sections = Map.copyOf(sections);
    }

    static DeepStructureResult capture(ServerLevel level, Collection<DeepStructureRegion.StagingChunk> chunks) {
        Map<SectionPos, Builder> result = new HashMap<>();
        var ordered = chunks.stream().sorted(Comparator.comparingInt((DeepStructureRegion.StagingChunk chunk) -> chunk.getPos().x())
                .thenComparingInt(chunk -> chunk.getPos().z())).toList();
        for (var chunk : ordered) {
            chunk.writes.forEach((pos, value) -> builder(result, pos).blocks.put(pos, value));
            for (BlockPos pos : chunk.getBlockEntitiesPos()) {
                var tag = chunk.getBlockEntityNbtForSaving(pos, level.registryAccess());
                if (tag != null) {
                    builder(result, pos).blockEntities.put(pos.immutable(), tag.copy());
                }
            }
            for (CompoundTag tag : chunk.getEntities()) {
                var pos = BlockPos.containing(tag.read("Pos", Vec3.CODEC).orElseThrow());
                builder(result, pos).entities.add(tag.copy());
            }
            var ticks = chunk.getTicksForSerialization(0);
            ticks.blocks().forEach(tick -> builder(result, tick.pos()).blockTicks.add(tick));
            ticks.fluids().forEach(tick -> builder(result, tick.pos()).fluidTicks.add(tick));
            var post = chunk.getPostProcessing();
            for (int index = 0; index < post.length; index++) {
                if (post[index] != null && !post[index].isEmpty()) {
                    var key = SectionPos.of(chunk.getPos().x(), chunk.getSectionYFromSectionIndex(index), chunk.getPos().z());
                    result.computeIfAbsent(key, ignored -> new Builder()).postProcessing.addAll(post[index]);
                }
            }
        }
        if (result.isEmpty()) {
            return EMPTY;
        }
        Map<SectionPos, Delta> immutable = new HashMap<>();
        result.forEach((key, value) -> immutable.put(key, value.build()));
        return new DeepStructureResult(immutable);
    }

    private static Builder builder(Map<SectionPos, Builder> sections, BlockPos pos) {
        return sections.computeIfAbsent(SectionPos.of(pos), ignored -> new Builder());
    }

    boolean isEmpty() {
        return sections.isEmpty();
    }

    Set<SectionPos> sections() {
        return sections.keySet();
    }

    boolean intersects(SectionPos pos) {
        return sections.containsKey(pos);
    }

    /** Section caches retain only their own delta, never all the other sections of a large structure. */
    DeepStructureResult forSection(SectionPos pos) {
        var delta = sections.get(pos);
        return delta == null ? EMPTY : new DeepStructureResult(Map.of(pos, delta));
    }

    void apply(LevelChunkSection section, int sx, int sy, int sz) {
        var delta = sections.get(SectionPos.of(sx, sy, sz));
        if (delta != null) {
            delta.blocks.forEach((pos, value) -> section.setBlockState(SectionPos.sectionRelative(pos.getX()),
                    SectionPos.sectionRelative(pos.getY()), SectionPos.sectionRelative(pos.getZ()), value, false));
        }
    }

    void copyExtras(CubeAccess cube, int sectionIndex) {
        var pos = cube.cc_getCubePos();
        var key = SectionPos.of(Coords.cubeToSection(pos.getX(), Coords.indexToX(sectionIndex)),
                Coords.cubeToSection(pos.getY(), Coords.indexToY(sectionIndex)), Coords.cubeToSection(pos.getZ(), Coords.indexToZ(sectionIndex)));
        var delta = sections.get(key);
        if (delta == null) {
            return;
        }
        delta.blocks.keySet().forEach(cube::removeBlockEntity);
        delta.blockEntities.values().forEach(tag -> cube.setBlockEntityNbt(tag.copy()));
        if (cube instanceof ProtoCube proto) {
            delta.entities.forEach(tag -> proto.addEntity(tag.copy()));
        }
        delta.blockTicks.forEach(tick -> cube.getBlockTicks().schedule(tick.unpack(0, 0)));
        delta.fluidTicks.forEach(tick -> cube.getFluidTicks().schedule(tick.unpack(0, 0)));
        if (delta.postProcessing.length != 0) {
            var post = cube.getPostProcessing();
            if (post[sectionIndex] == null) {
                post[sectionIndex] = new ShortArrayList();
            }
            for (short value : delta.postProcessing) {
                post[sectionIndex].add(value);
            }
        }
    }

    private record Delta(
            Map<BlockPos, BlockState> blocks, Map<BlockPos, CompoundTag> blockEntities, List<CompoundTag> entities, List<SavedTick<Block>> blockTicks,
            List<SavedTick<Fluid>> fluidTicks, short[] postProcessing
    ) {}

    private static final class Builder {
        private final Map<BlockPos, BlockState> blocks = new HashMap<>();
        private final Map<BlockPos, CompoundTag> blockEntities = new HashMap<>();
        private final List<CompoundTag> entities = new ArrayList<>();
        private final List<SavedTick<Block>> blockTicks = new ArrayList<>();
        private final List<SavedTick<Fluid>> fluidTicks = new ArrayList<>();
        private final ShortArrayList postProcessing = new ShortArrayList();

        private Delta build() {
            return new Delta(Map.copyOf(blocks), Map.copyOf(blockEntities), List.copyOf(entities), List.copyOf(blockTicks), List.copyOf(fluidTicks),
                    postProcessing.toShortArray());
        }
    }
}
