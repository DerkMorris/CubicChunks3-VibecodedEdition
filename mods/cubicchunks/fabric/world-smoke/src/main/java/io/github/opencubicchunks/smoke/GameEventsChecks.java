package io.github.opencubicchunks.smoke;

import java.util.ArrayList;
import java.util.List;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.CubicChunks;
import io.github.opencubicchunks.cubicchunks.world.level.CubicLevelReader;
import io.github.opencubicchunks.cubicchunks.world.level.gameevent.CubeGameEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SculkSensorBlock;
import net.minecraft.world.level.block.entity.SculkSensorBlockEntity;
import net.minecraft.world.level.block.state.properties.SculkSensorPhase;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;

/** Dedicated-compatible fixture: actual listener lifecycle and ordinary block/entity ticks, without client classes. */
public final class GameEventsChecks implements AutoCloseable {
    private final ServerLevel level;
    private final ServerPlayer player;
    private final GameType originalMode;
    private final BlockPos sensor;
    private final List<BlockPos> readiness = new ArrayList<>();
    private SculkSensorBlockEntity sensorEntity;
    private Allay allay;
    private Warden warden;
    private BlockPos disturbance;
    private int ticks;
    private int stage;
    private int stageStarted;
    private boolean activated;
    private boolean complete;

    public GameEventsChecks(ServerLevel level, ServerPlayer player, BlockPos origin) {
        if (CubicChunks.IS_IN_TEST) {
            throw new IllegalStateException("Game-event smoke requires production ticks");
        }
        this.level = level;
        this.player = player;
        this.originalMode = player.gameMode.getGameModeForPlayer();
        this.sensor = new BlockPos((origin.getX() & ~31) + 16, origin.getY() & ~31, (origin.getZ() & ~31) + 16);
        var center = CubePos.from(sensor);
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                for (int y = -1; y <= 1; y++) {
                    ((CubicLevelReader) level).cc_getCube(center.getX() + x, center.getY() + y, center.getZ() + z, ChunkStatus.FULL, true);
                }
            }
        }
        BlockPos min = sensor.offset(-8, -6, -8);
        BlockPos max = sensor.offset(8, 6, 8);
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            boolean shell = pos.getX() == min.getX() || pos.getX() == max.getX() || pos.getZ() == min.getZ() || pos.getZ() == max.getZ()
                    || pos.getY() == min.getY() || pos.getY() == max.getY();
            level.setBlock(pos, (shell ? Blocks.BEDROCK : Blocks.AIR).defaultBlockState(), 2);
        }
        readiness.add(sensor);
        readiness.add(sensor.below(4));
        player.setGameMode(GameType.SPECTATOR);
        player.teleportTo(sensor.getX() + 4.5, sensor.getY() - 4, sensor.getZ() + .5);
    }

    /** Invoke at END_SERVER_TICK. All vibration travel, sensor cooldown and mob reactions run in Minecraft. */
    public boolean tick() {
        if (complete) {
            return true;
        }
        if (++ticks > 1200) {
            throw new IllegalStateException("Game events timed out " + diagnostic());
        }
        if (ticks % 100 == 0) {
            System.out.println("CC_GAME_EVENTS_PROGRESS " + diagnostic());
        }
        int elapsed = ticks - stageStarted;
        switch (stage) {
            case 0 -> {
                if (!readiness.stream().allMatch(level::isPositionEntityTicking) || !CubeGameEvents.adjacentSectionsTicking(level, sensor)) {
                    return false;
                }
                wall(true);
                level.setBlockAndUpdate(sensor, Blocks.SCULK_SENSOR.defaultBlockState());
                sensorEntity = (SculkSensorBlockEntity) level.getBlockEntity(sensor);
                emit(GameEvent.STEP, sensor.below(4));
                advance();
            }
            case 1 -> {
                if (elapsed < 20) {
                    return false;
                }
                require(sensorEntity.getLastVibrationFrequency() == 0, "Wool did not occlude the sensor");
                wall(false);
                emit(GameEvent.STEP, sensor.below(4));
                advance();
            }
            case 2 -> {
                var state = level.getBlockState(sensor);
                activated |= state.getValue(SculkSensorBlock.PHASE) == SculkSensorPhase.ACTIVE && state.getValue(SculkSensorBlock.POWER) > 0;
                if (!activated || state.getValue(SculkSensorBlock.PHASE) != SculkSensorPhase.INACTIVE) {
                    return false;
                }
                require(sensorEntity.getLastVibrationFrequency() == 1, "Sensor received the wrong event frequency");
                System.out.println(
                        "CC_GAME_EVENTS_SENSOR y=" + sensor.getY() + " woolOccluded=true nativeTravel=true nativePower=true scheduledCooldown=true");
                level.setBlockAndUpdate(sensor, Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(sensor.below(4), Blocks.NOTE_BLOCK.defaultBlockState());
                allay = EntityTypes.ALLAY.create(level, EntitySpawnReason.COMMAND);
                require(allay != null, "Allay creation failed");
                allay.snapTo(Vec3.atCenterOf(sensor));
                allay.setNoGravity(true);
                require(level.addFreshEntity(allay), "Allay registration failed");
                advance();
            }
            case 3 -> {
                if (allay.tickCount < 5) {
                    return false;
                }
                emit(GameEvent.NOTE_BLOCK_PLAY, sensor.below(4));
                advance();
            }
            case 4 -> {
                var liked = allay.getBrain().getMemory(MemoryModuleType.LIKED_NOTEBLOCK_POSITION);
                if (liked.isEmpty()) {
                    return false;
                }
                require(liked.get().equals(GlobalPos.of(level.dimension(), sensor.below(4))), "Allay note memory lost full Y");
                System.out
                        .println("CC_GAME_EVENTS_ALLAY y=" + sensor.getY() + " nativeListener=true nativeTravel=true likedNote=" + liked.get().pos());
                allay.discard();
                warden = EntityTypes.WARDEN.create(level, EntitySpawnReason.COMMAND);
                require(warden != null, "Warden creation failed");
                warden.snapTo(Vec3.atBottomCenterOf(sensor.offset(0, -5, 3)));
                warden.setNoGravity(true);
                require(level.addFreshEntity(warden), "Warden registration failed");
                advance();
            }
            case 5 -> {
                if (warden.tickCount < 5) {
                    return false;
                }
                disturbance = warden.blockPosition().offset(4, 0, 0);
                emit(GameEvent.STEP, disturbance);
                advance();
            }
            case 6 -> {
                var remembered = warden.getBrain().getMemory(MemoryModuleType.DISTURBANCE_LOCATION);
                if (!warden.getBrain().hasMemoryValue(MemoryModuleType.VIBRATION_COOLDOWN) || remembered.isEmpty()) {
                    return false;
                }
                require(remembered.get().equals(disturbance), "Warden disturbance lost full Y");
                System.out.println("CC_GAME_EVENTS_WARDEN y=" + sensor.getY() + " nativeListener=true nativeTravel=true cooldown=true disturbance="
                        + remembered.get());
                complete = true;
                System.out.println("CC_GAME_EVENTS_COMPLETE y=" + sensor.getY() + " ticks=" + ticks + " nativeTicks=true testBypass=false");
            }
            default -> throw new IllegalStateException("Unknown game-event stage " + stage);
        }
        return complete;
    }

    private void wall(boolean wool) {
        for (BlockPos pos : BlockPos.betweenClosed(sensor.offset(-1, -2, -1), sensor.offset(1, -2, 1))) {
            level.setBlock(pos, (wool ? Blocks.WOOL.white() : Blocks.AIR).defaultBlockState(), 2);
        }
    }

    private void emit(Holder<GameEvent> event, BlockPos pos) {
        level.gameEvent(event, Vec3.atCenterOf(pos), GameEvent.Context.of((net.minecraft.world.entity.Entity) null));
    }

    private void advance() {
        stage++;
        stageStarted = ticks;
    }

    private String diagnostic() {
        return "y=" + sensor.getY() + " stage=" + stage + " ticks=" + ticks + " activated=" + activated;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    @Override public void close() {
        if (allay != null) {
            allay.discard();
        }
        if (warden != null) {
            warden.discard();
        }
        player.setGameMode(originalMode);
    }
}
