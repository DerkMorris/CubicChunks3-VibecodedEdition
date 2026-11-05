package io.github.opencubicchunks.smoke;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientCubeCache;
import io.github.opencubicchunks.cubicchunks.world.level.CubicLevelReader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.storage.LevelResource;

/** Locate natural starts, sample their generated blocks through the cube API, check the packet on the client, then reopen. */
final class StructureGenerationProbe {
    private CompletableFuture<BlockPos> work;
    private int phase;

    void tick(Minecraft client, boolean reopen) {
        var server = client.getSingleplayerServer();
        if (work == null) {
            work = server.submit(() -> {
                var level = server.overworld();
                var marker = server.getWorldPath(LevelResource.ROOT).resolve("cc-structure-check.txt");
                try {
                    if (phase == 0) { checkCatalog(level); }
                    if (reopen && phase == 0) {
                        var xyz = Files.readString(marker).trim().split(" ");
                        var saved = new BlockPos(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]));
                        if (!level.getBlockState(saved).is(Blocks.EMERALD_BLOCK)) {
                            throw new IllegalStateException("Player edit in generated structure was lost " + saved);
                        }
                    }
                    BlockPos pos = sample(level, phase);
                    if (phase == 5) { checkOtherDimensions(level); }
                    if (phase == 0 && !reopen) {
                        var edit = pos.above();
                        level.setBlockAndUpdate(edit, Blocks.EMERALD_BLOCK.defaultBlockState());
                        Files.writeString(marker, edit.getX() + " " + edit.getY() + " " + edit.getZ());
                    }
                    var player = server.getPlayerList().getPlayers().getFirst();
                    player.getAbilities().flying = true;
                    player.onUpdateAbilities();
                    player.teleportTo(pos.getX() + .5, pos.getY() + 2, pos.getZ() + .5);
                    return pos;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            return;
        }
        if (!work.isDone()) {
            return;
        }
        var pos = work.join();
        CubePos key = CubePos.from(pos);
        var cube = ((ClientCubeCache) client.level.getChunkSource()).cc_getCube(key.getX(), key.getY(), key.getZ(), false);
        if (cube == null) {
            return;
        }
        if (!matches(cube.getBlockState(pos), phase)) {
            throw new IllegalStateException("Client structure block mismatch at " + pos);
        }
        System.out.println("CC_STRUCTURE_CLIENT phase=" + phase + " block=" + cube.getBlockState(pos) + " pos=" + pos);
        if (++phase == 6) {
            System.out.println("CC_STRUCTURE_PASS reopen=" + reopen);
            client.stop();
        }
        work = null;
    }

    private static BlockPos sample(ServerLevel level, int phase) {
        var reg = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        HolderSet<Structure> types = switch (phase) {
            case 0 -> HolderSet.direct(reg.getOrThrow(BuiltinStructures.TRIAL_CHAMBERS));
            case 1 -> reg.getOrThrow(StructureTags.VILLAGE);
            case 2 -> HolderSet.direct(reg.getOrThrow(BuiltinStructures.STRONGHOLD));
            case 3 -> HolderSet.direct(reg.getOrThrow(BuiltinStructures.OCEAN_MONUMENT));
            case 4 -> reg.getOrThrow(StructureTags.ABANDONED_CAMP);
            default -> HolderSet.direct(reg.getOrThrow(BuiltinStructures.BURIED_TREASURE));
        };
        if (phase == 0) {
            level.getServer().getCommands().performPrefixedCommand(level.getServer().createCommandSourceStack(), "locate structure minecraft:trial_chambers");
        }
        var result = level.getChunkSource().getGenerator().findNearestMapStructure(level, types, BlockPos.ZERO, 100, false);
        if (result == null) {
            throw new IllegalStateException("Locate failed for " + types);
        }
        var owner = ChunkPos.containing(result.getFirst());
        var start = level.getChunk(owner.x(), owner.z(), ChunkStatus.STRUCTURE_STARTS).getStartForStructure(result.getSecond().value());
        if (start == null || !start.isValid()) {
            throw new IllegalStateException("Missing start at " + owner);
        }
        System.out.println("CC_STRUCTURE_LOCATE key=" + result.getSecond().getRegisteredName() + " origin=" + owner + " bounds=" + start.getBoundingBox());
        if (phase == 5) {
            var center = start.getPieces().getFirst().getBoundingBox().getCenter();
            var chunks = level.getChunkSource();
            int top = Math.min(level.getMaxY(), 4 + chunks.getGenerator().getBaseHeight(center.getX(), center.getZ(),
                    Heightmap.Types.WORLD_SURFACE_WG, level, chunks.randomState()));
            for (int y = top; y >= level.getMinY(); y--) {
                var at = new BlockPos(center.getX(), y, center.getZ());
                if (level.getBlockEntity(at) instanceof RandomizableContainerBlockEntity chest && chest.getLootTable() != null
                        && chest.getLootTable().identifier().getPath().equals("chests/buried_treasure")) {
                    System.out.println("CC_STRUCTURE_LOOT " + chest.getLootTable().identifier() + " at " + at);
                    return at;
                }
            }
            throw new IllegalStateException("No buried treasure chest at " + center);
        }
        var cubes = new HashSet<CubePos>();
        BlockPos found = null;
        for (var piece : start.getPieces()) {
            BoundingBox bounds = piece.getBoundingBox();
            CubePos at = CubePos.from(bounds.getCenter());
            if (!cubes.add(at)) {
                continue;
            }
            var cube = ((CubicLevelReader) level).cc_getCube(at.getX(), at.getY(), at.getZ(), ChunkStatus.FULL, true);
            var sample = BlockPos.betweenClosed(Math.max(at.minCubeX(), bounds.minX()), Math.max(at.minCubeY(), bounds.minY()),
                    Math.max(at.minCubeZ(), bounds.minZ()), Math.min(at.maxCubeX(), bounds.maxX()), Math.min(at.maxCubeY(), bounds.maxY()),
                    Math.min(at.maxCubeZ(), bounds.maxZ()));
            for (var pos : sample) {
                if (matches(cube.getBlockState(pos), phase)) {
                    if (!level.structureManager().startsForStructure(pos.getX() >> 4, pos.getZ() >> 4, result.getSecond().value())
                            .stream().anyMatch(s -> s.getChunkPos().equals(owner))) {
                        throw new IllegalStateException("No reference back to " + owner + " at " + pos);
                    }
                    if (found != null) {
                        System.out.println("CC_STRUCTURE_CROSS_CUBE first=" + found + " second=" + pos);
                        return found;
                    }
                    found = pos.immutable();
                    break;
                }
            }
        }
        if (found == null) {
            throw new IllegalStateException("No generated structure blocks for " + result.getSecond());
        }
        return found;
    }

    private static void checkCatalog(ServerLevel level) {
        Set<Structure> placed = new HashSet<>();
        var server = level.getServer();
        for (var dimension : server.getAllLevels()) {
            dimension.getChunkSource().getGeneratorState().possibleStructureSets().forEach(set ->
                    set.value().structures().forEach(entry -> placed.add(entry.structure().value())));
        }
        var vanilla = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).listElements()
                .filter(holder -> holder.key().identifier().getNamespace().equals("minecraft")).toList();
        if (!vanilla.stream().allMatch(holder -> placed.contains(holder.value()))) {
            throw new IllegalStateException("Missing vanilla structure placement set");
        }
        System.out.println("CC_STRUCTURE_REGISTRY vanilla=" + vanilla.size() + " all=covered");
    }

