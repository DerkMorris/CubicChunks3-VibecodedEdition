package io.github.notstirred.dasm.mod;

import io.github.notstirred.dasm.api.provider.MappingsProvider;
import io.github.notstirred.dasm.util.ClassNodeProvider;

public class DasmBootstrap {
    private static boolean initialized = false;
    private static DasmExtension dasmExtension;

    public static DasmExtension init(MappingsProvider mappingsProvider, ClassNodeProvider classProvider) {
        if (initialized) {
            return dasmExtension;
        }
        initialized = true;
        return dasmExtension = DasmExtension.setup(mappingsProvider, classProvider);
    }
}
