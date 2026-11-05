package io.github.opencubicchunks.cubicchunks.world.level.cube;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.mojang.logging.LogUtils;
import io.github.notstirred.dasm.api.annotations.Dasm;
import io.github.notstirred.dasm.api.annotations.selector.Ref;
import io.github.notstirred.dasm.api.annotations.transform.TransformFromClass;
import io.github.notstirred.dasm.api.annotations.transform.TransformFromMethod;
import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cc_core.world.level.CloPos;
import io.github.opencubicchunks.cubicchunks.exception.DasmFailedToApply;
import io.github.opencubicchunks.cubicchunks.mixin.dasmsets.ChunkToCubeSet;
import io.github.opencubicchunks.cubicchunks.server.level.CubicServerLevel;
import io.github.opencubicchunks.cubicchunks.world.level.chunklike.LevelClo;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.heightmap.CubeHeightmap;
import io.github.opencubicchunks.cubicchunks.world.level.poi.CubePoiLifecycle;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.debug.DebugStructureInfo;
import net.minecraft.util.debug.DebugSubscriptions;
import net.minecraft.util.debug.DebugValueSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.gameevent.GameEventListenerRegistry;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.blending.BlendingData;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.TickContainerAccess;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

@Dasm(ChunkToCubeSet.class)
public class LevelCube extends CubeAccess implements LevelClo {
    // Fields matching LevelChunk
    static final Logger LOGGER = LogUtils.getLogger();
    private static final TickingBlockEntity NULL_TICKER = new TickingBlockEntity() {
        @Override public void tick() {}

        @Override public boolean isRemoved() {
            return true;
        }

        @Override public BlockPos getPos() {
            return BlockPos.ZERO;
        }

        @Override public String getType() {
            return "<null>";
        }
    };
    private final Map<BlockPos, RebindableTickingBlockEntityWrapper> tickersInLevel = Maps.newHashMap();
    private boolean loaded;
    final Level level;
    private @Nullable Supplier<FullChunkStatus> fullStatus;
    private @Nullable PostLoadProcessor postLoad;
    private final Int2ObjectMap<GameEventListenerRegistry> gameEventListenerRegistrySections;
    private final LevelChunkTicks<Block> blockTicks;
    private final LevelChunkTicks<Fluid> fluidTicks;
    private LevelCube.UnsavedListener unsavedListener = cubePos -> {};

    // Constructors mirroring vanilla signatures
    public LevelCube(Level level, CubePos pos) {
        this(level, pos, UpgradeData.EMPTY, new LevelChunkTicks<>(), new LevelChunkTicks<>(), 0L, null, null, null);
    }

    public LevelCube(
            Level level, CubePos pos, UpgradeData data, LevelChunkTicks<Block> blockTicks, LevelChunkTicks<Fluid> fluidTicks, long inhabitedTime,
            @Nullable LevelChunkSection[] sections, @Nullable LevelCube.PostLoadProcessor postLoad, @Nullable BlendingData blendingData
    ) {
        super(pos, data, level, level.palettedContainerFactory(), inhabitedTime, sections, blendingData);
        this.level = level;
        this.gameEventListenerRegistrySections = new Int2ObjectOpenHashMap<>();

        // Heights are derived lazily from the installed sections and maintained once queried.

        this.postLoad = postLoad;
        this.blockTicks = blockTicks;
        this.fluidTicks = fluidTicks;
    }

