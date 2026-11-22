package io.github.opencubicchunks.smoke;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.world.level.CloPos;
import io.github.opencubicchunks.cubicchunks.CubicChunks;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientCubeCache;
import io.github.opencubicchunks.cubicchunks.server.level.CCServerPlayer;
import io.github.opencubicchunks.cubicchunks.server.level.CloHolder;
import io.github.opencubicchunks.cubicchunks.server.level.CubicServerLevel;
import io.github.opencubicchunks.cubicchunks.server.network.CubeEntityTrackingSender;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeSource;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightProvider;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** Paired cold-arrival measurements. Reads only existing cubes; ordinary tracking supplies all work. */
final class WorldLoadingLatencyProbe {
    private static final double SOURCE_X = -18.3;
    private static final double SOURCE_Y = -64;
    private static final double Z = .959;
    private static final double TARGET_X = SOURCE_X + 1056;
    private static final long SAMPLE_NANOS = 100_000_000L;
    private static final long DEADLINE_NANOS = 30_000_000_000L;
    private static final long OBSERVATION_NANOS = 60_000_000_000L;
    private static final String[] STAGES = { "tracked", "generation", "full", "serverLight", "sent", "client", "clientLight", "collision" };
    private final String variant;
    private final int targetY;
    private final List<Cohort> cohorts = new ArrayList<>();
    private CompletableFuture<Void> setup;
    private CompletableFuture<Void> pending;
    private volatile long started;
    private long setupStarted;
    private long lastSample;
    private long lastDispatcherDiagnostic;
    private long lastClientLightDiagnostic;
    private boolean startedRelocation;
    private boolean anchored;
    private boolean deadlineSampleRequested;
    private volatile boolean deadlineChecked;
    private volatile boolean failed;
    private volatile boolean finished;

