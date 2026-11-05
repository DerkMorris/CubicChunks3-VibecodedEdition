package io.github.opencubicchunks.cubicchunks.world.level.entity;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.PotentialCalculator;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.phys.Vec3;

/** Per-tick cubic caps: one vanilla category allowance per player, shared by every vertical layer near that player. */
public final class CubeSpawnState {
    public static final int RANGE = 128;
    private final ServerLevel level;
    private final List<PlayerCount> players;
    private final Map<MobCategory, Integer> totals = new EnumMap<>(MobCategory.class);
    private final PotentialCalculator potential = new PotentialCalculator();

    public CubeSpawnState(ServerLevel level) {
        this(level, true);
    }

    CubeSpawnState(ServerLevel level, boolean countPopulation) {
        this.level = level;
        players = level.players().stream().filter(player -> !player.isSpectator() && !player.isRemoved() && player.level() == level)
                .map(PlayerCount::new).toList();
        if (countPopulation) {
            for (Entity entity : level.getAllEntities()) {
                count(entity);
            }
        }
    }

    public List<Vec3> playerPositions() {
        return players.stream().map(player -> player.pos).toList();
    }

    public double nearestDistance(BlockPos pos) {
        Vec3 center = Vec3.atBottomCenterOf(pos);
        return players.stream().mapToDouble(player -> player.pos.distanceToSqr(center)).min().orElse(Double.POSITIVE_INFINITY);
    }

    public boolean canSpawn(MobCategory category, BlockPos pos) {
        int cap = category.getMaxInstancesPerChunk();
        if (category == MobCategory.MISC || totals.getOrDefault(category, 0) >= (long) cap * players.size()) {
            return false;
        }
        Vec3 center = Vec3.atBottomCenterOf(pos);
        return players.stream().anyMatch(player -> player.near(center) && player.counts.getOrDefault(category, 0) < cap);
    }

    public boolean withinCost(EntityType<?> type, BlockPos pos) {
        var cost = settings(pos).getMobSpawnCost(type);
        return cost == null || potential.getPotentialEnergyChange(pos, cost.charge()) <= cost.energyBudget();
    }

    public MobSpawnSettings settings(BlockPos pos) {
        return level.environmentAttributes().getValue(EnvironmentAttributes.NATURAL_MOB_SPAWNS, pos);
    }

    public int count(MobCategory category) {
        return totals.getOrDefault(category, 0);
    }

    /** Called for each successfully admitted entity, including jockey passengers. Persistent mobs do not consume natural caps. */
    public void count(Entity entity) {
        if (entity.isRemoved() || entity instanceof Mob mob && (mob.isPersistenceRequired() || mob.requiresCustomPersistence())) {
            return;
        }
        MobCategory category = entity.getType().getCategory();
        if (category == MobCategory.MISC) {
            return;
        }
        totals.merge(category, 1, Integer::sum);
        for (PlayerCount player : players) {
            if (player.near(entity.position())) {
                player.counts.merge(category, 1, Integer::sum);
            }
        }
        if (CubeMobBlockView.loadedCube(level, entity.blockPosition()) != null) {
            var cost = settings(entity.blockPosition()).getMobSpawnCost(entity.getType());
            if (cost != null) {
                potential.addCharge(entity.blockPosition().immutable(), cost.charge());
            }
        }
    }

    private static final class PlayerCount {
        private final Vec3 pos;
        private final Map<MobCategory, Integer> counts = new EnumMap<>(MobCategory.class);

        private PlayerCount(ServerPlayer player) {
            pos = player.position();
        }

        private boolean near(Vec3 position) {
            return pos.distanceToSqr(position) < RANGE * RANGE;
        }
    }
}
