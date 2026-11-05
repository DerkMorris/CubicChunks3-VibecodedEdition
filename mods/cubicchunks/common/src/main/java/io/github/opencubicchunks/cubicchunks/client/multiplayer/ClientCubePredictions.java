package io.github.opencubicchunks.cubicchunks.client.multiplayer;

import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundCubeBlockChangesPacket;
import io.github.opencubicchunks.cubicchunks.network.CubeBlockEntityUpdate;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Per-world speculative states keyed by immutable coordinates, retaining vanilla cumulative sequence semantics. */

public final class ClientCubePredictions {
    private final Map<BlockPos, Pending> pending = new HashMap<>();

    public void retain(ClientLevel level, BlockPos pos, BlockState state, @Nullable CubeBlockEntityUpdate entity, LocalPlayer player, int sequence) {
        LevelCube cube = loadedCube(level, pos);
        if (cube == null) {
            return;
        }
        Pending previous = pending.get(pos);
        if (previous != null && previous.cube == cube) {
            previous.sequence = sequence;
        } else {
            pending.put(pos.immutable(), new Pending(cube, sequence, state, entity, player.position()));
        }
    }

    /** Keep authoritative data until the latest prediction for this block is acknowledged. */
    public boolean update(LevelCube cube, BlockPos pos, BlockState state, @Nullable CubeBlockEntityUpdate blockEntity) {
        Pending previous = pending.get(pos);
        if (previous == null) {
            return false;
        }
        if (previous.cube != cube) {
            pending.remove(pos);
            return false;
        }
        previous.state = state;
        previous.blockEntity = blockEntity;
        return true;
    }

    public void acknowledge(ClientLevel level, @Nullable LocalPlayer player, int sequence, int lastTeleportSequence) {
        var iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            Pending prediction = entry.getValue();
            if (prediction.sequence <= sequence) {
                iterator.remove();
                reconcile(level, player, entry.getKey(), prediction, lastTeleportSequence < sequence);
            }
        }
    }

    private static void reconcile(ClientLevel level, @Nullable LocalPlayer player, BlockPos pos, Pending prediction, boolean canMovePlayer) {
        if (loadedCube(level, pos) != prediction.cube) {
            return;
        }
        boolean changed = ClientCubeBlockChanges.applyState(level, prediction.cube, pos, prediction.state, prediction.blockEntity);
        if (changed && canMovePlayer && player != null && player.level() == level && player.isColliding(pos, prediction.state)) {
            Vec3 original = prediction.playerPos;
            player.absSnapTo(original.x(), original.y(), original.z());
        }
    }

    public void forget(CubePos pos) {
        pending.keySet().removeIf(block -> CubePos.from(block).equals(pos));
    }

    /** Full relight packets may arrive between a local action and its acknowledgement. Retain the visible speculative states. */
    public Snapshot beforeFullSnapshot(CubePos pos) {
        Map<BlockPos, Predicted> captured = new HashMap<>();
        pending.forEach((at, prediction) -> {
            if (CubePos.from(at).equals(pos)) {
                BlockState state = prediction.cube.getBlockState(at);
                captured.put(at,
                        new Predicted(prediction, state, CCClientboundCubeBlockChangesPacket.captureBlockEntity(prediction.cube, at, state)));
            }
        });
        return new Snapshot(Map.copyOf(captured));
    }

    /** Run after the packet's light callback has captured its revision; replay must invalidate light for the older block snapshot. */
    public void afterFullSnapshot(ClientLevel level, LevelCube cube, Snapshot snapshot) {
        snapshot.entries.forEach((pos, predicted) -> {
            Pending prediction = predicted.pending;
            if (prediction.cube == cube && loadedCube(level, pos) == cube) {
                prediction.state = cube.getBlockState(pos);
                prediction.blockEntity = CCClientboundCubeBlockChangesPacket.captureBlockEntity(cube, pos, prediction.state);
                pending.put(pos, prediction);
                ClientCubeBlockChanges.applyState(level, cube, pos, predicted.state, predicted.entity);
            }
        });
    }

    private static @Nullable LevelCube loadedCube(ClientLevel level, BlockPos pos) {
        CubePos cube = CubePos.from(pos);
        return ((ClientCubeCache) level.getChunkSource()).cc_getCube(cube.getX(), cube.getY(), cube.getZ(), false);
    }

    public static final class Snapshot {
        private final Map<BlockPos, Predicted> entries;

        private Snapshot(Map<BlockPos, Predicted> entries) {
            this.entries = entries;
        }
    }

    private record Predicted(Pending pending, BlockState state, @Nullable CubeBlockEntityUpdate entity) {}

    private static final class Pending {
        private final LevelCube cube;
        private final Vec3 playerPos;
        private int sequence;
        private BlockState state;
        @Nullable private CubeBlockEntityUpdate blockEntity;

        private Pending(LevelCube cube, int sequence, BlockState state, @Nullable CubeBlockEntityUpdate entity, Vec3 playerPos) {
            this.cube = cube;
            this.sequence = sequence;
            this.state = state;
            this.blockEntity = entity;
            this.playerPos = playerPos;
        }
    }
}
