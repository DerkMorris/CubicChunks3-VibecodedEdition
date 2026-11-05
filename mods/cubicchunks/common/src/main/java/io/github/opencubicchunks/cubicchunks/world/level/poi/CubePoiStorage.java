package io.github.opencubicchunks.cubicchunks.world.level.poi;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.world.level.CubicLevelReader;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess;
import io.github.opencubicchunks.cubicchunks.world.level.cube.storage.CubeSaveQueue;
import io.github.opencubicchunks.cubicchunks.world.level.cube.storage.CubeStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.PoiTypeTags;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiSection;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Owner-thread native POI sections grouped by CubePos. Reads of saved POI do not load terrain. */
public final class CubePoiStorage implements AutoCloseable {
    private static final int MAX_QUERY_CUBES = 16384;
    private static final int NO_VILLAGE = PoiManager.MAX_VILLAGE_DISTANCE + 1;
    private final Path directory;
    private final RegistryAccess registries;
    private final Thread owner = Thread.currentThread();
    private final Map<CubePos, PoiSection[]> cubes = new LinkedHashMap<>();
    private final Set<CubePos> dirty = new LinkedHashSet<>();
    private final Set<SectionPos> centers = new HashSet<>();
    private @Nullable CubeStorage storage;
    private @Nullable RuntimeException failure;
    private boolean closed;
    private final CubeSaveQueue<CubePos> saves = new CubeSaveQueue<>();
    private static final int SAVES_PER_TICK = 4;

    public CubePoiStorage(Path directory, RegistryAccess registries) {
        this.directory = directory;
        this.registries = registries;
    }

    public PoiSection section(BlockPos pos) {
        check();
        CubicBlockBounds.requireValid(pos);
        return load(CubePos.from(pos))[Coords.blockToIndex(pos)];
    }

    private PoiSection[] load(CubePos pos) {
        var present = cubes.get(pos);
        if (present != null) {
            return present;
        }
        try {
            var saved = store().read(pos).join().map(tag -> CubePoiData.load(registries, pos, tag));
            PoiSection[] sections = new PoiSection[CubicConstants.SECTION_COUNT];
            for (int index = 0; index < sections.length; index++) {
                var packed = saved.isPresent() ? saved.get().get(index) : new PoiSection.Packed(false, List.of());
                SectionPos sectionPos = sectionPos(pos, index);
                sections[index] = packed.unpack(() -> changed(sectionPos));
            }
            cubes.put(pos, sections);
            for (int index = 0; index < sections.length; index++) {
                updateCenter(sectionPos(pos, index), sections[index]);
            }
            return sections;
        } catch (RuntimeException exception) {
            throw fail(exception);
        }
    }

    public static SectionPos sectionPos(CubePos cube, int index) {
        return SectionPos.of(Coords.cubeToSection(cube.getX(), Coords.indexToX(index)), Coords.cubeToSection(cube.getY(), Coords.indexToY(index)),
                Coords.cubeToSection(cube.getZ(), Coords.indexToZ(index)));
    }

    private void changed(SectionPos pos) {
        check();
        CubePos cube = CubePos.from(pos.origin());
        dirty.add(cube);
        updateCenter(pos, cubes.get(cube)[Coords.sectionToIndex(pos.x(), pos.y(), pos.z())]);
    }

    private void updateCenter(SectionPos pos, PoiSection section) {
        if (section.getRecords(type -> type.is(PoiTypeTags.VILLAGE), PoiManager.Occupancy.IS_OCCUPIED).findAny().isPresent()) {
            centers.add(pos);
        } else {
            centers.remove(pos);
        }
    }

    /** The native 26-neighbor distance graph is exactly Chebyshev distance to its nearest occupied VILLAGE source. */
    public int sectionsToVillage(SectionPos pos) {
        check();
        int distance = NO_VILLAGE;
        for (SectionPos center : centers) {
            distance = Math.min(distance,
                    Math.max(Math.abs(pos.x() - center.x()), Math.max(Math.abs(pos.y() - center.y()), Math.abs(pos.z() - center.z()))));
        }
        return distance;
    }

