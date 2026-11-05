package io.github.opencubicchunks.cubicchunks.mixin.dasmsets;

import io.github.notstirred.dasm.api.annotations.redirect.sets.RedirectSet;

/** Vanilla has no loader-added calls. FML builds replace this extension point with their native redirects. */
public interface LoaderRedirectSet {
    @RedirectSet
    interface Global {}

    @RedirectSet
    interface Cube {}

    @RedirectSet
    interface Clo {}
}