    public LevelCube(ServerLevel level, ProtoCube cube, @Nullable LevelCube.PostLoadProcessor postLoad) {
        this(level, cube.cc_getCubePos(), cube.getUpgradeData(), cube.unpackBlockTicks(), cube.unpackFluidTicks(), cube.getInhabitedTime(),
                cube.getSections(), postLoad, cube.getBlendingData());

        if (!Collections.disjoint(cube.pendingBlockEntities.keySet(), cube.blockEntities.keySet())) {
            LOGGER.error("Cube at {} contains duplicated block entities", cube.cc_getCubePos());
        }

        for (BlockEntity blockentity : cube.getBlockEntities().values()) {
            this.setBlockEntity(blockentity);
        }

        this.pendingBlockEntities.putAll(cube.getBlockEntityNbts());

        for (int i = 0; i < cube.getPostProcessing().length; ++i) {
            this.postProcessing[i] = cube.getPostProcessing()[i];
        }

        this.setAllStarts(cube.getAllStarts());
        this.setAllReferences(cube.getAllReferences());

        this.cc_restoreHeightmaps(cube.cc_snapshotHeightmaps());

        this.setLightCorrect(cube.isLightCorrect());
        this.markUnsaved();
    }

    public void setUnsavedListener(LevelCube.UnsavedListener unsavedListener) {
        this.unsavedListener = unsavedListener;
        if (this.isUnsaved()) {
            this.unsavedListener.setUnsaved(this.cubePos);
        }
    }

    @Override public void cc_setUnsavedListener(LevelClo.UnsavedListener unsavedListener) {
        this.setUnsavedListener(cubePos -> unsavedListener.setUnsaved(CloPos.cube(cubePos)));
    }

    @TransformFromMethod(value = "markUnsaved()V", owner = @Ref(LevelChunk.class))
    @Override public native void markUnsaved();

    @TransformFromMethod(value = "getBlockTicks()Lnet/minecraft/world/ticks/TickContainerAccess;", owner = @Ref(LevelChunk.class))
    @Override public native TickContainerAccess<Block> getBlockTicks();

    @TransformFromMethod(value = "getFluidTicks()Lnet/minecraft/world/ticks/TickContainerAccess;", owner = @Ref(LevelChunk.class))
    @Override public native TickContainerAccess<Fluid> getFluidTicks();

    @TransformFromMethod(value = "getTicksForSerialization(J)Lnet/minecraft/world/level/chunk/ChunkAccess$PackedTicks;", owner = @Ref(LevelChunk.class))
    @Override public native ChunkAccess.PackedTicks getTicksForSerialization(long gameTime);

    // TODO should this actually be dasm'd?
    @TransformFromMethod(value = "getListenerRegistry(I)Lnet/minecraft/world/level/gameevent/GameEventListenerRegistry;", owner = @Ref(LevelChunk.class))
    @Override public native GameEventListenerRegistry getListenerRegistry(int sectionY);

    // dasm + mixin
    @TransformFromMethod(value = "getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;", owner = @Ref(LevelChunk.class))
    @Override public native @NotNull BlockState getBlockState(BlockPos pos);

    @TransformFromMethod(value = "getFluidState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;", owner = @Ref(LevelChunk.class))
    @Override public native @NotNull FluidState getFluidState(BlockPos pos);

    // dasm + mixin
    @TransformFromMethod(value = "getFluidState(III)Lnet/minecraft/world/level/material/FluidState;", owner = @Ref(LevelChunk.class))
    @Override public native FluidState getFluidState(int x, int y, int z);

