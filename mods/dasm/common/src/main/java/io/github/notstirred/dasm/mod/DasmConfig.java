package io.github.notstirred.dasm.mod;

import com.google.gson.annotations.SerializedName;

import java.util.List;

public class DasmConfig {
    public final @SerializedName("requiredVersion") String requiredVersion;
    public final @SerializedName("dasm") List<String> dasmClasses;

    public DasmConfig(String requiredVersion, List<String> dasmClasses) {
        this.requiredVersion = requiredVersion;
        this.dasmClasses = dasmClasses;
    }
}
