package io.github.opencubicchunks.smoke;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cc_core.world.level.CloPos;
import io.github.opencubicchunks.cubicchunks.CubicChunks;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientCubeCache;
import io.github.opencubicchunks.cubicchunks.server.level.CCServerPlayer;
import io.github.opencubicchunks.cubicchunks.server.level.CloHolder;
import io.github.opencubicchunks.cubicchunks.server.level.ServerCubeCache;
import io.github.opencubicchunks.cubicchunks.server.network.CubeEntityTrackingSender;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeSource;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeAccess;
import io.github.opencubicchunks.cubicchunks.world.level.cube.ImposterProtoCube;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.CaveParameter;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.DeepCaveSettings;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.HeightFormula;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightProvider;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightBlockSnapshot;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightView;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;

/** Reproduces the saved test_2 route through ordinary player tracking, without requesting cubes from the probe. */
final class DescentProbe {
    static final long SEED = 5015645173147404823L;
    private static final double X = -18.3;
    private static final double Z = .959;
    private static final boolean STATIONARY = Boolean.getBoolean("cubicchunks.smoke.descentStationary");
    private static final int FAR_HOLD_SECONDS = STATIONARY ? 120 : 30;
    private static final int[] TARGETS = STATIONARY ? new int[] { -62 } : new int[] { -62, -160, -256 };
    private static final int[] SAMPLE_Y = { -63, -65, -95, -97, -127, -129, -161, -193, -225, -256, -257 };
    private static final List<BlockPos> ROCK_SAMPLES = List.of(new BlockPos(-19, -65, 2), new BlockPos(-18, -65, 2),
            new BlockPos(-17, -65, 1), new BlockPos(-17, -66, 2), new BlockPos(-19, -68, 4));
    private final boolean reopen;
    private final ConcurrentLinkedQueue<String> failures = new ConcurrentLinkedQueue<>();
    private final Map<CubePos, SourceObservation> previousSources = new HashMap<>();
    private final Map<CubePos, Long> firstServerAvailableNano = new HashMap<>();
    private final Map<CubePos, Long> firstSentNano = new HashMap<>();
    private CompletableFuture<Void> setup;
    private CompletableFuture<Void> sampling;
    private int stage;
    private int ticks;
    private long holdStarted;
    private long deadline;
    private long lastDiagnostics;
    private boolean verifying;
    private boolean nearVerifying;
    private boolean nearChecked;
    private boolean anchored;
    private boolean earlyDiagnosed;

    DescentProbe(boolean reopen) { this.reopen = reopen; }

    static void configureCreation() {
        CubicChunks.config().setDeepCaveSettings(new DeepCaveSettings(true, 24, 26, 35, Map.of(),
                Map.of(CaveParameter.SCALE, HeightFormula.parse("500"), CaveParameter.OPENNESS, HeightFormula.parse("200"))));
    }

