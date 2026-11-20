package io.github.opencubicchunks.cubicchunks.mixin.access.common;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Entity.class)
public interface EntityBoundsAccess {
    @Invoker("makeBoundingBox") AABB cc_makeBoundingBox(Vec3 position);
}
