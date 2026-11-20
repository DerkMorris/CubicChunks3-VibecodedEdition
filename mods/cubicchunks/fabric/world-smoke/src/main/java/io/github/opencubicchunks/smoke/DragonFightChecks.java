package io.github.opencubicchunks.smoke;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.entity.projectile.hurtingprojectile.DragonFireball;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Called on the server thread, once per game tick, after a real player has entered the normal End. */
final class DragonFightChecks {
    private final ServerLevel level;
    private final EnderDragonFight fight;
    private EnderDragon dragon;
    private IronGolem target;
    private UUID originalDragon;
    private Vec3 initialPosition;
    private final List<EndCrystal> respawnCrystals = new ArrayList<>();
    private long lastTime = Long.MIN_VALUE;
    private int ticks;
    private int stage;
    private int stageTicks;
    private boolean fireball;
    private boolean cloud;
    private boolean done;

    DragonFightChecks(ServerLevel level) {
        this.level = level;
        require(level.dimension() == Level.END && !((CanBeCubic) level).cc_isCubic(), "Dragon fight must run in the native End dimension");
        fight = level.getDragonFight();
        require(fight != null, "End is missing its native fight manager");
    }

    boolean tick() {
        if (done || lastTime == level.getGameTime())
            return done;
        lastTime = level.getGameTime();
        ticks++;
        stageTicks++;
        if (ticks % 100 == 0)
            System.out.println(
                    "CC_DRAGON_PROGRESS ticks=" + ticks + " stage=" + stage + " dragons=" + level.getDragons().size() + " state=" + dragonState());
        require(ticks < 1800, "Native End dragon lifecycle timed out in stage " + stage + " state=" + dragonState());
        ServerPlayer player = level.players().getFirst();
        if (stage == 0) {
            if (level.getDragons().isEmpty())
                return false;
            dragon = level.getDragons().getFirst();
            require(dragon.getUUID().equals(fight.dragonUUID()), "Spawned dragon is not linked to the End fight");
            originalDragon = dragon.getUUID();
            initialPosition = dragon.position();
            next();
        } else if (stage == 1) {
            // The newly entered End needs to promote the dragon's chunk to entity-ticking first.
            // Count actual dragon ticks; a fixed world-tick delay races that promotion.
            if (stageTicks < 40 || dragon.tickCount < 40)
                return false;
            require(dragon.position().distanceTo(initialPosition) > 1, "Native dragon AI did not fly: " + dragonState());
            for (var part : dragon.getSubEntities())
                require(part.position().distanceTo(dragon.position()) < 25, "Dragon multipart position desynchronized");
            dragon.snapTo(0, 100, 0, 180, 0);
            dragon.setDeltaMovement(Vec3.ZERO);
            target = EntityTypes.IRON_GOLEM.create(level, EntitySpawnReason.TRIGGERED);
            require(target != null, "Dragon target creation failed");
            target.snapTo(0, 100, 24);
            target.setNoAi(true);
            target.setNoGravity(true);
            target.setPersistenceRequired();
            level.addFreshEntity(target);
            dragon.getPhaseManager().setPhase(EnderDragonPhase.STRAFE_PLAYER);
            dragon.getPhaseManager().getPhase(EnderDragonPhase.STRAFE_PLAYER).setTarget(target);
            next();
        } else if (stage == 2) {
            AABB arena = new AABB(-100, 0, -100, 100, 200, 100);
            fireball |= !level.getEntitiesOfClass(DragonFireball.class, arena).isEmpty();
            cloud |= !level.getEntitiesOfClass(AreaEffectCloud.class, arena).isEmpty();
            if (!fireball || !cloud || target.getHealth() == target.getMaxHealth())
                return false;
            target.discard();
            dragon.getPhaseManager().setPhase(EnderDragonPhase.HOVERING);
            float before = dragon.getHealth();
            require(dragon.hurt(level, dragon.head, level.damageSources().playerAttack(player), 20) && dragon.getHealth() < before,
                    "Dragon multipart player damage did not reach its health");
            next();
        } else if (stage == 3) {
            if (stageTicks < 25)
                return false;
            dragon.getPhaseManager().setPhase(EnderDragonPhase.SITTING_SCANNING);
            require(dragon.hurt(level, dragon.head, level.damageSources().playerAttack(player), Float.MAX_VALUE), "Dragon killing blow was rejected");
            require(dragon.getHealth() == 0, "Sitting dragon did not enter the native death sequence");
            next();
        } else if (stage == 4) {
            if (!dragon.isRemoved())
                return false;
            require(dragon.dragonDeathTime >= 200 && fight.hasPreviouslyKilledDragon(), "Dragon death failed to finish its fight");
            CompoundTag saved = (CompoundTag) EnderDragonFight.CODEC.encodeStart(NbtOps.INSTANCE, fight).getOrThrow();
            require(saved.equals(EnderDragonFight.CODEC
                    .encodeStart(NbtOps.INSTANCE, EnderDragonFight.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow()).getOrThrow()),
                    "Completed End fight did not preserve native saved state");
            BlockPos portal = saved.read("exit_portal_location", BlockPos.CODEC).orElseThrow();
            boolean activePortal = false;
            for (BlockPos pos : BlockPos.betweenClosed(portal.offset(-3, 0, -3), portal.offset(3, 1, 3))) {
                activePortal |= level.getBlockState(pos).is(Blocks.END_PORTAL);
            }
            require(activePortal, "Dragon death did not activate the exit portal");
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos pos = portal.above().relative(direction, 3);
                EndCrystal crystal = EntityTypes.END_CRYSTAL.create(level, EntitySpawnReason.TRIGGERED);
                require(crystal != null, "Respawn crystal creation failed");
                crystal.snapTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
                level.addFreshEntity(crystal);
                respawnCrystals.add(crystal);
            }
            fight.tryRespawn();
            next();
        } else if (stage == 5) {
            EnderDragon replacement = level.getDragons().stream().filter(entity -> !entity.getUUID().equals(originalDragon)).findFirst().orElse(null);
            if (replacement == null || replacement.tickCount < 20)
                return false;
            require(replacement.getUUID().equals(fight.dragonUUID()), "Respawned dragon UUID is not linked to its fight");
            require(respawnCrystals.stream().allMatch(EndCrystal::isRemoved), "Respawn did not consume the four crystals");
            System.out.println("CC_DRAGON_COMPLETE nativeEnd=true flight=true multipart=true strafeFireball=true breathDamage=true playerDamage=true"
                    + " deathTicks=" + dragon.dragonDeathTime + " exitPortal=true fightPersistence=true crystalRespawn=true ticks=" + ticks);
            done = true;
        }
        return done;
    }

    private void next() {
        stage++;
        stageTicks = 0;
    }

    private String dragonState() {
        return dragon == null ? "notSpawned"
                : "entityTicks=" + dragon.tickCount + " position=" + dragon.position() + " phase="
                        + dragon.getPhaseManager().getCurrentPhase().getPhase() + " entityTicking="
                        + level.isPositionEntityTicking(dragon.blockPosition());
    }

    private static void require(boolean condition, String message) {
        if (!condition)
            throw new IllegalStateException(message);
    }
}
