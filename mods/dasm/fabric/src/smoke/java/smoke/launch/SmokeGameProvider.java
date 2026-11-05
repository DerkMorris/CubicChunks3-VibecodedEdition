package smoke.launch;

import net.fabricmc.loader.impl.game.GameProvider;
import net.fabricmc.loader.impl.game.patch.GameTransformer;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.metadata.BuiltinModMetadata;
import net.fabricmc.loader.impl.util.Arguments;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/** Exercises the real Knot/Mixin pipeline without launching Minecraft. */
public final class SmokeGameProvider implements GameProvider {
    private final Arguments arguments = new Arguments();
    private final GameTransformer transformer = new GameTransformer() {
        @Override
        public byte[] transform(String name) { return null; }
    };

    public String getGameId() { return "dasm-smoke"; }
    public String getGameName() { return "DASM Knot smoke fixture"; }
    public String getRawGameVersion() { return "26.3"; }
    public String getNormalizedGameVersion() { return "26.3"; }
    public Collection<BuiltinMod> getBuiltinMods() {
        return List.of(new BuiltinMod(List.of(), new BuiltinModMetadata.Builder("minecraft", "26.3").build()));
    }
    public String getEntrypoint() { return "smoke.game.Target"; }
    public Path getLaunchDirectory() { return Path.of(System.getProperty("user.dir")); }
    public boolean requiresUrlClassLoader() { return false; }
    public Set<BuiltinTransform> getBuiltinTransforms(String name) { return Set.of(); }
    public boolean isEnabled() { return true; }
    public boolean locateGame(FabricLauncher launcher, String[] args) { arguments.parse(args); return true; }
    public void initialize(FabricLauncher launcher) { }
    public GameTransformer getEntrypointTransformer() { return transformer; }
    public void unlockClassPath(FabricLauncher launcher) { }
    public void launch(ClassLoader loader) {
        try {
            Object value = loader.loadClass("smoke.game.Target").getMethod("copied", int.class).invoke(null, 21);
            if (!Integer.valueOf(42).equals(value)) throw new AssertionError("Wrong transformed value: " + value);
            System.out.println("DASM_KNOT_SMOKE_OK: Java 25 method transformed and executed through Fabric Knot");
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("DASM Knot fixture failed", exception);
        }
    }
    public Arguments getArguments() { return arguments; }
    public String[] getLaunchArguments(boolean sanitize) { return arguments.toArray(); }
    public boolean canOpenErrorGui() { return false; }
    public boolean hasAwtSupport() { return false; }
    public String getRuntimeNamespace(String defaultNs) { return "official"; }
    public String getDefaultModDistributionNamespace(String defaultNs) { return "official"; }
}