    public Stream<PoiRecord> inBox(Predicate<Holder<PoiType>> filter, BlockPos center, int radius, PoiManager.Occupancy occupancy) {
        check();
        List<PoiRecord> records = new ArrayList<>();
        for (CubePos pos : queryCubes(center, radius)) {
            for (PoiSection section : load(pos)) {
                section.getRecords(filter, occupancy).filter(record -> insideBox(record.getPos(), center, radius)).forEach(records::add);
            }
        }
        return records.stream();
    }

    /** Column-only debug callers can enumerate resident sections, but cannot request an unbounded vertical disk scan. */
    public Stream<PoiRecord> inLoadedColumn(Predicate<Holder<PoiType>> filter, ChunkPos column, PoiManager.Occupancy occupancy) {
        check();
        return cubes.values().stream().flatMap(Arrays::stream).flatMap(section -> section.getRecords(filter, occupancy))
                .filter(record -> SectionPos.blockToSectionCoord(record.getPos().getX()) == column.x()
                        && SectionPos.blockToSectionCoord(record.getPos().getZ()) == column.z())
                .toList().stream();
    }

    private static boolean insideBox(BlockPos pos, BlockPos center, int radius) {
        return Math.abs((long) pos.getX() - center.getX()) <= radius && Math.abs((long) pos.getY() - center.getY()) <= radius
                && Math.abs((long) pos.getZ() - center.getZ()) <= radius;
    }

