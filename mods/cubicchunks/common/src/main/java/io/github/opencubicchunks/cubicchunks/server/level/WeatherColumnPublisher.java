package io.github.opencubicchunks.cubicchunks.server.level;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

import javax.annotation.Nullable;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundWeatherColumnPacket;
import io.github.opencubicchunks.cubicchunks.platform.Platform;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.heightmap.CubeColumnSnapshot;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.heightmap.CubeColumnSource;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;
import net.minecraft.world.level.levelgen.Heightmap;

/** Bounded camera interest and detached worker jobs; mutations revoke before the corresponding block packets. */
public final class WeatherColumnPublisher {
    private static final int CAMERA_RADIUS = 32;
    private static final int MAX_COLUMNS = 256;
    private static final int MAX_JOBS = 2;
    private static final int RETRY_TICKS = 20;
    private final ServerLevel level;
    private final Executor worker;
    private final BiConsumer<ServerPlayer, CCClientboundWeatherColumnPacket> sink;
    private final Map<CubePos, Entry> columns = new LinkedHashMap<>();
    private final List<Job> jobs = new ArrayList<>();
    private long nextEpoch;

    public WeatherColumnPublisher(ServerLevel level) {
        this(level, Util.backgroundExecutor(), (player, packet) -> Platform.get().send(player, packet));
    }

    public WeatherColumnPublisher(ServerLevel level, Executor worker, BiConsumer<ServerPlayer, CCClientboundWeatherColumnPacket> sink) {
        this.level = level;
        this.worker = worker;
        this.sink = sink;
    }

    public void changed(CubePos cube) {
        var entry = columns.get(CubePos.of(cube.getX(), 0, cube.getZ()));
        if (entry == null) {
            return;
        }
        entry.epoch = ++nextEpoch;
        entry.retryAt = 0;
        if (entry.heights != null) {
            entry.heights = null;
            broadcast(entry, null);
        }
    }

    public void tick() {
        reconcile(interested());
        finishJobs();
        if (jobs.size() < MAX_JOBS) {
            startOne();
        }
    }

    private Map<CubePos, Set<ServerPlayer>> interested() {
        Map<CubePos, Set<ServerPlayer>> wanted = new LinkedHashMap<>();
        for (var player : level.players()) {
            var camera = player.getCamera();
            var center = camera == null ? player.blockPosition() : camera.blockPosition();
            for (int x = Coords.blockToCube(center.getX() - CAMERA_RADIUS); x <= Coords.blockToCube(center.getX() + CAMERA_RADIUS); x++) {
                for (int z = Coords.blockToCube(center.getZ() - CAMERA_RADIUS); z <= Coords.blockToCube(center.getZ() + CAMERA_RADIUS); z++) {
                    addInterest(wanted, CubePos.of(x, 0, z), player);
                }
            }
        }
        return wanted;
    }

    private static void addInterest(Map<CubePos, Set<ServerPlayer>> wanted, CubePos key, ServerPlayer player) {
        if (!CubicBlockBounds.contains(key.minCubeX()) || !CubicBlockBounds.contains(key.minCubeZ())) {
            return;
        }
        if (wanted.containsKey(key) || wanted.size() < MAX_COLUMNS) {
            wanted.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(player);
        }
    }

    private void reconcile(Map<CubePos, Set<ServerPlayer>> wanted) {
        for (var entry : columns.values()) {
            var watchers = wanted.getOrDefault(entry.pos, Set.of());
            for (var player : entry.watchers) {
                if (!watchers.contains(player)) {
                    send(player, entry, null);
                }
            }
        }
        columns.keySet().retainAll(wanted.keySet());
        wanted.forEach((pos, watchers) -> {
            Entry entry = columns.computeIfAbsent(pos, key -> new Entry(key, ++nextEpoch));
            if (entry.heights != null) {
                for (var player : watchers) {
                    if (!entry.watchers.contains(player)) {
                        send(player, entry, entry.heights);
                    }
                }
            }
            entry.watchers = Set.copyOf(watchers);
        });
    }

    private void finishJobs() {
        var iterator = jobs.iterator();
        while (iterator.hasNext()) {
            var job = iterator.next();
            if (!job.future.isDone()) {
                continue;
            }
            iterator.remove();
            var entry = columns.get(job.pos);
            if (entry == null || entry.job != job) {
                continue;
            }
            entry.job = null;
            if (entry.epoch != job.epoch || job.future.isCompletedExceptionally()) {
                continue;
            }
            publish(entry, job.future.getNow(null));
        }
    }

    private void publish(Entry entry, int[] heights) {
        entry.retryAt = level.getGameTime() + RETRY_TICKS;
        entry.unknown = Arrays.stream(heights).anyMatch(y -> y == CCClientboundWeatherColumnPacket.UNKNOWN);
        if (!Arrays.equals(entry.heights, heights)) {
            entry.heights = heights;
            broadcast(entry, heights);
        }
    }

    private void startOne() {
        var source = (CubeColumnSource) level.getChunkSource().chunkMap;
        for (var entry : columns.values()) {
            if (entry.job != null || entry.heights != null && !entry.unknown || level.getGameTime() < entry.retryAt) {
                continue;
            }
            entry.retryAt = level.getGameTime() + RETRY_TICKS;
            CubeColumnSnapshot snapshot = source.cc_column(entry.pos.minCubeX(), entry.pos.minCubeZ());
            if (snapshot == null) {
                continue;
            }
            var future = CompletableFuture.supplyAsync(() -> capture(snapshot), worker);
            var job = new Job(entry.pos, entry.epoch, future);
            entry.job = job;
            jobs.add(job);
            // Deliver while the server waits between ticks. Waiting until the next tick's RETURN
            // can discard every result when this column changes during every simulation tick.
            future.whenComplete((heights, failure) -> level.getServer().execute(this::finishJobs));
            return;
        }
    }

    /** Pure bounded extraction over detached paletted sections, safe on the worker and directly testable. */
    public static int[] capture(CubeColumnSnapshot snapshot) {
        int[] heights = new int[CCClientboundWeatherColumnPacket.COUNT];
        int diameter = CubicConstants.DIAMETER_IN_BLOCKS;
        for (int z = 0; z < diameter; z++) {
            for (int x = 0; x < diameter; x++) {
                heights[x + z * diameter] = snapshot.firstAvailable(Heightmap.Types.MOTION_BLOCKING, Coords.cubeToMinBlock(snapshot.cubeX()) + x,
                        Coords.cubeToMinBlock(snapshot.cubeZ()) + z).orElse(CCClientboundWeatherColumnPacket.UNKNOWN);
            }
        }
        return heights;
    }

    private void broadcast(Entry entry, @Nullable int[] heights) {
        for (var player : entry.watchers) {
            send(player, entry, heights);
        }
    }

    private void send(ServerPlayer player, Entry entry, @Nullable int[] heights) {
        if (player.level() == level && player.connection != null) {
            sink.accept(player,
                    new CCClientboundWeatherColumnPacket(level.dimension().identifier(), entry.pos.getX(), entry.pos.getZ(), entry.epoch, heights));
        }
    }

    private static final class Entry {
        private final CubePos pos;
        private long epoch;
        private Set<ServerPlayer> watchers = Set.of();
        private @Nullable int[] heights;
        private @Nullable Job job;
        private long retryAt;
        private boolean unknown;

        private Entry(CubePos pos, long epoch) {
            this.pos = pos;
            this.epoch = epoch;
        }
    }

    private record Job(CubePos pos, long epoch, CompletableFuture<int[]> future) {}
}
