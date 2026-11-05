package io.github.opencubicchunks.cubicchunks.world.level.levelgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.Misc;
import io.github.opencubicchunks.cubicchunks.testutils.VanillaRegistriesExtension;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.structures.BuriedTreasurePieces;
import org.junit.jupiter.api.Test;

class TestNativeStructureLayout extends BaseTest {
    private static ProtoChunk chunk(ServerLevel level, ChunkPos pos) {
        return new ProtoChunk(pos, UpgradeData.EMPTY, LevelHeightAccessor.create(-64, 384), level.palettedContainerFactory(), null);
    }

    @Test
    void savedStartsHaveIndependentPiecesAndKeepBothReferencesAtTheEightChunkBoundary() {
        var level = Misc.mockLevelWithPalettes(ServerLevel.class);
        when(level.getServer().registryAccess()).thenReturn(VanillaRegistriesExtension.shared());
        when(level.structureManager().shouldGenerateStructures()).thenReturn(true);
        var type = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getValueOrThrow(BuiltinStructures.BURIED_TREASURE);
        var target = new ChunkPos(-2, 3);
        var first = new ChunkPos(target.x() - 8, target.z());
        var second = new ChunkPos(target.x() + 8, target.z());
        var beyond = new ChunkPos(target.x() + 9, target.z());
        var empty = new CompoundTag();
        empty.putString("Status", "minecraft:full");
        Map<ChunkPos, CompoundTag> saved = new HashMap<>();
        for (var pos : List.of(first, second, beyond)) {
            var start = new StructureStart(type, pos, 2, new PiecesContainer(
                    List.of(new BuriedTreasurePieces.BuriedTreasurePiece(new BlockPos(target.getMinBlockX(), -32, target.getMinBlockZ())))));
            var starts = new CompoundTag();
            starts.put("minecraft:buried_treasure", start.createTag(StructurePieceSerializationContext.fromLevel(level), pos));
            var structures = new CompoundTag();
            structures.put("starts", starts);
            var root = empty.copy();
            root.put("structures", structures);
            saved.put(pos, root);
        }
        var source = level.getChunkSource();
        when(source.chunkScanner().scanChunk(any(), any())).thenAnswer(call -> {
            saved.getOrDefault(call.getArgument(0), empty).acceptAsRoot(call.getArgument(1, StreamTagVisitor.class));
            return CompletableFuture.completedFuture(null);
        });
        var layout = new NativeStructureLayout(level);
        var column = chunk(level, target);
        layout.installReferences(column);
        assertEquals(java.util.Set.of(first.pack(), second.pack()), column.getReferencesForStructure(type));
        layout.installStarts(column);
        assertTrue(column.getAllStarts().isEmpty());
        verify(source, never()).getGeneratorState();

        var owner = chunk(level, first);
        var otherTile = chunk(level, first);
        layout.installStarts(owner);
        layout.installStarts(otherTile);
        var one = owner.getStartForStructure(type);
        var two = otherTile.getStartForStructure(type);
        assertNotSame(one.getPieces().getFirst(), two.getPieces().getFirst());
        one.addReference();
        assertEquals(2, two.getReferences());
        assertEquals(-32, two.getBoundingBox().minY());
    }

    @Test
    void disabledGenerationSkipsCandidateAndDiskLookups() {
        var level = Misc.mockLevelWithPalettes(ServerLevel.class);
        var source = level.getChunkSource();
        var layout = new NativeStructureLayout(level);
        var column = chunk(level, new ChunkPos(-1, 0));
        layout.installStarts(column);
        layout.installReferences(column);
        assertTrue(column.getAllReferences().isEmpty());
        assertTrue(column.getAllStarts().isEmpty());
        verify(source, never()).chunkScanner();
        verify(source, never()).getGeneratorState();
    }
}
