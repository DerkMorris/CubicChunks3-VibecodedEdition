package io.github.notstirred.dasm.mod;

import io.github.notstirred.dasm.annotation.AnnotationUtil;
import io.github.notstirred.dasm.api.provider.MappingsProvider;
import io.github.notstirred.dasm.exception.NoSuchTypeExists;
import io.github.notstirred.dasm.util.ClassNodeProvider;
import io.github.notstirred.dasm.util.TypeUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.IMixinService;
import org.spongepowered.asm.service.MixinService;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.objectweb.asm.Opcodes.ACC_INTERFACE;

public abstract class BaseConfigPlugin implements IMixinConfigPlugin {
    protected static final Logger logger = LogManager.getLogger("dasm");

    protected DasmExtension extension;

    protected ClassNodeProvider classNodeProvider;

    private int invocations = 0;

    public BaseConfigPlugin() {
        classNodeProvider = classNodeProvider();
    }

    @Override
    public void onLoad(String mixinPackage) {
        this.extension = DasmBootstrap.init(mappingsProvider(), classNodeProvider);
    }

    protected abstract MappingsProvider mappingsProvider();

    protected abstract ClassNodeProvider classNodeProvider();

    protected abstract List<DasmConfig> dasmConfigs();

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        invocations++;
        if (invocations <= 2) {
            return true; // Must apply the first dummy
        } else if (invocations == 3) {
            DummyTargetsFunc dummyTargets = reflectIntoDummyTargets();

            List<DasmConfig> dasmConfigs = dasmConfigs();
            Set<String> dasmTypes = new HashSet<>();
            for (DasmConfig dasmConfig : dasmConfigs) {
                for (String dasmClass : dasmConfig.dasmClasses) {
                    this.extension.shouldApplyMixin(dasmClass);
                    dasmTypes.add(dasmClass);
                }
            }

            try {
                for (String dasmClass : dasmTypes) {
                    ClassNode classNode = classNodeProvider.classNode(Type.getObjectType(TypeUtil.classNameToInternalName(dasmClass)));
                    if (!AnnotationUtil.isAnnotationPresent(classNode.invisibleAnnotations, Mixin.class)) {
                        dummyTargets.add(dasmClass, (classNode.access & ACC_INTERFACE) != 0);
                    }
                }
            } catch (NoSuchTypeExists e) {
                throw new RuntimeException(e);
            }
            return false;  // Don't need to apply the second dummy mixin
        }
        return true; // Always apply the mixins we added in invoc 1/2
    }

    private static DummyTargetsFunc reflectIntoDummyTargets() {
        try {
            // Knot installs the active Mixin transformer before configs are
            // prepared. Do not reach into Forge's unrelated launch service.
            Object transformer = MixinEnvironment.getCurrentEnvironment().getActiveTransformer();
            if (transformer == null) {
                throw new IllegalStateException("DASM requires an active Mixin transformer");
            }

            Class<?> mixinTransformerClass = Class.forName("org.spongepowered.asm.mixin.transformer.MixinTransformer");
            Field processorField = mixinTransformerClass.getDeclaredField("processor");
            processorField.setAccessible(true);
            Object processor = processorField.get(transformer);

            Class<?> mixinProcessorClass = Class.forName("org.spongepowered.asm.mixin.transformer.MixinProcessor");
            Field pendingConfigsField = mixinProcessorClass.getDeclaredField("pendingConfigs");
            pendingConfigsField.setAccessible(true);
            List<IMixinConfig> pendingConfigs = (List<IMixinConfig>) pendingConfigsField.get(processor);

            IMixinConfig config = pendingConfigs.stream().filter(c -> c.getName().equals("dasm-mod.mixins.json")).findFirst()
                    .orElseThrow(() -> new RuntimeException("Dasm failed to initialize, missing dasm mixin config"));

            Class<?> mixinConfigClass = Class.forName("org.spongepowered.asm.mixin.transformer.MixinConfig");
            Field pendingMixinsField = mixinConfigClass.getDeclaredField("pendingMixins");
            pendingMixinsField.setAccessible(true);
            IMixinInfo pendingMixin = ((List<IMixinInfo>) pendingMixinsField.get(config)).get(0); // There must be one as invoc 1 has already happened

            Class<?> mixinInfoClass = Class.forName("org.spongepowered.asm.mixin.transformer.MixinInfo");
            Field declaredTargetsField = mixinInfoClass.getDeclaredField("declaredTargets");
            declaredTargetsField.setAccessible(true);
            List<Object> classTargets = (List<Object>) declaredTargetsField.get(pendingMixin);

            Class<?> declaredTargetClass = Class.forName("org.spongepowered.asm.mixin.transformer.MixinInfo$DeclaredTarget");
            Constructor<?> declaredTargetsConstructor = declaredTargetClass.getDeclaredConstructor(String.class, boolean.class);
            declaredTargetsConstructor.setAccessible(true);

            IMixinInfo pendingMixinInterface = ((List<IMixinInfo>) pendingMixinsField.get(config)).get(1); // There must be one as invoc 1 has already happened
            List<Object> interfaceTargets = (List<Object>) declaredTargetsField.get(pendingMixinInterface);

            classTargets.clear();
            interfaceTargets.clear();

            return (target, isInterface) -> {
                try {
                    Object declaredTarget = declaredTargetsConstructor.newInstance(target, false);
                    if (isInterface) {
                        interfaceTargets.add(declaredTarget);
                    } else {
                        classTargets.add(declaredTarget);
                    }
                } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
                    throw new RuntimeException(e);
                }
            };
        } catch (NoSuchMethodException | IllegalAccessException | ClassNotFoundException | NoSuchFieldException e) {
            throw new RuntimeException("Failed to initialise DASM", e);
        }
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @FunctionalInterface
    interface DummyTargetsFunc {
        void add(String target, boolean isInterface);
    }
}
