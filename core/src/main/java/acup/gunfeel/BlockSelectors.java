package acup.gunfeel;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Whether a bullet rule's selectors take a block, by its facts (BlockFacts: what the version's table holds for its
 * state, the tags as the game had them when the facts were made). Any one selector, in order, the first that takes
 * it: {@code *} any block; {@code #tag} one of its tags; {@code @sound} one of the names of its sound
 * (BlockFacts.sound, "a/b" for one sound under two names; a mod's own, "?", has none); {@code %mod} a block of that
 * namespace that is a machine or not a full cube ({@code partial}: the version's answer, asked only here);
 * {@code $material} its material by the rules ({@code material}, asked only here), any case; {@code ~regex} its id
 * whole; else its id as the game reads the selector (ResourceIds).
 */
public final class BlockSelectors {
    private BlockSelectors() {
    }

    private static final Map<String, Pattern> PATTERNS = new ConcurrentHashMap<>();

    public static boolean matches(List<String> sel, BlockFacts f, Supplier<Materials.Material> material, BooleanSupplier partial) {
        String id = f.id();
        for (String s : sel) {
            if (s.equals("*")) return true;
            if (s.startsWith("#")) {
                String t = ResourceIds.tryParse(s.substring(1));
                if (t != null && f.tags().contains(t)) return true;
            } else if (s.startsWith("@")) {
                if (sound(f.sound(), s.substring(1).toLowerCase(Locale.ROOT))) return true;
            } else if (s.startsWith("%")) {
                if (f.namespace().equals(s.substring(1)) && partial.getAsBoolean()) return true;
            } else if (s.startsWith("$")) {
                if (material.get().name().equalsIgnoreCase(s.substring(1))) return true;
            } else if (idMatches(s, id)) return true;
        }
        return false;
    }

    // one of the sound's names ("a/b": two), none for "?"
    private static boolean sound(String names, String want) {
        if (names.equals("?")) return false;
        int from = 0;
        while (true) {
            int to = names.indexOf('/', from);
            if (to < 0) to = names.length();
            if (to - from == want.length() && names.startsWith(want, from)) return true;
            if (to == names.length()) return false;
            from = to + 1;
        }
    }

    /** {@code ~regex}: the id whole (a bad regex takes nothing); else the id as the game reads the selector */
    public static boolean idMatches(String sel, String id) {
        if (sel.startsWith("~")) {
            Pattern p = PATTERNS.computeIfAbsent(sel, s -> {
                try {
                    return Pattern.compile(s.substring(1));
                } catch (RuntimeException e) {
                    return Pattern.compile("(?!)");
                }
            });
            return p.matcher(id).matches();
        }
        String want = ResourceIds.tryParse(sel);
        return want != null && want.equals(id);
    }
}
