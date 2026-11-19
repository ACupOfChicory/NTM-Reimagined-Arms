package acup.gunfeel;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import acup.gunfeel.BulletRuleText.Break;
import acup.gunfeel.BulletRuleText.Effect;
import acup.gunfeel.BulletRuleText.Rule;
import acup.gunfeel.BulletRuleText.Sound;
import acup.gunfeel.BulletRuleText.Whizz;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

// What bullets do to the blocks and creatures they hit, besides NTM's damage: break blocks, particles, sounds.
// Rules come from ntmgunfeel-bullets.txt in the mod jar, then config/ntm-gunfeel-bullets.txt (its lines win: of the
// rules that match, the last one counts). The server acts on break, material, path, ricochet, entity and kill lines; the
// client draws by hit, decal, hole, whizz and fly, and predicts the server's by the same lines. On another game's server
// the client takes the server's lines of the server's kinds instead of its own (ServerSettings).
public final class BulletRules extends BulletRuleText {
    private BulletRules() {
    }

    private static final Map<BlockState, java.util.Optional<Break>> BREAKS = new java.util.IdentityHashMap<>();
    private static final Map<BlockState, Effect> HITS = new java.util.IdentityHashMap<>();
    private static final Map<BlockState, Boolean> RICOCHETS = new java.util.IdentityHashMap<>();
    private static final Map<BlockState, Decals.Set> DECALS = new java.util.IdentityHashMap<>();
    private static final Map<BlockState, java.util.Optional<String>> PATHS = new java.util.IdentityHashMap<>();
    private static final Map<BlockState, String[]> HOLES = new java.util.IdentityHashMap<>();
    // goes up each time the rules are read anew (PassableClip forgets the shapes it took from them)
    private static int generation;

    // the rules' reading the caches above were worked out of
    private static int readingSeen;

    private static synchronized List<Rule> rules() {
        List<Rule> all = load();
        if (readingSeen != readings) {
            readingSeen = readings;
            BREAKS.clear();
            HITS.clear();
            RICOCHETS.clear();
            DECALS.clear();
            PATHS.clear();
            HOLES.clear();
            generation++;
            MATERIALS.clear();
            materialRules = null;
        }
        return all;
    }

    // bullets pierce machines (material machine) only when the server says so (off by default)
    public static final String MACHINES_KEY = "bulletPierceMachines";

    // ---- matching

    private static final Map<String, SoundType> SOUND_TYPES = soundTypes();

