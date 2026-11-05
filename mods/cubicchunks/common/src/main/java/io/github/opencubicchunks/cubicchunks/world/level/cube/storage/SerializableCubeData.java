package io.github.opencubicchunks.cubicchunks.world.level.cube.storage;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.mojang.serialization.Codec;
import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess;
import io.github.opencubicchunks.cubicchunks.world.level.cube.ImposterProtoCube;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.cube.ProtoCube;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.heightmap.CubeHeightmap;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeSkyOcclusion;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.ProtoChunkTicks;
import net.minecraft.world.ticks.SavedTick;
import net.minecraft.world.ticks.TickPriority;

/**
 * An immutable, eagerly encoded cube snapshot. Call copyOf on the owning game thread, then write on an I/O worker.
 * This format is deliberately distinct from vanilla chunk NBT and must never be passed through vanilla chunk data fixers.
 */
public final class SerializableCubeData {
    public static final int FORMAT_VERSION = 2;
    private static final int LEGACY_FORMAT_VERSION = 1;
    private static final int MAX_PACKED_OFFSET = 4095;
    private static final String FORMAT_ID = "cubicchunks:cube";
    private static final Set<String> ROOT_FIELDS = Set.of("Format", "CubeFormat", "DataVersion", "CubeDiameter", "xPos", "yPos", "zPos", "Status",
            "LastUpdate", "InhabitedTime", "sections", "block_entities", "entities", "block_ticks", "fluid_ticks", "PostProcessing", "structures",
            "isLightOn", "CubeHeightmaps");
    private static final Codec<List<SavedTick<Block>>> BLOCK_TICKS = SavedTick.codec(BuiltInRegistries.BLOCK.byNameCodec()).listOf();
    private static final Codec<List<SavedTick<Fluid>>> FLUID_TICKS = SavedTick.codec(BuiltInRegistries.FLUID.byNameCodec()).listOf();
    private final CompoundTag data;
    private final CubePos pos;
    private final ChunkStatus status;

    private SerializableCubeData(CompoundTag data, CubePos pos, ChunkStatus status) {
        this.data = data.copy();
        this.pos = pos;
        this.status = status;
    }

    public CubePos cubePos() {
        return pos;
    }

    public ChunkStatus chunkStatus() {
        return status;
    }

    /** Returns a detached tag so callers cannot mutate this snapshot or another in-flight save. */
    public CompoundTag write() {
        return data.copy();
    }

    /** Detached local maps for surface aggregation; legacy files rebuild from sections without loading world objects. */
    public Map<Heightmap.Types, long[]> surfaceHeightmaps(PalettedContainerFactory palettes) {
        if (intValue(data, "CubeFormat") != LEGACY_FORMAT_VERSION) {
            return readHeightmaps(data);
        }
        return sectionSnapshot(palettes).cc_snapshotHeightmaps();
    }

    /** Rebuild from authoritative sections in either supported format, without attaching world objects. */
    public CubeSkyOcclusion skyOcclusion(PalettedContainerFactory palettes) {
        return sectionSnapshot(palettes).cc_snapshotSkyOcclusion();
    }

    public io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightBlockSnapshot lightBlocks(PalettedContainerFactory palettes) {
        return io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightBlockSnapshot.capture(sectionSnapshot(palettes));
    }

    private ProtoCube sectionSnapshot(PalettedContainerFactory palettes) {
        return new ProtoCube(pos, UpgradeData.EMPTY, readSections(palettes), new ProtoChunkTicks<>(), new ProtoChunkTicks<>(),
                LevelHeightAccessor.create(pos.minCubeY(), CubicConstants.DIAMETER_IN_BLOCKS), palettes, null);
    }

