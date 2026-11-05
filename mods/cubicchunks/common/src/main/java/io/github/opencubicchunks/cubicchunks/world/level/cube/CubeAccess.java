package io.github.opencubicchunks.cubicchunks.world.level.cube;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.google.common.collect.Maps;
import io.github.notstirred.dasm.api.annotations.Dasm;
import io.github.notstirred.dasm.api.annotations.selector.Ref;
import io.github.notstirred.dasm.api.annotations.transform.TransformFromMethod;
import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cc_core.world.level.CloPos;
import io.github.opencubicchunks.cubicchunks.CubicChunks;
import io.github.opencubicchunks.cubicchunks.mixin.dasmsets.ChunkToCubeSet;
import io.github.opencubicchunks.cubicchunks.world.level.chunklike.CloAccess;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.heightmap.CloHeightmap;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.heightmap.CubeHeightmap;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightProvider;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeSkyOcclusion;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.shorts.ShortList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.gameevent.GameEventListenerRegistry;
import net.minecraft.world.level.levelgen.BelowZeroRetrogen;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.blending.BlendingData;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.lighting.ChunkSkyLightSources;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.TickContainerAccess;
import org.jetbrains.annotations.Nullable;

@Dasm(ChunkToCubeSet.class)
public abstract class CubeAccess implements CloAccess {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(CubeAccess.class);
    private static final LongSet EMPTY_REFERENCE_SET = new LongOpenHashSet();
    // Fields copied from ChunkAccess, except ChunkPos -> CubePos
    protected final ShortList[] postProcessing;
    private volatile boolean unsaved;
    private volatile boolean isLightCorrect;
    protected final CubePos cubePos;
    private long inhabitedTime;
    @Nullable @Deprecated
    private BiomeGenerationSettings carverBiomeSettings;

    protected final UpgradeData upgradeData;
    protected @Nullable BlendingData blendingData;
    protected final Map<Heightmap.Types, CloHeightmap> heightmaps = Maps.newEnumMap(Heightmap.Types.class);
    protected ChunkSkyLightSources skyLightSources;
    private @Nullable CubeSkyOcclusion cc_skyOcclusion;
    private volatile long cc_lightRevision;
    private final Map<Structure, StructureStart> structureStarts = Maps.newHashMap();
    private final Map<Structure, LongSet> structureReferences = Maps.newHashMap();
    protected final Map<BlockPos, CompoundTag> pendingBlockEntities = Maps.newHashMap();
    protected final Map<BlockPos, BlockEntity> blockEntities = new Object2ObjectOpenHashMap<>();
    protected final LevelHeightAccessor levelHeightAccessor;
    protected final LevelChunkSection[] sections;

    // Constructor signature matches ChunkAccess for DASM redirect purposes
    public CubeAccess(
            CubePos cubePos, UpgradeData upgradeData, LevelHeightAccessor levelHeightAccessor, PalettedContainerFactory containerFactory,
            long inhabitedTime, @Nullable LevelChunkSection[] chunkSections, @Nullable BlendingData blendingData
    ) {
        this.cubePos = cubePos;
        this.upgradeData = upgradeData;
        // Copied chunk methods use this accessor for bounds. A cube can be far outside the vanilla dimension's vertical span.
        this.levelHeightAccessor = LevelHeightAccessor.create(cubePos.minCubeY(), CubicConstants.DIAMETER_IN_BLOCKS);
        this.sections = new LevelChunkSection[CubicConstants.SECTION_COUNT];
        this.inhabitedTime = inhabitedTime;
        this.postProcessing = new ShortList[CubicConstants.SECTION_COUNT];
        this.blendingData = blendingData;
        this.skyLightSources = new ChunkSkyLightSources(this.levelHeightAccessor);
        if (chunkSections != null) {
            if (this.sections.length == chunkSections.length) {
                System.arraycopy(chunkSections, 0, this.sections, 0, this.sections.length);
            } else {
                CubicChunks.LOGGER.warn("Could not set level cube sections, array length is {} instead of {}", chunkSections.length,
                        this.sections.length);
            }
        }

        replaceMissingSections(containerFactory, this.sections);
    }

