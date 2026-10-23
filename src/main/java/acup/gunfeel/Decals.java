package acup.gunfeel;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// The textures a bullet leaves on a block, by style. Which style a block gets comes from its material (Set.of, the
// same material its piercing uses), changed by the "decal" rules (BulletRules.decal). A kind is one style: an index
// into files(). Each style is one texture, a grid of GRID x GRID variants (cell(): where a variant lies on it):
//   hole_<style> ("old": the mod's first 8 holes, repeated over the grid), scratch_<style> (a ricochet),
//   scorch_<style> (explosive and incendiary rounds).
// The textures are made by review/decals/render.js from the decal generator page. Their alpha is how dark the mark
// is; their grey is how much of the block's own colour shows in it (the vertex colour, Tracers): black in the
// deepest middle, the colour round it.
public final class Decals {
    private Decals() {
    }

    public static final List<String> HOLES = List.of("old", "spall", "shatter", "soot");
    public static final List<String> SCRATCHES = List.of("gouge", "burn", "lines");
    public static final List<String> SCORCHES = List.of("star", "blot", "crater");
    public static final int GRID = 8, VARIANTS = GRID * GRID;
    // the styles of a block no rule names (not on any material list): the old holes, a gouge, a blot
    public static final String HOLE = "old", SCRATCH = "gouge", SCORCH = "blot";

    private static final int FIRST_SCRATCH = HOLES.size(), FIRST_SCORCH = FIRST_SCRATCH + SCRATCHES.size();
    public static final int COUNT = FIRST_SCORCH + SCORCHES.size();

    private static final String[] FILES = new String[COUNT];
    static {
        for (int s = 0; s < HOLES.size(); s++) FILES[s] = "hole_" + HOLES.get(s);
        for (int s = 0; s < SCRATCHES.size(); s++) FILES[FIRST_SCRATCH + s] = "scratch_" + SCRATCHES.get(s);
        for (int s = 0; s < SCORCHES.size(); s++) FILES[FIRST_SCORCH + s] = "scorch_" + SCORCHES.get(s);
    }

    // the texture's file name in textures/effect/, without ".png"
    public static String file(int kind) {
        return FILES[kind];
    }

    public static List<String> files() {
        return Arrays.asList(FILES.clone());
    }

    // a hole of that style. An unknown style gives the default's.
    public static int hole(String style) {
        return index(HOLES, style, HOLE);
    }

    // the variant random in [0, 1) picks (NaN and anything outside: the nearest end)
    public static int variant(double random) {
        return random >= 0 ? Math.min(VARIANTS - 1, (int) (random * VARIANTS)) : 0;
    }

    // where (u, v) of a mark's own square (0..1 each) lies on its style's texture for that variant: in its cell, kept
    // a hair inside so the next cell's edge pixels never show
    public static float[] cell(int variant, float u, float v) {
        int c = Math.floorMod(variant, VARIANTS);
        float e = 1F / 512F;
        u = Math.min(1F - e, Math.max(e, u));
        v = Math.min(1F - e, Math.max(e, v));
        return new float[] {(c % GRID + u) / GRID, (c / GRID + v) / GRID};
    }

    public static int scratch(String style) {
        return FIRST_SCRATCH + index(SCRATCHES, style, SCRATCH);
    }

    public static int scorch(String style) {
        return FIRST_SCORCH + index(SCORCHES, style, SCORCH);
    }

    public static boolean isScratch(int kind) {
        return kind >= FIRST_SCRATCH && kind < FIRST_SCORCH;
    }

    public static boolean isScorch(int kind) {
        return kind >= FIRST_SCORCH && kind < COUNT;
    }

    // the style a rule names, or null when it names none of that list (a typo in a config line)
    public static String known(List<String> styles, String name) {
        if (name == null) return null;
        String n = name.toLowerCase(Locale.ROOT);
        return styles.contains(n) ? n : null;
    }

    private static int index(List<String> styles, String style, String def) {
        String k = known(styles, style);
        return styles.indexOf(k != null ? k : def);
    }

    // what a block gets: one style for each kind of mark
    public record Set(String hole, String scratch, String scorch) {
        public static final Set DEFAULT = new Set(HOLE, SCRATCH, SCORCH);

        // by the block's material, as its piercing: stone and concrete chip, glass cracks, soft ground takes a soft
        // hole, metal, machines and wood keep the old holes
        public static Set of(Materials.Material m) {
            return switch (m) {
                case STONE, CONCRETE -> new Set("spall", "gouge", "crater");
                case GLASS, TOUGH_GLASS, REINFORCED_GLASS, LAMINATE -> new Set("shatter", "lines", "star");
                case SOFT, SOIL -> new Set("soot", "burn", "blot");
                case METAL, STEEL, MACHINE -> new Set("old", "lines", "star");
                case WOOD, NONE, UNBREAKABLE -> DEFAULT;
            };
        }

        public static Set of(List<Map<String, String>> rules) {
            return of(DEFAULT, rules);
        }

        // `base` changed by the options of the decal rules that match a block, in file order: each style from the
        // last rule that names a known one (so a later line can change one and keep the others)
        public static Set of(Set base, List<Map<String, String>> rules) {
            String hole = base.hole(), scratch = base.scratch(), scorch = base.scorch();
            for (Map<String, String> o : rules) {
                String h = known(HOLES, o.get("hole")), s = known(SCRATCHES, o.get("scratch")), c = known(SCORCHES, o.get("scorch"));
                if (h != null) hole = h;
                if (s != null) scratch = s;
                if (c != null) scorch = c;
            }
            return new Set(hole, scratch, scorch);
        }
    }
}
