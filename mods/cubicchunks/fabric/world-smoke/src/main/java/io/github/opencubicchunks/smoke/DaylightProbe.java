package io.github.opencubicchunks.smoke;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.phys.Vec3;

final class DaylightProbe {
    private Enderman mob;
    private Vec3 previous;
    private boolean started;
    private volatile Vec3 destination;
    private volatile int id;
    private int waited;

    DaylightProbe() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (mob == null) { return; }
            var level = server.overworld();
            if (mob.tickCount % 100 == 0) {
                System.out.println("CC_DAYLIGHT age=" + mob.tickCount + " brightOutside=" + level.isBrightOutside()
                        + " eyeMagic=" + mob.getLightLevelDependentMagicValue() + " sky=" + level.canSeeSky(mob.blockPosition())
                        + " noAI=" + mob.isNoAi() + " destination=" + destination + " pos=" + mob.position() + " levelEye=" + level.getLightLevelDependentMagicValue(BlockPos.containing(mob.getX(), mob.getEyeY(), mob.getZ())));
            }
            if (destination == null && previous != null && mob.position().distanceTo(previous) > 5 && mob.tickCount >= 600) {
                destination = mob.position();
                System.out.println("CC_DAYLIGHT_JUMP from=" + previous + " to=" + destination);
                server.getPlayerList().getPlayers().getFirst().teleportTo(destination.x + 3, destination.y, destination.z + 3);
                mob.setNoAi(true);
            }
            previous = mob.position();
        });
    }

    void tick(Minecraft client) {
        if (!started) {
            started = true;
            var server = client.getSingleplayerServer();
            server.execute(() -> {
                var level = server.overworld();
                var source = level.getChunkSource();
                int y = source.getGenerator().getBaseHeight(8, 8, net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG,
                        level, source.randomState());
                mob = EntityTypes.ENDERMAN.spawn(level, new BlockPos(8, y, 8), EntitySpawnReason.SPAWN_ITEM_USE);
                mob.setPersistenceRequired();
                id = mob.getId();
            });
        }
        var target = destination;
        var visible = client.level.getEntity(id);
        if (target != null && visible != null && visible.position().distanceTo(target) < 1) {
            System.out.println("CC_DAYLIGHT_SUCCESS client=" + visible.position() + " server=" + target);
            client.stop();
        }
        if (++waited > 2000) { throw new IllegalStateException("No synchronized daylight teleport"); }
    }
}