    // TODO might be dasm-able eventually, if we get more powerful mixin tools
    @SuppressWarnings({ "checkstyle:CyclomaticComplexity", "checkstyle:NPathComplexity" }) // <-- copies structure of vanilla method
    @Override public @Nullable BlockState setBlockState(BlockPos pos, BlockState state, int flags) {
        this.cc_checkBlockPosition(pos);
        var chunkSection = this.getSection(Coords.blockToIndex(pos));
        boolean wasOnlyAir = chunkSection.hasOnlyAir();
        if (wasOnlyAir && state.isAir()) {
            return null;
        } else {
            int sectionLocalX = pos.getX() & SectionPos.SECTION_MASK;
            int sectionLocalY = pos.getY() & SectionPos.SECTION_MASK;
            int sectionLocalZ = pos.getZ() & SectionPos.SECTION_MASK;
            var previousState = chunkSection.setBlockState(sectionLocalX, sectionLocalY, sectionLocalZ, state);
            if (previousState == state) {
                return null;
            } else {
                var block = state.getBlock();
                this.cc_updateHeightmaps(pos, state);
                this.cc_onSectionEmptinessChanged(pos, wasOnlyAir, chunkSection.hasOnlyAir());

                this.cc_onLightPropertiesChanged(pos, previousState, state);

                boolean flag4 = !previousState.is(block);
                boolean flag2 = (flags & Block.UPDATE_MOVE_BY_PISTON) != 0;
                boolean flag3 = (flags & Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS) == 0;
                if (flag4 && previousState.hasBlockEntity()) {
                    if (!this.level.isClientSide() && flag3) {
                        BlockEntity blockentity = this.level.getBlockEntity(pos);
                        if (blockentity != null) {
                            blockentity.preRemoveSideEffects(pos, previousState);
                        }
                    }

                    this.removeBlockEntity(pos);
                }

                if ((flag4 || block instanceof BaseRailBlock) && this.level instanceof ServerLevel serverlevel && ((flags & 1) != 0 || flag2)) {
                    previousState.affectNeighborsAfterRemoval(serverlevel, pos, flag2);
                }

                if (!chunkSection.getBlockState(sectionLocalX, sectionLocalY, sectionLocalZ).is(block)) {
                    return null;
                } else {
                    if (!this.level.isClientSide() && (flags & Block.UPDATE_SKIP_ON_PLACE) == 0) {
                        state.onPlace(this.level, pos, previousState, flag2);
                    }

                    if (state.hasBlockEntity()) {
                        BlockEntity blockentity1 = this.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK);
                        if (blockentity1 != null && !blockentity1.isValidBlockState(state)) {
                            LOGGER.warn("Found mismatched block entity @ {}: type = {}, state = {}", pos,
                                    blockentity1.getType().builtInRegistryHolder().key().identifier(), state);
                            this.removeBlockEntity(pos);
                            blockentity1 = null;
                        }

                        if (blockentity1 == null) {
                            blockentity1 = ((EntityBlock) block).newBlockEntity(pos, state);
                            if (blockentity1 != null) {
                                this.addAndRegisterBlockEntity(blockentity1);
                            }
                        } else {
                            blockentity1.setBlockState(state);
                            this.updateBlockEntityTicker(blockentity1);
                        }
                    }

                    CubePoiLifecycle.blockChanged(this.level, pos, previousState, state);
                    this.markUnsaved();
                    return previousState;
                }
            }
        }
    }

    private void cc_onLightPropertiesChanged(BlockPos pos, BlockState previous, BlockState state) {
        if (LightEngine.hasDifferentLightProperties(previous, state)
                && this.level.getChunkSource() instanceof io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightProvider provider) {
            provider.cc_onCubeLightCellChanged(this, pos, previous);
        }
    }

    private void cc_onSectionEmptinessChanged(BlockPos pos, boolean wasOnlyAir, boolean isOnlyAir) {
        if (wasOnlyAir != isOnlyAir) {
            this.level.getChunkSource().getLightEngine().updateSectionStatus(pos, isOnlyAir);
            this.level.getChunkSource().onSectionEmptinessChanged(SectionPos.blockToSectionCoord(pos.getX()),
                    SectionPos.blockToSectionCoord(pos.getY()), SectionPos.blockToSectionCoord(pos.getZ()), isOnlyAir);
        }
    }

    @TransformFromMethod(value = "addEntity(Lnet/minecraft/world/entity/Entity;)V", owner = @Ref(LevelChunk.class))
    @Deprecated
    @Override public native void addEntity(Entity entity);

    @TransformFromMethod(value = "createBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;", owner = @Ref(LevelChunk.class))
    private native @Nullable BlockEntity createBlockEntity(BlockPos pos);

    @TransformFromMethod(value = "getBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;", owner = @Ref(LevelChunk.class))
    @Override public native @Nullable BlockEntity getBlockEntity(BlockPos pos);

    @TransformFromMethod(value = "getBlockEntity(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/chunk/LevelChunk$EntityCreationType;)"
            + "Lnet/minecraft/world/level/block/entity/BlockEntity;", owner = @Ref(LevelChunk.class))
    public native @Nullable BlockEntity getBlockEntity(BlockPos pos, LevelChunk.EntityCreationType creationType);

    @TransformFromMethod(value = "addAndRegisterBlockEntity(Lnet/minecraft/world/level/block/entity/BlockEntity;)V", owner = @Ref(LevelChunk.class))
    public native void addAndRegisterBlockEntity(BlockEntity blockEntity);

    @TransformFromMethod(value = "isInLevel()Z", owner = @Ref(LevelChunk.class))
    private native boolean isInLevel();

    public boolean isTicking(BlockPos pos) {
        if (!this.level.getWorldBorder().isWithinBounds(pos)) {
            return false;
        }
        return !(this.level instanceof ServerLevel) || this.getFullStatus().isOrAfter(FullChunkStatus.BLOCK_TICKING)
                && ((CubicServerLevel) this.level).cc_areCubeEntitiesLoaded(this.cubePos);
    }

    @TransformFromMethod(value = "setBlockEntity(Lnet/minecraft/world/level/block/entity/BlockEntity;)V", owner = @Ref(LevelChunk.class))
    @Override public native void setBlockEntity(BlockEntity blockEntity);

    @TransformFromMethod(value = "getBlockEntityNbtForSaving(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/HolderLookup$Provider;)Lnet/minecraft/nbt/CompoundTag;", owner = @Ref(LevelChunk.class))
    @Override public native @Nullable CompoundTag getBlockEntityNbtForSaving(BlockPos pos, HolderLookup.Provider provider);

    @TransformFromMethod(value = "removeBlockEntity(Lnet/minecraft/core/BlockPos;)V", owner = @Ref(LevelChunk.class))
    @Override public native void removeBlockEntity(BlockPos pos);

    // TODO maybe shouldn't be dasm
    @TransformFromMethod(value = "removeGameEventListener(Lnet/minecraft/world/level/block/entity/BlockEntity;Lnet/minecraft/server/level/ServerLevel;)V", owner = @Ref(LevelChunk.class))
    private native <T extends BlockEntity> void removeGameEventListener(T blockEntity, ServerLevel level);

    // TODO sectionY is definitely wrong here
    @TransformFromMethod(value = "removeGameEventListenerRegistry(I)V", owner = @Ref(LevelChunk.class))
    private native void removeGameEventListenerRegistry(int sectionY);

    @TransformFromMethod(value = "removeBlockEntityTicker(Lnet/minecraft/core/BlockPos;)V", owner = @Ref(LevelChunk.class))
    private native void removeBlockEntityTicker(BlockPos pos);

    @TransformFromMethod(value = "runPostLoad()V", owner = @Ref(LevelChunk.class))
    public native void runPostLoad();

    @TransformFromMethod(value = "isEmpty()Z", owner = @Ref(LevelChunk.class))
    public native boolean isEmpty();

    /**
     * Adapts the 26.3 packet API to the cube reader. The payload must contain this cube's section data;
     * this does not convert a vanilla column into cubes. Heightmaps use cube-sized arrays; lighting remains unfinished.
     */
    @Override public void replaceWithPacketData(int chunkX, int chunkZ, ClientboundLevelChunkPacketData packetData) {
        FriendlyByteBuf buffer = packetData.getReadBuffer();
        try {
            this.replaceWithPacketData(buffer, packetData.getHeightmaps(), output -> packetData.forEachBlockEntityTag(chunkX, chunkZ, output));
        } finally {
            buffer.release();
        }
    }

    // TODO this should maybe be dasm + mixin, there's a lot of vanilla code duplication here
    public void replaceWithPacketData(
            FriendlyByteBuf buffer, Map<Heightmap.Types, long[]> map, Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> outputTagConsumer
    ) {
        // Prepare sections and entity instances before replacing any existing block/entity data.
        map.values().forEach(CubeHeightmap::validateRawData);
        LevelChunkSection[] replacement = this.cc_readPacketSections(buffer);
        Map<BlockPos, BlockEntity> entities = this.cc_readPacketEntities(replacement, outputTagConsumer);
        this.cc_clearClientBlockEntities();
        this.cc_invalidateHeightmaps();
        System.arraycopy(replacement, 0, this.sections, 0, this.sections.length);
        map.forEach(this::setHeightmap);
        entities.values().forEach(this::addAndRegisterBlockEntity);
    }

    private LevelChunkSection[] cc_readPacketSections(FriendlyByteBuf buffer) {
        LevelChunkSection[] replacement = new LevelChunkSection[CubicConstants.SECTION_COUNT];
        for (int index = 0; index < replacement.length; index++) {
            replacement[index] = new LevelChunkSection(this.level.palettedContainerFactory());
            replacement[index].read(buffer);
        }
        if (buffer.isReadable()) {
            throw new IllegalArgumentException("Trailing cube section packet data");
        }
        return replacement;
    }

    private Map<BlockPos, BlockEntity> cc_readPacketEntities(
            LevelChunkSection[] replacement, Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> consumer
    ) {
        Map<BlockPos, BlockEntity> entities = new LinkedHashMap<>();
        try (var reporter = new ProblemReporter.ScopedCollector(this.problemPath(), LOGGER)) {
            consumer.accept((pos, type, tag) -> {
                if (!this.cubePos.equals(CubePos.from(pos)) || entities.containsKey(pos)) {
                    throw new IllegalArgumentException("Duplicate or foreign block entity in cube snapshot");
                }
                BlockState state = replacement[Coords.blockToIndex(pos)].getBlockState(SectionPos.sectionRelative(pos.getX()),
                        SectionPos.sectionRelative(pos.getY()), SectionPos.sectionRelative(pos.getZ()));
                BlockEntity entity = this.cc_createPacketEntity(pos, type, state);
                if (tag != null) {
                    entity.loadWithComponents(TagValueInput.create(reporter.forChild(entity.problemPath()), this.level.registryAccess(), tag.copy()));
                }
                entities.put(pos.immutable(), entity);
            });
        }
        return entities;
    }

    private BlockEntity cc_createPacketEntity(BlockPos pos, BlockEntityType<?> type, BlockState state) {
        if (!state.hasBlockEntity() || !type.isValid(state)) {
            throw new IllegalArgumentException("Block entity type does not match cube section data");
        }
        BlockEntity entity = type.create(pos.immutable(), state);
        if (entity == null || entity.getType() != type) {
            throw new IllegalArgumentException("Cannot create block entity from cube snapshot");
        }
        entity.setLevel(this.level);
        return entity;
    }

    /** Clear both instantiated objects and deferred tags when a client cube is replaced or unloaded. */
    public void cc_clearClientBlockEntities() {
        this.clearAllBlockEntities();
        this.pendingBlockEntities.clear();
    }

    @TransformFromMethod(value = "replaceBiomes(Lnet/minecraft/network/FriendlyByteBuf;)V", owner = @Ref(LevelChunk.class))
    public native void replaceBiomes(FriendlyByteBuf buffer);

    @TransformFromMethod(value = "setLoaded(Z)V", owner = @Ref(LevelChunk.class))
    public native void setLoaded(boolean loaded);

    @TransformFromMethod(value = "getLevel()Lnet/minecraft/world/level/Level;", owner = @Ref(LevelChunk.class))
    public native Level getLevel();

    @TransformFromMethod(value = "getBlockEntities()Ljava/util/Map;", owner = @Ref(LevelChunk.class))
    public native Map<BlockPos, BlockEntity> getBlockEntities();

    // TODO P2 or P3 figure this out later - stub method for now
    public void postProcessGeneration(ServerLevel serverLevel) {
        for (int i = 0; i < this.postProcessing.length; ++i) {
            if (this.postProcessing[i] != null) {
                this.postProcessing[i].clear();
            }
        }

        for (BlockPos blockpos1 : ImmutableList.copyOf(this.pendingBlockEntities.keySet())) {
            this.getBlockEntity(blockpos1);
        }

        this.pendingBlockEntities.clear();
    }

    @TransformFromMethod(value = "promotePendingBlockEntity(Lnet/minecraft/core/BlockPos;Lnet/minecraft/nbt/CompoundTag;)"
            + "Lnet/minecraft/world/level/block/entity/BlockEntity;", owner = @Ref(LevelChunk.class))
    private native @Nullable BlockEntity promotePendingBlockEntity(BlockPos pos, CompoundTag tag);

    @TransformFromMethod(value = "unpackTicks(J)V", owner = @Ref(LevelChunk.class))
    public native void unpackTicks(long pos);

    // TODO (P2 or P3) ticks are disabled for now; stub methods as placeholders