    public static SerializableCubeData parse(CompoundTag tag, CubePos expectedPos) {
        validateFormat(tag);
        require(intValue(tag, "DataVersion") == SharedConstants.getCurrentVersion().dataVersion().version(),
                "Cube Minecraft DataVersion differs from this game; no cube data fixer is installed");
        require(intValue(tag, "CubeDiameter") == CubicConstants.DIAMETER_IN_SECTIONS, "Cube size differs from this configuration");
        CubePos pos = CubePos.of(intValue(tag, "xPos"), intValue(tag, "yPos"), intValue(tag, "zPos"));
        validateCoordinates(pos);
        require(pos.equals(expectedPos), "Cube coordinates do not match requested position " + expectedPos + ": " + pos);
        ChunkStatus status = decode(ChunkStatus.CODEC, required(tag, "Status"), "Status");
        require(required(tag, "isLightOn") instanceof ByteTag, "Expected byte isLightOn");
        require(!tag.getBoolean("isLightOn").orElseThrow(() -> invalid("Missing isLightOn")), "Saved cube lighting is not supported yet");
        require(longValue(tag, "InhabitedTime") >= 0, "Negative inhabited time");
        longValue(tag, "LastUpdate");
        ListTag sections = list(tag, "sections");
        require(sections.size() == CubicConstants.SECTION_COUNT, "Wrong cube section count");
        boolean[] seen = new boolean[CubicConstants.SECTION_COUNT];
        for (Tag value : sections) {
            CompoundTag section = compound(value, "section");
            rejectUnknownFields(section, Set.of("i", "block_states", "biomes"), "section");
            int index = intValue(section, "i");
            require(index >= 0 && index < seen.length && !seen[index], "Invalid or duplicate cube section index " + index);
            seen[index] = true;
            compound(required(section, "block_states"), "block_states");
            compound(required(section, "biomes"), "biomes");
        }
        require(list(tag, "PostProcessing").size() == CubicConstants.SECTION_COUNT, "Wrong post-processing section count");
        list(tag, "block_entities");
        ListTag entities = list(tag, "entities");
        require(status.getChunkType() != ChunkType.LEVELCHUNK || entities.isEmpty(), "FULL cube entity storage is managed separately");
        compound(required(tag, "structures"), "structures");
        validateTickTags(list(tag, "block_ticks"));
        validateTickTags(list(tag, "fluid_ticks"));
        return new SerializableCubeData(tag, pos, status);
    }

    public static SerializableCubeData copyOf(ServerLevel level, CubeAccess input) {
        CubeAccess cube = input instanceof ImposterProtoCube imposter ? (LevelCube) imposter.cc_getWrappedClo() : input;
        require(cube.canBeSerialized(), "Cube cannot be serialized");
        require(!cube.isLightCorrect(), "Saving initialized cube lighting is not implemented");
        require(cube.getUpgradeData().isEmpty() && cube.getBlendingData() == null && cube.getBelowZeroRetrogen() == null,
                "Cube upgrade, blending, and retrogen data are not supported by this format");
        CubePos pos = cube.cc_getCubePos();
        validateCoordinates(pos);
        CompoundTag tag = NbtUtils.addCurrentDataVersion(new CompoundTag());
        tag.putString("Format", FORMAT_ID);
        tag.putInt("CubeFormat", FORMAT_VERSION);
        tag.putInt("CubeDiameter", CubicConstants.DIAMETER_IN_SECTIONS);
        tag.putInt("xPos", pos.getX());
        tag.putInt("yPos", pos.getY());
        tag.putInt("zPos", pos.getZ());
        tag.put("Status", encode(ChunkStatus.CODEC, cube.getPersistedStatus(), "Status"));
        tag.putLong("LastUpdate", level.getGameTime());
        tag.putLong("InhabitedTime", cube.getInhabitedTime());
        tag.putBoolean("isLightOn", false);
        tag.put("sections", writeSections(level.palettedContainerFactory(), cube));
        tag.put("CubeHeightmaps", writeHeightmaps(cube));
        tag.put("block_entities", writeBlockEntities(level, cube));
        ListTag entities = new ListTag();
        if (cube instanceof ProtoCube proto) {
            proto.getEntities().forEach(entity -> {
                validateEntity(entity);
                entities.add(entity.copy());
            });
        }
        tag.put("entities", entities);
        ChunkAccess.PackedTicks ticks = cube.getTicksForSerialization(level.getGameTime());
        validateTicks(ticks.blocks(), pos);
        validateTicks(ticks.fluids(), pos);
        tag.put("block_ticks", encode(BLOCK_TICKS, ticks.blocks(), "block_ticks"));
        tag.put("fluid_ticks", encode(FLUID_TICKS, ticks.fluids(), "fluid_ticks"));
        tag.put("PostProcessing", writePostProcessing(cube));
        tag.put("structures", writeStructures(level, cube));
        return parse(tag, pos);
    }

