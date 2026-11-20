package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.BulletRuleText.Break;
import acup.gunfeel.BulletRuleText.Effect;
import acup.gunfeel.BulletRuleText.Rule;
import acup.gunfeel.BulletRuleText.Sound;
import acup.gunfeel.BulletRuleText.Whizz;
import acup.gunfeel.host.BlockTable;
import acup.gunfeel.host.EntityTypeTable;

/**
 * The bullet rules' answers without the game (BulletRules: what bullets do to the blocks and creatures they hit, by
 * ntmgunfeel-bullets.txt and the config's file): a block by its state's number in the version's BlockTable, a
 * creature by its type's number in its EntityTypeTable. The answers per state are kept until the rules are read anew.
 *
 * <p>BulletRules extends it on each version and DECLARES every entry its callers use (by the game's types, and
 * {@link #whizz}, {@link #fly}, {@link #generation}, {@link #anyThrough} over these): the version's file listener is set
 * up by BulletRules' class initialisation, and a call to a static only inherited from here initialises this class, not
 * BulletRules (JVMS 5.5), so the rules' file would never be looked at again. BulletRulesEntriesTest holds 26.2 to it.
 */
public class BulletRulesBase extends BulletRuleText {
    BulletRulesBase() {
    }

    // where the version gives no block table (core's own tests): each question throws
    private static final BlockTable NO_BLOCKS = new BlockTable() {
        @Override
        public BlockFacts facts(int state) {
            throw new UnsupportedOperationException("no BlockTable here");
        }

        @Override
        public String key(int state) {
            throw new UnsupportedOperationException("no BlockTable here");
        }

        @Override
        public boolean air(int state) {
            throw new UnsupportedOperationException("no BlockTable here");
        }

        @Override
        public boolean machineOrPartial(int state) {
            throw new UnsupportedOperationException("no BlockTable here");
        }
    };

    private static final BlockTable BLOCKS = CoreServices.load(BlockTable.class, NO_BLOCKS);
    private static final EntityTypeTable TYPES = CoreServices.load(EntityTypeTable.class, EntityTypeTable.MISSING);

    // the answers per block (BlockRuleAnswers), by its state's number
    private static final BlockRuleAnswers ANSWERS = new BlockRuleAnswers();
    // goes up each time the rules are read anew (PassableClip forgets the shapes it took from them)
    private static int generation;

    // the rules' reading the caches above were worked out of
    private static int readingSeen;

    private static synchronized List<Rule> rules() {
        List<Rule> all = load();
        if (readingSeen != readings) {
            readingSeen = readings;
            ANSWERS.clear();
            generation++;
            materialRules = null;
        }
        return all;
    }

    // ---- matching

    // which blocks a rule's selectors take: BlockSelectors on the state's facts (the version's table: the tags as the game
    // had them when they were made, made anew when the game's tags are), the material and %mod's machine-or-partial
    // the game's, each asked only for its selector
    static boolean matches(List<String> sel, int number) {
        return BlockSelectors.matches(sel, BLOCKS.facts(number), () -> material(number).material(), () -> BLOCKS.machineOrPartial(number));
    }

    // which creatures: by their type's id ("*", an id or a mask) or a type tag ("#namespace:path")
    static boolean entityMatches(List<String> sel, int type) {
        String key = TYPES.name(type);
        for (String s : sel) {
            if (s.equals("*")) return true;
            if (s.startsWith("#")) {
                if (TYPES.tagged(type, s.substring(1))) return true;
            } else if (BlockSelectors.idMatches(s, key)) return true;
        }
        return false;
    }

    private static @Nullable Rule last(String kind, java.util.function.Predicate<Rule> match) {
        return BulletRuleText.last(rules(), kind, match);
    }

    // ---- answers

    static synchronized @Nullable Break breakRule(int number) {
        rules();
        return ANSWERS.breaks.get(number, n -> BulletRuleText.breakOf(last("break", x -> matches(x.selectors(), n))));
    }

