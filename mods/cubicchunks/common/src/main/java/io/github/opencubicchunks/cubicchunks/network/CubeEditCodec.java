package io.github.opencubicchunks.cubicchunks.network;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Vanilla field limits plus room for framing within the serverbound custom-payload budget. */
public final class CubeEditCodec {
    public static final int MAX_TEXT_BYTES = 32000;
    public static final int MAX_STRING_LENGTH = 32767;
    public static final int SIGN_LINE_LENGTH = 384;
    public static final int SIGN_LINES = 4;
    public static final int STRUCTURE_DATA_LENGTH = 128;

    private CubeEditCodec() {}

    public static void string(String value, int maxLength) {
        Objects.requireNonNull(value, "text");
        if (value.length() > maxLength) {
            throw new IllegalArgumentException("Cube edit text exceeds its character limit");
        }
    }

    public static void textBudget(String... values) {
        int bytes = 0;
        for (String value : values) {
            bytes += value.getBytes(StandardCharsets.UTF_8).length;
        }
        if (bytes > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException("Cube edit text exceeds its packet budget");
        }
    }

    public static int bounded(int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException("Cube edit value is out of bounds");
        }
        return value;
    }
}