    public ProtoCube read(ServerLevel level) {
        PalettedContainerFactory palettes = level.palettedContainerFactory();
        LevelChunkSection[] sections = readSections(palettes);
        List<SavedTick<Block>> blockTicks = decode(BLOCK_TICKS, required(data, "block_ticks"), "block_ticks");
        List<SavedTick<Fluid>> fluidTicks = decode(FLUID_TICKS, required(data, "fluid_ticks"), "fluid_ticks");
        validateTicks(blockTicks, pos);
        validateTicks(fluidTicks, pos);
        CubeAccess cube;
        if (status.getChunkType() == ChunkType.LEVELCHUNK) {
            cube = new LevelCube(level, pos, UpgradeData.EMPTY, new LevelChunkTicks<>(blockTicks), new LevelChunkTicks<>(fluidTicks),
                    longValue(data, "InhabitedTime"), sections, loaded -> loadBlockEntities(level, loaded), null);
        } else {
            ProtoCube proto = new ProtoCube(pos, UpgradeData.EMPTY, sections, ProtoChunkTicks.load(blockTicks), ProtoChunkTicks.load(fluidTicks),
                    level, palettes, null);
            proto.setPersistedStatus(status);
            proto.setInhabitedTime(longValue(data, "InhabitedTime"));
            if (status.isOrAfter(ChunkStatus.INITIALIZE_LIGHT)) {
                proto.setLightEngine(level.getChunkSource().getLightEngine());
            }
            for (Tag entity : list(data, "entities")) {
                CompoundTag entityTag = compound(entity, "entity");
                validateEntity(entityTag);
                proto.addEntity(entityTag.copy());
            }
            cube = proto;
        }
        // Preserve tags even if a loaded FULL cube is saved again before its post-load callback executes.
        Set<BlockPos> blockEntityPositions = new HashSet<>();
        for (Tag value : list(data, "block_entities")) {
            CompoundTag blockEntity = compound(value, "block entity");
            require(blockEntityPositions.add(validateBlockEntity(blockEntity, pos)), "Duplicate block entity position");
            cube.setBlockEntityNbt(blockEntity.copy());
        }
        readPostProcessing(cube);
        readStructures(level, cube);
        if (intValue(data, "CubeFormat") == LEGACY_FORMAT_VERSION) {
            // Format 1 had no heightmaps. They are derived entirely from its persisted sections.
            cube.cc_snapshotHeightmaps();
        } else {
            cube.cc_restoreHeightmaps(readHeightmaps(data));
        }
        cube.setLightCorrect(false);
        cube.tryMarkSaved();
        return cube instanceof LevelCube full ? new ImposterProtoCube(full, false) : (ProtoCube) cube;
    }

    private static void validateFormat(CompoundTag tag) {
        require(FORMAT_ID.equals(tag.getString("Format").orElse(null)), "Not a Cubic Chunks cube file");
        int version = intValue(tag, "CubeFormat");
        require(version == FORMAT_VERSION || version == LEGACY_FORMAT_VERSION, "Unsupported cube format version");
        rejectUnknownFields(tag, ROOT_FIELDS, "cube");
        if (version == LEGACY_FORMAT_VERSION) {
            require(!tag.contains("CubeHeightmaps"), "Cube format 1 cannot contain heightmaps");
        } else {
            readHeightmaps(tag);
        }
    }