    void tick(Minecraft client) {
        if (stage == TARGETS.length) return;
        if (setup == null) {
            if (CubicChunks.IS_IN_TEST) throw new IllegalStateException("Descent must use production paths");
            deadline = System.nanoTime() + java.time.Duration.ofMinutes(5).toNanos();
            var server = client.getSingleplayerServer();
            setup = CompletableFuture.runAsync(() -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                System.out.println("CC_DESCENT_SETUP reopen=" + reopen + " stationary=" + STATIONARY + " farHoldSeconds=" + FAR_HOLD_SECONDS
                        + " saved=" + player.position());
                player.setGameMode(GameType.SPECTATOR);
                player.teleportTo(X, 104, Z);
            }, server);
            return;
        }
        if (System.nanoTime() > deadline) {
            fail("Descent timeout stage=" + stage);
            finish(client);
            return;
        }
        if (!setup.isDone()) return;
        try {
            setup.join();
            if (sampling != null && sampling.isCompletedExceptionally()) sampling.join();
        } catch (RuntimeException exception) {
            fail("Descent asynchronous failure " + exception);
            finish(client);
            return;
        }
        if (!client.player.isSpectator() || client.gui.screen() != null) return;
        if (!anchored) {
            // A reopened spectator is already in the requested game mode before the teleport packet arrives.
            if (Math.abs(client.player.getY() - 104) >= .01 || Math.abs(client.player.getX() - X) >= .01
                    || Math.abs(client.player.getZ() - Z) >= .01) return;
            anchored = true;
            System.out.println("CC_DESCENT_ANCHORED reopen=" + reopen + " pos=" + client.player.position());
        }
        if (holdStarted != 0 && (Math.abs(client.player.getY() - TARGETS[stage]) >= .01 || Math.abs(client.player.getX() - X) >= .01
                || Math.abs(client.player.getZ() - Z) >= .01)) {
            System.out.println("CC_DESCENT_HOLD_RESET reopen=" + reopen + " targetY=" + TARGETS[stage] + " pos=" + client.player.position());
            holdStarted = 0;
            nearChecked = false;
            nearVerifying = false;
            verifying = false;
            earlyDiagnosed = false;
        }
        if (nearVerifying) {
            if (!sampling.isDone()) return;
            sampling.join();
            nearVerifying = false;
            nearChecked = true;
            System.out.println("CC_DESCENT_NEAR_CHECKED reopen=" + reopen + " y=" + TARGETS[stage] + " failures=" + failures.size()
                    + " radiusBlocks=32 holdSeconds=5 elapsedMilliseconds=" + (System.nanoTime() - holdStarted) / 1_000_000);
        }
        if (verifying) {
            if (!sampling.isDone()) return;
            sampling.join();
            System.out.println("CC_DESCENT_HOLD_COMPLETE reopen=" + reopen + " y=" + TARGETS[stage] + " failures=" + failures.size()
                    + " radiusBlocks=192 holdSeconds=" + FAR_HOLD_SECONDS + " elapsedMilliseconds=" + (System.nanoTime() - holdStarted) / 1_000_000);
            stage++;
            holdStarted = 0;
            verifying = false;
            nearChecked = false;
            earlyDiagnosed = false;
            if (stage == TARGETS.length) {
                finish(client);
                return;
            }
        }
        client.player.setYRot(180);
        client.player.setXRot(75);
        client.player.setDeltaMovement(0, 0, 0);
        double y = Math.max(TARGETS[stage], client.player.getY() - .3);
        client.player.setPos(X, y, Z);
        if (++ticks % 40 == 0 && (sampling == null || sampling.isDone())) sample(client, Check.OBSERVE);
        if (Math.abs(y - TARGETS[stage]) < .01) {
            if (holdStarted == 0) {
                holdStarted = System.nanoTime();
                System.out.println("CC_DESCENT_ARRIVED reopen=" + reopen + " y=" + TARGETS[stage] + " pos=" + client.player.position());
            }
            if (!nearChecked && System.nanoTime() - holdStarted >= java.time.Duration.ofSeconds(5).toNanos()
                    && (sampling == null || sampling.isDone())) {
                sample(client, Check.NEAR);
                nearVerifying = true;
            } else if (STATIONARY && nearChecked && !earlyDiagnosed && System.nanoTime() - holdStarted >= java.time.Duration.ofSeconds(30).toNanos()
                    && (sampling == null || sampling.isDone())) {
                sample(client, Check.EARLY);
                earlyDiagnosed = true;
            } else if (nearChecked && System.nanoTime() - holdStarted >= java.time.Duration.ofSeconds(FAR_HOLD_SECONDS).toNanos()
                    && (sampling == null || sampling.isDone())) {
                sample(client, Check.ALL);
                verifying = true;
            }
        }
    }

    private void sample(Minecraft client, Check check) {
        boolean verify = check == Check.NEAR || check == Check.ALL;
        var cache = (ClientCubeCache) client.level.getChunkSource();
        ClientCubeCache.Storage storage = null;
        try {
            storage = (ClientCubeCache.Storage) field(client.level.getChunkSource(), "cc_cubeStorage");
        } catch (ReflectiveOperationException exception) {
            System.out.println("CC_DESCENT_CACHE_DIAGNOSTICS_ERROR " + exception);
        }
        double playerY = client.player.getY();
        List<Cell> cells = new ArrayList<>();
        List<BlockPos> positions = new ArrayList<>(ROCK_SAMPLES);
        for (int y : SAMPLE_Y) positions.add(new BlockPos(-19, y, 0));
        for (BlockPos pos : positions) {
            if (Math.abs(pos.getY() - playerY) > 192) continue;
            CubePos key = CubePos.from(pos);
            var cube = cache.cc_getCube(key.getX(), key.getY(), key.getZ(), false);
            var light = cache.cc_getCubeLightView(key);
            cells.add(new Cell(pos, cube == null ? null : cube.getBlockState(pos).toString(), collisionBlock(client.level, pos),
                    storage == null ? "unknown" : Boolean.toString(storage.inRange(key.getX(), key.getY(), key.getZ())), light != null && light.isReady(),
                    describeLight(light, pos), describeLight(cache.cc_getVisibleCubeLightView(key), pos),
                    client.levelRenderer.isSectionCompiledAndVisible(pos, 0)));
        }
        boolean diagnostics = verify || System.nanoTime() - lastDiagnostics >= java.time.Duration.ofSeconds(10).toNanos();
        if (diagnostics) lastDiagnostics = System.nanoTime();
        long holdMilliseconds = holdStarted == 0 ? 0 : (System.nanoTime() - holdStarted) / 1_000_000;
        var server = client.getSingleplayerServer();
        sampling = CompletableFuture.runAsync(() -> {
            var level = server.overworld();
            var source = (CubeSource) level.getChunkSource();
            var lights = (CubeLightProvider) level.getChunkSource();
            var player = server.getPlayerList().getPlayers().getFirst();
            var tracking = ((CCServerPlayer) player).cc_getCloTrackingView();
            var status = ((ServerCubeCache) level.getChunkSource()).cc_getCubeLightStatus().getNow(null);
            System.out.println("CC_DESCENT_SAMPLE reopen=" + reopen + " verify=" + verify + " check=" + check + " clientY=" + playerY + " serverY="
                    + player.getY() + " holdMilliseconds=" + holdMilliseconds + " lighting=" + status);
            if (check == Check.EARLY) {
                System.out.println("CC_DESCENT_EARLY_DIAGNOSTIC reopen=" + reopen + " y=" + playerY + " holdMilliseconds=" + holdMilliseconds
                        + " checked=false finalHoldSeconds=" + FAR_HOLD_SECONDS);
            }
            if (diagnostics) diagnoseWorldLights(level.getChunkSource());
            boolean nearCubeFound = false;
            for (Cell cell : cells) {
                CubePos key = CubePos.from(cell.pos);
                var cube = source.cc_getCubeNow(key.getX(), key.getY(), key.getZ());
                var light = lights.cc_getCubeLightView(key);
                boolean tracked = tracking.cc_contains(key.getX(), key.getY(), key.getZ());
                boolean sent = ((CubeEntityTrackingSender) player.connection.chunkSender).cc_hasSentEntityCube(level, key);
                long observedNano = System.nanoTime();
                if (cube != null && cube.getFullStatus().isOrAfter(FullChunkStatus.FULL)
                        && firstServerAvailableNano.putIfAbsent(key, observedNano) == null) {
                    System.out.println("CC_DESCENT_CUBE_AVAILABLE pos=" + key + " firstServerAvailableNano=" + observedNano
                            + " holdMilliseconds=" + holdMilliseconds + " firstObserved=true");
                }
                if (sent && firstSentNano.putIfAbsent(key, observedNano) == null) {
                    System.out.println("CC_DESCENT_CUBE_SENT pos=" + key + " firstSentNano=" + observedNano + " holdMilliseconds=" + holdMilliseconds
                            + " serverAvailableAgeMs=" + observedAge(firstServerAvailableNano, key, observedNano) + " firstObserved=true");
                }
                boolean near = Math.abs(cell.pos.getY() - playerY) <= 32;
                boolean checked = verify && (check != Check.NEAR || near);
                if (tracked && cube != null && Math.abs(cell.pos.getY() - playerY) <= 32) nearCubeFound = true;
                String serverBlock = cube == null ? null : cube.getBlockState(cell.pos).toString();
                String serverCollision = collisionBlock(level, cell.pos);
                System.out.println("CC_DESCENT_CELL y=" + cell.pos.getY() + " pos=" + cell.pos + " server=" + serverBlock + " status="
                        + (cube == null ? "missing" : cube.getFullStatus()) + " serverLight=" + (light != null && light.isReady())
                        + " serverLightState=" + describeLight(light, cell.pos) + " tracked=" + tracked + " sent=" + sent
                        + " clientInRange=" + cell.inRange + " checked=" + checked + " client=" + cell.block
                        + " serverCollision=" + serverCollision + " clientCollision=" + cell.collision
                        + " clientLight=" + cell.ready + " clientCurrent=" + cell.currentLight + " clientVisible=" + cell.displayLight
                        + " mesh=" + cell.visible + " firstServerAvailableNano=" + firstServerAvailableNano.get(key)
                        + " serverAvailableAgeMs=" + observedAge(firstServerAvailableNano, key, observedNano)
                        + " firstSentNano=" + firstSentNano.get(key) + " sentAgeMs=" + observedAge(firstSentNano, key, observedNano));
                if (check == Check.EARLY && tracked && cube != null
                        && (!serverBlock.equals(cell.block) || !serverBlock.equals(serverCollision) || !serverBlock.equals(cell.collision))) {
                    System.out.println("CC_DESCENT_EARLY_MISMATCH y=" + playerY + " at=" + cell.pos + " actual=" + serverBlock + " client="
                            + cell.block + " serverCollision=" + serverCollision + " clientCollision=" + cell.collision + " checked=false");
                }
                if (checked && check == Check.NEAR && (!tracked || cube == null)) {
                    fail("Descent near server cube unavailable holdY=" + playerY + " at " + cell.pos + " tracked=" + tracked);
                }
                if (checked && tracked && cube != null && !serverBlock.equals(cell.block)) {
                    fail("Descent client mismatch holdY=" + playerY + " at " + cell.pos + " server=" + serverBlock + " client=" + cell.block);
                }
                if (checked && tracked && cube != null && (!serverBlock.equals(serverCollision) || !serverBlock.equals(cell.collision))) {
                    fail("Descent collision mismatch holdY=" + playerY + " at " + cell.pos + " actual=" + serverBlock
                            + " serverCollision=" + serverCollision + " clientCollision=" + cell.collision);
                }
            }
            if (verify && !nearCubeFound) fail("Descent missing tracked server cube within 32 blocks of " + playerY);
        }, server);
    }

    private void fail(String reason) {
        failures.add(reason);
        System.out.println("CC_DESCENT_FAILURE reopen=" + reopen + " reason=" + reason);
    }

    private static long observedAge(Map<CubePos, Long> firstObserved, CubePos pos, long now) {
        Long first = firstObserved.get(pos);
        return first == null ? -1 : (now - first) / 1_000_000;
    }

    private void finish(Minecraft client) {
        stage = TARGETS.length;
        client.stop();
        System.out.println((failures.isEmpty() ? "CC_DESCENT_COMPLETE" : "CC_DESCENT_FAILED") + " reopen=" + reopen
                + " failures=" + failures.size() + " nonLoading=true productionTracking=true testBypass=false gracefulStop=true");
    }

    private static String describeLight(CubeLightView light, BlockPos pos) {
        return light == null ? "missing" : "{ready=" + light.isReady() + ",block=" + light.sample(LightLayer.BLOCK, pos)
                + ",sky=" + light.sample(LightLayer.SKY, pos) + "}";
    }

    private static String collisionBlock(Level level, BlockPos pos) {
        var reader = level.getChunkForCollisions(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
        return reader == null ? "missing-reader" : reader.getBlockState(pos).toString();
    }

    /** Read-only reflection stays in this development entrypoint, on the owning server thread. */
    private void diagnoseWorldLights(Object source) {
        try {
            Object chunkMap = field(source, "chunkMap");
            Object manager = field(chunkMap, "cc_worldLights");
            if (manager == null) {
                System.out.println("CC_DESCENT_LIGHTS manager=null");
                return;
            }
            var sources = (Map<?, ?>) field(manager, "sources");
            diagnoseSourceChanges(sources);
            var targets = (List<?>) field(manager, "targets");
            // Diagnostics must not refresh the access order of the production snapshot cache.
            var captures = new java.util.LinkedHashMap<>((Map<?, ?>) field(manager, "captures"));
            var views = (Map<?, ?>) field(manager, "views");
            var dirty = (java.util.Set<?>) field(manager, "dirty");
            var urgent = (java.util.Set<?>) field(manager, "urgent");
            var jobs = (Map<?, ?>) field(manager, "jobs");
            var skyIndexes = (Map<?, ?>) field(manager, "skyIndexes");
            var diskBlocks = (Map<?, ?>) field(manager, "diskBlocks");
            System.out.println("CC_DESCENT_LIGHTS sources=" + sources.size() + " targets=" + targets.size() + " captures=" + captures.size()
                    + " views=" + views.size() + " dirty=" + dirty.size() + " urgent=" + urgent.size() + " jobs=" + jobs.size()
                    + " tick=" + field(manager, "tick") + " epoch=" + field(manager, "epoch")
                    + " firstUrgent=" + urgent.stream().limit(8).toList());
            for (var entry : jobs.entrySet()) {
                Object job = entry.getValue();
                var result = (CompletableFuture<?>) field(job, "result");
                var inputs = (CompletableFuture<?>) field(job, "inputs");
                System.out.println("CC_DESCENT_LIGHT_JOB pos=" + entry.getKey() + " done=" + result.isDone()
                        + " exceptional=" + result.isCompletedExceptionally() + " state=" + field(job, "state") + " depsCurrent="
                        + call(manager, "current", job.getClass(), job)
                        + " inputsDone=" + inputs.isDone() + " inputsExceptional=" + inputs.isCompletedExceptionally()
                        + " inputCache=" + describeInputCache((CubePos) entry.getKey(), captures, skyIndexes, diskBlocks));
            }
            var firstDeliveries = (java.util.Set<?>) field(manager, "firstDeliveries");
            var epochs = (Map<?, ?>) field(manager, "epochs");
            for (CubePos focus : List.of(CubePos.of(-1, -3, 0), CubePos.of(-1, -8, 0))) {
                Object stamp = sources.get(focus);
                Object cube = stamp == null ? null : field(stamp, "cube");
                LevelCube direct = ((CubeSource) source).cc_getCubeNow(focus.getX(), focus.getY(), focus.getZ());
                Object job = jobs.get(focus);
                var inputs = job == null ? null : (CompletableFuture<?>) field(job, "inputs");
                System.out.println("CC_DESCENT_LIGHT_FOCUS pos=" + focus + " source=" + (stamp != null) + " sourceIdentity=" + identity(cube)
                        + " directIdentity=" + identity(direct) + " sourceMatchesDirect=" + (cube == direct) + " targetIndex=" + targets.indexOf(focus)
                        + " firstDelivery=" + firstDeliveries.contains(focus) + " capture=" + captures.containsKey(focus)
                        + " view=" + views.containsKey(focus) + " dirty=" + dirty.contains(focus) + " epoch=" + epochs.get(focus)
                        + " job=" + (job != null) + " jobDone=" + (job == null ? "missing" : ((CompletableFuture<?>) field(job, "result")).isDone())
                        + " jobState=" + (job == null ? "missing" : field(job, "state"))
                        + " inputsDone=" + (inputs == null ? "missing" : inputs.isDone())
                        + " inputsExceptional=" + (inputs == null ? "missing" : inputs.isCompletedExceptionally())
                        + " urgent=" + urgent.contains(focus) + " stampRevision=" + (stamp == null ? "missing" : field(stamp, "revision"))
                        + " cubeRevision=" + (cube == null ? "missing" : ((CubeAccess) cube).cc_getLightRevision())
                        + " needsJob=" + call(manager, "needsJob", CubePos.class, focus) + " ready=unknown"
                        + " activeLightingSource=" + (call(chunkMap, "cc_findLightingSource", CubePos.class, focus) != null)
                        + " inputCache=" + describeInputCache(focus, captures, skyIndexes, diskBlocks));
                for (String mapName : List.of("updatingChunkMap", "visibleChunkMap", "pendingUnloads")) {
                    diagnoseHolder(chunkMap, mapName, focus, direct);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            System.out.println("CC_DESCENT_LIGHT_DIAGNOSTICS_ERROR " + exception);
        }
    }

    /** Cache entries can be evicted while a job retains its inputs; missing here does not prove that job is waiting. */
    private static String describeInputCache(CubePos center, Map<?, ?> captures, Map<?, ?> skyIndexes, Map<?, ?> diskBlocks)
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
                + ",blockLive=" + liveBlocks + ",diskPresent=" + diskPresent + ",diskDone=" + diskDone + ",diskExceptional="
                + diskExceptional + ",blockMissing=" + (27 - liveBlocks - diskPresent) + "}";
    }

    private static void diagnoseHolder(Object chunkMap, String mapName, CubePos pos, LevelCube direct) {
        try {
            var holders = (Map<?, ?>) field(chunkMap, mapName);
            ChunkHolder holder = (ChunkHolder) holders.get(CloPos.cube(pos).asLong());
            var latest = holder == null ? null : ((CloHolder) holder).cc_getLatestClo();
            Object unwrapped = latest instanceof ImposterProtoCube imposter ? imposter.cc_getWrappedClo() : latest;
            System.out.println("CC_DESCENT_HOLDER pos=" + pos + " map=" + mapName + " holder=" + identity(holder)
                    + " ticket=" + (holder == null ? "missing" : holder.getTicketLevel()) + " latest=" + identity(latest)
                    + " unwrapped=" + identity(unwrapped) + " matchesDirect=" + (unwrapped == direct)
                    + " persisted=" + (unwrapped instanceof CubeAccess cube ? cube.getPersistedStatus() : "missing")
                    + " fullStatus=" + (unwrapped instanceof LevelCube cube ? cube.getFullStatus() : "missing"));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            System.out.println("CC_DESCENT_HOLDER_DIAGNOSTICS_ERROR pos=" + pos + " map=" + mapName + " exception=" + exception);
        }
    }

    private void diagnoseSourceChanges(Map<?, ?> sources) throws ReflectiveOperationException {
        long started = System.nanoTime();
        Map<CubePos, SourceObservation> current = new HashMap<>();
        int added = 0, changed = 0, removed = 0, logged = 0, snapshots = 0, blockExamples = 0;
        for (var entry : sources.entrySet()) {
            CubePos pos = (CubePos) entry.getKey();
            if (pos.getX() < -2 || pos.getX() > 0 || pos.getZ() < -1 || pos.getZ() > 1) continue;
            CubeAccess cube = (CubeAccess) field(entry.getValue(), "cube");
            long stampRevision = (Long) field(entry.getValue(), "revision");
            long cubeRevision = cube.cc_getLightRevision();
            SourceObservation before = previousSources.get(pos);
            boolean replaced = before == null || before.cube != cube;
            boolean updated = replaced || before.stampRevision != stampRevision || before.cubeRevision != cubeRevision;
            if (before == null) added++;
            else if (updated) changed++;
            if (updated && logged++ < 32) {
                System.out.println("CC_DESCENT_SOURCE_CHANGED pos=" + pos + " previous=" + identity(before == null ? null : before.cube)
                        + " current=" + identity(cube) + " replaced=" + replaced + " stampRevision="
                        + (before == null ? "missing" : before.stampRevision) + "->" + stampRevision + " cubeRevision="
                        + (before == null ? "missing" : before.cubeRevision) + "->" + cubeRevision + " delta="
                        + (before == null || replaced ? "new-source" : cubeRevision - before.cubeRevision));
            }
            CubeLightBlockSnapshot blocks = before == null || replaced ? null : before.blocks;
            long blockRevision = before == null || replaced ? -1 : before.blockRevision;
            if ((blocks == null || blockRevision != cubeRevision) && snapshots < 64
                    && (snapshots == 0 || System.nanoTime() - started < 10_000_000L)) {
                CubeLightBlockSnapshot next = CubeLightBlockSnapshot.capture(cube);
                snapshots++;
                if (before != null && before.blocks != null && blockExamples < 8) {
                    blockExamples += reportBlockDifferences(pos, before.blocks, next, before.blockRevision, cubeRevision, 8 - blockExamples);
                }
                blocks = next;
                blockRevision = cubeRevision;
            }
            current.put(pos, new SourceObservation(cube, stampRevision, cubeRevision, blocks, blockRevision));
        }
        for (var entry : previousSources.entrySet()) {
            if (!current.containsKey(entry.getKey())) {
                removed++;
                if (logged++ < 32) System.out.println("CC_DESCENT_SOURCE_REMOVED pos=" + entry.getKey() + " previous=" + identity(entry.getValue().cube));
            }
        }
        previousSources.clear();
        previousSources.putAll(current);
        long pendingSnapshots = current.values().stream().filter(value -> value.blocks == null || value.blockRevision != value.cubeRevision).count();
        System.out.println("CC_DESCENT_SOURCE_CHANGES focusXZ=[-2..0,-1..1] count=" + current.size() + " added=" + added + " changed=" + changed
                + " removed=" + removed + " suppressed=" + Math.max(0, logged - 32) + " snapshots=" + snapshots + " pendingSnapshots="
                + pendingSnapshots + " blockExamples=" + blockExamples + " milliseconds=" + (System.nanoTime() - started) / 1_000_000);
    }

    private static int reportBlockDifferences(CubePos pos, CubeLightBlockSnapshot before, CubeLightBlockSnapshot after,
            long beforeRevision, long afterRevision, int limit) {
        int count = 0;
        int size = CubicConstants.DIAMETER_IN_BLOCKS;
        for (int y = 0; y < size; y++) {
            for (int z = 0; z < size; z++) {
                for (int x = 0; x < size; x++) {
                    if (before.state(x, y, z) != after.state(x, y, z)) {
                        System.out.println("CC_DESCENT_SOURCE_BLOCK pos=" + new BlockPos(pos.minCubeX() + x, pos.minCubeY() + y, pos.minCubeZ() + z)
                                + " revision=" + beforeRevision + "->" + afterRevision + " before=" + before.state(x, y, z)
                                + " after=" + after.state(x, y, z));
                        if (++count == limit) return count;
                    }
                }
            }
        }
        return count;
    }

    private static String identity(Object source) {
        return source == null ? "missing" : source.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(source));
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                var field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(target.getClass().getName() + "." + name);
    }

    private static Object call(Object target, String name, Class<?> argumentType, Object argument) throws ReflectiveOperationException {
        var method = target.getClass().getDeclaredMethod(name, argumentType);
        method.setAccessible(true);
        return method.invoke(target, argument);
    }

    private enum Check { OBSERVE, EARLY, NEAR, ALL }
    private record Cell(BlockPos pos, String block, String collision, String inRange, boolean ready, String currentLight, String displayLight, boolean visible) {}
    private record SourceObservation(CubeAccess cube, long stampRevision, long cubeRevision, CubeLightBlockSnapshot blocks, long blockRevision) {}
}
