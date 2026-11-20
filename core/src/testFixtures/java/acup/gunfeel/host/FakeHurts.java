package acup.gunfeel.host;

import java.util.ArrayList;
import java.util.List;

/**
 * Hurts without the game, for core's tests: a hurt is a {@link Hurt} (which questions it answers yes, its direct
 * entity's kind, NTM's category, its type's name: each may be null), each call noted in {@link #calls} in order.
 */
public final class FakeHurts implements Hurts {
    /** a hurt: {@code yes} the bits (1 << question) {@link Hurts#is} answers true */
    public record Hurt(int yes, int directKind, String category, String typeName) {
    }

    public final List<String> calls = new ArrayList<>();

    @Override
    public boolean is(Object hurt, int what) {
        calls.add("is " + what);
        return (((Hurt) hurt).yes() & 1 << what) != 0;
    }

    @Override
    public int directKind(Object hurt) {
        calls.add("directKind");
        return ((Hurt) hurt).directKind();
    }

    @Override
    public String ntmCategory(Object hurt) {
        calls.add("ntmCategory");
        return ((Hurt) hurt).category();
    }

    @Override
    public String typeName(Object hurt) {
        calls.add("typeName");
        return ((Hurt) hurt).typeName();
    }
}