//    @TransformFromMethod(value = @MethodSig("registerTickContainerInLevel(Lnet/minecraft/server/level/ServerLevel;)V"), owner = @Ref(LevelChunk
//    .class))
//    public native void registerTickContainerInLevel(ServerLevel level);
    public void registerTickContainerInLevel(ServerLevel level) {}

    // @TransformFromMethod(value = @MethodSig("unregisterTickContainerFromLevel(Lnet/minecraft/server/level/ServerLevel;)V"), owner = @Ref
    // (LevelChunk.class))
//    public native void unregisterTickContainerFromLevel(ServerLevel level);
    public void unregisterTickContainerFromLevel(ServerLevel level) {}

    @Override public void registerDebugValues(ServerLevel level, DebugValueSource.Registration registration) {
        if (!this.getAllStarts().isEmpty()) {
            registration.register(DebugSubscriptions.STRUCTURES, () -> {
                List<DebugStructureInfo> structures = new ArrayList<>();
                for (var start : this.getAllStarts().values()) {
                    var pieces = start.getPieces();
                    List<DebugStructureInfo.Piece> debugPieces = new ArrayList<>(pieces.size());
                    for (int i = 0; i < pieces.size(); i++) {
                        debugPieces.add(new DebugStructureInfo.Piece(pieces.get(i).getBoundingBox(), i == 0));
                    }
                    structures.add(new DebugStructureInfo(start.getBoundingBox(), debugPieces));
                }
                return structures;
            });
        }
        registration.register(DebugSubscriptions.RAIDS, () -> {
            List<BlockPos> centers = new ArrayList<>();
            for (int dx = 0; dx < CubicConstants.DIAMETER_IN_SECTIONS; dx++) {
                for (int dz = 0; dz < CubicConstants.DIAMETER_IN_SECTIONS; dz++) {
                    ChunkPos column = new ChunkPos(Coords.cubeToSection(this.cubePos.getX(), dx), Coords.cubeToSection(this.cubePos.getZ(), dz));
                    for (BlockPos center : level.getRaids().getRaidCentersInChunk(column)) {
                        if (Coords.blockToCube(center.getY()) == this.cubePos.getY()) {
                            centers.add(center);
                        }
                    }
                }
            }
            return centers;
        });
    }

    @TransformFromMethod(value = "getPersistedStatus()Lnet/minecraft/world/level/chunk/status/ChunkStatus;", owner = @Ref(LevelChunk.class))
    @Override public native ChunkStatus getPersistedStatus();

    @TransformFromMethod(value = "getFullStatus()Lnet/minecraft/server/level/FullChunkStatus;", owner = @Ref(LevelChunk.class))
    public native FullChunkStatus getFullStatus();

    @TransformFromMethod(value = "setFullStatus(Ljava/util/function/Supplier;)V", owner = @Ref(LevelChunk.class))
    public native void setFullStatus(Supplier<FullChunkStatus> fullStatus);

    // TODO a bit concerning
    @TransformFromMethod(value = "clearAllBlockEntities()V", owner = @Ref(LevelChunk.class))
    public native void clearAllBlockEntities();

    @TransformFromMethod(value = "registerAllBlockEntitiesAfterLevelLoad()V", owner = @Ref(LevelChunk.class))
    public native void registerAllBlockEntitiesAfterLevelLoad();

    // TODO (P3): GameEvent stuff is a bit concerning
    @TransformFromMethod(value = "addGameEventListener(Lnet/minecraft/world/level/block/entity/BlockEntity;Lnet/minecraft/server/level/ServerLevel;)V", owner = @Ref(LevelChunk.class))
    private native <T extends BlockEntity> void addGameEventListener(T blockEntity, ServerLevel level);

    @TransformFromMethod(value = "updateBlockEntityTicker(Lnet/minecraft/world/level/block/entity/BlockEntity;)V", owner = @Ref(LevelChunk.class))
    private native <T extends BlockEntity> void updateBlockEntityTicker(T blockEntity);

    @TransformFromMethod(value = "createTicker(Lnet/minecraft/world/level/block/entity/BlockEntity;Lnet/minecraft/world/level/block/entity/BlockEntityTicker;)"
            + "Lnet/minecraft/world/level/block/entity/TickingBlockEntity;", owner = @Ref(LevelChunk.class))
    private native <T extends BlockEntity> TickingBlockEntity createTicker(T blockEntity, BlockEntityTicker<T> ticker);

    // TODO forge stuff
    // FORGE START
