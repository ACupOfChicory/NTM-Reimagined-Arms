package acup.gunfeel.host;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Every {@link HookPoint} is wired on this version: some method of its code is marked {@link Hook} with it, and every
 * marked method is called from the version's code (an event's listener, a mixin, a method reference). A version's
 * test extends this, reading its compiled classes (the marks are in the class files only).
 */
public abstract class HookPointContract {
    /** Every method of the version's code marked {@link Hook}: "owner.name(descriptor)" and its point. */
    protected abstract Map<String, HookPoint> marked() throws Exception;

    /** The methods of the version's code that something else in its code calls or names ("owner.name(descriptor)"). */
    protected abstract Set<String> called() throws Exception;

    /** Points this version leaves out on purpose, each with its reason in the test; none by default. */
    protected Set<HookPoint> notHere() {
        return EnumSet.noneOf(HookPoint.class);
    }

    @Test
    void everyPointHasAMethod() throws Exception {
        Set<HookPoint> missing = EnumSet.allOf(HookPoint.class);
        missing.removeAll(marked().values());
        missing.removeAll(notHere());
        assertEquals(EnumSet.noneOf(HookPoint.class), missing,
                "no method of this version is marked @Hook with these: the systems behind them never run here");
    }

    @Test
    void everyMarkedMethodIsCalled() throws Exception {
        Set<String> called = called();
        List<String> idle = new ArrayList<>();
        for (Map.Entry<String, HookPoint> e : marked().entrySet()) if (!called.contains(e.getKey())) idle.add(e.getKey() + " " + e.getValue());
        assertEquals(List.of(), idle, "marked @Hook but nothing calls them: an event or a mixin is missing");
    }

    @Test
    void theListIsNotEmpty() throws Exception {
        assertEquals(false, marked().isEmpty(), "found no @Hook at all: the class files were not read");
    }
}
