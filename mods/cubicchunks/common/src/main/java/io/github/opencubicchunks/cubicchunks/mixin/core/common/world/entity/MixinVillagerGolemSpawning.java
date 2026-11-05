package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.entity;

import java.util.Optional;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubeGolemSpawning;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.SpawnUtil;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Villager.class)
public abstract class MixinVillagerGolemSpawning {
    @WrapOperation(method = "spawnGolemIfNeeded", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/SpawnUtil;trySpawnMob(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/entity/EntitySpawnReason;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;IIILnet/minecraft/util/SpawnUtil$Strategy;Z)Ljava/util/Optional;"))
    private Optional<IronGolem> cc_spawnGolem(
            EntityType<IronGolem> type, EntitySpawnReason reason, ServerLevel level, BlockPos start, int attempts, int rangeXZ, int rangeY,
            SpawnUtil.Strategy strategy, boolean checkCollisions, Operation<Optional<IronGolem>> original
    ) {
        return ((CanBeCubic) level).cc_isCubic()
                ? CubeGolemSpawning.spawn(type, reason, level, start, attempts, rangeXZ, rangeY, strategy, checkCollisions)
                : original.call(type, reason, level, start, attempts, rangeXZ, rangeY, strategy, checkCollisions);
    }
}
