package io.github.opencubicchunks.cubicchunks.mixin.test.common.server.level;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.world.level.CloPos;
import io.github.opencubicchunks.cubicchunks.world.level.chunklike.CloAccess;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightManager;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exercises the actual production save/load/unload paths; the storage substitute is not installed for these tests. */
@Mixin(value = ChunkMap.class, priority = 900)
public interface ChunkMapPersistenceTestAccess {
    @Dynamic("Added by MixinChunkMap") @Invoker("cc_save") boolean cc_testSaveAsync(CloAccess clo);

    @Dynamic("Added by MixinChunkMap") @Invoker("cc_awaitCubeSaves") void cc_testAwaitSaves();

    @Dynamic("Added by MixinChunkMap") @Invoker("cc_scheduleChunkLoad") CompletableFuture<CloAccess> cc_testLoad(CloPos pos);

    @Dynamic("Added by MixinChunkMap") @Invoker("cc_scheduleUnload") void cc_testScheduleUnload(long pos, ChunkHolder holder);

    @Dynamic("Added by MixinChunkMap") @Invoker("cc_processUnloads") void cc_testProcessUnloadsAsync(BooleanSupplier haveTime);

    @Invoker("saveAllChunks") void cc_testSaveAllChunks(boolean flush);

    @Invoker("getPoiManager") net.minecraft.world.entity.ai.village.poi.PoiManager cc_testPoiManager();

    @Accessor("visibleChunkMap") Long2ObjectLinkedOpenHashMap<ChunkHolder> cc_testVisibleChunkMap();

    @Accessor("updatingChunkMap") Long2ObjectLinkedOpenHashMap<ChunkHolder> cc_testUpdatingChunkMap();

    @Accessor("pendingUnloads") Long2ObjectLinkedOpenHashMap<ChunkHolder> cc_testPendingUnloads();

    @Accessor("mainThreadExecutor") BlockableEventLoop<Runnable> cc_testMainThreadExecutor();

    @Dynamic("Added by MixinChunkMap") @Invoker("cc_findLightingSource") CubeLightManager.Source cc_testFindLightingSource(CubePos pos);

    @Dynamic("Added by MixinChunkMap") @Accessor("cc_cubeLightManager") void cc_testSetCubeLightManager(CubeLightManager manager);

    @Dynamic("Added by MixinChunkMap") @Accessor("cc_cubeLightManager") CubeLightManager cc_testCubeLightManager();

    @Dynamic("Added by MixinChunkMap") @Invoker("cc_closeCubeLighting") void cc_testCloseCubeLighting();

    @Dynamic("Added by MixinChunkMap") @Invoker("cc_tick") void cc_testTick();

    @Dynamic("Added by MixinChunkMap") @Invoker("cc_onCubeLightPublicationChanged") void cc_testLightPublicationChanged(long epoch, Set<CubePos> affected);

    @Dynamic("Added by MixinChunkMap") @Invoker("cc_tickCubeLighting") void cc_testTickCubeLighting();
}