    private static CompoundTag writeHeightmaps(CubeAccess cube) {
        Map<Heightmap.Types, long[]> maps = cube.cc_snapshotHeightmaps();
        require(maps.size() == Heightmap.Types.values().length, "Wrong cube heightmap count");
        CompoundTag tag = new CompoundTag();
        for (Heightmap.Types type : Heightmap.Types.values()) {
            long[] packed = maps.get(type);
            require(packed != null, "Missing cube heightmap " + type.getSerializationKey());
            CubeHeightmap.validateRawData(packed);
            tag.putLongArray(type.getSerializationKey(), packed);
        }
        return tag;
    }

    private static Map<Heightmap.Types, long[]> readHeightmaps(CompoundTag data) {
        CompoundTag tag = compound(required(data, "CubeHeightmaps"), "CubeHeightmaps");
        require(tag.size() == Heightmap.Types.values().length, "Wrong cube heightmap count");
        Map<Heightmap.Types, long[]> maps = new EnumMap<>(Heightmap.Types.class);
        for (Heightmap.Types type : Heightmap.Types.values()) {
            String name = type.getSerializationKey();
            long[] packed = tag.getLongArray(name).orElseThrow(() -> invalid("Missing long array cube heightmap " + name));
            CubeHeightmap.validateRawData(packed);
            maps.put(type, packed.clone());
        }
        return maps;
    }

    private LevelChunkSection[] readSections(PalettedContainerFactory palettes) {
        LevelChunkSection[] sections = new LevelChunkSection[CubicConstants.SECTION_COUNT];
        for (Tag value : list(data, "sections")) {
            CompoundTag section = compound(value, "section");
            sections[intValue(section, "i")] = new LevelChunkSection(
                    decode(palettes.blockStatesContainerCodec(), required(section, "block_states"), "block_states"),
                    decode(palettes.biomeContainerCodec(), required(section, "biomes"), "biomes"));
        }
        return sections;
    }

    private static ListTag writeSections(PalettedContainerFactory palettes, CubeAccess cube) {
        LevelChunkSection[] sections = cube.getSections();
        require(sections.length == CubicConstants.SECTION_COUNT, "Wrong cube section count");
        ListTag tags = new ListTag();
        for (int i = 0; i < sections.length; i++) {
            CompoundTag section = new CompoundTag();
            section.putInt("i", i);
            section.put("block_states", encode(palettes.blockStatesContainerCodec(), sections[i].getStates(), "block_states"));
            section.put("biomes", encode(palettes.biomeContainerCodec(), sections[i].getBiomes(), "biomes"));
            tags.add(section);
        }
        return tags;
    }

    private static ListTag writeBlockEntities(ServerLevel level, CubeAccess cube) {
        ListTag tags = new ListTag();
        for (BlockPos pos : cube.getBlockEntitiesPos()) {
            CompoundTag tag = cube.getBlockEntityNbtForSaving(pos, level.registryAccess());
            require(tag != null, "Block entity at " + pos + " cannot be serialized");
            validateBlockEntity(tag, cube.cc_getCubePos());
            tags.add(tag.copy());
        }
        return tags;
    }

    private void loadBlockEntities(ServerLevel level, LevelCube cube) {
        for (Tag value : list(data, "block_entities")) {
            CompoundTag tag = compound(value, "block entity");
            if (!tag.getBooleanOr("keepPacked", false)) {
                BlockPos blockPos = validateBlockEntity(tag, pos);
                BlockEntity entity = BlockEntity.loadStatic(blockPos, cube.getBlockState(blockPos), tag.copy(), level.registryAccess());
                require(entity != null, "Cannot instantiate saved block entity at " + blockPos);
                cube.setBlockEntity(entity);
            }
        }
    }

