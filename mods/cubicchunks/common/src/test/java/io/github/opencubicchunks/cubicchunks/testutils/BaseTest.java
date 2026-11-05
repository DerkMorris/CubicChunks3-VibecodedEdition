package io.github.opencubicchunks.cubicchunks.testutils;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.CubicChunks;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class BaseTest {
    private static boolean initialized;

    @BeforeAll
    public static synchronized void setup() {
        if (initialized) {
            return;
        }
        SharedConstants.tryDetectVersion();
        CubicChunks.IS_IN_TEST = true;
        assertTrue(CanBeCubic.class.isAssignableFrom(Level.class),
                "Tests require Fabric Loader JUnit with the Cubic Chunks mixins and DASM adapter loaded; plain vanilla classes are not valid test subjects");
        Bootstrap.bootStrap();
        // Loader JUnit does not run the mod initializer; register the same synced serializer before registry freeze.
        io.github.opencubicchunks.cubicchunks.network.CubeEntityDataSerializers.register();
        // Fabric defers registry freezing to the game entry point, which Loader JUnit does not invoke.
        // Complete that same vanilla bootstrap before loading world registries and their tags.
        BuiltInRegistries.bootStrap();
        SharedConstants.IS_RUNNING_IN_IDE = true;
        initialized = true;
    }

    @AfterEach
    public void afterEach() {
        // For some reason mockito is keeping around inline mocks when no reference to them exists, so we force it to clear them after each test.
        // This was creating a strange issue where running a subset of tests would OOME only when running under gradle, but not intellij.
        // Probably a mockito bug?
        Mockito.framework().clearInlineMocks();
    }
}
