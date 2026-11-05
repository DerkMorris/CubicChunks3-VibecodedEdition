package io.github.notstirred.dasm.mod;

import io.github.notstirred.dasm.annotation.AnnotationParser;
import io.github.notstirred.dasm.annotation.AnnotationUtil;
import io.github.notstirred.dasm.annotation.parse.RefImpl;
import io.github.notstirred.dasm.annotation.parse.AddedParameter;
import io.github.notstirred.dasm.api.annotations.Dasm;
import io.github.notstirred.dasm.api.provider.MappingsProvider;
import io.github.notstirred.dasm.exception.NoSuchTypeExists;
import io.github.notstirred.dasm.mod.util.Either;
import io.github.notstirred.dasm.notify.Notification;
import io.github.notstirred.dasm.transformer.Transformer;
import io.github.notstirred.dasm.transformer.data.ClassTransform;
import io.github.notstirred.dasm.transformer.data.MethodTransform;
import io.github.notstirred.dasm.util.ClassNodeProvider;
import io.github.notstirred.dasm.util.NotifyStack;
import io.github.notstirred.dasm.util.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.TypeReference;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.ParameterNode;
import org.objectweb.asm.tree.TypeAnnotationNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class DasmService {
    private static final Logger logger = LogManager.getLogger("dasm");

    private final ClassNodeProvider classProvider;
    private final Transformer transformer;
    private final AnnotationParser annotationParser;

    public DasmService(MappingsProvider mappingsProvider, ClassNodeProvider classProvider) {
        this.classProvider = classProvider;
        this.transformer = new Transformer(classProvider, mappingsProvider);
        this.annotationParser = new AnnotationParser(this.classProvider);
    }

    class DasmTransform {
        final DasmTarget target;
        final Either<List<MethodTransform>, ClassTransform> transform;

        DasmTransform(DasmTarget target, Either<List<MethodTransform>, ClassTransform> transform) {
            this.target = target;
            this.transform = transform;
        }
    }

    DasmTransform scanForTransforms(Type dasmClassType) throws NoSuchTypeExists {
        DasmTarget target = getDasmTarget(dasmClassType);

        handleNotification(annotationParser.findDasmAnnotations(target.primary));
        Optional<Collection<MethodTransform>> methodTransformsPrimary = handleNotification(annotationParser.buildContext().buildMethodTargets(target.primary, ""));
        Optional<ClassTransform> classTransform = handleNotification(annotationParser.buildContext().buildClassTarget(target.primary));
        Optional<Collection<MethodTransform>> methodTransformsSecondary = target.secondary.flatMap(classNode -> {
            handleNotification(annotationParser.findDasmAnnotations(classNode));
            return handleNotification(annotationParser.buildContext().buildMethodTargets(classNode, ""));
        });

        List<MethodTransform> methodTransforms = Stream.of(methodTransformsPrimary, methodTransformsSecondary)
                .filter(Optional::isPresent).map(Optional::get)
                .flatMap(Collection::stream).collect(Collectors.toList());

        if (classTransform.isPresent()) {
            // TODO: nice error
            assert (!methodTransformsSecondary.isPresent() || methodTransformsSecondary.get().isEmpty()) && (!methodTransformsPrimary.isPresent() || methodTransformsPrimary.get().isEmpty()) : "Whole class transform WITH method transforms?";
            return new DasmTransform(target, Either.right(classTransform.get()));
        } else {
            return new DasmTransform(target, Either.left(methodTransforms));
        }
    }

    public ClassNode doTransform(ClassNode input, Either<List<MethodTransform>, ClassTransform> transform) {
        transform.left().ifPresent(methodTransforms -> {
            Transformer.TransformResult<List<MethodNode>> result = transformer.transform(input, methodTransforms);
            handleNotification(result.notifications());

            result.changed().forEach(method -> {
                methodTransforms.stream()
                        .filter(spec -> spec.dstMethodName().equals(method.name)
                                && spec.originalTransformData().methodNode().desc.equals(method.desc))
                        .findFirst().ifPresent(spec -> repairAddedParameterMetadata(method, spec.transformChanges().addedParameters()));
                // By default, Mixin will merge the native dasm transform method over the dasm output method, this is the easiest way to prevent it
                // Unfortunately mixin outputs a warning for every case. See MixinApplicatorStandard#isAlreadyMerged
                if (method.visibleAnnotations == null) method.visibleAnnotations = new ArrayList<>(1);
                method.visibleAnnotations.add(new AnnotationNode("Lorg/spongepowered/asm/mixin/Final;"));
            });

        });
        transform.right().ifPresent(classTransform -> {
            try {
                transformer.transform(input, classTransform);
            } catch (NoSuchTypeExists e) { // TODO: remove when dasm doesn't throw here.
                handleNotification(Collections.singletonList(new Notification(e.getMessage(), Notification.Kind.ERROR, e.getClass())));
            }
        });
        doDasmOut(input);
        return input;
    }

    /**
     * DASM 3.2 shifts bytecode locals for AddUnusedParam, but retains source
     * parameter attributes. Absent optional name attributes remain absent.
     * Partial annotation tables (for example, constructors omitting synthetic
     * parameters) are rejected because their positional convention is ambiguous.
     * This repairs metadata, not upstream wide-local or stack-frame rewriting.
     */
    static void repairAddedParameterMetadata(MethodNode method, List<AddedParameter> addedParameters) {
        if (addedParameters.isEmpty()) return;
        int argumentCount = Type.getArgumentTypes(method.desc).length;
        Set<Integer> addedIndices = addedParameters.stream().map(AddedParameter::index).collect(Collectors.toSet());
        int[] sourceToTarget = new int[argumentCount - addedIndices.size()];
        if ((method.visibleAnnotableParameterCount != 0 && method.visibleAnnotableParameterCount != sourceToTarget.length)
                || (method.invisibleAnnotableParameterCount != 0 && method.invisibleAnnotableParameterCount != sourceToTarget.length)) {
            throw new IllegalStateException("Unsupported partial parameter annotation table in " + method.name + method.desc);
        }
        for (int target = 0, source = 0; target < argumentCount; target++) {
            if (!addedIndices.contains(target)) sourceToTarget[source++] = target;
        }
        if (method.parameters != null) {
            if (method.parameters.size() != sourceToTarget.length) {
                throw new IllegalStateException("Unexpected source MethodParameters count in " + method.name + method.desc);
            }
            List<ParameterNode> parameters = new ArrayList<>(argumentCount);
            for (int target = 0, source = 0; target < argumentCount; target++) {
                parameters.add(addedIndices.contains(target) ? new ParameterNode(null, 0) : method.parameters.get(source++));
            }
            method.parameters = parameters;
        }
        method.visibleParameterAnnotations = shiftParameterAnnotations(method.visibleParameterAnnotations, argumentCount, sourceToTarget);
        method.invisibleParameterAnnotations = shiftParameterAnnotations(method.invisibleParameterAnnotations, argumentCount, sourceToTarget);
        if (method.visibleParameterAnnotations != null) method.visibleAnnotableParameterCount = argumentCount;
        if (method.invisibleParameterAnnotations != null) method.invisibleAnnotableParameterCount = argumentCount;
        shiftParameterTypeAnnotations(method.visibleTypeAnnotations, sourceToTarget);
        shiftParameterTypeAnnotations(method.invisibleTypeAnnotations, sourceToTarget);
    }

    @SuppressWarnings("unchecked")
    private static List<AnnotationNode>[] shiftParameterAnnotations(List<AnnotationNode>[] annotations, int argumentCount, int[] sourceToTarget) {
        if (annotations == null) return null;
        List<AnnotationNode>[] shifted = new List[argumentCount];
        for (int source = 0; source < sourceToTarget.length && source < annotations.length; source++) {
            shifted[sourceToTarget[source]] = annotations[source];
        }
        return shifted;
    }

    private static void shiftParameterTypeAnnotations(List<TypeAnnotationNode> annotations, int[] sourceToTarget) {
        if (annotations == null) return;
        for (TypeAnnotationNode annotation : annotations) {
            TypeReference reference = new TypeReference(annotation.typeRef);
            if (reference.getSort() == TypeReference.METHOD_FORMAL_PARAMETER) {
                annotation.typeRef = TypeReference.newFormalParameterReference(sourceToTarget[reference.getFormalParameterIndex()]).getValue();
            }
        }
    }


    static class DasmTarget {
        ClassNode primary;
        Optional<ClassNode> secondary;

        DasmTarget(ClassNode primary, Optional<ClassNode> secondary) {
            this.primary = primary;
            this.secondary = secondary;
        }
    }

    private DasmTarget getDasmTarget(Type dasmType) throws NoSuchTypeExists {
        ClassNode dasmClassNode = this.classProvider.classNode(dasmType);

        AnnotationNode dasmAnnotation = AnnotationUtil.getAnnotationIfPresent(dasmClassNode.invisibleAnnotations, Dasm.class);
        Optional<Type> targetSpecifier = Optional.empty();
        if (dasmAnnotation != null) {
            targetSpecifier = RefImpl.parseOptionalRefAnnotation((AnnotationNode) AnnotationUtil.getAnnotationValues(dasmAnnotation, Dasm.class).get("target"));
        }

        Optional<ClassNode> targetClassNode = Optional.empty();
        if (targetSpecifier.isPresent() && !targetSpecifier.get().equals(dasmType)) {
            targetClassNode = Optional.of(this.classProvider.classNode(targetSpecifier.get()));
        }

        // If there is a @Dasm target it is the primary and the dasm class is the secondary
        // otherwise the dasm class is the primary
        ClassNode primary = targetClassNode.orElse(dasmClassNode);
        Optional<ClassNode> secondary = targetClassNode.map(target -> dasmClassNode);

        return new DasmTarget(primary, secondary);
    }

    private void doDasmOut(ClassNode classNode) {
        if (!Boolean.getBoolean("dasm.debug.export")) return;
        ClassWriter classWriter = new ClassWriter(0);
        classNode.accept(classWriter);
        try {
            Path path = Paths.get(".dasm.out/APPLY/" + classNode.name.replace('.', '/') + ".class").toAbsolutePath();
            Files.createDirectories(path.getParent());
            Files.write(path, classWriter.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private <T> T handleNotification(Pair<T, List<Notification>> result) {
        handleNotification(result.second());
        return result.first();
    }

    private void handleNotification(NotifyStack notifyStack) {
        handleNotification(notifyStack.notifications());
    }

    private void handleNotification(List<Notification> notifications) {
        for (Notification notification : notifications) {
            switch (notification.kind) {
                case INFO:
                    logger.info(notification.message);
                    break;
                case WARNING:
                    logger.warn(notification.message);
                    break;
                case ERROR:
                    logger.fatal(notification.message);
                    break;
                default:
                    throw new IllegalStateException("Unknown DASM notification kind: " + notification.kind);
            }
        }
        if (notifications.stream().anyMatch(n -> n.kind == Notification.Kind.ERROR)) {
            throw new RuntimeException("DASM Failure, please see log output");
        }
    }
}
