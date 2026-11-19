package acup.gunfeel.host;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * On a version's method that the game calls at a {@link HookPoint} (from an event or a mixin): the version's test
 * finds every point marked and every marked method called from the mod's code ({@code HookPointContract}).
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Hook {
    HookPoint value();
}
