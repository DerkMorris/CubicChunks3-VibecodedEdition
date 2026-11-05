package io.github.opencubicchunks.cubicchunks.world.level.levelgen.structure;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.mojang.logging.LogUtils;
import io.github.opencubicchunks.cubicchunks.mixin.access.common.EntityManagerAccess;
import io.github.opencubicchunks.cubicchunks.mixin.access.common.ServerLevelEntityAccess;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.BlockAttachedEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureEntityInfo;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/** Structure snapshots of resident entities. This does not supply cube entity disk storage or ticking. */
public final class CubeTemplateEntities {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_ENTITIES = 1024;
    private static final int MAX_DEPTH = 32;

    private CubeTemplateEntities() {}

    public static List<StructureEntityInfo> capture(ServerLevel level, BlockPos min, BlockPos max) {
        try (var reporter = new ProblemReporter.ScopedCollector(LOGGER)) {
            return capture(level, min, max, reporter);
        }
    }

    private static List<StructureEntityInfo> capture(ServerLevel level, BlockPos min, BlockPos max, ProblemReporter reporter) {
        AABB box = AABB.encapsulatingFullBlocks(min, max);
        var manager = ((ServerLevelEntityAccess) level).cc_entityManager();
        var sections = ((EntityManagerAccess) manager).cc_sectionStorage();
        Set<Entity> selected = new HashSet<>();
        // Iterate resident sections without decoding their packed Y. Full bounding boxes distinguish aliased heights.
        sections.getAllChunksWithExistingSections().forEach((long key) -> sections.getExistingSectionsInChunk(key)
                .forEach(section -> section.getEntities().filter(entity -> eligible(entity, level, box)).forEach(selected::add)));
        require(selected.size() <= MAX_ENTITIES, "Too many entities in structure");
        List<StructureEntityInfo> result = new ArrayList<>();
        Budget budget = new Budget();
        for (Entity entity : selected) {
            if (!selected.contains(entity.getVehicle())) {
                // saveAsPassenger recursively visits even descendants outside the selection before we prune them.
                validateSaveTree(entity, budget, 0);
                Vec3 relative = entity.position().subtract(Vec3.atLowerCornerOf(min));
                BlockPos anchor = entity instanceof BlockAttachedEntity attached ? attached.getPos().subtract(min) : BlockPos.containing(relative);
                result.add(new StructureEntityInfo(relative, anchor, saveTree(entity, selected, reporter, 0)));
            }
        }
        return result;
    }

    private static void validateSaveTree(Entity entity, Budget budget, int depth) {
        require(depth < MAX_DEPTH && ++budget.count <= MAX_ENTITIES, "Structure passenger tree exceeds serialization limits");
        for (Entity passenger : entity.getPassengers()) {
            validateSaveTree(passenger, budget, depth + 1);
        }
    }

    private static boolean eligible(Entity entity, ServerLevel level, AABB box) {
        return entity.level() == level && !(entity instanceof Player) && !entity.isRemoved() && entity.getType().canSerialize()
                && entity.getBoundingBox().intersects(box);
    }

    private static CompoundTag saveTree(Entity entity, Set<Entity> selected, ProblemReporter reporter, int depth) {
        require(depth < MAX_DEPTH, "Structure passenger tree is too deep");
        TagValueOutput output = TagValueOutput.createWithContext(reporter, entity.registryAccess());
        // Vanilla writes full entity state, including equipment and components. Replace its unfiltered passenger list below.
        require(entity.saveAsPassenger(output), "Entity refused structure serialization");
        CompoundTag tag = output.buildResult();
        tag.store("Pos", Vec3.CODEC, entity.position());
        ListTag passengers = new ListTag();
        for (Entity passenger : entity.getPassengers()) {
            if (selected.contains(passenger)) {
                passengers.add(saveTree(passenger, selected, reporter, depth + 1));
            }
        }
        tag.remove("Passengers");
        if (!passengers.isEmpty()) {
            tag.put("Passengers", passengers);
        }
        return tag;
    }

    public static Optional<Placement> prepare(ServerLevel level, List<StructureEntityInfo> infos, BlockPos origin, StructurePlaceSettings settings) {
        List<Entity> roots = new ArrayList<>();
        try (var reporter = new ProblemReporter.ScopedCollector(LOGGER)) {
            Budget budget = new Budget();
            for (StructureEntityInfo info : infos) {
                BlockPos anchor = StructureTemplate.calculateRelativePosition(settings, info.blockPos).offset(origin);
                if (settings.getBoundingBox() == null || settings.getBoundingBox().isInside(anchor)) {
                    Vec3 savedPos = info.nbt.read("Pos", Vec3.CODEC).orElse(Vec3.ZERO);
                    Vec3 offset = info.pos.subtract(savedPos);
                    roots.add(loadTree(level, info.nbt, offset, origin, anchor, settings, reporter, budget, 0));
                }
            }
            return Optional.of(new Placement(level, roots, settings.shouldFinalizeEntities()));
        } catch (RuntimeException failure) {
            LOGGER.warn("Cannot prepare cubic structure entities: {}", failure.getMessage());
            return Optional.empty();
        }
    }

