package io.github.opencubicchunks.smoke;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.consume_effects.TeleportRandomlyConsumeEffect;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Isolated entity checks sharing the gameplay room, driven only by ordinary server ticks. */
final class GameplayEntityChecks {
    private final ServerLevel level;
    private final int height;
    private final Mob teleported;
    private final FallingBlockEntity suspended;
    private final BlockPos source;
    private boolean complete;

    GameplayEntityChecks(ServerLevel level, int height) {
        this.level = level;
        this.height = height;
        teleported = EntityTypes.PIG.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (teleported == null) { throw new IllegalStateException("Could not create gameplay teleport subject"); }
        teleported.setNoAi(true);
        teleported.setPersistenceRequired();
        teleported.snapTo(27.5, height - 2, 10.5);
        if (!level.addFreshEntity(teleported)) { throw new IllegalStateException("Could not register gameplay teleport subject"); }
        if (!teleported.randomTeleport(36.5, height + 1, 12.5, true, state -> false)
                || !teleported.position().equals(new Vec3(36.5, height - 2, 12.5))) {
            throw new IllegalStateException("Native living-entity random teleport failed at height " + height);
        }
        // A zero-diameter effect isolates the consume path's height clamp without random room exits.
        if (!new TeleportRandomlyConsumeEffect(0, false).apply(level, new ItemStack(Items.CHORUS_FRUIT), teleported)
                || !teleported.position().equals(new Vec3(36.5, height - 2, 12.5))) {
            throw new IllegalStateException("Native consume teleport effect failed at height " + height);
        }

        suspended = EntityTypes.FALLING_BLOCK.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (suspended == null) { throw new IllegalStateException("Could not create gameplay falling entity"); }
        suspended.snapTo(29.5, height + 1, 12.5);
        suspended.setNoGravity(true);
        suspended.setDeltaMovement(Vec3.ZERO);
        suspended.dropItem = false;
        if (!level.addFreshEntity(suspended)) { throw new IllegalStateException("Could not register gameplay falling entity"); }
        source = new BlockPos(26, height + 1, 11);
        // onPlace schedules the real block tick; falling and landing are never invoked by the probe.
        level.setBlockAndUpdate(source, Blocks.SAND.defaultBlockState());
    }

    boolean capture(int ticks) {
        if (complete) { return true; }
        if (ticks < 110) { return false; }
        if (suspended.isRemoved()) {
            throw new IllegalStateException("Falling entity did not survive 100 real ticks at height " + height
                    + ": removed=" + suspended.isRemoved() + " age=" + suspended.time);
        }
        // Block ticking may start before entity ticking while the new cube becomes active.
        // The enclosing scene timeout still fails entities that never progress.
        if (suspended.time <= 100) { return false; }
        if (!level.getBlockState(source).isAir() || !level.getBlockState(new BlockPos(26, height - 2, 11)).is(Blocks.SAND)) {
            return false;
        }
        complete = true;
        System.out.println("CC_GAMEPLAY_ENTITIES height=" + height
                + " genericTeleport=true consumeTeleportEffect=true fallingSourceAndLanding=true aliveAfter100Ticks=true entityTicks=" + suspended.time);
        return true;
    }

    void close() {
        teleported.discard();
        suspended.discard();
    }
}
