package io.github.opencubicchunks.cubicchunks.world.level.cube.storage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import io.github.opencubicchunks.cc_core.api.CubePos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/**
 * Ordered cube NBT storage. Each cube is a compressed file replaced atomically after its contents are forced to disk.
 * This deliberately favors a simple recoverable format over region-file packing. The caller owns NBT schema/version validation.
 * Close must run outside this store's completion callbacks; it drains accepted work and releases the exclusive directory lock.
 */
public final class CubeStorage implements AutoCloseable {
    private static final int REGION_SIZE = 32;
    private static final long MAX_NBT_BYTES = 64L * 1024 * 1024;
    private static final int MAX_COLUMN_CUBES = 16384;
    private static final long MAX_COLUMN_NBT_BYTES = 128L * 1024 * 1024;
    // Bound metadata work as well as decoded data; reaching any limit fails instead of returning partial coverage.
    private static final int MAX_COLUMN_SCANNED_ENTRIES = 1000000;
    private static final Pattern REGION_NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.(-?\\d+)");
    private static final Pattern CUBE_NAME = Pattern.compile("c\\.(-?\\d+)\\.(-?\\d+)\\.(-?\\d+)\\.nbt");
    private static final Pattern TEMPORARY_NAME = Pattern.compile("c\\.-?\\d+\\.-?\\d+\\.-?\\d+\\.nbt\\.\\d+\\.tmp");

    private final Path directory;
    private final FileChannel lockChannel;
    private final FileLock directoryLock;
    private final ExecutorService executor;
    private final int maxColumnCubes;
    private final long maxColumnNbtBytes;
    private final int maxColumnScannedEntries;
    private final Object lifecycle = new Object();
    private volatile Thread workerThread;
    private @Nullable IOException writeFailure;
    private @Nullable CompletableFuture<Void> closeFuture;

    public CubeStorage(Path directory) throws IOException {
        this(directory, MAX_COLUMN_CUBES, MAX_COLUMN_NBT_BYTES, MAX_COLUMN_SCANNED_ENTRIES);
    }

