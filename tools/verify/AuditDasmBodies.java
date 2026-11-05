package io.github.notstirred.dasm.mod;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.notstirred.dasm.api.provider.MappingsProvider;
import io.github.notstirred.dasm.util.CachingClassProvider;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Applies real DASM transforms, then checks references from the emitted cube
 * classes. This runs before Mixin: intentional signature placeholders that a
 * later Mixin redirects are reported separately, and JVM verification and
 * Mixin injection validity still require a real launch.
 */
public final class AuditDasmBodies {
    private static CachingClassProvider provider;
    private static final Map<String, ClassNode> transformed = new LinkedHashMap<>();
    private static final Set<String> findings = new TreeSet<>();
    private static final Map<String, Set<String>> mixinMethods = new LinkedHashMap<>();
    private static final Map<String, Set<String>> mixinFields = new LinkedHashMap<>();
    private static final Map<String, Set<String>> mixinInterfaces = new LinkedHashMap<>();
    private static final Map<String, Set<String>> mixinRewrites = new LinkedHashMap<>();

    private static Object value(AnnotationNode annotation, String key) {
        if (annotation.values == null) return null;
        for (int index = 0; index < annotation.values.size(); index += 2) {
            if (key.equals(annotation.values.get(index))) return annotation.values.get(index + 1);
        }
        return null;
    }

    private static List<AnnotationNode> annotations(List<AnnotationNode> visible, List<AnnotationNode> invisible) {
        List<AnnotationNode> all = new ArrayList<>();
        if (visible != null) all.addAll(visible);
        if (invisible != null) all.addAll(invisible);
        return all;
    }

