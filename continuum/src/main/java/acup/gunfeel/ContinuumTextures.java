package acup.gunfeel;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

// NTM Continuum keeps most model textures where NTM 1.7.10 did: NEXT's "hbm:textures/block/models/x.png" is
// Continuum's "hbm:textures/models/x.png", "hbm:textures/block/x.png" its "hbm:textures/blocks/x.png". A line of the
// machine glass list (written for NEXT) holds for its own name and for Continuum's.
public final class ContinuumTextures {
    // moved to another folder as well, as Continuum's renderers name them
    private static final Map<String, String> MOVED = Map.of(
        "hbm:textures/block/models/radiolysis.png", "hbm:textures/models/machines/radiolysis.png",
        "hbm:textures/block/models/duchessgambit.png", "hbm:textures/models/misc/duchessgambit.png");

    private ContinuumTextures() {}

    // the texture's NEXT name and the names Continuum may give it
    public static Set<String> names(String next) {
        Set<String> out = new LinkedHashSet<>();
        out.add(next);
        if (MOVED.containsKey(next)) out.add(MOVED.get(next));
        if (next.startsWith("hbm:textures/block/models/")) out.add("hbm:textures/models/" + next.substring("hbm:textures/block/models/".length()));
        else if (next.startsWith("hbm:textures/block/")) out.add("hbm:textures/blocks/" + next.substring("hbm:textures/block/".length()));
        return out;
    }
}
