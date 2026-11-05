package smoke.game;

import io.github.notstirred.dasm.api.annotations.Dasm;
import io.github.notstirred.dasm.api.annotations.redirect.sets.RedirectSet;
import io.github.notstirred.dasm.api.annotations.selector.Ref;
import io.github.notstirred.dasm.api.annotations.transform.TransformFromMethod;

@Dasm(Target.EmptySet.class)
public class Target {
    @TransformFromMethod(value = "original(I)I", owner = @Ref(Source.class))
    public static native int copied(int value);

    @RedirectSet
    public interface EmptySet { }
}
