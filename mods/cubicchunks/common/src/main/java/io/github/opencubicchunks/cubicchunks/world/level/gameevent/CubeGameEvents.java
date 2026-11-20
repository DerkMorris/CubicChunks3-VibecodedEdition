package io.github.opencubicchunks.cubicchunks.world.level.gameevent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubeMobBlockView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.debug.DebugGameEventInfo;
import net.minecraft.util.debug.DebugSubscriptions;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.GameEventListener;
import net.minecraft.world.level.gameevent.GameEventListenerRegistry;
import net.minecraft.world.phys.Vec3;

/** Native registry range filtering and delivery order, with full XYZ section ownership and non-loading cube reads. */
public final class CubeGameEvents {
    private CubeGameEvents() {}

    public static void post(ServerLevel level, Holder<GameEvent> event, Vec3 source, GameEvent.Context context) {
        BlockPos center = BlockPos.containing(source);
        if (!CubicBlockBounds.contains(center)) {
            return;
        }
        int radius = event.value().notificationRadius();
        List<GameEvent.ListenerInfo> ordered = new ArrayList<>();
        GameEventListenerRegistry.ListenerVisitor visitor = (listener, position) -> {
            if (listener.getDeliveryMode() == GameEventListener.DeliveryMode.BY_DISTANCE) {
                ordered.add(new GameEvent.ListenerInfo(event, source, context, listener, position));
            } else {
                listener.handleGameEvent(level, event, context, source);
            }
        };
        boolean delivered = visit(level, event, source, context, center, radius, visitor);
        Collections.sort(ordered);
        for (var message : ordered) {
            message.recipient().handleGameEvent(level, message.gameEvent(), message.context(), message.source());
        }
        if (delivered) {
            level.debugSynchronizers().broadcastEventToTracking(center, DebugSubscriptions.GAME_EVENTS, new DebugGameEventInfo(event, source));
        }
    }

    private static boolean visit(
            ServerLevel level, Holder<GameEvent> event, Vec3 source, GameEvent.Context context, BlockPos center, int radius,
            GameEventListenerRegistry.ListenerVisitor visitor
    ) {
        boolean delivered = false;
        for (int x = section((long) center.getX() - radius); x <= section((long) center.getX() + radius); x++) {
            for (int z = section((long) center.getZ() - radius); z <= section((long) center.getZ() + radius); z++) {
                for (int y = section((long) center.getY() - radius); y <= section((long) center.getY() + radius); y++) {
                    BlockPos origin = SectionPos.of(x, y, z).origin();
                    var cube = CubeMobBlockView.loadedCube(level, origin);
                    if (cube != null) {
                        delivered |= cube.cc_getListenerRegistry(origin).visitInRangeListeners(event, source, context, visitor);
                    }
                }
            }
        }
        return delivered;
    }

    private static int section(long coordinate) {
        return SectionPos.blockToSectionCoord((int) Math.clamp(coordinate, CubicBlockBounds.MIN, CubicBlockBounds.MAX));
    }

    /** The native neighboring-column requirement extends to neighboring sections on all three axes in a cubic world. */
    public static boolean adjacentSectionsTicking(Level level, BlockPos position) {
        if (!CubicBlockBounds.contains(position)) {
            return false;
        }
        SectionPos center = SectionPos.of(position);
        for (int x = center.x() - 1; x <= center.x() + 1; x++) {
            for (int z = center.z() - 1; z <= center.z() + 1; z++) {
                for (int y = center.y() - 1; y <= center.y() + 1; y++) {
                    BlockPos origin = SectionPos.of(x, y, z).origin();
                    // Sections outside the finite cubic world cannot load and cannot contain an occluding block.
                    if (!CubicBlockBounds.contains(origin)) {
                        continue;
                    }
                    if (CubeMobBlockView.loadedCube(level, origin) == null || !level.shouldTickBlocksAt(origin)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }
}