    private static List<CubePos> queryCubes(BlockPos center, int radius) {
        CubicBlockBounds.requireValid(center);
        if (radius < 0) {
            throw new IllegalArgumentException("Negative POI radius");
        }
        CubePos min = clipped(center, -(long) radius);
        CubePos max = clipped(center, radius);
        long horizontalCount = (long) (max.getX() - min.getX() + 1) * (max.getZ() - min.getZ() + 1);
        long count = horizontalCount > MAX_QUERY_CUBES ? horizontalCount : horizontalCount * (max.getY() - min.getY() + 1);
        if (count > MAX_QUERY_CUBES) {
            throw new IllegalArgumentException("POI query exceeds the cube scan budget");
        }
        List<CubePos> result = new ArrayList<>((int) count);
        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    result.add(CubePos.of(x, y, z));
                }
            }
        }
        return result;
    }

    private static CubePos clipped(BlockPos center, long offset) {
        return CubePos.from(new BlockPos(clip(center.getX() + offset), clip(center.getY() + offset), clip(center.getZ() + offset)));
    }

    private static int clip(long coordinate) {
        return (int) Math.clamp(coordinate, CubicBlockBounds.MIN, CubicBlockBounds.MAX);
    }

    public void reconcile(CubeAccess cube) {
        check();
        for (int index = 0; index < CubicConstants.SECTION_COUNT; index++) {
            reconcile(sectionPos(cube.cc_getCubePos(), index), cube.getSection(index));
        }
    }

    /** Reconcile against blocks even when saved Valid is true; retain tickets only for the same position AND type. */
    public void reconcile(SectionPos pos, LevelChunkSection blocks) {
        PoiSection previous = section(pos.origin());
        Map<BlockPos, PoiRecord.Packed> old = new HashMap<>();
        previous.pack().records().forEach(record -> old.put(record.pos(), record));
        List<PoiRecord.Packed> records = new ArrayList<>();
        if (blocks.maybeHas(PoiTypes::hasPoi)) {
            pos.blocksInside().forEach(block -> PoiTypes.forState(blocks.getBlockState(SectionPos.sectionRelative(block.getX()),
                    SectionPos.sectionRelative(block.getY()), SectionPos.sectionRelative(block.getZ()))).ifPresent(type -> {
                        var saved = old.get(block);
                        records.add(saved != null && saved.poiType().equals(type) ? saved
                                : new PoiRecord.Packed(block.immutable(), type, type.value().maxTickets()));
                    }));
        }
        if (previous.isValid() && new HashSet<>(old.values()).equals(new HashSet<>(records))) {
            return;
        }
        cubes.get(CubePos.from(pos.origin()))[Coords.sectionToIndex(pos.x(), pos.y(), pos.z())] = new PoiSection.Packed(true, records)
                .unpack(() -> changed(pos));
        changed(pos);
    }

    public void ensureLoadedAndValid(LevelReader reader, BlockPos center, int radius) {
        check();
        for (CubePos pos : queryCubes(center, radius)) {
            if (Arrays.stream(load(pos)).allMatch(PoiSection::isValid)) {
                continue;
            }
            // Only this explicit native API may load terrain. Failed/unavailable cubes remain retryable.
            CubeAccess cube = ((CubicLevelReader) reader).cc_getCube(pos.getX(), pos.getY(), pos.getZ(), ChunkStatus.EMPTY, true);
            if (cube != null && cube.cc_getCubePos().equals(pos)) {
                reconcile(cube);
            }
        }
    }

    public boolean hasWork() {
        check();
        return !dirty.isEmpty() || saves.hasWork();
    }

    public void tick(BooleanSupplier haveTime) {
        check();
        saves.poll();
        int submitted = 0;
        for (CubePos pos : new ArrayList<>(dirty)) {
            if (submitted >= SAVES_PER_TICK || !haveTime.getAsBoolean()) {
                break;
            }
            if (save(pos)) {
                submitted++;
            }
        }
    }

    public void flushAll() {
        check();
        for (CubePos pos : new ArrayList<>(dirty)) {
            flush(pos);
        }
        saves.awaitAll();
    }

    public void flush(CubePos pos) {
        check();
        saves.awaitAll();
        save(pos);
        saves.awaitAll();
    }

    public boolean save(CubePos pos) {
        check();
        if (!saves.canSubmit(pos)) {
            return false;
        }
        if (!dirty.contains(pos)) {
            return false;
        }
        try {
            var tag = CubePoiData.save(registries, pos, Arrays.stream(cubes.get(pos)).map(PoiSection::pack).toList());
            if (!saves.submit(pos, tag, snapshot -> store().write(pos, snapshot), () -> {}, () -> dirty.add(pos))) {
                return false;
            }
            dirty.remove(pos);
            return true;
        } catch (RuntimeException exception) {
            throw fail(exception);
        }
    }

    public void unload(CubePos pos) {
        flush(pos);
        forget(pos);
    }

    public boolean isSaved(CubePos pos) {
        save(pos);
        return !dirty.contains(pos) && !saves.contains(pos);
    }

    public boolean tryUnload(CubePos pos) {
        if (!isSaved(pos)) {
            return false;
        }
        forget(pos);
        return true;
    }

    private void forget(CubePos pos) {
        cubes.remove(pos);
        for (int index = 0; index < CubicConstants.SECTION_COUNT; index++) {
            centers.remove(sectionPos(pos, index));
        }
    }

    private CubeStorage store() {
        if (storage == null) {
            try {
                storage = new CubeStorage(directory);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
        return storage;
    }

    private void check() {
        if (Thread.currentThread() != owner || closed) {
            throw new IllegalStateException("Cube POI storage is closed or used off its owner thread");
        }
        if (failure != null) {
            throw new IllegalStateException("An earlier cube POI operation failed", failure);
        }
    }

    private RuntimeException fail(RuntimeException exception) {
        if (failure == null) {
            failure = exception;
        }
        return exception;
    }

    @Override public void close() throws IOException {
        if (closed) {
            return;
        }
        if (Thread.currentThread() != owner) {
            throw new IOException("Cannot close cube POI storage off its owner thread");
        }
        try (CubeStorage closeStorage = storage) {
            try {
                flushAll();
            } catch (RuntimeException exception) {
                throw new IOException("Cannot save cube POI", exception);
            } finally {
                closed = true;
            }
        }
    }
}
