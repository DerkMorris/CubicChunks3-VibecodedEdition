import com.google.gson.Gson;
import io.github.opencubicchunks.javaheaders.Config;
import io.github.opencubicchunks.javaheaders.JavaHeadersTransformer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/** Links selected Core tests while leaving the packaged Core implementation unchanged. */
public final class LinkHeaders {
    private static final String HEADERS = "io/github/opencubicchunks/cc_core/minecraft/";

    public static void main(String[] args) throws IOException {
        if (args.length != 4) {
            throw new IllegalArgumentException("Expected: javaHeaders.json raw-core.jar compiled-tests.jar linked-tests.jar");
        }
        Path output = Path.of(args[3]);
        Path input = output.resolveSibling("tests-with-headers.jar");
        Set<String> entries = new HashSet<>();
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(input))) {
            copyClasses(Path.of(args[2]), jar, entries, false);
            copyClasses(Path.of(args[1]), jar, entries, true);
        }
        Config config = new Gson().fromJson(Files.readString(Path.of(args[0])), Config.class);
        // The old linker logs every rewritten instruction at INFO; keep warnings and failures visible.
        java.util.logging.Logger.getLogger("io.github.opencubicchunks.dasm").setLevel(java.util.logging.Level.WARNING);
        JavaHeadersTransformer.transformCoreLibrary(config, input.toFile(), output.toFile());
        try (JarFile linked = new JarFile(output.toFile())) {
            if (linked.stream().anyMatch(entry -> entry.getName().startsWith(HEADERS)
                    && entry.getName().substring(HEADERS.length()).startsWith("MC"))) {
                throw new IllegalStateException("Unlinked Minecraft header classes remain in the test artifact");
            }
        }
    }

    private static void copyClasses(Path source, JarOutputStream output, Set<String> entries, boolean onlyHeaders) throws IOException {
        try (JarFile jar = new JarFile(source.toFile())) {
            for (JarEntry entry : jar.stream().toList()) {
                String name = entry.getName();
                if (entry.isDirectory() || !name.endsWith(".class") || (onlyHeaders && !name.startsWith(HEADERS)) || !entries.add(name)) {
                    continue;
                }
                output.putNextEntry(new JarEntry(name));
                try (var input = jar.getInputStream(entry)) {
                    input.transferTo(output);
                }
                output.closeEntry();
            }
        }
    }
}
