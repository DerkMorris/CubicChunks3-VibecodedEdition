package io.github.opencubicchunks.cubicchunks.world.level.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.phys.AABB;

/** Exact full-coordinate queries over visible entities, without packed section keys or a maximum entity size assumption. */
public final class CubeEntityGetter<T extends EntityAccess> implements LevelEntityGetter<T> {
    private final LevelEntityGetter<T> delegate;

    public CubeEntityGetter(LevelEntityGetter<T> delegate) {
        this.delegate = delegate;
    }

    @Override public @Nullable T get(int id) {
        return delegate.get(id);
    }

    @Override public @Nullable T get(UUID id) {
        return delegate.get(id);
    }

    @Override public <U extends T> void get(EntityTypeTest<T, U> type, AbortableIterationConsumer<U> consumer) {
        for (T entity : snapshot()) {
            U cast = type.tryCast(entity);
            if (cast != null && present(entity) && consumer.accept(cast).shouldAbort()) {
                return;
            }
        }
    }

    @Override public void get(AABB box, Consumer<T> consumer) {
        for (T entity : snapshot()) {
            if (present(entity) && entity.getBoundingBox().intersects(box)) {
                consumer.accept(entity);
            }
        }
    }

    @Override public <U extends T> void get(EntityTypeTest<T, U> type, AABB box, AbortableIterationConsumer<U> consumer) {
        for (T entity : snapshot()) {
            U cast = type.tryCast(entity);
            if (cast != null && present(entity) && entity.getBoundingBox().intersects(box) && consumer.accept(cast).shouldAbort()) {
                return;
            }
        }
    }

    @Override public Iterable<T> getAll() {
        return delegate.getAll();
    }

    private boolean present(T entity) {
        return delegate.get(entity.getId()) == entity;
    }

    private List<T> snapshot() {
        List<T> result = new ArrayList<>();
        delegate.getAll().forEach(result::add);
        return result;
    }
}
