package io.github.opencubicchunks.smoke;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Development-only phase coordination. Gameplay assertions always read native game state. */
final class ProbeSignals {
    final Path directory;

    ProbeSignals(Path directory) {
        this.directory = directory;
    }

    boolean has(String name) {
        return Files.isRegularFile(directory.resolve(name));
    }

    String read(String name) {
        try {
            return has(name) ? Files.readString(directory.resolve(name)).trim() : "";
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read probe signal " + name, e);
        }
    }

    void put(String name, String value) {
        try {
            Files.createDirectories(directory);
            Path temporary = directory.resolve(name + ".tmp");
            Files.writeString(temporary, value);
            Files.move(temporary, directory.resolve(name), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write probe signal " + name, e);
        }
    }

    void signal(String name) {
        if (!has(name))
            put(name, "done");
    }

    static void require(boolean condition, String message) {
        if (!condition)
            throw new IllegalStateException(message);
    }
}
