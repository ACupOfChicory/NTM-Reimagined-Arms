package acup.gunfeel;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * The fence families of a bullet's way through a block (bullet rules "path"; PassableClip): a fence, a fence gate, a
 * wall, the bamboo ones. Per family the properties that change its model and their values, as tools/fence_shapes.py
 * makes them, and the key of the family's cells for a block with these properties. PassableClip names them for its
 * callers (PassableClip.FAMILIES, familyKey); the block's own family by its class stays there.
 */
public final class FenceFamilies {
    private FenceFamilies() {
    }

    // the family a block takes: the path rule's when it names one (another path: none), else its class's unless it
    // has a mask
    public static @Nullable String family(@Nullable String path, @Nullable String byClass, boolean masked) {
        if (path != null) return FAMILIES.containsKey(path) ? path : null;
        return masked ? null : byClass;
    }

    // the words of a "path" bullet rule: the four ways and the families
    public static final Set<String> PATH_WORDS = Set.of("hitbox", "pixels", "model", "through", "fence", "fence_gate", "wall",
            "bamboo_fence", "bamboo_fence_gate");

    // per family the properties that change its model, their values, the first one the default (for a block made a
    // fence by a rule without the fence's sides: a post), as tools/fence_shapes.py makes them
    private static final List<String> BOOL = List.of("false", "true");
    private static final Map<String, List<String>> FENCE = Map.of("east", BOOL, "north", BOOL, "south", BOOL, "west", BOOL);
    private static final Map<String, List<String>> GATE = Map.of("facing", List.of("north", "east", "south", "west"), "in_wall", BOOL,
            "open", BOOL);
    private static final List<String> SIDE = List.of("none", "low", "tall");
    public static final Map<String, Map<String, List<String>>> FAMILIES = Map.of("fence", FENCE, "fence_gate", GATE,
            "wall", Map.of("east", SIDE, "north", SIDE, "south", SIDE, "west", SIDE, "up", List.of("true", "false")),
            "bamboo_fence", FENCE, "bamboo_fence_gate", GATE);

    // the key of the family's cells for a block with these properties (the family's own, the rest left out; one it
    // lacks or with a value the family has not takes the default)
    public static String familyKey(String family, Map<String, String> props) {
        Map<String, List<String>> own = FAMILIES.get(family);
        StringBuilder k = new StringBuilder("~").append(family).append('[');
        String sep = "";
        for (String name : new java.util.TreeSet<>(own.keySet())) {
            String v = props.get(name);
            if (v == null || !own.get(name).contains(v)) v = own.get(name).getFirst();
            k.append(sep).append(name).append('=').append(v);
            sep = ",";
        }
        return k.append(']').toString();
    }
}