    private static void replaceMissingSections(PalettedContainerFactory containerFactory, LevelChunkSection[] sections) {
        for (int i = 0; i < sections.length; ++i) {
            if (sections[i] == null) {
                sections[i] = new LevelChunkSection(containerFactory);
            }
        }
    }

    @TransformFromMethod(value = "getListenerRegistry(I)Lnet/minecraft/world/level/gameevent/GameEventListenerRegistry;", owner = @Ref(ChunkAccess.class))
    @Override public native GameEventListenerRegistry getListenerRegistry(int sectionY);

    @TransformFromMethod(owner = @Ref(ChunkAccess.class), value = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)"
            + "Lnet/minecraft/world/level/block/state/BlockState;")
    @Override public native @Nullable BlockState setBlockState(BlockPos pos, BlockState state);

    @Override public abstract @Nullable BlockState setBlockState(BlockPos pos, BlockState state, int flags);

    @Override public abstract void setBlockEntity(BlockEntity blockEntity);

    @Override public abstract void addEntity(Entity entity);

    // A cube's section array interleaves X/Y/Z; the last occupied array index is not its highest vertical section.
    @Override public int getHighestFilledSectionIndex() {
        int highest = -1;
        LevelChunkSection[] cubeSections = this.getSections();
        for (int i = 0; i < cubeSections.length; i++) {
            if (!cubeSections[i].hasOnlyAir()) {
                highest = Math.max(highest, Coords.indexToY(i));
            }
        }
        return highest;
    }

    @Override public int getHighestSectionPosition() {
        int highest = this.getHighestFilledSectionIndex();
        return this.getMinY() + SectionPos.sectionToBlockCoord(Math.max(highest, 0));
    }

    @TransformFromMethod(value = "getBlockEntitiesPos()Ljava/util/Set;", owner = @Ref(ChunkAccess.class))
    @Override public native Set<BlockPos> getBlockEntitiesPos();

    @TransformFromMethod(value = "getSections()[Lnet/minecraft/world/level/chunk/LevelChunkSection;", owner = @Ref(ChunkAccess.class))
    @Override public native LevelChunkSection[] getSections();

    @TransformFromMethod(value = "getSection(I)Lnet/minecraft/world/level/chunk/LevelChunkSection;", owner = @Ref(ChunkAccess.class))
    @Override public native LevelChunkSection getSection(int index);

    @Override public Collection<Map.Entry<Heightmap.Types, CloHeightmap>> cc_getHeightmaps() {
        return Map.copyOf(this.heightmaps).entrySet();
    }

    @Override public void setHeightmap(Heightmap.Types type, long[] data) {
        CubeHeightmap map = new CubeHeightmap(this, type);
        map.setRawData(data);
        this.heightmaps.put(type, map);
    }

    @Override public CloHeightmap cc_getOrCreateHeightmapUnprimed(Heightmap.Types type) {
        return this.heightmaps.computeIfAbsent(type, key -> new CubeHeightmap(this, key));
    }

    @Override public boolean hasPrimedHeightmap(Heightmap.Types type) {
        return this.heightmaps.get(type) instanceof CubeHeightmap map && map.isPrimed();
    }

    @Override public int getHeight(Heightmap.Types type, int x, int z) {
        return this.cc_getOrCreateHeightmapUnprimed(type).getHighestTaken(Coords.blockToLocal(x), Coords.blockToLocal(z));
    }

    /** These are surfaces within this cube, not authoritative heights of an entire vertical world column. */
    public Map<Heightmap.Types, long[]> cc_snapshotHeightmaps() {
        if (this instanceof ImposterProtoCube imposter) {
            return ((CubeAccess) imposter.cc_getWrappedClo()).cc_snapshotHeightmaps();
        }
        Map<Heightmap.Types, long[]> snapshot = new EnumMap<>(Heightmap.Types.class);
        for (Heightmap.Types type : Heightmap.Types.values()) {
            snapshot.put(type, this.cc_getOrCreateHeightmapUnprimed(type).getRawData());
        }
        return snapshot;
    }

    /** Validate the complete snapshot before changing any cached heights. */
    public void cc_restoreHeightmaps(Map<Heightmap.Types, long[]> snapshot) {
        if (!snapshot.keySet().equals(Set.of(Heightmap.Types.values()))) {
            throw new IllegalArgumentException("A cube heightmap snapshot must contain every heightmap type");
        }
        Map<Heightmap.Types, CloHeightmap> restored = new EnumMap<>(Heightmap.Types.class);
        snapshot.forEach((type, data) -> {
            CubeHeightmap map = new CubeHeightmap(this, type);
            map.setRawData(data);
            restored.put(type, map);
        });
        this.heightmaps.clear();
        this.heightmaps.putAll(restored);
    }

    /** Call before bulk section replacement; direct section mutations cannot notify height or sky occlusion caches. */
    public void cc_invalidateHeightmaps() {
        this.cc_invalidateSkyOcclusion();
        this.heightmaps.clear();
    }

    protected void cc_updateHeightmaps(BlockPos pos, BlockState state) {
        // The section has already changed. Invalidate even if updating a heightmap subsequently fails.
        this.cc_invalidateSkyOcclusion();
        for (CloHeightmap map : this.heightmaps.values()) {
            map.update(Coords.blockToLocal(pos.getX()), pos.getY(), Coords.blockToLocal(pos.getZ()), state);
        }
    }

    /** Capture on the owning game thread; the returned local summary is immutable and safe to retain. */
    public CubeSkyOcclusion cc_snapshotSkyOcclusion() {
        if (this instanceof ImposterProtoCube imposter) {
            return ((CubeAccess) imposter.cc_getWrappedClo()).cc_snapshotSkyOcclusion();
        }
        if (this.cc_skyOcclusion == null) {
            this.cc_skyOcclusion = CubeSkyOcclusion.capture(this);
        }
        return this.cc_skyOcclusion;
    }

    public void cc_invalidateSkyOcclusion() {
        if (this instanceof ImposterProtoCube imposter) {
            ((CubeAccess) imposter.cc_getWrappedClo()).cc_invalidateSkyOcclusion();
        } else {
            this.cc_skyOcclusion = null;
            this.cc_lightRevision++;
            if (this instanceof LevelCube cube && cube.getLevel() != null && cube.getLevel().getChunkSource() instanceof CubeLightProvider provider) {
                provider.cc_onCubeBlockDataChanged(this);
            }
        }
    }

    /** Owner-thread revision of block data used by experimental lighting; saves and cache captures do not change it. */
    public long cc_getLightRevision() {
        if (this instanceof ImposterProtoCube imposter) {
            return ((CubeAccess) imposter.cc_getWrappedClo()).cc_getLightRevision();
        }
        return this.cc_lightRevision;
    }

    protected void cc_checkBlockPosition(BlockPos pos) {
        if (Coords.blockToCube(pos.getX()) != this.cubePos.getX() || Coords.blockToCube(pos.getY()) != this.cubePos.getY()
                || Coords.blockToCube(pos.getZ()) != this.cubePos.getZ()) {
            throw new IllegalArgumentException("Block " + pos + " is outside cube " + this.cubePos);
        }
    }

    public CubePos cc_getCubePos() {
        return cubePos;
    }

    @Override public CloPos cc_getCloPos() {
        return CloPos.cube(cubePos);
    }

    @TransformFromMethod(value = "getStartForStructure(Lnet/minecraft/world/level/levelgen/structure/Structure;)"
            + "Lnet/minecraft/world/level/levelgen/structure/StructureStart;", owner = @Ref(ChunkAccess.class))
    @Override public native @Nullable StructureStart getStartForStructure(Structure structure);

    @TransformFromMethod(value = "setStartForStructure(Lnet/minecraft/world/level/levelgen/structure/Structure;"
            + "Lnet/minecraft/world/level/levelgen/structure/StructureStart;)V", owner = @Ref(ChunkAccess.class))
    @Override public native void setStartForStructure(Structure structure, StructureStart structureStart);

    @TransformFromMethod(value = "getAllStarts()Ljava/util/Map;", owner = @Ref(ChunkAccess.class))
    @Override public native Map<Structure, StructureStart> getAllStarts();

    @TransformFromMethod(value = "setAllStarts(Ljava/util/Map;)V", owner = @Ref(ChunkAccess.class))
    @Override public native void setAllStarts(Map<Structure, StructureStart> structureStarts);

    @TransformFromMethod(value = "getReferencesForStructure(Lnet/minecraft/world/level/levelgen/structure/Structure;)Lit/unimi/dsi/fastutil/longs/LongSet;", owner = @Ref(ChunkAccess.class))
    @Override public native LongSet getReferencesForStructure(Structure structure);

    @TransformFromMethod(value = "addReferenceForStructure(Lnet/minecraft/world/level/levelgen/structure/Structure;J)V", owner = @Ref(ChunkAccess.class))
    @Override public native void addReferenceForStructure(Structure structure, long reference);

    @TransformFromMethod(value = "getAllReferences()Ljava/util/Map;", owner = @Ref(ChunkAccess.class))
    @Override public native Map<Structure, LongSet> getAllReferences();

    @TransformFromMethod(value = "setAllReferences(Ljava/util/Map;)V", owner = @Ref(ChunkAccess.class))
    @Override public native void setAllReferences(Map<Structure, LongSet> structureReferencesMap);

    @Override public boolean isYSpaceEmpty(int startY, int endY) {
        // TODO
        return false;
    }

    @Override public boolean isSectionEmpty(int sectionY) {
        // TODO
        return false;
    }

    @TransformFromMethod(value = "markUnsaved()V", owner = @Ref(ChunkAccess.class))
    @Override public native void markUnsaved();

    @TransformFromMethod(value = "tryMarkSaved()Z", owner = @Ref(ChunkAccess.class))
    @Override public native boolean tryMarkSaved();

    @TransformFromMethod(value = "isUnsaved()Z", owner = @Ref(ChunkAccess.class))
    @Override public native boolean isUnsaved();

    @Override public abstract ChunkStatus getPersistedStatus();

    @Override public ChunkStatus getHighestGeneratedStatus() {
        // In ChunkAccess this method is only used for below-zero retrogen; with no retrogen it does this
        return this.getPersistedStatus();
    }

    @Override public abstract void removeBlockEntity(BlockPos pos);

    @TransformFromMethod(value = "markPosForPostProcessing(Lnet/minecraft/core/BlockPos;)V", owner = @Ref(ChunkAccess.class))
    @Override public native void markPosForPostProcessing(BlockPos pos);

    @TransformFromMethod(value = "getPostProcessing()[Lit/unimi/dsi/fastutil/shorts/ShortList;", owner = @Ref(ChunkAccess.class))
    @Override public native ShortList[] getPostProcessing();

    @TransformFromMethod(value = "addPackedPostProcess(Lit/unimi/dsi/fastutil/shorts/ShortList;I)V", owner = @Ref(ChunkAccess.class))
    @Override public native void addPackedPostProcess(ShortList offsets, int index);

    @Override public void setBlockEntityNbt(CompoundTag tag) {
        int x = tag.getIntOr("x", 0);
        int y = tag.getIntOr("y", 0);
        int z = tag.getIntOr("z", 0);
        if (Coords.blockToCube(x) != this.cubePos.getX() || Coords.blockToCube(y) != this.cubePos.getY()
                || Coords.blockToCube(z) != this.cubePos.getZ()) {
            LOGGER.warn("Block entity {} found in a wrong cube, expected position from cube {}", tag, this.cubePos);
            x = Coords.localToBlock(this.cubePos.getX(), Coords.blockToLocal(x));
            y = Coords.localToBlock(this.cubePos.getY(), Coords.blockToLocal(y));
            z = Coords.localToBlock(this.cubePos.getZ(), Coords.blockToLocal(z));
        }
        this.pendingBlockEntities.put(new BlockPos(x, y, z), tag);
    }

    @TransformFromMethod(value = "getBlockEntityNbt(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/nbt/CompoundTag;", owner = @Ref(ChunkAccess.class))
    @Override public native @Nullable CompoundTag getBlockEntityNbt(BlockPos pos);

    @Override public abstract @Nullable CompoundTag getBlockEntityNbtForSaving(BlockPos pos, HolderLookup.Provider provider);

    @TransformFromMethod(value = "findBlockLightSources(Ljava/util/function/BiConsumer;)V", owner = @Ref(ChunkAccess.class))
    @Override public native void findBlockLightSources(BiConsumer<BlockPos, BlockState> output);

    @Override public void findBlocks(Predicate<BlockState> predicate, BiConsumer<BlockPos, BlockState> output) {
        // Vanilla now scans a vertical column directly; cubes must visit all three axes.
        this.findBlocks(predicate, (state, pos) -> predicate.test(state), output);
    }

    @Override public void findBlocks(
            Predicate<BlockState> predicate, java.util.function.BiPredicate<BlockState, BlockPos> fineFilter, BiConsumer<BlockPos, BlockState> output
    ) {
        BlockPos.MutableBlockPos mutableBlockPos = new BlockPos.MutableBlockPos();

        for (int y = 0; y < CubicConstants.DIAMETER_IN_SECTIONS; y++) {
            for (int z = 0; z < CubicConstants.DIAMETER_IN_SECTIONS; z++) {
                for (int x = 0; x < CubicConstants.DIAMETER_IN_SECTIONS; x++) {
                    LevelChunkSection levelchunksection = this.getSection(Coords.sectionToIndex(x, y, z));
                    if (levelchunksection.maybeHas(predicate)) {
                        BlockPos blockpos = this.cubePos.asSectionPos().offset(x, y, z).origin();

                        for (int sectionLocalY = 0; sectionLocalY < SectionPos.SECTION_SIZE; ++sectionLocalY) {
                            for (int sectionLocalZ = 0; sectionLocalZ < SectionPos.SECTION_SIZE; ++sectionLocalZ) {
                                for (int sectionLocalX = 0; sectionLocalX < SectionPos.SECTION_SIZE; ++sectionLocalX) {
                                    BlockState blockstate = levelchunksection.getBlockState(sectionLocalX, sectionLocalY, sectionLocalZ);
                                    mutableBlockPos.setWithOffset(blockpos, sectionLocalX, sectionLocalY, sectionLocalZ);
                                    if (fineFilter.test(blockstate, mutableBlockPos)) {
                                        output.accept(mutableBlockPos, blockstate);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Override public abstract TickContainerAccess<Block> getBlockTicks();

    @Override public abstract TickContainerAccess<Fluid> getFluidTicks();

    @TransformFromMethod(value = "canBeSerialized()Z", owner = @Ref(ChunkAccess.class))
    @Override public native boolean canBeSerialized();

    @Override public abstract ChunkAccess.PackedTicks getTicksForSerialization(long todoNameThis);

    @TransformFromMethod(value = "getUpgradeData()Lnet/minecraft/world/level/chunk/UpgradeData;", owner = @Ref(ChunkAccess.class))
    @Override public native UpgradeData getUpgradeData();

    @TransformFromMethod(value = "isOldNoiseGeneration()Z", owner = @Ref(ChunkAccess.class))
    @Override public native boolean isOldNoiseGeneration();

    @TransformFromMethod(value = "getBlendingData()Lnet/minecraft/world/level/levelgen/blending/BlendingData;", owner = @Ref(ChunkAccess.class))
    @Override public native @Nullable BlendingData getBlendingData();

    @TransformFromMethod(value = "getInhabitedTime()J", owner = @Ref(ChunkAccess.class))
    @Override public native long getInhabitedTime();

    @TransformFromMethod(value = "incrementInhabitedTime()V", owner = @Ref(ChunkAccess.class))
    @Override public native void incrementInhabitedTime();

    @TransformFromMethod(value = "setInhabitedTime(J)V", owner = @Ref(ChunkAccess.class))
    @Override public native void setInhabitedTime(long inhabitedTime);

    @TransformFromMethod(value = "getOrCreateOffsetList([Lit/unimi/dsi/fastutil/shorts/ShortList;I)Lit/unimi/dsi/fastutil/shorts/ShortList;", owner = @Ref(ChunkAccess.class))
    public static native ShortList getOrCreateOffsetList(ShortList[] packedPositions, int index);

    @TransformFromMethod(value = "isLightCorrect()Z", owner = @Ref(ChunkAccess.class))
    @Override public native boolean isLightCorrect();

    @TransformFromMethod(value = "setLightCorrect(Z)V", owner = @Ref(ChunkAccess.class))
    @Override public native void setLightCorrect(boolean lightCorrect);

    @TransformFromMethod(value = "getMinY()I", owner = @Ref(ChunkAccess.class))
    @Override public native int getMinY();

    // these are two semantically different methods that happen to share a name;
    // one is for accessing heightmaps, and the other for getting height of the world.
    // we match the method ordering of the vanilla ChunkAccess class, which doesn't group them.
    @SuppressWarnings("checkstyle:OverloadMethodsDeclarationOrder")
    @TransformFromMethod(value = "getHeight()I", owner = @Ref(ChunkAccess.class))
    @Override public native int getHeight();

    @Override public BiomeGenerationSettings carverBiome(Supplier<BiomeGenerationSettings> carverBiomeSettingsProvider) {
        throw new UnsupportedOperationException(); // TODO P3
    }

    @Override public Holder<Biome> getNoiseBiome(int x, int y, int z) {
        int section = Coords.sectionToIndex(net.minecraft.core.QuartPos.toSection(x), net.minecraft.core.QuartPos.toSection(y),
                net.minecraft.core.QuartPos.toSection(z));
        int mask = net.minecraft.core.QuartPos.fromBlock(SectionPos.SECTION_SIZE) - 1;
        return getSection(section).getNoiseBiome(x & mask, y & mask, z & mask);
    }

    @Override public void fillBiomesFromNoise(BiomeResolver resolver) {
        int quartSize = net.minecraft.core.QuartPos.fromBlock(SectionPos.SECTION_SIZE);
        for (int i = 0; i < sections.length; i++) {
            int x = net.minecraft.core.QuartPos.fromBlock(cc_getCubePos().minCubeX()) + Coords.indexToX(i) * quartSize;
            int y = net.minecraft.core.QuartPos.fromBlock(cc_getCubePos().minCubeY()) + Coords.indexToY(i) * quartSize;
            int z = net.minecraft.core.QuartPos.fromBlock(cc_getCubePos().minCubeZ()) + Coords.indexToZ(i) * quartSize;
            sections[i].fillBiomesFromNoise(resolver, x, y, z);
        }
        markUnsaved();
    }

    @Override public void collectBiomesInPalette(Set<Holder<Biome>> biomes) {
        for (LevelChunkSection section : this.sections) {
            section.getBiomes().forEachInPalette(biomes::add);
        }
    }

    @Override public @Nullable BelowZeroRetrogen getBelowZeroRetrogen() {
        return null; // No below-zero retrogen in cubic worlds :)
    }

    @Override public boolean isUpgrading() {
        return false; // Used for below-zero retrogen; not applicable to cubes
    }

    @TransformFromMethod(value = "getHeightAccessorForGeneration()Lnet/minecraft/world/level/LevelHeightAccessor;", owner = @Ref(ChunkAccess.class))
    @Override public native LevelHeightAccessor getHeightAccessorForGeneration();

    @Override public void initializeLightSources() {
        this.cc_snapshotSkyOcclusion();
    }

    @Override public ChunkSkyLightSources getSkyLightSources() {
        throw new UnsupportedOperationException("Cube-local sky occlusion is not a vanilla column sky light source map");
    }

    public static ProblemReporter.PathElement problemPath(CubePos cubePos) {
        return new CubePathElement(cubePos);
    }

    public ProblemReporter.PathElement problemPath() {
        return problemPath(this.cc_getCubePos());
    }

    record CubePathElement(CubePos pos) implements ProblemReporter.PathElement {
        @Override public String get() {
            return "cube@" + this.pos;
        }
    }
}
