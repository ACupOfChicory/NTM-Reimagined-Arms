package acup.gunfeel;

import java.util.Arrays;
import java.util.function.IntFunction;

import org.jspecify.annotations.Nullable;

/**
 * The bullet rules' answers for a block, kept by its state's number (BlockTable) till the rules are read anew
 * ({@link #clear}): its break rule, hit effect, ricochet, decals, path, hole and material. Not thread-safe: the
 * version asks under its rules' lock.
 */
public final class BlockRuleAnswers {
    public final Slot<BulletRuleText.@Nullable Break> breaks = new Slot<>();
    public final Slot<BulletRuleText.Effect> hits = new Slot<>();
    public final Slot<Boolean> ricochets = new Slot<>();
    public final Slot<Decals.Set> decals = new Slot<>();
    public final Slot<@Nullable String> paths = new Slot<>();
    public final Slot<String[]> holes = new Slot<>();
    public final Slot<Materials.Verdict> materials = new Slot<>();

    /** every answer forgotten */
    public void clear() {
        breaks.clear();
        hits.clear();
        ricochets.clear();
        decals.clear();
        paths.clear();
        holes.clear();
        materials.clear();
    }

    /** One answer per state's number, null among them: once made, kept till cleared. */
    public static final class Slot<T> {
        private static final Object NONE = new Object();
        private Object[] values = new Object[0];

        /** the answer kept for the state, else {@code make}'s, kept (in the slot as it is after making it) */
        @SuppressWarnings("unchecked")
        public T get(int state, IntFunction<T> make) {
            Object[] v = values;
            if (state < v.length && v[state] != null) return v[state] == NONE ? null : (T) v[state];
            T made = make.apply(state);
            v = values;
            if (state >= v.length) values = v = Arrays.copyOf(v, Math.max(64, Math.max(state + 1, v.length * 2)));
            v[state] = made == null ? NONE : made;
            return made;
        }

        void clear() {
            values = new Object[0];
        }
    }
}
