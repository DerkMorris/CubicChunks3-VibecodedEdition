package io.github.opencubicchunks.cubicchunks.world.level.entity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import net.minecraft.SharedConstants;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

/** Strict, versioned snapshots. Passenger trees belong to the root's cube and retain their UUIDs. */
public final class CubeEntityData {
    private static final String FORMAT = "cubicchunks:entities";
    private static final int VERSION = 1;
    private static final int MAX_ENTITIES = 16384;
    private static final int MAX_DEPTH = 32;

    private CubeEntityData() {}

    public static CompoundTag save(CubePos pos, List<Entity> roots) {
        CompoundTag result = new CompoundTag();
        result.putString("Format", FORMAT);
        result.putInt("EntityFormat", VERSION);
        result.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        result.putIntArray("Position", new int[] { pos.getX(), pos.getY(), pos.getZ() });
        Budget budget = new Budget();
        // Check the complete graph before native saveAsPassenger recursively walks it.
        roots.forEach(entity -> validateLive(entity, budget, 0));
        ListTag list = new ListTag();
        for (Entity root : roots) {
            require(CubePos.from(root.blockPosition()).equals(pos), "Entity root belongs to another cube");
            list.add(saveTree(root));
        }
        result.put("Entities", list);
        return result;
    }

    private static void validateLive(Entity entity, Budget budget, int depth) {
        budget.visit(depth);
        require(valid(entity.position()), "Entity position outside cubic bounds");
        require(budget.uuids.add(entity.getUUID()), "Repeated entity UUID in cube");
        for (Entity child : entity.getPassengers()) {
            validateLive(child, budget, depth + 1);
        }
    }

    private static CompoundTag saveTree(Entity entity) {
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, entity.registryAccess());
        require(entity.saveAsPassenger(output), "Entity refused serialization");
        CompoundTag tag = output.buildResult();
        // Native passenger saving substitutes its vehicle's X/Z. Preserve its actual coordinates instead.
        tag.store("Pos", Vec3.CODEC, entity.position());
        tag.remove("Passengers");
        ListTag passengers = new ListTag();
        for (Entity child : entity.getPassengers()) {
            if (!(child instanceof Player) && child.getType().canSerialize() && !child.isRemoved()) {
                passengers.add(saveTree(child));
            }
        }
        if (!passengers.isEmpty()) {
            tag.put("Passengers", passengers);
        }
        return tag;
    }

    public static List<Entity> load(ServerLevel level, CubePos pos, CompoundTag tag) {
        require(FORMAT.equals(tag.getString("Format").orElse(null)), "Not a cube entity file");
        require(tag.getIntOr("EntityFormat", -1) == VERSION, "Unsupported cube entity format");
        require(tag.getIntOr("DataVersion", -1) == SharedConstants.getCurrentVersion().dataVersion().version(), "Unsupported entity DataVersion");
        int[] coordinates = tag.getIntArray("Position").orElseThrow();
        require(java.util.Arrays.equals(coordinates, new int[] { pos.getX(), pos.getY(), pos.getZ() }), "Wrong cube entity file position");
        ListTag list = tag.getList("Entities").orElseThrow();
        Budget budget = new Budget();
        // Validate every root and descendant before constructing or registering any entity.
        for (Tag value : list) {
            CompoundTag root = compound(value);
            Vec3 position = validateSaved(root, budget, 0);
            require(CubePos.from(net.minecraft.core.BlockPos.containing(position)).equals(pos), "Entity root outside its saved cube");
        }
        List<Entity> roots = new ArrayList<>();
        for (Tag value : list) {
            roots.add(loadTree(level, compound(value)));
        }
        return roots;
    }

    private static Vec3 validateSaved(CompoundTag tag, Budget budget, int depth) {
        budget.visit(depth);
        Vec3 pos = tag.read("Pos", Vec3.CODEC).orElseThrow();
        require(valid(pos), "Invalid saved entity position");
        require(budget.uuids.add(tag.read("UUID", UUIDUtil.CODEC).orElseThrow()), "Repeated saved UUID");
        if (tag.contains("Passengers")) {
            for (Tag child : tag.getList("Passengers").orElseThrow()) {
                validateSaved(compound(child), budget, depth + 1);
            }
        }
        return pos;
    }

    private static Entity loadTree(ServerLevel level, CompoundTag tag) {
        CompoundTag copy = tag.copy();
        copy.remove("Passengers");
        Entity entity = EntityType
                .create(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), copy), level,
                        new EntitySpawnRequest(EntitySpawnReason.LOAD, false))
                .orElseThrow(() -> new IllegalArgumentException("Unknown saved entity type"));
        require(!(entity instanceof Player), "Players cannot be loaded from cube files");
        require(entity.getUUID().equals(tag.read("UUID", UUIDUtil.CODEC).orElseThrow()), "Entity UUID changed during load");
        for (Tag value : tag.getListOrEmpty("Passengers")) {
            Entity child = loadTree(level, compound(value));
            require(child.startRiding(entity, true, false), "Cannot restore passenger relationship");
        }
        return entity;
    }

    private static CompoundTag compound(Tag tag) {
        require(tag instanceof CompoundTag, "Invalid entity compound");
        return (CompoundTag) tag;
    }

    private static boolean valid(Vec3 pos) {
        return coordinate(pos.x) && coordinate(pos.y) && coordinate(pos.z);
    }

    private static boolean coordinate(double value) {
        return Double.isFinite(value) && value >= CubicBlockBounds.MIN && value < (double) CubicBlockBounds.MAX + 1;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private static final class Budget {
        private int count;
        private final Set<UUID> uuids = new HashSet<>();

        private void visit(int depth) {
            require(depth < MAX_DEPTH && ++count <= MAX_ENTITIES, "Cube entity tree exceeds serialization limits");
        }
    }
}
