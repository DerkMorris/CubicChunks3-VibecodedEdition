package io.github.opencubicchunks.cubicchunks.world.level.levelgen;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;

/** The native seed layout for one dimension. Each terrain region gets its own mutable piece instances. */
public final class NativeStructureLayout {
    private static final int CACHED_ORIGINS = 2048;
    private final ServerLevel level;
    private final LoadingCache<ChunkPos, List<Start>> origins;

    NativeStructureLayout(ServerLevel level) {
        this.level = level;
        origins = CacheBuilder.newBuilder().maximumSize(CACHED_ORIGINS).build(CacheLoader.from(this::readOrGenerate));
    }

    private List<Start> readOrGenerate(ChunkPos pos) {
        // The private lighting/decoration halo can extend beyond loaded columns. A completed column with no
        // starts (including worlds saved by older builds) is authoritative too.
        var fields = new CollectFields(new FieldSelector(StringTag.TYPE, "Status"), new FieldSelector("structures", CompoundTag.TYPE, "starts"));
        level.getChunkSource().chunkScanner().scanChunk(pos, fields).join();
        if (fields.getResult() instanceof CompoundTag root) {
            var status = root.getString("Status").map(ChunkStatus::byName);
            if (status.isPresent() && status.get().isOrAfter(ChunkStatus.STRUCTURE_STARTS)) {
                var tags = root.getCompoundOrEmpty("structures").getCompoundOrEmpty("starts");
                List<Start> starts = new ArrayList<>();
                for (String name : tags.keySet()) {
                    var tag = tags.getCompoundOrEmpty(name);
                    var start = StructureStart.loadStaticStart(StructurePieceSerializationContext.fromLevel(level), tag, level.getSeed());
                    if (start != null && start.isValid()) {
                        starts.add(new Start(start.getStructure(), start.getBoundingBox(), tag));
                    }
                }
                return List.copyOf(starts);
            }
        }
        return generate(pos);
    }

    private List<Start> generate(ChunkPos pos) {
        var source = level.getChunkSource();
        var state = source.getGeneratorState();
        if (state.possibleStructureSets().stream().noneMatch(set -> set.value().placement().isStructureChunk(state, pos.x(), pos.z()))) {
            return List.of();
        }
        var chunk = new ProtoChunk(pos, UpgradeData.EMPTY, LevelHeightAccessor.create(level.getMinY(), level.getHeight()),
                level.palettedContainerFactory(), null);
        source.getGenerator().createStructures(level.registryAccess(), state, level.structureManager(), chunk, level.getStructureTemplateManager(),
                level.dimension());
        return snapshot(chunk);
    }

    public void rememberLoaded(ChunkAccess chunk) {
        origins.put(chunk.getPos(), snapshot(chunk));
    }

    private List<Start> snapshot(ChunkAccess chunk) {
        List<Start> starts = new ArrayList<>();
        chunk.getAllStarts().forEach((type, start) -> {
            if (start.isValid()) {
                starts.add(new Start(type, start.getBoundingBox(),
                        start.createTag(StructurePieceSerializationContext.fromLevel(level), chunk.getPos())));
            }
        });
        return List.copyOf(starts);
    }

    public void installStarts(ChunkAccess chunk) {
        if (!level.structureManager().shouldGenerateStructures()) {
            return;
        }
        for (Start start : origins.getUnchecked(chunk.getPos())) {
            var copy = StructureStart.loadStaticStart(StructurePieceSerializationContext.fromLevel(level), start.tag.copy(), level.getSeed());
            chunk.setStartForStructure(start.type, Objects.requireNonNull(copy, "Failed to load generated structure start"));
        }
    }

    /** Match ChunkGenerator.createReferences using the full native neighborhood, independent of the cube ticket radius. */
    public void installReferences(ChunkAccess chunk) {
        if (!level.structureManager().shouldGenerateStructures()) {
            return;
        }
        ChunkPos pos = chunk.getPos();
        for (int x = pos.x() - ChunkStatus.MAX_STRUCTURE_DISTANCE; x <= pos.x() + ChunkStatus.MAX_STRUCTURE_DISTANCE; x++) {
            for (int z = pos.z() - ChunkStatus.MAX_STRUCTURE_DISTANCE; z <= pos.z() + ChunkStatus.MAX_STRUCTURE_DISTANCE; z++) {
                var origin = new ChunkPos(x, z);
                for (Start start : origins.getUnchecked(origin)) {
                    if (start.bounds.intersects(pos.getMinBlockX(), pos.getMinBlockZ(), pos.getMaxBlockX(), pos.getMaxBlockZ())) {
                        chunk.addReferenceForStructure(start.type, origin.pack());
                    }
                }
            }
        }
    }

    private record Start(Structure type, BoundingBox bounds, CompoundTag tag) {}
}