    private static ListTag writePostProcessing(CubeAccess cube) {
        ListTag sections = new ListTag();
        for (var offsets : cube.getPostProcessing()) {
            ListTag list = new ListTag();
            if (offsets != null) {
                for (short offset : offsets) {
                    require(offset >= 0 && offset <= MAX_PACKED_OFFSET, "Invalid post-processing offset");
                    list.add(ShortTag.valueOf(offset));
                }
            }
            sections.add(list);
        }
        return sections;
    }

    private void readPostProcessing(CubeAccess cube) {
        ListTag sections = list(data, "PostProcessing");
        for (int i = 0; i < sections.size(); i++) {
            require(sections.get(i) instanceof ListTag, "Invalid post-processing section");
            ShortArrayList offsets = new ShortArrayList();
            for (Tag value : (ListTag) sections.get(i)) {
                require(value instanceof ShortTag, "Expected short post-processing offset");
                short offset = value.asShort().orElseThrow(() -> invalid("Invalid post-processing offset"));
                require(offset >= 0 && offset <= MAX_PACKED_OFFSET, "Invalid post-processing offset");
                offsets.add(offset);
            }
            if (!offsets.isEmpty()) {
                cube.addPackedPostProcess(offsets, i);
            }
        }
    }

    private static CompoundTag writeStructures(ServerLevel level, CubeAccess cube) {
        var context = StructurePieceSerializationContext.fromLevel(level);
        Registry<Structure> registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        CompoundTag starts = new CompoundTag();
        cube.getAllStarts().forEach((structure, start) -> {
            Identifier key = registry.getKey(structure);
            require(key != null, "Unregistered structure start");
            starts.put(key.toString(), start.createTag(context, start.getChunkPos()));
        });
        CompoundTag references = new CompoundTag();
        cube.getAllReferences().forEach((structure, values) -> {
            Identifier key = registry.getKey(structure);
            require(key != null, "Unregistered structure reference");
            references.putLongArray(key.toString(), values.toLongArray());
        });
        CompoundTag structures = new CompoundTag();
        structures.put("starts", starts);
        structures.put("References", references);
        return structures;
    }

    private void readStructures(ServerLevel level, CubeAccess cube) {
        CompoundTag structures = compound(required(data, "structures"), "structures");
        rejectUnknownFields(structures, Set.of("starts", "References"), "structures");
        Registry<Structure> registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        CompoundTag starts = compound(required(structures, "starts"), "structure starts");
        Map<Structure, StructureStart> decodedStarts = new HashMap<>();
        var context = StructurePieceSerializationContext.fromLevel(level);
        for (String name : starts.keySet()) {
            Structure structure = registry.getValue(Identifier.parse(name));
            require(structure != null, "Unknown structure " + name);
            CompoundTag tag = compound(required(starts, name), "structure start");
            StructureStart start = StructureStart.loadStaticStart(context, tag, level.getSeed());
            require(start != null, "Cannot decode structure " + name);
            if (start != StructureStart.INVALID_START) {
                require(start.getStructure() == structure, "Structure type mismatch");
                require(start.getPieces().size() == list(tag, "Children").size(), "Structure pieces were dropped while decoding " + name);
            }
            decodedStarts.put(structure, start);
        }
        Map<Structure, LongSet> decodedReferences = new HashMap<>();
        CompoundTag references = compound(required(structures, "References"), "structure references");
        for (String name : references.keySet()) {
            Structure structure = registry.getValue(Identifier.parse(name));
            require(structure != null, "Unknown referenced structure " + name);
            long[] positions = references.getLongArray(name).orElseThrow(() -> invalid("Invalid references for " + name));
            LongSet uniquePositions = new LongOpenHashSet(positions);
            require(uniquePositions.size() == positions.length, "Duplicate structure references");
            decodedReferences.put(structure, uniquePositions);
        }
        cube.setAllStarts(decodedStarts);
        cube.setAllReferences(decodedReferences);
    }

    private static BlockPos validateBlockEntity(CompoundTag tag, CubePos pos) {
        if (!"DUMMY".equals(tag.getString("id").orElse(null))) {
            validateId(tag, BuiltInRegistries.BLOCK_ENTITY_TYPE, "block entity");
        }
        BlockPos blockPos = new BlockPos(intValue(tag, "x"), intValue(tag, "y"), intValue(tag, "z"));
        require(inCube(blockPos, pos), "Block entity is outside cube " + pos);
        return blockPos;
    }

