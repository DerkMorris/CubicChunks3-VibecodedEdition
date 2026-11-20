package io.github.opencubicchunks.cubicchunks.test.world.level.cube.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.world.level.cube.storage.CubeStorage;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real sparse files and aggregate budgets for the portal POI area query. */
class TestCubeHorizontalStorage extends BaseTest {
    @TempDir
    Path directory;

    @Test
    void inclusiveHorizontalBoundsPreserveAllSignedHeightsAcrossRegionSeams() throws Exception {
        var low = CubePos.of(-33, -CubePos.MAX_COORDINATE_VALUE - 1, 31);
        var middle = CubePos.of(-32, -1000, 32);
        var high = CubePos.of(-31, CubePos.MAX_COORDINATE_VALUE, 33);
        Set<CubePos> expected = Set.of(low, middle, high);
        try (var storage = new CubeStorage(directory)) {
            for (CubePos pos : expected) {
                storage.write(pos, tag(pos.toString()));
            }
            storage.write(CubePos.of(-34, 0, 32), tag("west"));
            storage.write(CubePos.of(-30, 0, 32), tag("east"));
            storage.write(CubePos.of(-32, 0, 30), tag("north"));
            storage.write(CubePos.of(-32, 0, 34), tag("south"));
            Map<CubePos, CompoundTag> area = storage.readHorizontalArea(-33, -31, 31, 33).join();
            assertEquals(expected, area.keySet());
            assertEquals(tag(low.toString()), area.get(low));
            assertEquals(tag(high.toString()), area.get(high));
        }
        try (var reopened = new CubeStorage(directory)) {
            assertEquals(expected, reopened.readHorizontalArea(-33, -31, 31, 33).join().keySet());
        }
    }

    @Test
    void oneQueuedSnapshotObservesPriorWritesAndDeletesWithoutSharingMutableTags() throws Exception {
        CubePos a = CubePos.of(0, -1000000, 0);
        CubePos b = CubePos.of(1, 1000000, 1);
        try (var storage = new CubeStorage(directory)) {
            CompoundTag initial = tag("initial");
            storage.write(a, initial);
            initial.putString("value", "caller mutation");
            storage.write(b, tag("keep"));
            var first = storage.readHorizontalArea(0, 1, 0, 1);
            storage.write(a, (CompoundTag) null);
            storage.write(b, tag("replacement"));
            var second = storage.readHorizontalArea(0, 1, 0, 1);
            assertEquals(Map.of(a, tag("initial"), b, tag("keep")), first.join());
            assertEquals(Map.of(b, tag("replacement")), second.join());
            assertThrows(UnsupportedOperationException.class, () -> first.join().put(a, tag("forbidden map mutation")));
            first.join().get(b).putString("value", "result mutation");
            assertEquals(tag("replacement"), storage.readHorizontalArea(0, 1, 0, 1).join().get(b));
            storage.flush().join();
        }
        try (var reopened = new CubeStorage(directory)) {
            assertEquals(Map.of(b, tag("replacement")), reopened.readHorizontalArea(0, 1, 0, 1).join());
        }
    }

    @Test
    void fileBudgetIsSharedByDifferentHorizontalColumns() throws Exception {
        try (var storage = limited(2, 1024 * 1024, 100)) {
            storage.write(CubePos.of(0, 0, 0), tag("first column"));
            storage.write(CubePos.of(1, 1000, 0), tag("second column lower"));
            storage.write(CubePos.of(1, 1001, 0), tag("second column upper"));
            assertEquals(2, storage.readHorizontalArea(1, 1, 0, 0).join().size());
            CompletionException failure = assertThrows(CompletionException.class, () -> storage.readHorizontalArea(0, 1, 0, 0).join());
            assertInstanceOf(IOException.class, failure.getCause());
            assertEquals(1, storage.readHorizontalArea(0, 0, 0, 0).join().size(), "An over-budget read must not poison future reads");
        }
    }

    @Test
    void decodedByteBudgetIsSharedAcrossColumnsEvenWhenFilesCompressWell() throws Exception {
        try (var storage = limited(10, 2048, 100)) {
            CompoundTag data = new CompoundTag();
            data.putByteArray("compressible", new byte[1536]);
            storage.write(CubePos.of(0, 0, 0), data);
            storage.write(CubePos.of(1, 0, 1), data);
            assertEquals(1, storage.readHorizontalArea(0, 0, 0, 0).join().size());
            assertThrows(CompletionException.class, () -> storage.readHorizontalArea(0, 1, 0, 1).join());
        }
    }

