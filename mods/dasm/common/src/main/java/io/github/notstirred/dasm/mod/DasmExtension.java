package io.github.notstirred.dasm.mod;

import io.github.notstirred.dasm.api.provider.MappingsProvider;
import io.github.notstirred.dasm.exception.NoSuchTypeExists;
import io.github.notstirred.dasm.mod.util.Either;
import io.github.notstirred.dasm.transformer.data.ClassTransform;
import io.github.notstirred.dasm.transformer.data.MethodTransform;
import io.github.notstirred.dasm.util.ClassNodeProvider;
import io.github.notstirred.dasm.util.TypeUtil;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.ClassInfo;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.mixin.transformer.ext.Extensions;
import org.spongepowered.asm.mixin.transformer.ext.IExtension;
import org.spongepowered.asm.mixin.transformer.ext.ITargetClassContext;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

public class DasmExtension implements IExtension {
    public static DasmExtension extension;
    private final DasmService dasmService;

    private final Map<String, Either<List<MethodTransform>, ClassTransform>> transforms = new HashMap<>();

    public DasmExtension(MappingsProvider mappingsProvider, ClassNodeProvider classProvider) {
        this.dasmService = new DasmService(mappingsProvider, classProvider);
    }

    public boolean shouldApplyMixin(String targetClassName) {
        try {
            DasmService.DasmTransform dasmTransform = dasmService.scanForTransforms(Type.getObjectType(TypeUtil.classNameToInternalName(targetClassName)));
            Either<List<MethodTransform>, ClassTransform> targetTransforms = dasmTransform.transform;

            String key = dasmTransform.target.primary.name.replace('/', '.');
            targetTransforms.right().ifPresent(classTransform -> transforms.put(key, Either.right(classTransform)));
            targetTransforms.left().ifPresent(methodTransforms ->
                    transforms.computeIfAbsent(key, k -> Either.left(new ArrayList<>())).left().get()
                            .addAll(methodTransforms));
        } catch (NoSuchTypeExists e) {
            throw new RuntimeException(e);
        }
        return true;
    }

    @Override
    public boolean checkActive(MixinEnvironment environment) {
        environment.getMixinConfigs();
        return true;
    }

    @Override
    public void preApply(ITargetClassContext context) {
        String key = context.getClassInfo().getClassName();
        Either<List<MethodTransform>, ClassTransform> transforms = this.transforms.get(key);
        if (transforms != null) {
            this.dasmService.doTransform(context.getClassNode(), transforms);

            try {
                // ugly hack to add class metadata to mixin
                // based on
                // https://github.com/Chocohead/OptiFabric/blob/54fc2ef7533e43d1982e14bc3302bcf156f590d8/src/main/java/me/modmuss50/optifabric/compat/fabricrendererapi
                // /RendererMixinPlugin.java#L25:L44
                Method addMethod = ClassInfo.class.getDeclaredMethod("addMethod", MethodNode.class, boolean.class);
                addMethod.setAccessible(true);

                ClassInfo ci = ClassInfo.forName(context.getClassNode().name);
                Set<String> existingMethods = ci.getMethods().stream().map(x -> x.getName() + x.getDesc()).collect(Collectors.toSet());
                for (MethodNode method : context.getClassNode().methods) {
                    if (!existingMethods.contains(method.name + method.desc)) {
                        addMethod.invoke(ci, method, false);
                    }
                }
            } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @Override
    public void postApply(ITargetClassContext context) {
    }

    @Override
    public void export(MixinEnvironment env, String name, boolean force, ClassNode classNode) {

    }

    static DasmExtension setup(MappingsProvider mappingsProvider, ClassNodeProvider classProvider) {
        try {
            Extensions extensions = getExtensions();

            DasmExtension dasmExtension = new DasmExtension(mappingsProvider, classProvider);
            extension = dasmExtension;

            extensions.add(dasmExtension);

            return dasmExtension;
        } catch (RuntimeException e) {
            throw new RuntimeException(e);
        }
    }

    private static Extensions getExtensions() {
        IMixinTransformer transformer = (IMixinTransformer) MixinEnvironment.getCurrentEnvironment().getActiveTransformer();
        return (Extensions) transformer.getExtensions();
    }
}
