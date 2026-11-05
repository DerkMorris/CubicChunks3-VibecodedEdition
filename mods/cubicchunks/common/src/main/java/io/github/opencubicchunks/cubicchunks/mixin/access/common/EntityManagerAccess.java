package io.github.opencubicchunks.cubicchunks.mixin.access.common;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.LevelCallback;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(PersistentEntitySectionManager.class)
public interface EntityManagerAccess {
    @Accessor("sectionStorage") EntitySectionStorage<Entity> cc_sectionStorage();

    @Accessor("entityGetter") LevelEntityGetter<Entity> cc_entityGetter();

    @Accessor("callbacks") LevelCallback<Entity> cc_callbacks();

    @Invoker("startTracking") void cc_startTracking(EntityAccess entity);

    @Invoker("stopTracking") void cc_stopTracking(EntityAccess entity);

    @Invoker("startTicking") void cc_startTicking(EntityAccess entity);

    @Invoker("stopTicking") void cc_stopTicking(EntityAccess entity);
}