    private static void checkOtherDimensions(ServerLevel overworld) {
        for (var dimension : List.of(Level.NETHER, Level.END)) {
            var level = overworld.getServer().getLevel(dimension);
            if (((CanBeCubic) level).cc_isCubic()) { throw new IllegalStateException("Unexpected cubic dimension " + dimension); }
            var key = dimension == Level.NETHER ? BuiltinStructures.FORTRESS : BuiltinStructures.END_CITY;
            var type = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(key);
            var result = level.getChunkSource().getGenerator().findNearestMapStructure(level, HolderSet.direct(type), BlockPos.ZERO, 100, false);
            if (result == null) { throw new IllegalStateException("Missing " + key); }
            var owner = ChunkPos.containing(result.getFirst());
            var start = level.getChunk(owner.x(), owner.z(), ChunkStatus.STRUCTURE_STARTS).getStartForStructure(type.value());
            var block = dimension == Level.NETHER ? Blocks.NETHER_BRICKS : Blocks.PURPUR_BLOCK;
            var verified = false;
            for (var piece : start.getPieces()) {
                var box = piece.getBoundingBox();
                var target = ChunkPos.containing(box.getCenter());
                var chunk = level.getChunk(target.x(), target.z(), ChunkStatus.FULL);
                var points = BlockPos.betweenClosed(Math.max(target.getMinBlockX(), box.minX()), box.minY(), Math.max(target.getMinBlockZ(), box.minZ()),
                        Math.min(target.getMaxBlockX(), box.maxX()), box.maxY(), Math.min(target.getMaxBlockZ(), box.maxZ()));
                for (var point : points) {
                    if (chunk.getBlockState(point).is(block)) {
                        System.out.println("CC_STRUCTURE_DIMENSION " + key.identifier() + " block=" + block + " at " + point);
                        verified = true;
                        break;
                    }
                }
                if (verified) { break; }
            }
            if (!verified) { throw new IllegalStateException("No generated " + key + " blocks"); }
        }
    }

    private static boolean matches(BlockState state, int phase) {
        return switch (phase) {
            case 0 -> state.is(Blocks.TUFF_BRICKS) || state.is(Blocks.TRIAL_SPAWNER) || state.is(Blocks.VAULT);
            case 1 -> state.is(BlockTags.BEDS) || state.is(Blocks.BELL) || state.is(Blocks.COMPOSTER) || state.is(Blocks.CRAFTING_TABLE);
            case 2 -> state.is(Blocks.STONE_BRICKS) || state.is(Blocks.END_PORTAL_FRAME) || state.is(Blocks.CRACKED_STONE_BRICKS);
            case 3 -> state.is(Blocks.PRISMARINE) || state.is(Blocks.PRISMARINE_BRICKS) || state.is(Blocks.DARK_PRISMARINE);
            case 4 -> state.is(BlockTags.WOOL) || state.is(BlockTags.CAMPFIRES) || state.is(Blocks.BARREL) || state.is(Blocks.DIRT_PATH);
            default -> state.is(Blocks.CHEST);
        };
    }
}
