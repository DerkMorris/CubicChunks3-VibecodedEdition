package io.github.opencubicchunks.cubicchunks.test.world.level.cube.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.world.level.cube.storage.CubeStorage;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestCubeStorage extends BaseTest {
    private static CompoundTag payload(String value) {
        CompoundTag result = new CompoundTag();
        result.putString("value", value);
        CompoundTag extra = new CompoundTag();
        extra.putLongArray("unknown_data", new long[] { Long.MIN_VALUE, 0, Long.MAX_VALUE });
        result.put("extension", extra);
        return result;
    }

    @Test
    void bufferedCompressionPreservesLargeIncompressibleNbtAndNativeFileCompatibility(@TempDir Path directory) throws IOException {
        byte[] noise = new byte[2 * 1024 * 1024];
        new java.util.Random(12345).nextBytes(noise);
        CompoundTag data = payload("large replacement");
        data.putByteArray("noise", noise);
        CubePos pos = CubePos.of(-33, 1000000, 7);
        try (CubeStorage storage = new CubeStorage(directory)) {
            storage.write(pos, payload("old")).join();
            storage.write(pos, data).join();
            assertEquals(data, NbtIo.readCompressed(cubeFile(directory, pos), net.minecraft.nbt.NbtAccounter.unlimitedHeap()));
        }
        try (CubeStorage reopened = new CubeStorage(directory)) {
            assertEquals(data, reopened.read(pos).join().orElseThrow());
        }
    }

    @Test
    void failedAtomicReplacementCleansTemporaryFileAndReportsFailure(@TempDir Path directory) throws IOException {
        Path target = cubeFile(directory, CubePos.ZERO);
        Files.createDirectories(target);
        Path protectedFile = target.resolve("keep");
        Files.writeString(protectedFile, "existing directory");
        CubeStorage storage = new CubeStorage(directory);
        assertThrows(CompletionException.class, () -> storage.write(CubePos.ZERO, payload("cannot replace directory")).join());
        assertEquals("existing directory", Files.readString(protectedFile));
        assertThrows(CompletionException.class, () -> storage.flush().join());
        assertThrows(IOException.class, storage::close);
        try (var files = Files.walk(directory)) {
            assertFalse(files.anyMatch(path -> path.toString().endsWith(".tmp")));
        }
    }

    @Test
    void closeAndReopenPreservesIndependentCoordinates(@TempDir Path directory) throws IOException {
        CubePos negative = CubePos.of(-33, -64, 7);
        CubePos above = CubePos.of(-33, -63, 7);
        CubePos distant = CubePos.of(1_000_000, -1_000_000, -1_000_000);
        try (CubeStorage storage = new CubeStorage(directory)) {
            assertTrue(storage.read(negative).join().isEmpty());
            storage.write(negative, payload("below"));
            storage.write(above, payload("above"));
            storage.write(distant, payload("distant"));
        }
        try (CubeStorage reopened = new CubeStorage(directory)) {
            assertEquals(payload("below"), reopened.read(negative).join().orElseThrow());
            assertEquals(payload("above"), reopened.read(above).join().orElseThrow());
            assertEquals(payload("distant"), reopened.read(distant).join().orElseThrow());
        }
    }

    @Test
    void operationsAreOrderedAndWritesCaptureCallerData(@TempDir Path directory) throws IOException {
        CubePos pos = CubePos.of(2, 3, 4);
        try (CubeStorage storage = new CubeStorage(directory)) {
            CompoundTag first = payload("first");
            storage.write(pos, first);
            first.putString("value", "mutated after submission");
            var firstRead = storage.read(pos);
            storage.write(pos, payload("second"));
            var secondRead = storage.read(pos);
            storage.flush().join();
            assertEquals(payload("first"), firstRead.join().orElseThrow());
            assertEquals(payload("second"), secondRead.join().orElseThrow());
            firstRead.join().orElseThrow().putString("value", "mutated read result");
            assertEquals(payload("second"), storage.read(pos).join().orElseThrow());
        }
    }

    @Test
    void overwriteAndDeleteSurviveReopening(@TempDir Path directory) throws IOException {
        CubePos kept = CubePos.of(0, 0, 0);
        CubePos deleted = CubePos.of(0, 1, 0);
        try (CubeStorage storage = new CubeStorage(directory)) {
            storage.write(kept, payload("old")).join();
            storage.write(kept, payload("new")).join();
            storage.write(deleted, payload("deleted")).join();
            storage.write(deleted, (CompoundTag) null).join();
        }
        try (CubeStorage reopened = new CubeStorage(directory)) {
            assertEquals(payload("new"), reopened.read(kept).join().orElseThrow());
            assertTrue(reopened.read(deleted).join().isEmpty());
        }
        try (var files = Files.walk(directory)) {
            assertFalse(files.anyMatch(path -> path.toString().endsWith(".tmp")));
        }
    }

    @Test
    void corruptedDataFailsInsteadOfBecomingAnEmptyCube(@TempDir Path directory) throws IOException {
        CubePos pos = CubePos.of(-1, -1, -1);
        try (CubeStorage storage = new CubeStorage(directory)) {
            storage.write(pos, payload("valid")).join();
        }
        Path saved;
        try (var files = Files.walk(directory)) {
            saved = files.filter(path -> path.toString().endsWith(".nbt")).findFirst().orElseThrow();
        }
        Files.write(saved, new byte[] { 1, 2, 3, 4 });
        try (CubeStorage reopened = new CubeStorage(directory)) {
            CompletionException failure = assertThrows(CompletionException.class, () -> reopened.read(pos).join());
            assertInstanceOf(IOException.class, failure.getCause());
            assertEquals(4, Files.size(saved));
        }
    }

    @Test
    void failedWritePreservesOldDataAndFailsFlushAndClose(@TempDir Path directory) throws IOException {
        CubePos pos = CubePos.of(0, 0, 0);
        CubeStorage storage = new CubeStorage(directory);
        storage.write(pos, payload("committed")).join();
        CompoundTag oversized = new CompoundTag();
        oversized.putByteArray("too_large", new byte[64 * 1024 * 1024]);
        CompletionException writeFailure = assertThrows(CompletionException.class, () -> storage.write(pos, oversized).join());
        assertInstanceOf(IOException.class, writeFailure.getCause());
        assertEquals(payload("committed"), storage.read(pos).join().orElseThrow());
        assertThrows(CompletionException.class, () -> storage.flush().join());
        assertThrows(IOException.class, storage::close);
        // Even a failed close releases the directory lock once its accepted operations have drained.
        try (CubeStorage reopened = new CubeStorage(directory)) {
            assertEquals(payload("committed"), reopened.read(pos).join().orElseThrow());
        }
    }

    @Test
    void closeDrainsEveryAcceptedWriteAndRejectsFurtherWork(@TempDir Path directory) throws IOException {
        CubeStorage storage = new CubeStorage(directory);
        List<CompletableFuture<Void>> writes = new ArrayList<>();
        for (int y = -16; y < 16; y++) {
            writes.add(storage.write(CubePos.of(5, y, 9), payload("height " + y)));
        }
        storage.close();
        storage.close();
        assertTrue(writes.stream().allMatch(future -> future.isDone() && !future.isCompletedExceptionally()));
        assertThrows(CompletionException.class, () -> storage.read(CubePos.ZERO).join());
        assertThrows(CompletionException.class, () -> storage.write(CubePos.ZERO, payload("closed")).join());
        assertThrows(CompletionException.class, () -> storage.flush().join());
        try (CubeStorage reopened = new CubeStorage(directory)) {
            for (int y = -16; y < 16; y++) {
                assertEquals(payload("height " + y), reopened.read(CubePos.of(5, y, 9)).join().orElseThrow());
            }
        }
    }

    @Test
    void aSecondWriterCannotOpenTheSameDirectory(@TempDir Path directory) throws IOException {
        try (CubeStorage first = new CubeStorage(directory)) {
            assertThrows(IOException.class, () -> new CubeStorage(directory));
            first.write(CubePos.ZERO, payload("first")).join();
        }
        try (CubeStorage reopened = new CubeStorage(directory)) {
            assertEquals(payload("first"), reopened.read(CubePos.ZERO).join().orElseThrow());
        }
    }

    @Test
    void suppliersRunOnTheCallerAndTheirFailuresRemainVisible(@TempDir Path directory) throws IOException {
        CubeStorage storage = new CubeStorage(directory);
        Thread caller = Thread.currentThread();
        AtomicReference<Thread> supplierThread = new AtomicReference<>();
        storage.write(CubePos.ZERO, () -> {
            supplierThread.set(Thread.currentThread());
            return payload("supplier");
        }).join();
        assertEquals(caller, supplierThread.get());
        CompletionException failure = assertThrows(CompletionException.class, () -> storage.write(CubePos.ZERO, () -> {
            throw new IllegalStateException("snapshot failed");
        }).join());
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertEquals(payload("supplier"), storage.read(CubePos.ZERO).join().orElseThrow());
        assertThrows(CompletionException.class, () -> storage.flush().join());
        assertThrows(IOException.class, storage::close);
    }

    @Test
    void invalidRegionFilesystemFailsWithoutClaimingAnEmptyCube(@TempDir Path directory) throws IOException {
        CubeStorage storage = new CubeStorage(directory);
        Path obstructedRegion = directory.resolve("r.0.0.0");
        Files.writeString(obstructedRegion, "existing unexpected file");
        assertThrows(CompletionException.class, () -> storage.read(CubePos.ZERO).join());
        assertThrows(CompletionException.class, () -> storage.write(CubePos.ZERO, payload("must fail")).join());
        assertEquals("existing unexpected file", Files.readString(obstructedRegion));
        assertThrows(CompletionException.class, () -> storage.flush().join());
        assertThrows(IOException.class, storage::close);
    }

    @Test
    void unreadableReplacementNeverOverwritesCommittedData(@TempDir Path directory) throws IOException {
        CubeStorage storage = new CubeStorage(directory);
        storage.write(CubePos.ZERO, payload("committed")).join();
        CompoundTag excessiveDepth = new CompoundTag();
        CompoundTag current = excessiveDepth;
        for (int depth = 0; depth < 550; depth++) {
            CompoundTag child = new CompoundTag();
            current.put("child", child);
            current = child;
        }
        assertThrows(CompletionException.class, () -> storage.write(CubePos.ZERO, excessiveDepth).join());
        assertEquals(payload("committed"), storage.read(CubePos.ZERO).join().orElseThrow());
        assertThrows(IOException.class, storage::close);
        try (CubeStorage reopened = new CubeStorage(directory)) {
            assertEquals(payload("committed"), reopened.read(CubePos.ZERO).join().orElseThrow());
        }
        try (var files = Files.walk(directory)) {
            assertFalse(files.anyMatch(path -> path.toString().endsWith(".tmp")));
        }
    }

    @Test
    void columnReopenFindsEveryPersistedYAndReturnsDetachedOrderedData(@TempDir Path directory) throws IOException {
        try (CubeStorage storage = new CubeStorage(directory)) {
            for (int y : new int[] { 64, -1, -65, 0 }) {
                storage.write(CubePos.of(-33, y, 7), payload("height " + y));
            }
            storage.write(CubePos.of(-34, 0, 7), payload("neighbor x"));
            storage.write(CubePos.of(-33, 0, 8), payload("neighbor z"));
        }
        try (CubeStorage storage = new CubeStorage(directory)) {
            var column = storage.readColumn(-33, 7, -100, 100).join();
            assertEquals(List.of(-65, -1, 0, 64), new ArrayList<>(column.keySet()));
            column.forEach((y, tag) -> assertEquals(payload("height " + y), tag));
            assertThrows(UnsupportedOperationException.class, () -> column.remove(0));
            column.get(-65).putString("value", "caller mutation");
            assertEquals(payload("height -65"), storage.readColumn(-33, 7, -65, -65).join().get(-65));
        }
    }

    @Test
    void columnReadsObserveQueuedWritesOverwritesAndDeletesInOrder(@TempDir Path directory) throws IOException {
        CubeStorage storage = new CubeStorage(directory);
        CubePos below = CubePos.of(2, -1, 4);
        CubePos above = CubePos.of(2, 1, 4);
        storage.write(below, payload("first"));
        var first = storage.readColumn(2, 4, -2, 2);
        storage.write(below, payload("second"));
        storage.write(above, payload("above"));
        var second = storage.readColumn(2, 4, -2, 2);
        storage.write(below, (CompoundTag) null);
        var third = storage.readColumn(2, 4, -2, 2);
        storage.close();
        assertEquals(List.of(-1), new ArrayList<>(first.join().keySet()));
        assertEquals(payload("first"), first.join().get(-1));
        assertEquals(List.of(-1, 1), new ArrayList<>(second.join().keySet()));
        assertEquals(payload("second"), second.join().get(-1));
        assertEquals(List.of(1), new ArrayList<>(third.join().keySet()));
        assertThrows(CompletionException.class, () -> storage.readColumn(2, 4, -2, 2).join());
    }

    @Test
    void columnBoundsAreInclusiveAndDoNotReadCorruptFilesOutsideTheRange(@TempDir Path directory) throws IOException {
        try (CubeStorage storage = new CubeStorage(directory)) {
            for (int y : new int[] { -33, -32, -1, 0, 31, 32 }) {
                storage.write(CubePos.of(0, y, 0), payload("height " + y));
            }
        }
        Files.write(cubeFile(directory, CubePos.of(0, -33, 0)), new byte[] { 1, 2, 3 });
        Files.write(cubeFile(directory, CubePos.of(0, 32, 0)), new byte[] { 1, 2, 3 });
        Files.write(cubeFile(directory, CubePos.ZERO), new byte[] { 1, 2, 3 });
        try (CubeStorage storage = new CubeStorage(directory)) {
            assertEquals(List.of(-32, -1), new ArrayList<>(storage.readColumn(0, 0, -32, -1).join().keySet()));
            assertEquals(List.of(31), new ArrayList<>(storage.readColumn(0, 0, 1, 31).join().keySet()));
            assertTrue(storage.readColumn(0, 0, 100, 200).join().isEmpty());
            assertThrows(CompletionException.class, () -> storage.readColumn(0, 0, -1, 0).join());
        }
    }

    @Test
    void columnDistinguishesAbsentCoverageFromAnExplicitEmptyTag(@TempDir Path directory) throws IOException {
        try (CubeStorage storage = new CubeStorage(directory)) {
            assertTrue(storage.readColumn(-1, -1, -20, 20).join().isEmpty());
            storage.write(CubePos.of(-1, -3, -1), new CompoundTag());
            var column = storage.readColumn(-1, -1, -20, 20).join();
            assertEquals(List.of(-3), new ArrayList<>(column.keySet()));
            assertEquals(new CompoundTag(), column.get(-3));
        }
        try (CubeStorage storage = new CubeStorage(directory)) {
            assertTrue(storage.readColumn(-1, -1, -20, 20).join().containsKey(-3));
        }
    }

    @Test
    void corruptCubeFailsTheWholeColumnAndDoesNotPoisonReadOnlyClose(@TempDir Path directory) throws IOException {
        try (CubeStorage storage = new CubeStorage(directory)) {
            storage.write(CubePos.of(0, -1, 0), payload("below"));
            storage.write(CubePos.of(0, 1000, 0), payload("above"));
        }
        Files.write(cubeFile(directory, CubePos.of(0, 1000, 0)), new byte[] { 3, 4, 5 });
        try (CubeStorage storage = new CubeStorage(directory)) {
            CompletionException failure = assertThrows(CompletionException.class, () -> storage.readColumn(0, 0, -1, 1000).join());
            assertInstanceOf(IOException.class, failure.getCause());
            assertEquals(payload("below"), storage.read(CubePos.of(0, -1, 0)).join().orElseThrow());
            storage.flush().join();
        }
    }

    @Test
    void columnRejectsFilesInTheWrongRegion(@TempDir Path directory) throws IOException {
        CubePos pos = CubePos.of(0, 1, 0);
        try (CubeStorage storage = new CubeStorage(directory)) {
            storage.write(pos, payload("misplaced")).join();
        }
        Path wrongRegion = directory.resolve("r.0.1.0");
        Files.createDirectories(wrongRegion);
        Files.move(cubeFile(directory, pos), wrongRegion.resolve("c.0.1.0.nbt"));
        try (CubeStorage storage = new CubeStorage(directory)) {
            CompletionException failure = assertThrows(CompletionException.class, () -> storage.readColumn(0, 0, 0, 63).join());
            assertInstanceOf(IOException.class, failure.getCause());
        }
    }

    @Test
    void columnIgnoresItsTemporaryFilesButRejectsMalformedAndAliasedEntries(@TempDir Path directory) throws IOException {
        Path region = directory.resolve("r.0.0.0");
        try (CubeStorage storage = new CubeStorage(directory)) {
            storage.write(CubePos.ZERO, payload("committed")).join();
            Files.write(region.resolve("c.0.0.0.nbt.123456.tmp"), new byte[] { 1, 2, 3 });
            assertEquals(payload("committed"), storage.readColumn(0, 0, 0, 1).join().get(0));
            Path malformed = region.resolve("c.0.bad.0.nbt");
            Files.write(malformed, new byte[] { 1 });
            assertThrows(CompletionException.class, () -> storage.readColumn(0, 0, 0, 1).join());
            Files.delete(malformed);
            Path alias = region.resolve("c.00.0.0.nbt");
            Files.copy(cubeFile(directory, CubePos.ZERO), alias);
            assertThrows(CompletionException.class, () -> storage.readColumn(0, 0, 0, 1).join());
        }
    }

    @Test
    void columnRejectsObstructedRegionAndCubePaths(@TempDir Path directory) throws IOException {
        Path regionRoot = directory.resolve("region-obstruction");
        try (CubeStorage storage = new CubeStorage(regionRoot)) {
            Files.writeString(regionRoot.resolve("r.0.0.0"), "not a directory");
            assertThrows(CompletionException.class, () -> storage.readColumn(0, 0, 0, 0).join());
        }
        Path fileRoot = directory.resolve("file-obstruction");
        try (CubeStorage storage = new CubeStorage(fileRoot)) {
            Files.createDirectories(cubeFile(fileRoot, CubePos.ZERO));
            assertThrows(CompletionException.class, () -> storage.readColumn(0, 0, 0, 0).join());
        }
    }

    @Test
    void columnRejectsSymbolicLinksWhenSupported(@TempDir Path directory) throws IOException {
        try (CubeStorage storage = new CubeStorage(directory)) {
            storage.write(CubePos.ZERO, payload("target")).join();
            Path target = cubeFile(directory, CubePos.ZERO);
            Path link = cubeFile(directory, CubePos.of(0, 1, 0));
            try {
                Files.createSymbolicLink(link, target.toAbsolutePath());
            } catch (FileSystemException | UnsupportedOperationException | SecurityException unavailable) {
                Assumptions.assumeTrue(false, "Creating symbolic links is unavailable: " + unavailable.getMessage());
            }
            assertThrows(CompletionException.class, () -> storage.readColumn(0, 0, 0, 1).join());
        }
    }

    @Test
    void columnLimitsFailInsteadOfReturningPartialCoverage(@TempDir Path directory) throws Exception {
        Path fileLimit = directory.resolve("file-limit");
        try (CubeStorage storage = limitedStorage(fileLimit, 2, 128 * 1024 * 1024L, 100)) {
            for (int y = 0; y < 3; y++) {
                storage.write(CubePos.of(0, y, 0), payload("height " + y));
            }
            assertThrows(CompletionException.class, () -> storage.readColumn(0, 0, 0, 2).join());
            assertEquals(2, storage.readColumn(0, 0, 0, 1).join().size());
        }
        Path byteLimit = directory.resolve("byte-limit");
        try (CubeStorage storage = limitedStorage(byteLimit, 10, 2048, 100)) {
            CompoundTag data = new CompoundTag();
            data.putByteArray("data", new byte[1536]);
            storage.write(CubePos.ZERO, data);
            storage.write(CubePos.of(0, 1, 0), data);
            assertEquals(1, storage.readColumn(0, 0, 0, 0).join().size());
            assertThrows(CompletionException.class, () -> storage.readColumn(0, 0, 0, 1).join());
        }
        Path scanLimit = directory.resolve("scan-limit");
        try (CubeStorage storage = limitedStorage(scanLimit, 10, 128 * 1024 * 1024L, 4)) {
            for (int y = 0; y < 3; y++) {
                storage.write(CubePos.of(0, y, 0), payload("height " + y));
            }
            // The lock, region directory, and three cube files require five enumerated entries.
            assertThrows(CompletionException.class, () -> storage.readColumn(0, 0, 0, 2).join());
        }
    }

    @Test
    void columnReadsRetainTheSamePerCubeNbtLimitAsIndividualReads(@TempDir Path directory) throws IOException {
        try (CubeStorage storage = new CubeStorage(directory)) {
            Path file = cubeFile(directory, CubePos.ZERO);
            Files.createDirectories(file.getParent());
            CompoundTag oversized = new CompoundTag();
            // The array alone reaches 64 MiB; its tag/compound accounting makes this exceed the per-cube limit.
            oversized.putByteArray("data", new byte[64 * 1024 * 1024]);
            NbtIo.writeCompressed(oversized, file);
            CompletionException individual = assertThrows(CompletionException.class, () -> storage.read(CubePos.ZERO).join());
            CompletionException column = assertThrows(CompletionException.class, () -> storage.readColumn(0, 0, 0, 0).join());
            assertEquals(individual.getCause().getClass(), column.getCause().getClass());
        }
    }

    @Test
    void columnRejectsInvalidRangesAndCoordinatesWithoutScanning(@TempDir Path directory) throws IOException {
        try (CubeStorage storage = new CubeStorage(directory)) {
            for (int[] query : new int[][] { { 0, 0, 2, 1 }, { Integer.MAX_VALUE, 0, 0, 0 }, { 0, Integer.MIN_VALUE, 0, 0 },
                { 0, 0, Integer.MIN_VALUE, 0 }, { 0, 0, 0, Integer.MAX_VALUE } }) {
                CompletionException failure = assertThrows(CompletionException.class,
                        () -> storage.readColumn(query[0], query[1], query[2], query[3]).join());
                assertInstanceOf(IllegalArgumentException.class, failure.getCause());
            }
            assertTrue(storage.readColumn(-(1 << 20), (1 << 20) - 1, -(1 << 20), (1 << 20) - 1).join().isEmpty());
        }
    }

    private static Path cubeFile(Path directory, CubePos pos) {
        return directory.resolve("r." + Math.floorDiv(pos.getX(), 32) + "." + Math.floorDiv(pos.getY(), 32) + "." + Math.floorDiv(pos.getZ(), 32))
                .resolve("c." + pos.getX() + "." + pos.getY() + "." + pos.getZ() + ".nbt");
    }

    private static CubeStorage limitedStorage(Path directory, int files, long bytes, int scannedEntries) throws Exception {
        var constructor = CubeStorage.class.getDeclaredConstructor(Path.class, int.class, long.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(directory, files, bytes, scannedEntries);
    }
}
