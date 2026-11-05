package io.github.notstirred.dasm.mod;

import io.github.notstirred.dasm.api.annotations.Dasm;
import io.github.notstirred.dasm.api.annotations.redirect.sets.RedirectSet;
import io.github.notstirred.dasm.api.annotations.selector.Ref;
import io.github.notstirred.dasm.api.annotations.transform.TransformFromMethod;
import io.github.notstirred.dasm.api.annotations.transform.AddUnusedParam;
import io.github.notstirred.dasm.api.provider.MappingsProvider;
import io.github.notstirred.dasm.util.CachingClassProvider;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.ParameterNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.List;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class DasmServiceTest {
    @Test
    void copiesAndExecutesMethodInJava25Class() throws Exception {
        CachingClassProvider provider = new CachingClassProvider(name -> {
            try (InputStream stream = getClass().getClassLoader().getResourceAsStream(name.replace('.', '/') + ".class")) {
                return stream == null ? Optional.empty() : Optional.of(stream.readAllBytes());
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        });
        DasmService service = new DasmService(MappingsProvider.IDENTITY, provider);
        DasmService.DasmTransform transform = service.scanForTransforms(Type.getType(TargetFixture.class));
        ClassNode target = provider.classNode(Type.getType(TargetFixture.class));
        assertEquals(Opcodes.V25, target.version);
        service.doTransform(target, transform.transform);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        target.accept(writer);
        byte[] bytes = writer.toByteArray();
        Class<?> transformed = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() {
                return defineClass(TargetFixture.class.getName(), bytes, 0, bytes.length);
            }
        }.define();
        assertEquals(42, transformed.getMethod("copied", int.class).invoke(null, 21));
    }

    @Test
    void preservesReflectiveMetadataWhenAddingAParameter() throws Exception {
        CachingClassProvider provider = new CachingClassProvider(name -> {
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(name.replace('.', '/') + ".class")) {
                if (input == null) return Optional.empty();
                byte[] bytes = input.readAllBytes();
                if (name.replace('/', '.').equals(ParameterSource.class.getName())) {
                    ClassNode source = new ClassNode();
                    new ClassReader(bytes).accept(source, 0);
                    source.methods.stream().filter(method -> method.name.equals("original")).findFirst().orElseThrow().parameters =
                            List.of(new ParameterNode("left", 0), new ParameterNode("right", 0));
                    ClassWriter writer = new ClassWriter(0);
                    source.accept(writer);
                    bytes = writer.toByteArray();
                }
                return Optional.of(bytes);
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        });
        DasmService service = new DasmService(MappingsProvider.IDENTITY, provider);
        DasmService.DasmTransform transform = service.scanForTransforms(Type.getType(ParameterTarget.class));
        ClassNode target = provider.classNode(Type.getType(ParameterTarget.class));
        service.doTransform(target, transform.transform);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        target.accept(writer);
        byte[] bytes = writer.toByteArray();
        Class<?> transformed = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() {
                return defineClass(ParameterTarget.class.getName(), bytes, 0, bytes.length);
            }
        }.define();
        var method = transformed.getMethod("copied", int.class, int.class, int.class);
        var parameters = method.getParameters();
        assertEquals(3, parameters.length);
        assertEquals("left", parameters[0].getName());
        assertEquals("right", parameters[2].getName());
        assertNull(parameters[1].getAnnotation(Marker.class));
        assertNotNull(parameters[2].getAnnotation(Marker.class));
        assertNull(method.getAnnotatedParameterTypes()[1].getAnnotation(Marker.class));
        assertNotNull(method.getAnnotatedParameterTypes()[2].getAnnotation(Marker.class));
        assertEquals(42, method.invoke(null, 4, 999, 2));
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.PARAMETER, ElementType.TYPE_USE})
    public @interface Marker {}

    public static class ParameterSource {
        public static int original(int left, @Marker int right) {
            return left * 10 + right;
        }
    }

    @Dasm(EmptySet.class)
    public static class ParameterTarget {
        @TransformFromMethod(value = "original(II)I", owner = @Ref(ParameterSource.class))
        public static native int copied(int left, @AddUnusedParam int middle, int right);
    }

    public static class SourceFixture {
        public static int original(int input) {
            return input * 2;
        }
    }

    @Dasm(EmptySet.class)
    public static class TargetFixture {
        @TransformFromMethod(value = "original(I)I", owner = @Ref(SourceFixture.class))
        public static native int copied(int input);
    }

    @RedirectSet
    public interface EmptySet {}
}