    private static Entity loadTree(
            ServerLevel level, CompoundTag saved, Vec3 offset, BlockPos origin, BlockPos rootAnchor, StructurePlaceSettings settings,
            ProblemReporter reporter, Budget budget, int depth
    ) {
        require(depth < MAX_DEPTH && ++budget.count <= MAX_ENTITIES, "Structure entity budget exceeded");
        CompoundTag tag = saved.copy();
        Vec3 relative = saved.read("Pos", Vec3.CODEC).orElse(Vec3.ZERO).add(offset);
        Vec3 position = StructureTemplate.transform(relative, settings.getMirror(), settings.getRotation(), settings.getRotationPivot())
                .add(Vec3.atLowerCornerOf(origin));
        require(valid(position), "Invalid structure entity position");
        tag.store("Pos", Vec3.CODEC, position);
        tag.remove("UUID");
        tag.remove("Passengers");
        relocateAnchor(tag, offset, origin, rootAnchor, settings, depth);
        Entity entity = EntityType
                .create(TagValueInput.create(reporter, level.registryAccess(), tag), level,
                        new EntitySpawnRequest(EntitySpawnReason.STRUCTURE, false))
                .orElseThrow(() -> new IllegalArgumentException("Unknown entity type"));
        require(!(entity instanceof Player), "Players cannot be placed from structures");
        orient(entity, position, settings);
        require(validBounds(entity.getBoundingBox()) && CubeTemplateArea.isEntityAreaReady(level, entity.getBoundingBox()),
                "Structure entity needs loaded cubes within supported bounds");
        for (var value : saved.getListOrEmpty("Passengers")) {
            require(value instanceof CompoundTag, "Invalid structure passenger");
            Entity passenger = loadTree(level, (CompoundTag) value, offset, origin, rootAnchor, settings, reporter, budget, depth + 1);
            require(passenger.startRiding(entity, true, false), "Cannot attach structure passenger");
        }
        return entity;
    }

    private static void orient(Entity entity, Vec3 position, StructurePlaceSettings settings) {
        float yaw = entity.rotate(settings.getRotation());
        yaw += entity.mirror(settings.getMirror()) - entity.getYRot();
        if (entity instanceof BlockAttachedEntity attached) {
            // Painting.snapTo floors its visual center into a new anchor. Even-sized paintings would shift a block.
            // Rebuild the specialized bounding box from the transformed anchor instead (also needed for item frames).
            attached.setPos(Vec3.atLowerCornerOf(attached.getPos()));
            attached.setOldPosAndRot();
        } else {
            entity.snapTo(position, yaw, entity.getXRot());
        }
        entity.setYBodyRot(yaw);
        entity.setYHeadRot(yaw);
    }

    private static void relocateAnchor(
            CompoundTag tag, Vec3 offset, BlockPos origin, BlockPos rootAnchor, StructurePlaceSettings settings, int depth
    ) {
        if (!tag.contains("block_pos")) {
            return;
        }
        BlockPos savedAnchor = tag.read("block_pos", BlockPos.CODEC).orElseThrow();
        BlockPos anchor = depth == 0 ? rootAnchor
                : StructureTemplate.calculateRelativePosition(settings, BlockPos.containing(Vec3.atLowerCornerOf(savedAnchor).add(offset)))
                        .offset(origin);
        require(CubicBlockBounds.contains(anchor), "Invalid structure entity anchor");
        tag.store("block_pos", BlockPos.CODEC, anchor);
    }

    private static boolean valid(Vec3 pos) {
        return validCoordinate(pos.x) && validCoordinate(pos.y) && validCoordinate(pos.z);
    }

    private static boolean validCoordinate(double value) {
        return Double.isFinite(value) && value >= CubicBlockBounds.MIN && value < (double) CubicBlockBounds.MAX + 1;
    }

    private static boolean validBounds(AABB box) {
        return valid(new Vec3(box.minX, box.minY, box.minZ))
                && valid(new Vec3(Math.nextDown(box.maxX), Math.nextDown(box.maxY), Math.nextDown(box.maxZ)));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new InvalidSnapshotException(message);
        }
    }

    public static final class InvalidSnapshotException extends IllegalArgumentException {
        private InvalidSnapshotException(String message) {
            super(message);
        }
    }

    private static final class Budget {
        private int count;
    }

    public static final class Placement {
        private final ServerLevel level;
        private final List<Entity> roots;
        private final boolean finalizeEntities;
        private boolean successful = true;

        private Placement(ServerLevel level, List<Entity> roots, boolean finalizeEntities) {
            this.level = level;
            this.roots = roots;
            this.finalizeEntities = finalizeEntities;
        }

        public void place() {
            for (Entity root : roots) {
                if (finalizeEntities) {
                    root.getSelfAndPassengers().filter(entity -> entity instanceof Mob).map(entity -> (Mob) entity).forEach(
                            mob -> mob.finalizeSpawn(level, level.getCurrentDifficultyAt(mob.blockPosition()), EntitySpawnReason.STRUCTURE, null));
                }
                // Use the real manager/UUID checks and callbacks; no detached entity-only side store.
                successful &= level.tryAddFreshEntityWithPassengers(root);
            }
        }

        public boolean successful() {
            return successful;
        }
    }
}