//    private final net.neoforged.neoforge.attachment.AttachmentHolder.AsField attachmentHolder = new net.neoforged.neoforge.attachment
//    .AttachmentHolder.AsField();
//
//    @Override public boolean hasData(net.neoforged.neoforge.attachment.AttachmentType<?> type) {
//        return attachmentHolder.hasData(type);
//    }
//
//    @Override public <T> T getData(net.neoforged.neoforge.attachment.AttachmentType<T> type) {
//        return attachmentHolder.getData(type);
//    }
//
//    @Override public <T> @Nullable T setData(net.neoforged.neoforge.attachment.AttachmentType<T> type, T data) {
//        setUnsaved(true);
//        return attachmentHolder.setData(type, data);
//    }
    // FORGE END

    @TransformFromClass(value = @Ref(string = "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity"), sets = ChunkToCubeSet.class)
    class BoundTickingBlockEntity<T extends BlockEntity> implements TickingBlockEntity {
        private final T blockEntity;
        private final BlockEntityTicker<T> ticker;
        private boolean loggedInvalidBlockState;

        BoundTickingBlockEntity(T blockEntity, BlockEntityTicker<T> ticker) {
            throw new DasmFailedToApply();
        }

        @Override public native void tick();

        @Override public native boolean isRemoved();

        @Override public native BlockPos getPos();

        @Override public native String getType();

        @Override public native String toString();
    }

    @FunctionalInterface
    public interface PostLoadProcessor {
        void run(LevelCube cube);
    }

    @TransformFromClass(value = @Ref(string = "net.minecraft.world.level.chunk.LevelChunk$RebindableTickingBlockEntityWrapper"), sets = ChunkToCubeSet.class)
    public class RebindableTickingBlockEntityWrapper implements TickingBlockEntity {
        private TickingBlockEntity ticker;

        RebindableTickingBlockEntityWrapper(TickingBlockEntity ticker) {
            throw new DasmFailedToApply();
        }

        native void rebind(TickingBlockEntity ticker);

        @Override public native void tick();

        @Override public native boolean isRemoved();

        @Override public native BlockPos getPos();

        @Override public native String getType();

        @Override public native String toString();
    }

    @FunctionalInterface
    public interface UnsavedListener {
        void setUnsaved(CubePos cubePos);
    }
}