    @Test
    void directoryBudgetIsAggregateAcrossRegionsAndFailsWithoutPartialResults() throws Exception {
        try (var storage = limited(10, 1024 * 1024, 4)) {
            storage.write(CubePos.of(0, 0, 0), tag("west"));
            storage.write(CubePos.of(1, 32, 0), tag("east"));
            // One lock + two regions + two files = five entries for the one combined query.
            CompletionException failure = assertThrows(CompletionException.class, () -> storage.readHorizontalArea(0, 1, 0, 0).join());
            assertInstanceOf(IOException.class, failure.getCause());
        }
    }

    @Test
    void corruptRequestedFileFailsButCorruptOutsideAreaIsNotDecoded() throws Exception {
        CubePos good = CubePos.of(0, -1000, 0);
        CubePos corrupt = CubePos.of(1, 1000, 0);
        try (var storage = new CubeStorage(directory)) {
            storage.write(good, tag("good"));
            storage.write(corrupt, tag("corrupt"));
        }
        Files.write(file(corrupt), new byte[] { 1, 2, 3 });
        try (var storage = new CubeStorage(directory)) {
            assertEquals(Map.of(good, tag("good")), storage.readHorizontalArea(0, 0, 0, 0).join());
            assertThrows(CompletionException.class, () -> storage.readHorizontalArea(0, 1, 0, 0).join());
            storage.flush().join();
        }
    }

    @Test
    void ignoredAtomicTemporaryFilesDoNotHideMalformedAliasedOrMisplacedEntries() throws Exception {
        CubePos pos = CubePos.ZERO;
        try (var storage = new CubeStorage(directory)) {
            storage.write(pos, tag("valid")).join();
            Path region = file(pos).getParent();
            Files.write(region.resolve("c.0.0.0.nbt.123456.tmp"), new byte[] { 1 });
            assertEquals(1, storage.readHorizontalArea(0, 1, 0, 1).join().size());
            Path malformed = region.resolve("c.0.bad.0.nbt");
            Files.write(malformed, new byte[] { 1 });
            assertThrows(CompletionException.class, () -> storage.readHorizontalArea(0, 1, 0, 1).join());
            Files.delete(malformed);
            Path alias = region.resolve("c.00.0.0.nbt");
            Files.copy(file(pos), alias);
            assertThrows(CompletionException.class, () -> storage.readHorizontalArea(0, 1, 0, 1).join());
            Files.delete(alias);
            Path wrongRegion = directory.resolve("r.0.1.0");
            Files.createDirectories(wrongRegion);
            Files.copy(file(pos), wrongRegion.resolve("c.0.0.0.nbt"));
            assertThrows(CompletionException.class, () -> storage.readHorizontalArea(0, 1, 0, 1).join());
        }
    }

    @Test
    void invalidBoundsFailWhileFullRepresentableRectangleRemainsSparse() throws Exception {
        try (var storage = new CubeStorage(directory)) {
            for (int[] bounds : new int[][] { { 1, 0, 0, 0 }, { 0, 0, 1, 0 }, { Integer.MIN_VALUE, 0, 0, 0 }, { 0, Integer.MAX_VALUE, 0, 0 },
                { 0, 0, Integer.MIN_VALUE, 0 }, { 0, 0, 0, Integer.MAX_VALUE } }) {
                CompletionException failure = assertThrows(CompletionException.class,
                        () -> storage.readHorizontalArea(bounds[0], bounds[1], bounds[2], bounds[3]).join());
                assertInstanceOf(IllegalArgumentException.class, failure.getCause());
            }
            int min = -CubePos.MAX_COORDINATE_VALUE - 1;
            int max = CubePos.MAX_COORDINATE_VALUE;
            assertTrue(storage.readHorizontalArea(min, max, min, max).join().isEmpty());
            assertTrue(storage.readHorizontalArea(min, min, max, max).join().isEmpty());
            CubePos edge = CubePos.of(min, max, max);
            storage.write(edge, tag("edge"));
            assertEquals(Map.of(edge, tag("edge")), storage.readHorizontalArea(min, min, max, max).join());
            assertEquals(Map.of(edge, tag("edge")), storage.readHorizontalArea(min, max, min, max).join());
        }
    }

    private static CompoundTag tag(String value) {
        CompoundTag tag = new CompoundTag();
        tag.putString("value", value);
        return tag;
    }

    private Path file(CubePos pos) {
        return directory.resolve("r." + Math.floorDiv(pos.getX(), 32) + "." + Math.floorDiv(pos.getY(), 32) + "." + Math.floorDiv(pos.getZ(), 32))
                .resolve("c." + pos.getX() + "." + pos.getY() + "." + pos.getZ() + ".nbt");
    }

    private CubeStorage limited(int files, long bytes, int entries) throws Exception {
        var constructor = CubeStorage.class.getDeclaredConstructor(Path.class, int.class, long.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(directory, files, bytes, entries);
    }
}