    private static Map<String, SoundType> soundTypes() {
        Map<String, SoundType> out = new HashMap<>();
        for (Field f : SoundType.class.getFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || f.getType() != SoundType.class) continue;
            try {
                out.put(f.getName().toLowerCase(Locale.ROOT), (SoundType) f.get(null));
            } catch (IllegalAccessException e) {
                // skip
            }
        }
        return out;
    }

    private static final Map<String, Pattern> PATTERNS = new HashMap<>();

    private static boolean idMatches(String sel, Identifier id) {
        if (sel.startsWith("~")) {
            Pattern p = PATTERNS.computeIfAbsent(sel, s -> {
                try {
                    return Pattern.compile(s.substring(1));
                } catch (RuntimeException e) {
                    return Pattern.compile("(?!)");
                }
            });
            return p.matcher(id.toString()).matches();
        }
        Identifier want = Identifier.tryParse(sel);
        return want != null && want.equals(id);
    }

    public static boolean blockMatches(List<String> sel, BlockState state) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        for (String s : sel) {
            if (s.equals("*")) return true;
            if (s.startsWith("#")) {
                Identifier t = Identifier.tryParse(s.substring(1));
                if (t != null && state.is(TagKey.create(Registries.BLOCK, t))) return true;
            } else if (s.startsWith("@")) {
                SoundType st = SOUND_TYPES.get(s.substring(1).toLowerCase(Locale.ROOT));
                if (st != null && state.getSoundType() == st) return true;
            } else if (s.startsWith("%")) {
                if (id.getNamespace().equals(s.substring(1)) && machineOrPartial(state)) return true;
            } else if (s.startsWith("$")) {
                if (material(state).material().name().equalsIgnoreCase(s.substring(1))) return true;
            } else if (idMatches(s, id)) return true;
        }
        return false;
    }

    private static final Map<BlockState, Boolean> MACHINE = new java.util.concurrent.ConcurrentHashMap<>();

    // %mod: a machine (a block entity, drawn by a renderer) or a block that is not a full cube
    private static boolean machineOrPartial(BlockState state) {
        return MACHINE.computeIfAbsent(state, st -> {
            if (st.hasBlockEntity() || st.getRenderShape() != net.minecraft.world.level.block.RenderShape.MODEL) return true;
            try {
                return !net.minecraft.world.level.block.Block.isShapeFullBlock(
                        st.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, net.minecraft.core.BlockPos.ZERO));
            } catch (RuntimeException e) {
                return true;
            }
        });
    }

    static boolean entityMatches(List<String> sel, Entity e) {
        Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
        for (String s : sel) {
            if (s.equals("*")) return true;
            if (s.startsWith("#")) {
                Identifier t = Identifier.tryParse(s.substring(1));
                if (t != null && e.is(TagKey.create(Registries.ENTITY_TYPE, t))) return true;
            } else if (idMatches(s, id)) return true;
        }
        return false;
    }

    private static @Nullable Rule last(String kind, java.util.function.Predicate<Rule> match) {
        return BulletRuleText.last(rules(), kind, match);
    }

    // ---- answers

    public static synchronized @Nullable Break breakRule(BlockState state) {
        rules();
        java.util.Optional<Break> known = BREAKS.get(state);
        if (known != null) return known.orElse(null);
        Rule r = last("break", x -> blockMatches(x.selectors(), state));
        Break out = BulletRuleText.breakOf(r);
        BREAKS.put(state, java.util.Optional.ofNullable(out));
        return out;
    }

    private static final Map<BlockState, Materials.Verdict> MATERIALS = new java.util.IdentityHashMap<>();
    private static @Nullable List<Materials.Rule> materialRules;

    // what a bullet meets in this block, for piercing it: its material by the "material" lines, else as Materials
    // works it out from the block; once per block state (the rules' reload starts over)
    public static synchronized Materials.Verdict material(BlockState state) {
        List<Rule> all = rules();
        Materials.Verdict known = MATERIALS.get(state);
        if (known != null) return known;
        if (materialRules == null) materialRules = BulletRuleText.materialRules(all);
        Materials.Verdict v;
        try {
            v = Materials.classify(BlockFactsOf.of(state), materialRules);
        } catch (RuntimeException e) {
            // a block that can't tell its facts: stops bullets, as before materials
            v = new Materials.Verdict(Materials.Material.UNBREAKABLE, 1F, Materials.Source.UNBREAKABLE, "no facts");
        }
        MATERIALS.put(state, v);
        return v;
    }

    // bullets always fly through it (a "break ... through" or "path ... through" rule); it still counts the hits and
    // breaks by its break rule
    public static boolean through(BlockState state) {
        if (!anyThrough()) return false;
        if ("through".equals(path(state))) return true;
        Break b = breakRule(state);
        return b != null && b.through();
    }

    private static List<Rule> throughOf;
    private static boolean anyThrough;

    // some rule says "through" (else a bullet's path needs no walk through the blocks it passes)
    public static synchronized boolean anyThrough() {
        List<Rule> all = rules();
        if (all != throughOf) {
            throughOf = all;
            anyThrough = BulletRuleText.anyThrough(all);
        }
        return anyThrough;
    }

    // a bullet may bounce off it (NTM's ricochet at a grazing angle); "ricochet <blocks> no": it stops in it instead
    public static synchronized boolean ricochet(BlockState state) {
        rules();
        return RICOCHETS.computeIfAbsent(state, st -> {
            return BulletRuleText.ricochet(last("ricochet", x -> blockMatches(x.selectors(), st)));
        });
    }

    // how a bullet meets the block, by a "path" line: hitbox, pixels (its texture's mask: bars, grates), model (its
    // model's cells: scaffold, doors), a family's premade cells (fence, fence_gate, wall, bamboo_fence,
    // bamboo_fence_gate: PassableClip.FAMILIES) or through; null: no line, PassableClip works it out
    public static synchronized @Nullable String path(BlockState state) {
        rules();
        return PATHS.computeIfAbsent(state, st -> {
            Rule r = last("path", x -> x.words().length > 0 && PassableClip.PATH_WORDS.contains(x.words()[0]) && blockMatches(x.selectors(), st));
            return java.util.Optional.ofNullable(r == null ? null : r.words()[0]);
        }).orElse(null);
    }

    // how a hole on the block is drawn, by "hole" lines: {glass | plain, hitbox | pixels}, each from the last line
    // that names it, null where none does (Tracers works it out: glass by the material, the cut by the pixels)
    public static synchronized String[] hole(BlockState state) {
        rules();
        return HOLES.computeIfAbsent(state, st -> BulletRuleText.hole(rules(), r -> blockMatches(r.selectors(), st)));
    }

    public static synchronized int generation() {
        rules();
        return generation;
    }

    // the marks a bullet leaves on it: by its material (as its piercing), each of hole, scratch and scorch changed by
    // the last matching decal rule that names a known style for it (so a later line can change one, keep the others)
    public static synchronized Decals.Set decal(BlockState state) {
        List<Rule> all = rules();
        Decals.Set known = DECALS.get(state);
        if (known != null) return known;
        Decals.Set base = Decals.Set.of(material(state).material());
        List<Map<String, String>> mine = new ArrayList<>();
        for (Rule r : all) if (r.kind().equals("decal") && blockMatches(r.selectors(), state)) mine.add(r.opts());
        Decals.Set out = Decals.Set.of(base, mine);
        DECALS.put(state, out);
        return out;
    }

    public static synchronized Effect hit(BlockState state) {
        rules();
        Effect got = HITS.get(state);
        if (got != null) return got;
        Rule r = last("hit", x -> blockMatches(x.selectors(), state));
        Effect out = r == null ? BulletRuleText.NO_EFFECT : BulletRuleText.effect(r);
        HITS.put(state, out);
        return out;
    }

    public static synchronized Effect entity(String kind, Entity e) {
        Rule r = last(kind, x -> entityMatches(x.selectors(), e));
        return r == null ? BulletRuleText.NO_EFFECT : BulletRuleText.effect(r);
    }

    public static synchronized Whizz whizz() {
        return BulletRuleText.whizz(last("whizz", x -> true));
    }

    public static synchronized @Nullable Sound fly() {
        return BulletRuleText.fly(last("fly", x -> true));
    }

}