    private static void validateEntity(CompoundTag tag) {
        validateId(tag, BuiltInRegistries.ENTITY_TYPE, "entity");
        if (tag.contains("Passengers")) {
            for (Tag passenger : list(tag, "Passengers")) {
                validateEntity(compound(passenger, "passenger"));
            }
        }
    }

    private static void validateId(CompoundTag tag, Registry<?> registry, String description) {
        String name = tag.getString("id").orElseThrow(() -> invalid("Missing " + description + " id"));
        Identifier id = Identifier.tryParse(name);
        require(id != null && registry.containsKey(id), "Unknown " + description + " id " + name);
    }

    private static void validateTicks(List<? extends SavedTick<?>> ticks, CubePos pos) {
        Set<TickKey> seen = new HashSet<>();
        for (SavedTick<?> tick : ticks) {
            require(inCube(tick.pos(), pos), "Scheduled tick is outside cube " + pos);
            require(seen.add(new TickKey(tick.type(), tick.pos())), "Duplicate scheduled tick");
        }
    }

    private static void validateTickTags(ListTag ticks) {
        for (Tag value : ticks) {
            CompoundTag tick = compound(value, "tick");
            rejectUnknownFields(tick, Set.of("i", "x", "y", "z", "t", "p"), "tick");
            for (String key : List.of("x", "y", "z", "t", "p")) {
                intValue(tick, key);
            }
            int priority = intValue(tick, "p");
            require(priority >= TickPriority.EXTREMELY_HIGH.getValue() && priority <= TickPriority.EXTREMELY_LOW.getValue(), "Invalid tick priority");
        }
    }

    private static boolean inCube(BlockPos blockPos, CubePos pos) {
        return Coords.blockToCube(blockPos.getX()) == pos.getX() && Coords.blockToCube(blockPos.getY()) == pos.getY()
                && Coords.blockToCube(blockPos.getZ()) == pos.getZ();
    }

    private static void validateCoordinates(CubePos pos) {
        require(CubePos.from(pos.asLong()).equals(pos), "Cube coordinates exceed the packed position range: " + pos);
    }

    private static <T> Tag encode(Codec<T> codec, T value, String name) {
        return codec.encodeStart(NbtOps.INSTANCE, value).getOrThrow(message -> invalid(name + ": " + message));
    }

    private static <T> T decode(Codec<T> codec, Tag value, String name) {
        return codec.parse(NbtOps.INSTANCE, value).getOrThrow(message -> invalid(name + ": " + message));
    }

    private static Tag required(CompoundTag tag, String key) {
        Tag value = tag.get(key);
        require(value != null, "Missing " + key);
        return value;
    }

    private static int intValue(CompoundTag tag, String key) {
        require(required(tag, key) instanceof IntTag, "Expected integer " + key);
        return tag.getInt(key).orElseThrow(() -> invalid("Missing integer " + key));
    }

    private static long longValue(CompoundTag tag, String key) {
        require(required(tag, key) instanceof LongTag, "Expected long " + key);
        return tag.getLong(key).orElseThrow(() -> invalid("Missing long " + key));
    }

    private static CompoundTag compound(Tag tag, String description) {
        require(tag instanceof CompoundTag, "Expected compound " + description);
        return (CompoundTag) tag;
    }

    private static ListTag list(CompoundTag tag, String key) {
        return tag.getList(key).orElseThrow(() -> invalid("Missing list " + key));
    }

    private static void rejectUnknownFields(CompoundTag tag, Set<String> expected, String description) {
        for (String key : tag.keySet()) {
            require(expected.contains(key), "Unknown " + description + " field " + key);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw invalid(message);
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Invalid cube data: " + message);
    }

    private record TickKey(Object type, BlockPos pos) {}
}