    CubeStorage(Path directory, int maxColumnCubes, long maxColumnNbtBytes, int maxColumnScannedEntries) throws IOException {
        if (maxColumnCubes <= 0 || maxColumnNbtBytes <= 0 || maxColumnScannedEntries <= 0) {
            throw new IllegalArgumentException("Column scan limits must be positive");
        }
        this.maxColumnCubes = maxColumnCubes;
        this.maxColumnNbtBytes = maxColumnNbtBytes;
        this.maxColumnScannedEntries = maxColumnScannedEntries;
        Path normalized = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        Files.createDirectories(normalized);
        this.directory = normalized.toRealPath();
        Path lockPath = this.directory.resolve(".cube-storage.lock");
        rejectSymbolicLink(lockPath);
        this.lockChannel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            this.directoryLock = acquireLock(this.lockChannel, this.directory);
        } catch (IOException failure) {
            try {
                this.lockChannel.close();
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        this.executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "CubicChunks cube storage");
            thread.setDaemon(true);
            this.workerThread = thread;
            return thread;
        });
    }

    public CompletableFuture<Optional<CompoundTag>> read(CubePos pos) {
        Path path = pathFor(pos);
        return enqueue(() -> readNow(path), false);
    }

    private static Optional<CompoundTag> readNow(Path path) throws IOException {
        if (!regionExists(path.getParent())) {
            return Optional.empty();
        }
        rejectSymbolicLink(path);
        try {
            return Optional.of(NbtIo.readCompressed(path, NbtAccounter.create(MAX_NBT_BYTES)));
        } catch (NoSuchFileException missing) {
            return Optional.empty();
        }
    }

    /**
     * Reads persisted cubes in an inclusive vertical range, ordered by cube Y. Missing entries mean unknown coverage, not empty cubes.
     * One queued operation observes earlier writes/deletes together; no secondary index or cached NBT can diverge from the cube files.
     * The map structure is immutable and its tags belong to the caller. Schema validation remains the serializer's responsibility.
     */
    public CompletableFuture<NavigableMap<Integer, CompoundTag>> readColumn(int cubeX, int cubeZ, int minCubeY, int maxCubeY) {
        if (minCubeY > maxCubeY || !isPackedPosition(cubeX, minCubeY, cubeZ) || !isPackedPosition(cubeX, maxCubeY, cubeZ)) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Invalid cube column bounds: " + cubeX + ", " + cubeZ + ", " + minCubeY + ".." + maxCubeY));
        }
        return enqueue(() -> new ColumnScan(cubeX, cubeZ, minCubeY, maxCubeY).read(), false);
    }

    private void checkColumnScanBudget(int scanned) throws IOException {
        if (scanned > this.maxColumnScannedEntries) {
            throw new IOException("Cube column scan exceeds the " + this.maxColumnScannedEntries + " directory-entry limit");
        }
    }

    private static int[] parseCoordinates(Pattern pattern, String name) throws IOException {
        Matcher matcher = pattern.matcher(name);
        if (!matcher.matches()) {
            throw new IOException("Malformed cube storage entry: " + name);
        }
        int[] coordinates = new int[matcher.groupCount()];
        for (int i = 0; i < coordinates.length; i++) {
            String component = matcher.group(i + 1);
            try {
                coordinates[i] = Integer.parseInt(component);
            } catch (NumberFormatException failure) {
                throw new IOException("Invalid cube storage coordinate: " + name, failure);
            }
            if (!Integer.toString(coordinates[i]).equals(component)) {
                throw new IOException("Noncanonical cube storage coordinate: " + name);
            }
        }
        return coordinates;
    }

    private static boolean isPackedPosition(int x, int y, int z) {
        CubePos pos = CubePos.of(x, y, z);
        return CubePos.from(pos.asLong()).equals(pos);
    }

    /** Runs the supplier on the calling thread, then captures its result before scheduling disk I/O. */
    public CompletableFuture<Void> write(CubePos pos, Supplier<CompoundTag> data) {
        Objects.requireNonNull(data, "data");
        synchronized (this.lifecycle) {
            if (this.closeFuture != null) {
                return closedResult();
            }
        }
        try {
            return write(pos, data.get());
        } catch (RuntimeException failure) {
            return enqueue(() -> {
                throw failure;
            }, true);
        }
    }

    /** Captures the mutable tag on the calling thread; null deletes the cube. */
    public CompletableFuture<Void> write(CubePos pos, @Nullable CompoundTag data) {
        return writeChecked(pos, data, null);
    }

    /** Optional replacement preflight runs in the same ordered I/O operation as the write. */
    public CompletableFuture<Void> writeChecked(CubePos pos, @Nullable CompoundTag data, @Nullable Predicate<CompoundTag> allowed) {
        Path path = pathFor(pos);
        final CompoundTag snapshot;
        try {
            if (data != null && data.sizeInBytes() > MAX_NBT_BYTES) {
                throw new IOException("Cube NBT exceeds the " + MAX_NBT_BYTES + " byte accounting limit: " + pos);
            }
            snapshot = data == null ? null : data.copy();
        } catch (IOException | RuntimeException failure) {
            return enqueue(() -> {
                throw failure;
            }, true);
        }
        return enqueue(() -> {
            regionExists(path.getParent());
            rejectSymbolicLink(path);
            if (allowed != null && !readNow(path).map(allowed::test).orElse(true)) {
                throw new IOException("Refusing to replace a saved full cube with a proto cube: " + pos);
            }
            if (snapshot == null) {
                Files.deleteIfExists(path);
            } else {
                writeAtomically(path, snapshot);
            }
            return null;
        }, true);
    }

    /** Completes after earlier operations, and fails if any accepted write has failed. */
    public CompletableFuture<Void> flush() {
        return enqueue(() -> {
            if (this.writeFailure != null) {
                throw new IOException("An earlier cube write failed", this.writeFailure);
            }
            return null;
        }, false);
    }

    @Override public void close() throws IOException {
        if (Thread.currentThread() == this.workerThread) {
            throw new IOException("Cannot synchronously close cube storage from its I/O completion callback");
        }
        CompletableFuture<Void> completion;
        synchronized (this.lifecycle) {
            if (this.closeFuture == null) {
                this.closeFuture = new CompletableFuture<>();
                this.executor.execute(this::finishClose);
                this.executor.shutdown();
            }
            completion = this.closeFuture;
        }
        try {
            completion.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while draining cube storage; accepted writes continue", interrupted);
        } catch (ExecutionException failure) {
            throw new IOException("Failed to close cube storage at " + this.directory, failure.getCause());
        }
    }

    private Path pathFor(CubePos pos) {
        Objects.requireNonNull(pos, "pos");
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        String region = "r." + Math.floorDiv(x, REGION_SIZE) + "." + Math.floorDiv(y, REGION_SIZE) + "." + Math.floorDiv(z, REGION_SIZE);
        return this.directory.resolve(region).resolve("c." + x + "." + y + "." + z + ".nbt");
    }

    private <T> CompletableFuture<T> enqueue(IoOperation<T> operation, boolean write) {
        synchronized (this.lifecycle) {
            if (this.closeFuture != null) {
                return closedResult();
            }
            CompletableFuture<T> result = new CompletableFuture<>();
            this.executor.execute(() -> {
                try {
                    result.complete(operation.run());
                } catch (Throwable failure) {
                    if (write) {
                        IOException wrapped = new IOException("Could not persist cube data in " + this.directory, failure);
                        if (this.writeFailure == null) {
                            this.writeFailure = wrapped;
                        } else {
                            this.writeFailure.addSuppressed(wrapped);
                        }
                    }
                    result.completeExceptionally(failure);
                }
            });
            return result;
        }
    }

    private <T> CompletableFuture<T> closedResult() {
        return CompletableFuture.failedFuture(new IOException("Cube storage is closed: " + this.directory));
    }

    private void finishClose() {
        IOException failure = this.writeFailure;
        try {
            this.directoryLock.release();
        } catch (IOException cleanup) {
            failure = appendFailure(failure, cleanup);
        }
        try {
            this.lockChannel.close();
        } catch (IOException cleanup) {
            failure = appendFailure(failure, cleanup);
        }
        if (failure == null) {
            this.closeFuture.complete(null);
        } else {
            this.closeFuture.completeExceptionally(failure);
        }
    }

    private static IOException appendFailure(@Nullable IOException current, IOException next) {
        if (current == null) {
            return next;
        }
        current.addSuppressed(next);
        return current;
    }

    private static FileLock acquireLock(FileChannel channel, Path directory) throws IOException {
        try {
            FileLock lock = channel.tryLock();
            if (lock != null) {
                return lock;
            }
        } catch (OverlappingFileLockException conflict) {
            throw new IOException("Cube storage is already open: " + directory, conflict);
        }
        throw new IOException("Cube storage is already open: " + directory);
    }

    private static void rejectSymbolicLink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new IOException("Symbolic links are not permitted inside cube storage: " + path);
        }
    }

    private static boolean regionExists(Path region) throws IOException {
        try {
            BasicFileAttributes attributes = Files.readAttributes(region, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory()) {
                throw new IOException("Expected a cube region directory: " + region);
            }
            return true;
        } catch (NoSuchFileException missing) {
            return false;
        }
    }

    private static void writeAtomically(Path target, CompoundTag data) throws IOException {
        byte[] compressed = compressAndValidate(data);
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString() + ".", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = ByteBuffer.wrap(compressed);
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
                channel.force(true);
            }
            // Do not fall back to a non-atomic overwrite on unsupported filesystems.
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException failure) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    /** Validate the exact compressed bytes before touching disk, avoiding a second file open/read/decompression. */
    private static byte[] compressAndValidate(CompoundTag data) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        NbtIo.writeCompressed(data, output);
        byte[] compressed = output.toByteArray();
        NbtIo.readCompressed(new ByteArrayInputStream(compressed), NbtAccounter.create(MAX_NBT_BYTES));
        return compressed;
    }

    private final class ColumnScan {
        private final int cubeX;
        private final int cubeZ;
        private final int minCubeY;
        private final int maxCubeY;
        private final int regionX;
        private final int regionZ;
        private final int minRegionY;
        private final int maxRegionY;
        private final NavigableMap<Integer, CompoundTag> result = new TreeMap<>();
        private int scanned;
        private long decodedBytes;

        private ColumnScan(int cubeX, int cubeZ, int minCubeY, int maxCubeY) {
            this.cubeX = cubeX;
            this.cubeZ = cubeZ;
            this.minCubeY = minCubeY;
            this.maxCubeY = maxCubeY;
            this.regionX = Math.floorDiv(cubeX, REGION_SIZE);
            this.regionZ = Math.floorDiv(cubeZ, REGION_SIZE);
            this.minRegionY = Math.floorDiv(minCubeY, REGION_SIZE);
            this.maxRegionY = Math.floorDiv(maxCubeY, REGION_SIZE);
        }

        private NavigableMap<Integer, CompoundTag> read() throws IOException {
            try (var regions = Files.newDirectoryStream(CubeStorage.this.directory)) {
                for (Path region : regions) {
                    checkColumnScanBudget(++this.scanned);
                    readRegion(region);
                }
            }
            return Collections.unmodifiableNavigableMap(this.result);
        }

        private void readRegion(Path region) throws IOException {
            String name = region.getFileName().toString();
            if (!name.startsWith("r.")) {
                return;
            }
            if (!isRequestedRegion(parseCoordinates(REGION_NAME, name))) {
                return;
            }
            if (!Files.readAttributes(region, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isDirectory()) {
                throw new IOException("Expected a cube region directory: " + region);
            }
            try (var files = Files.newDirectoryStream(region)) {
                for (Path file : files) {
                    checkColumnScanBudget(++this.scanned);
                    readFile(file);
                }
            }
        }

        private boolean isRequestedRegion(int[] coordinates) {
            return coordinates[0] == this.regionX && coordinates[2] == this.regionZ && coordinates[1] >= this.minRegionY
                    && coordinates[1] <= this.maxRegionY;
        }

        private void readFile(Path file) throws IOException {
            String name = file.getFileName().toString();
            if (TEMPORARY_NAME.matcher(name).matches()) {
                return;
            }
            int[] coordinates = parseCoordinates(CUBE_NAME, name);
            if (isRequestedCube(coordinates)) {
                readCube(file, coordinates[1]);
            }
        }

        private boolean isRequestedCube(int[] coordinates) {
            return coordinates[0] == this.cubeX && coordinates[2] == this.cubeZ && coordinates[1] >= this.minCubeY && coordinates[1] <= this.maxCubeY;
        }

        private void readCube(Path file, int cubeY) throws IOException {
            CubePos pos = CubePos.of(this.cubeX, cubeY, this.cubeZ);
            if (!pathFor(pos).equals(file)) {
                throw new IOException("Cube file is in the wrong region: " + file);
            }
            if (!Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isRegularFile()) {
                throw new IOException("Expected a regular cube file: " + file);
            }
            if (this.result.size() >= CubeStorage.this.maxColumnCubes) {
                throw new IOException("Cube column exceeds the " + CubeStorage.this.maxColumnCubes + " file limit");
            }
            CompoundTag tag = readTag(file);
            if (this.result.putIfAbsent(cubeY, tag) != null) {
                throw new IOException("Duplicate saved cube Y " + cubeY + " in column " + this.cubeX + ", " + this.cubeZ);
            }
        }

        private CompoundTag readTag(Path file) throws IOException {
            long remainingBytes = CubeStorage.this.maxColumnNbtBytes - this.decodedBytes;
            if (remainingBytes <= 0) {
                throw new IOException("Cube column exceeds the " + CubeStorage.this.maxColumnNbtBytes + " byte accounting limit");
            }
            NbtAccounter accounter = NbtAccounter.create(Math.min(MAX_NBT_BYTES, remainingBytes));
            CompoundTag tag = NbtIo.readCompressed(file, accounter);
            this.decodedBytes += accounter.getUsage();
            return tag;
        }
    }

    @FunctionalInterface
    private interface IoOperation<T> {
        T run() throws IOException;
    }
}