    WorldLoadingLatencyProbe(String variant, boolean reopen) {
        if (!variant.equals("above") && !variant.equals("below")) throw new IllegalArgumentException("smokeLatency must be above or below");
        if (reopen) throw new IllegalArgumentException("Latency comparison requires a fresh world for each variant");
        this.variant = variant;
        this.targetY = variant.equals("above") ? 16 : -144;
        CubePos center = CubePos.from(new BlockPos((int) Math.floor(TARGET_X), targetY, 0));
        Cohort near = new Cohort("near27");
        Cohort far = new Cohort("far24");
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    near.cubes.add(new Observation(CubePos.of(center.getX() + dx, center.getY() + dy, center.getZ() + dz)));
                    if (dx != 0 || dz != 0) {
                        far.cubes.add(new Observation(CubePos.of(center.getX() + dx * 6, center.getY() + dy, center.getZ() + dz * 6)));
                    }
                }
            }
        }
        cohorts.add(near);
        cohorts.add(far);
    }

    void tick(Minecraft client) {
        if (finished) {
            if (pending != null) pending.join();
            System.out.println((failed ? "CC_LATENCY_FAILED" : "CC_LATENCY_COMPLETE") + " variant=" + variant
                    + " deadlineSeconds=30 observationSeconds=60 nonLoading=true productionTracking=true testBypass=false");
            client.stop();
            return;
        }
        if (setup == null) {
            if (CubicChunks.IS_IN_TEST) throw new IllegalStateException("Latency measurement must use production paths");
            setupStarted = System.nanoTime();
            var server = client.getSingleplayerServer();
            setup = CompletableFuture.runAsync(() -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SPECTATOR);
                player.teleportTo(SOURCE_X, SOURCE_Y, Z);
            }, server);
            return;
        }
        if (System.nanoTime() - setupStarted > 180_000_000_000L) {
            fail("setup_or_observation_timeout");
            finished = true;
            client.stop();
            return;
        }
        if (!setup.isDone()) return;
        setup.join();
        if (pending != null) {
            if (!pending.isDone()) return;
            pending.join();
        }
        if (!startedRelocation) {
            if (!client.player.isSpectator() || !at(client, SOURCE_X, SOURCE_Y)) return;
            List<ClientObservation> baseline = readClient(client);
            startedRelocation = true;
            var server = client.getSingleplayerServer();
            pending = CompletableFuture.runAsync(() -> {
                var source = (CubeSource) server.overworld().getChunkSource();
                int serverFull = 0;
                int clientPresent = 0;
                for (ClientObservation observed : baseline) {
                    CubePos pos = observed.observation.pos;
                    if (source.cc_getCubeNow(pos.getX(), pos.getY(), pos.getZ()) != null) serverFull++;
                    if (observed.block != null) clientPresent++;
                }
                System.out.println("CC_LATENCY_BASELINE variant=" + variant + " seed=" + DescentProbe.SEED + " sourceX=" + SOURCE_X
                        + " sourceY=" + SOURCE_Y + " targetX=" + TARGET_X + " targetY=" + targetY + " z=" + Z
                        + " displacementX=1056 displacementY=" + (targetY - SOURCE_Y)
                        + " renderDistance=15 verticalViewDistance=8 sampleCount=51 serverPresent=" + serverFull + " clientPresent=" + clientPresent
                        + " samplePeriodMs=100 timing=first_observed firstStageOrder=" + String.join(",", STAGES));
                if (serverFull != 0 || clientPresent != 0) {
                    fail("destination_not_cold");
                    finished = true;
                    return;
                }
                started = System.nanoTime();
                server.getPlayerList().getPlayers().getFirst().teleportTo(TARGET_X, targetY, Z);
            }, server);
            return;
        }
        if (started == 0) return;
        long now = System.nanoTime();
        if (!anchored && at(client, TARGET_X, targetY)) {
            anchored = true;
            System.out.println("CC_LATENCY_ANCHORED variant=" + variant + " milliseconds=" + millis(now));
        }
        if (anchored) {
            client.player.setDeltaMovement(0, 0, 0);
            client.player.setPos(TARGET_X, targetY, Z);
            client.player.setYRot(180);
            client.player.setXRot(75);
        }
        if (now - lastSample < SAMPLE_NANOS) return;
        lastSample = now;
        boolean deadline = !deadlineSampleRequested && now - started >= DEADLINE_NANOS;
        if (deadline) deadlineSampleRequested = true;
        boolean end = now - started >= OBSERVATION_NANOS;
        List<ClientObservation> clientSample = readClient(client);
        var server = client.getSingleplayerServer();
        pending = CompletableFuture.runAsync(() -> sampleServer(server.overworld(), clientSample, now, deadline, end), server);
    }

    private List<ClientObservation> readClient(Minecraft client) {
        var cache = (ClientCubeCache) client.level.getChunkSource();
        diagnoseClientRefresh(cache);
        List<ClientObservation> result = new ArrayList<>(51);
        for (Cohort cohort : cohorts) {
            for (Observation observation : cohort.cubes) {
                CubePos pos = observation.pos;
                var cube = cache.cc_getCube(pos.getX(), pos.getY(), pos.getZ(), false);
                var light = cache.cc_getCubeLightView(pos);
                var visible = cache.cc_getVisibleCubeLightView(pos);
                result.add(new ClientObservation(observation, cube == null ? null : cube.getBlockState(observation.block),
                        collisionBlock(client.level, observation.block), light != null && light.isReady() && visible != null && visible.isReady(),
                        visible != null && visible.isReady()));
            }
        }
        return result;
    }

    private void diagnoseClientRefresh(ClientCubeCache cache) {
        long now = System.nanoTime();
        if (!Boolean.getBoolean("cubicchunks.smoke.lightDiagnostics") || started == 0 || now - lastClientLightDiagnostic < 1_000_000_000L) return;
        lastClientLightDiagnostic = now;
        try {
            Object lights = field(cache, "cc_lightState");
            Map<?, ?> pending = (Map<?, ?>) field(lights, "scopedRefreshes");
            List<String> anchors = new ArrayList<>();
            for (Object refresh : pending.values()) {
                if (anchors.size() == 8) break;
                anchors.add(field(refresh, "pos") + "/attempts=" + field(refresh, "attempts") + "/remaining=" + field(refresh, "remainingTicks"));
            }
            System.out.println("CC_LATENCY_CLIENT_REFRESH variant=" + variant + " elapsedMs=" + millis(now)
                    + " pendingColumns=" + pending.size() + " cooldown=" + field(lights, "refreshCooldown")
                    + " acceptedEntries=" + ((Map<?, ?>) field(field(lights, "state"), "entries")).size()
                    + " displayedEntries=" + ((Map<?, ?>) field(lights, "displayed")).size() + " firstEight=" + anchors);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            System.out.println("CC_LATENCY_CLIENT_REFRESH_DIAGNOSTIC_ERROR " + exception);
        }
    }

    private void sampleServer(ServerLevel level, List<ClientObservation> clientSample, long clientObserved, boolean deadline, boolean end) {
        long now = System.nanoTime();
        if (now - lastDispatcherDiagnostic >= 1_000_000_000L) {
            lastDispatcherDiagnostic = now;
            diagnoseDispatcher(level);
            diagnoseWorldLights(level);
            diagnoseNativeTerrain(level);
        }
        var source = (CubeSource) level.getChunkSource();
        var lights = (CubeLightProvider) source;
        var player = level.getServer().getPlayerList().getPlayers().getFirst();
        var tracking = ((CCServerPlayer) player).cc_getCloTrackingView();
        Map<?, ?> holders;
        try {
            holders = (Map<?, ?>) field(level.getChunkSource().chunkMap, "updatingChunkMap");
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot observe generation holders", exception);
        }
        int index = 0;
        boolean allReady = true;
        boolean readyByDeadline = true;
        for (Cohort cohort : cohorts) {
            int[] counts = new int[STAGES.length];
            Map<String, Integer> generation = new LinkedHashMap<>();
            int nonempty = 0;
            int solidSamples = 0;
            int visibleLight = 0;
            int completeByDeadline = 0;
            for (Observation observation : cohort.cubes) {
                ClientObservation client = clientSample.get(index++);
                if (client.visibleLight) visibleLight++;
                CubePos pos = observation.pos;
                var cube = source.cc_getCubeNow(pos.getX(), pos.getY(), pos.getZ());
                var light = lights.cc_getCubeLightView(pos);
                var holder = (ChunkHolder) holders.get(CloPos.cube(pos).asLong());
                var latest = holder == null ? null : ((CloHolder) holder).cc_getLatestClo();
                generation.merge(latest == null ? "missing" : latest.getPersistedStatus().toString(), 1, Integer::sum);
                BlockState block = cube == null ? null : cube.getBlockState(observation.block);
                boolean full = cube != null && cube.getFullStatus().isOrAfter(FullChunkStatus.FULL);
                boolean[] available = { tracking.cc_contains(pos.getX(), pos.getY(), pos.getZ()), latest != null, full,
                        light != null && light.isReady(), ((CubeEntityTrackingSender) player.connection.chunkSender).cc_hasSentEntityCube(level, pos),
                        client.block != null, client.light,
                        full && block.equals(client.block) && block.equals(client.collision) && block.equals(collisionBlock(level, observation.block)) };
                if (cube != null && !cube.isEmpty()) nonempty++;
                if (block != null && !block.isAir()) solidSamples++;
                for (int s = 0; s < available.length; s++) {
                    if (!available[s]) continue;
                    counts[s]++;
                    if (observation.first[s] == 0) observation.first[s] = s == 5 || s == 6 ? clientObserved : now;
                }
                boolean onTime = Arrays.stream(observation.first).allMatch(first -> first > 0 && first - started <= DEADLINE_NANOS);
                if (onTime) completeByDeadline++;
                else readyByDeadline = false;
                if (deadline || end) {
                    System.out.println("CC_LATENCY_CUBE variant=" + variant + " cohort=" + cohort.name + " deadline=" + deadline
                            + " pos=" + pos + " elapsedMs=" + millis(now) + " generation=" + (latest == null ? "missing" : latest.getPersistedStatus())
                            + " available=" + flags(available) + " readyByDeadline=" + onTime + " firstMs=" + times(observation.first) + " serverBlock=" + block
                            + " clientBlock=" + client.block + " clientCollision=" + client.collision);
                }
            }
            for (int s = 0; s < STAGES.length; s++) {
                for (int p = 0; p < 2; p++) {
                    int percentile = p == 0 ? 80 : 95;
                    if (cohort.milestones[s][p] == 0 && counts[s] * 100 >= percentile * cohort.cubes.size()) {
                        cohort.milestones[s][p] = s == 5 || s == 6 ? clientObserved : now;
                        System.out.println("CC_LATENCY_MILESTONE variant=" + variant + " cohort=" + cohort.name + " stage=" + STAGES[s]
                                + " percent=" + percentile + " count=" + counts[s] + " total=" + cohort.cubes.size()
                                + " milliseconds=" + millis(cohort.milestones[s][p]));
                    }
                }
                if (counts[s] != cohort.cubes.size()) allReady = false;
            }
            System.out.println("CC_LATENCY_SAMPLE variant=" + variant + " cohort=" + cohort.name + " elapsedMs=" + millis(now)
                    + " clientElapsedMs=" + millis(clientObserved) + " sampleQueueMs=" + (now - clientObserved) / 1_000_000
                    + " deadline=" + deadline + " total=" + cohort.cubes.size() + " counts=" + counts(counts)
                    + " completeByDeadline=" + completeByDeadline
                    + " visibleLight=" + visibleLight
                    + " nonemptyServerCubes=" + nonempty + " solidBlockSamples=" + solidSamples + " generation=" + generation);
        }
        if (deadline) {
            deadlineChecked = true;
            boolean pass = readyByDeadline && allReady;
            if (!pass) fail("incomplete_at_30_seconds elapsedMs=" + millis(now));
            System.out.println("CC_LATENCY_DEADLINE variant=" + variant + " pass=" + pass + " elapsedMs=" + millis(now)
                    + " clientElapsedMs=" + millis(clientObserved) + " requiredCount=51");
        }
        if (end || deadlineChecked && allReady) finished = true;
    }

    private long millis(long nano) { return nano == 0 ? -1 : (nano - started) / 1_000_000; }

    private String times(long[] nanos) {
        long[] milliseconds = Arrays.stream(nanos).map(this::millis).toArray();
        return Arrays.toString(milliseconds).replace(" ", "");
    }

    private static String counts(int[] counts) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < STAGES.length; i++) values.add(STAGES[i] + ":" + counts[i]);
        return String.join(",", values);
    }

    private static String flags(boolean[] flags) {
        int[] counts = new int[flags.length];
        for (int i = 0; i < flags.length; i++) counts[i] = flags[i] ? 1 : 0;
        return counts(counts);
    }

    private void fail(String reason) {
        failed = true;
        System.out.println("CC_LATENCY_FAILURE variant=" + variant + " reason=" + reason);
    }

    /** Sizes are approximate because the dispatcher owns these collections on its separate executor. */
    private void diagnoseDispatcher(ServerLevel level) {
        try {
            Object manager = field(level.getChunkSource().chunkMap, "distanceManager");
            Object dispatcher = field(manager, "ticketDispatcher");
            Object queue = field(dispatcher, "queue");
            var priorities = (List<?>) field(queue, "queuesPerPriority");
            int queued = 0;
            for (Object priority : priorities) queued += ((Map<?, ?>) priority).size();
            System.out.println("CC_LATENCY_DISPATCHER variant=" + variant + " elapsedMs=" + millis(System.nanoTime())
                    + " approximate=true cap=" + field(dispatcher, "maxChunksInExecution")
                    + " inFlight=" + ((java.util.Set<?>) field(dispatcher, "chunkPositionsInExecution")).size()
                    + " queuedPositions=" + queued + " topPriority=" + field(queue, "topPriorityQueueIndex")
                    + " sleeping=" + field(dispatcher, "sleeping"));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            System.out.println("CC_LATENCY_DISPATCHER_DIAGNOSTIC_ERROR " + exception);
        }
    }

    private void diagnoseWorldLights(ServerLevel level) {
        try {
            Object manager = field(level.getChunkSource().chunkMap, "cc_worldLights");
            if (manager == null) return;
            diagnoseLightMetrics(manager);
            var sources = (Map<?, ?>) field(manager, "sources");
            var targets = (List<?>) field(manager, "targets");
            // Diagnostics must not refresh the access order of the production snapshot cache.
            var captures = new LinkedHashMap<>((Map<?, ?>) field(manager, "captures"));
            var views = (Map<?, ?>) field(manager, "views");
            var dirty = (java.util.Set<?>) field(manager, "dirty");
            var urgent = (java.util.Set<?>) field(manager, "urgent");
            var jobs = (Map<?, ?>) field(manager, "jobs");
            var firstDeliveries = (java.util.Set<?>) field(manager, "firstDeliveries");
            var skyIndexes = (Map<?, ?>) field(manager, "skyIndexes");
            var diskBlocks = (Map<?, ?>) field(manager, "diskBlocks");
            long dirtyFirst = dirty.stream().filter(firstDeliveries::contains).count();
            long viewsFirst = views.keySet().stream().filter(firstDeliveries::contains).count();
            var holders = (Map<?, ?>) field(level.getChunkSource().chunkMap, "updatingChunkMap");
            System.out.println("CC_LATENCY_LIGHTS variant=" + variant + " elapsedMs=" + millis(System.nanoTime())
                    + " sources=" + sources.size() + " targets=" + targets.size() + " captures=" + captures.size()
                    + " views=" + views.size() + " dirty=" + dirty.size() + " urgent=" + urgent.size()
                    + " notifications=" + ((Map<?, ?>) field(manager, "notifications")).size()
                    + " epoch=" + field(manager, "epoch") + " jobs=" + jobs.size() + " firstDeliveries=" + firstDeliveries.size()
                    + " dirtyFirst=" + dirtyFirst + " dirtyUpdates=" + (dirty.size() - dirtyFirst) + " viewsFirst=" + viewsFirst
                    + " viewsCertifiedSkyOccluded=" + certifiedSkyOccluded(views) + " jobsCertifiedSkyOccluded=" + certifiedSkyOccluded(jobs));
            for (var entry : jobs.entrySet()) {
                Object job = entry.getValue();
                var inputs = (CompletableFuture<?>) field(job, "inputs");
                var result = (CompletableFuture<?>) field(job, "result");
                System.out.println("CC_LATENCY_LIGHT_JOB variant=" + variant + " pos=" + entry.getKey()
                        + " firstDelivery=" + firstDeliveries.contains(entry.getKey())
                        + " state=" + field(job, "state") + " inputsDone=" + inputs.isDone()
                        + " inputsExceptional=" + inputs.isCompletedExceptionally() + " resultDone=" + result.isDone()
                        + " resultExceptional=" + result.isCompletedExceptionally()
                        + " depsCurrent=" + call(manager, "current", job.getClass(), job)
                        + " inputCache=" + inputCache((CubePos) entry.getKey(), captures, skyIndexes, diskBlocks));
            }
            for (int index : new int[] { 0, 12, 23 }) {
                CubePos pos = cohorts.get(1).cubes.get(index).pos;
                var cube = ((CubeSource) level.getChunkSource()).cc_getCubeNow(pos.getX(), pos.getY(), pos.getZ());
                var holder = (ChunkHolder) holders.get(CloPos.cube(pos).asLong());
                System.out.println("CC_LATENCY_LIGHT_FOCUS variant=" + variant + " pos=" + pos + " source=" + sources.containsKey(pos)
                        + " firstDelivery=" + firstDeliveries.contains(pos) + " targetIndex=" + targets.indexOf(pos)
                        + " capture=" + captures.containsKey(pos) + " view=" + views.containsKey(pos)
                        + " dirty=" + dirty.contains(pos) + " urgent=" + urgent.contains(pos) + " job=" + jobs.containsKey(pos)
                        + " fullStatus=" + (cube == null ? "missing" : cube.getFullStatus())
                        + " holderTicket=" + (holder == null ? "missing" : holder.getTicketLevel())
                        + " ready=unknown"
                        + " needsJob=" + call(manager, "needsJob", CubePos.class, pos)
                        + " inputCache=" + inputCache(pos, captures, skyIndexes, diskBlocks));
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            System.out.println("CC_LATENCY_LIGHT_DIAGNOSTIC_ERROR " + exception);
        }
    }

    private void diagnoseLightMetrics(Object manager) {
        try {
            Object totals = field(manager, "diagnosticTotals");
            if (totals != null) {
                System.out.println("CC_LATENCY_LIGHT_METRICS variant=" + variant + " elapsedMs=" + millis(System.nanoTime())
                        + " totals=" + totals + " tick=" + field(manager, "diagnosticTick"));
            }
        } catch (NoSuchFieldException ignored) {
            // Keep this development probe compatible with a frozen production baseline.
        } catch (ReflectiveOperationException | RuntimeException exception) {
            System.out.println("CC_LATENCY_LIGHT_METRICS_DIAGNOSTIC_ERROR " + exception);
        }
    }

    private void diagnoseNativeTerrain(ServerLevel level) {
        try {
            Object terrain = ((CubicServerLevel) level).cc_nativeBiomeTerrain();
            var snapshot = terrain.getClass().getDeclaredMethod("diagnosticsSnapshot");
            snapshot.setAccessible(true);
            Object value = snapshot.invoke(terrain);
            if (!(value instanceof Map<?, ?> metrics)) return;
            var scalarMetrics = new LinkedHashMap<>(metrics);
            Object creations = scalarMetrics.remove("createCounts");
            List<String> repeatedTiles = new ArrayList<>();
            int repeatedTileCount = 0;
            if (creations instanceof Map<?, ?> counts) {
                var repeated = counts.entrySet().stream()
                        .filter(entry -> entry.getValue() instanceof Number count && count.longValue() > 1)
                        .sorted(java.util.Comparator.<Map.Entry<?, ?>>comparingLong(entry -> ((Number) entry.getValue()).longValue()).reversed())
                        .toList();
                repeatedTileCount = repeated.size();
                repeated.stream().limit(16).forEach(entry -> repeatedTiles.add(entry.getKey() + ":" + entry.getValue()));
            }
            System.out.println("CC_LATENCY_NATIVE_TERRAIN variant=" + variant + " elapsedMs=" + millis(System.nanoTime())
                    + " totals=" + scalarMetrics + " repeatedTileCount=" + repeatedTileCount + " repeatedTilesTop16=" + repeatedTiles);
        } catch (NoSuchMethodException ignored) {
            // The old production baseline does not expose optional native-generation counters.
        } catch (ReflectiveOperationException | RuntimeException exception) {
            System.out.println("CC_LATENCY_NATIVE_TERRAIN_DIAGNOSTIC_ERROR " + exception);
        }
    }

    /** Older development runtimes lack this certificate field; -1 leaves their other diagnostics available. */
    private static int certifiedSkyOccluded(Map<?, ?> entries) {
        int count = 0;
        for (Object entry : entries.values()) {
            try {
                if (Boolean.TRUE.equals(field(entry, "skyOccluded"))) count++;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return -1;
            }
        }
        return count;
    }

    private static String inputCache(CubePos center, Map<?, ?> captures, Map<?, ?> skyIndexes, Map<?, ?> diskBlocks)
            throws ReflectiveOperationException {
        int skyPresent = 0, skyDone = 0, skyExceptional = 0, liveBlocks = 0, diskPresent = 0, diskDone = 0, diskExceptional = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                var sky = (CompletableFuture<?>) skyIndexes.get(CubePos.of(center.getX() + dx, 0, center.getZ() + dz));
                if (sky != null) {
                    skyPresent++;
                    if (sky.isDone()) skyDone++;
                    if (sky.isCompletedExceptionally()) skyExceptional++;
                }
                for (int dy = -1; dy <= 1; dy++) {
                    CubePos pos = CubePos.of(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    Object capture = captures.get(pos);
                    if (capture != null && field(capture, "blocks") != null) {
                        liveBlocks++;
                    } else {
                        var disk = (CompletableFuture<?>) diskBlocks.get(pos);
                        if (disk != null) {
                            diskPresent++;
                            if (disk.isDone()) diskDone++;
                            if (disk.isCompletedExceptionally()) diskExceptional++;
                        }
                    }
                }
            }
        }
        return "{cacheOnly=true,skyPresent=" + skyPresent + "/9,skyDone=" + skyDone + ",skyExceptional=" + skyExceptional
                + ",blockLive=" + liveBlocks + ",diskPresent=" + diskPresent + ",diskDone=" + diskDone
                + ",diskExceptional=" + diskExceptional + ",blockMissing=" + (27 - liveBlocks - diskPresent) + "}";
    }

    private static Object call(Object target, String name, Class<?> argumentType, Object argument) throws ReflectiveOperationException {
        var method = target.getClass().getDeclaredMethod(name, argumentType);
        method.setAccessible(true);
        return method.invoke(target, argument);
    }

    private static boolean at(Minecraft client, double x, double y) {
        return Math.abs(client.player.getX() - x) < .01 && Math.abs(client.player.getY() - y) < .01 && Math.abs(client.player.getZ() - Z) < .01;
    }

    private static BlockState collisionBlock(Level level, BlockPos pos) {
        var reader = level.getChunkForCollisions(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
        return reader == null ? null : reader.getBlockState(pos);
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                var field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    private static final class Cohort {
        private final String name;
        private final List<Observation> cubes = new ArrayList<>();
        private final long[][] milestones = new long[STAGES.length][2];
        private Cohort(String name) { this.name = name; }
    }

    private static final class Observation {
        private final CubePos pos;
        private final BlockPos block;
        private final long[] first = new long[STAGES.length];
        private Observation(CubePos pos) {
            this.pos = pos;
            this.block = new BlockPos(pos.minCubeX() + 16, pos.minCubeY() + 16, pos.minCubeZ() + 16);
        }
    }

    private record ClientObservation(Observation observation, BlockState block, BlockState collision, boolean light, boolean visibleLight) {}
}