    private static @Nullable List<Materials.Rule> materialRules;

    // what a bullet meets in this block, for piercing it: its material by the "material" lines, else as Materials
    // works it out from the block; once per block state (the rules' reload starts over)
    static synchronized Materials.Verdict material(int number) {
        List<Rule> all = rules();
        return ANSWERS.materials.get(number, n -> {
            if (materialRules == null) materialRules = BulletRuleText.materialRules(all);
            try {
                return Materials.classify(BLOCKS.facts(n), materialRules);
            } catch (RuntimeException e) {
                // a block that can't tell its facts: stops bullets, as before materials
                return new Materials.Verdict(Materials.Material.UNBREAKABLE, 1F, Materials.Source.UNBREAKABLE, "no facts");
            }
        });
    }

    // bullets always fly through it (a "break ... through" or "path ... through" rule); it still counts the hits and
    // breaks by its break rule
    static boolean through(int number) {
        if (!anyThrough()) return false;
        if ("through".equals(path(number))) return true;
        Break b = breakRule(number);
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
    static synchronized boolean ricochet(int number) {
        rules();
        return ANSWERS.ricochets.get(number, n -> BulletRuleText.ricochet(last("ricochet", x -> matches(x.selectors(), n))));
    }

    // how a bullet meets the block, by a "path" line: hitbox, pixels (its texture's mask: bars, grates), model (its
    // model's cells: scaffold, doors), a family's premade cells (fence, fence_gate, wall, bamboo_fence,
    // bamboo_fence_gate: FenceFamilies.FAMILIES) or through; null: no line, PassableClip works it out
    static synchronized @Nullable String path(int number) {
        rules();
        return ANSWERS.paths.get(number, n -> {
            Rule r = last("path", x -> x.words().length > 0 && FenceFamilies.PATH_WORDS.contains(x.words()[0]) && matches(x.selectors(), n));
            return r == null ? null : r.words()[0];
        });
    }

    // how a hole on the block is drawn, by "hole" lines: {glass | plain, hitbox | pixels}, each from the last line
    // that names it, null where none does (Tracers works it out: glass by the material, the cut by the pixels)
    static synchronized String[] hole(int number) {
        rules();
        return ANSWERS.holes.get(number, n -> BulletRuleText.hole(rules(), r -> matches(r.selectors(), n)));
    }

    public static synchronized int generation() {
        rules();
        return generation;
    }

    // the marks a bullet leaves on it: by its material (as its piercing), each of hole, scratch and scorch changed by
    // the last matching decal rule that names a known style for it (so a later line can change one, keep the others)
    static synchronized Decals.Set decal(int number) {
        List<Rule> all = rules();
        return ANSWERS.decals.get(number, n -> {
            Decals.Set base = Decals.Set.of(material(n).material());
            List<Map<String, String>> mine = new ArrayList<>();
            for (Rule r : all) if (r.kind().equals("decal") && matches(r.selectors(), n)) mine.add(r.opts());
            return Decals.Set.of(base, mine);
        });
    }

    static synchronized Effect hit(int number) {
        rules();
        return ANSWERS.hits.get(number, n -> {
            Rule r = last("hit", x -> matches(x.selectors(), n));
            return r == null ? BulletRuleText.NO_EFFECT : BulletRuleText.effect(r);
        });
    }

    // a creature hit ("entity") or killed ("kill") by a bullet, by its type's number
    static synchronized Effect entity(String kind, int type) {
        Rule r = last(kind, x -> entityMatches(x.selectors(), type));
        return r == null ? BulletRuleText.NO_EFFECT : BulletRuleText.effect(r);
    }

    public static synchronized Whizz whizz() {
        return BulletRuleText.whizz(last("whizz", x -> true));
    }

    public static synchronized @Nullable Sound fly() {
        return BulletRuleText.fly(last("fly", x -> true));
    }
}
