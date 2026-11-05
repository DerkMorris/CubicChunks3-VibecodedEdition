package io.github.opencubicchunks.cubicchunks.mixin.test.common.server.level;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.LevelLoadListener;
import net.minecraft.world.level.storage.ServerLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(MinecraftServer.class)
public interface MinecraftServerTestAccess {

    @Invoker(value = "prepareLevels") void invoke_prepareLevels();

    @Invoker("stopServer") void invoke_stopServer();

    @Invoker(value = "setInitialSpawn") static void invoke_setInitialSpawn(
            ServerLevel serverLevel, ServerLevelData serverLevelData, boolean generateBonusChest, boolean debug, LevelLoadListener listener
    ) {
        throw new AssertionError("Mixin invoker was not applied");
    }
}