    private static void scanMixins(Path classes) throws Exception {
        try (var paths = Files.walk(classes)) {
            for (Path path : paths.filter(path -> path.toString().endsWith(".class")).toList()) {
                ClassNode mixin = new ClassNode();
                new ClassReader(Files.readAllBytes(path)).accept(mixin, ClassReader.SKIP_CODE);
                AnnotationNode marker = annotations(mixin.visibleAnnotations, mixin.invisibleAnnotations).stream()
                        .filter(annotation -> annotation.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")).findFirst().orElse(null);
                if (marker == null) continue;
                List<String> owners = new ArrayList<>();
                if (value(marker, "value") instanceof List<?> values) {
                    for (Object type : values) owners.add(((Type) type).getInternalName());
                }
                if (value(marker, "targets") instanceof List<?> values) {
                    for (Object target : values) owners.add(target.toString().replace('.', '/'));
                }
                for (String owner : owners) {
                    mixinInterfaces.computeIfAbsent(owner, unused -> new HashSet<>()).addAll(mixin.interfaces);
                    mixin.fields.forEach(field -> {
                        if (annotations(field.visibleAnnotations, field.invisibleAnnotations).stream().noneMatch(annotation -> annotation.desc.endsWith("/Shadow;"))) {
                            mixinFields.computeIfAbsent(owner, unused -> new HashSet<>()).add(field.name + field.desc);
                        }
                    });
                    for (MethodNode method : mixin.methods) {
                        List<AnnotationNode> methodAnnotations = annotations(method.visibleAnnotations, method.invisibleAnnotations);
                        if (!method.name.startsWith("<") && methodAnnotations.stream().noneMatch(annotation -> annotation.desc.endsWith("/Shadow;"))) {
                            mixinMethods.computeIfAbsent(owner, unused -> new HashSet<>()).add(method.name + method.desc);
                        }
                        for (AnnotationNode annotation : methodAnnotations) {
                            if (!(annotation.desc.endsWith("/Redirect;") || annotation.desc.endsWith("/WrapOperation;"))) continue;
                            Object selectors = value(annotation, "method");
                            List<?> methods = selectors instanceof List<?> list ? list : selectors == null ? List.of() : List.of(selectors);
                            Object atValue = value(annotation, "at");
                            List<?> locations = atValue instanceof List<?> list ? list : atValue == null ? List.of() : List.of(atValue);
                            for (Object location : locations) {
                                if (!(location instanceof AnnotationNode at) || !(value(at, "target") instanceof String reference)) continue;
                                int ownerEnd = reference.indexOf(';');
                                if (!reference.startsWith("L") || ownerEnd < 0) continue;
                                String normalized = reference.substring(1, ownerEnd) + "." + reference.substring(ownerEnd + 1).replace(":", "");
                                for (Object selector : methods) {
                                    mixinRewrites.computeIfAbsent(owner + "." + selector, unused -> new HashSet<>()).add(normalized);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static ClassNode classNode(String owner) throws Exception {
        ClassNode result = transformed.get(owner);
        return result != null ? result : provider.classNode(Type.getObjectType(owner));
    }

    private static boolean hasMember(String owner, String name, String descriptor, boolean field, Set<String> visited) throws Exception {
        if (!visited.add(owner)) return false;
        if ((field ? mixinFields : mixinMethods).getOrDefault(owner, Set.of()).contains(name + descriptor)) return true;
        ClassNode node = classNode(owner);
        boolean found = field
                ? node.fields.stream().anyMatch(member -> member.name.equals(name) && member.desc.equals(descriptor))
                : node.methods.stream().anyMatch(member -> member.name.equals(name) && member.desc.equals(descriptor));
        if (found) return true;
        if (node.superName != null && hasMember(node.superName, name, descriptor, field, visited)) return true;
        for (String parent : node.interfaces) {
            if (hasMember(parent, name, descriptor, field, visited)) return true;
        }
        for (String parent : mixinInterfaces.getOrDefault(owner, Set.of())) {
            if (hasMember(parent, name, descriptor, field, visited)) return true;
        }
        return false;
    }

    private static void check(ClassNode source, MethodNode method, String owner, String name, String descriptor, boolean field) {
        if (!owner.startsWith("io/github/opencubicchunks/") && !owner.startsWith("net/minecraft/")) return;
        String reference = owner + "." + name + descriptor;
        try {
            if (hasMember(owner, name, descriptor, field, new HashSet<>())) return;
            boolean rewritten = mixinRewrites.getOrDefault(source.name + "." + method.name, Set.of()).contains(reference)
                    || mixinRewrites.getOrDefault(source.name + "." + method.name + method.desc, Set.of()).contains(reference);
            String kind = rewritten
                    ? "EXPECTED_MIXIN_REWRITE" : field ? "MISSING_FIELD" : "MISSING_METHOD";
            findings.add(kind + " " + source.name + "." + method.name + " -> " + reference);
        } catch (Exception exception) {
            findings.add("UNRESOLVED " + reference + " (" + exception.getMessage() + ")");
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected generated DASM config, output directory and compiled mod classes");
        scanMixins(Path.of(args[2]));
        provider = new CachingClassProvider(name -> {
            try (InputStream input = AuditDasmBodies.class.getClassLoader().getResourceAsStream(name.replace('.', '/') + ".class")) {
                return input == null ? Optional.empty() : Optional.of(input.readAllBytes());
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        });
        DasmService service = new DasmService(MappingsProvider.IDENTITY, provider);
        JsonObject config = JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
        List<DasmService.DasmTransform> transforms = new ArrayList<>();
        // Scan every set first, as the runtime bootstrap does, so added redirects
        // from mixins are registered before any method bodies are copied.
        for (String side : List.of("dasm", "client")) {
            if (!config.has(side)) continue;
            for (JsonElement entry : config.getAsJsonArray(side)) {
                transforms.add(service.scanForTransforms(Type.getObjectType(entry.getAsString().replace('.', '/'))));
            }
        }
        for (DasmService.DasmTransform transform : transforms) {
            ClassNode target = transform.target.primary;
            service.doTransform(target, transform.transform);
            transformed.put(target.name, target);
            ClassWriter writer = new ClassWriter(0);
            target.accept(writer);
            Path output = Path.of(args[1], target.name + ".class");
            Files.createDirectories(output.getParent());
            Files.write(output, writer.toByteArray());
        }
        for (ClassNode target : transformed.values()) {
            for (MethodNode method : target.methods) {
                if (method.parameters != null && method.parameters.size() != Type.getArgumentTypes(method.desc).length) {
                    findings.add("INVALID_PARAMETERS " + target.name + "." + method.name + method.desc);
                }
                for (AbstractInsnNode instruction : method.instructions) {
                    if (instruction instanceof FieldInsnNode field) {
                        check(target, method, field.owner, field.name, field.desc, true);
                    } else if (instruction instanceof MethodInsnNode call) {
                        check(target, method, call.owner, call.name, call.desc, false);
                    } else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                        for (Object argument : dynamic.bsmArgs) {
                            if (argument instanceof Handle handle) {
                                check(target, method, handle.getOwner(), handle.getName(), handle.getDesc(), handle.getTag() <= 4);
                            }
                        }
                    }
                }
            }
        }
        findings.forEach(System.out::println);
        long missing = findings.stream().filter(finding -> !finding.startsWith("EXPECTED_MIXIN_REWRITE")).count();
        System.out.println("Transformed classes: " + transformed.size() + "; unresolved members: " + missing);
        if (missing != 0) System.exit(1);
    }
}
