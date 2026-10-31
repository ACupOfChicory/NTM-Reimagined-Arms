package acup.gunfeel;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

// port: copied from 26.2 (0762086) with changes for 1.7.10, each marked "port:". The rules, their files, their order,
// their kinds and words are the same; the jar's rule files are 26.2's own (shared.txt). What differs:
//  - a block is (Block, meta), not a block state; caches per block and meta (PerMeta);
//  - a block's names: BlockIds (26.2 name, the canonical 1.7.10 one, "id:meta"); #tags and @sounds from BlockFacts
//    (BlockFactsOf: the Ore Dictionary, classes, sound types), as Materials has them;
//  - an entity's id from EntityList ("Zombie" is minecraft:zombie, a mod's "hbm.entity_x" is hbm:entity_x); no tags;
//  - FMLPaths -> PortPaths, slf4j -> log4j; Decals (hole marks) are not ported yet, so no decal().
// What bullets do to the blocks and creatures they hit, besides NTM's damage: break blocks, particles, sounds.
// Rules come from ntmgunfeel-bullets.txt in the mod jar, then config/ntm-gunfeel-bullets.txt (its lines win: of the
// rules that match, the last one counts). The server acts on break, material, path, ricochet, entity and kill lines; the
// client draws by hit, decal, hole, whizz and fly, and predicts the server's by the same lines. On another game's server
// the client takes the server's lines of the server's kinds instead of its own (ServerSettings).
public final class BulletRules {
    private BulletRules() {
    }

    public static final String FILE = "ntm-gunfeel-bullets.txt";

    // break: how a block gives way. mode: 0 never, 1 instant, 2 after `amount` hits, 3 after `amount` x hardness damage,
    // 4 after `amount` damage whatever the hardness (hp)
    // pierce: a bullet goes through it, keeping `keep` of its damage less `pierce` x hardness per block of its path
    // through it (0 = never pierced): a door, 3/16 of a block, takes far less than a block of planks
    // min: damage a bullet must bring for any wear: a hit counts only above it, a damage or hp rule takes only the part
    // above it (a machine gun's light rounds barely scratch a sandbag, a rifle's tear it)
    public record Break(int mode, float amount, boolean drop, boolean pass, boolean through, String replace, float pierce, float keep,
            float min) {
        // the bullet's damage after piercing a block of this hardness, or -1 when it stops in it
        public float pierced(float damage, float hardness, double length) {
            if (pierce <= 0F || through || hardness < 0F) return -1F;
            double len = Math.max(0.0, length);
            float left = (float) (damage * Math.pow(keep, len) - pierce * hardness * len);
            return left >= 1F ? left : -1F;
        }
    }

    // a particle: "block" (the hit block's crack), "block:<id>" (that block's crack) or a plain particle id
    public record Particle(String id, int count) {
    }

    // a sound: "block" (the hit block's own hit sound) or a sound id
    public record Sound(String id, float volume, float pitch) {
    }

    // range: blocks the sound is heard over (a block's hit sound alone fades out within 16)
    public record Effect(List<Particle> particles, Sound sound, boolean toAll, float range) {
        public boolean empty() {
            return particles.isEmpty() && sound == null;
        }
    }

    // crack: the supersonic snap on top of the whizz when it passes within crackRadius
    public record Whizz(Sound sound, float radius, Sound crack, float crackRadius) {
    }

    private record Rule(String kind, List<String> selectors, Map<String, String> opts, String[] words) {
    }

    // port: 1.7.10 has no block states: a cache per block and its meta (0-15)
    private static final class PerMeta<T> {
        private final Map<Block, Object[]> map = new IdentityHashMap<Block, Object[]>();

        @SuppressWarnings("unchecked")
        T get(Block b, int meta) {
            Object[] a = map.get(b);
            return a == null ? null : (T) a[meta & 15];
        }

        void put(Block b, int meta, T v) {
            Object[] a = map.get(b);
            if (a == null) map.put(b, a = new Object[16]);
            a[meta & 15] = v;
        }

        void clear() {
            map.clear();
        }
    }

    private static final Object NONE = new Object();

    private static final Effect NO_EFFECT = new Effect(List.of(), null, false, 16F);
    private static List<Rule> rules = List.of();
    private static long stamp = Long.MIN_VALUE, lastPoll;
    // the server's settings the rules were last read with (null: none, this game's own file alone)
    private static ServerSettings.Snapshot rulesOf;
    private static boolean builtinRead;
    private static List<Rule> builtin = List.of();
    // port: log4j
    private static final org.apache.logging.log4j.Logger LOGGER = GunFeel.LOGGER;
    private static final PerMeta<Object> BREAKS = new PerMeta<Object>();
    private static final PerMeta<Effect> HITS = new PerMeta<Effect>();
    private static final PerMeta<Boolean> RICOCHETS = new PerMeta<Boolean>();
    private static final PerMeta<Object> PATHS = new PerMeta<Object>();
    private static final PerMeta<String[]> HOLES = new PerMeta<String[]>();
    // goes up each time the rules are read anew (PassableClip forgets the shapes it took from them)
    private static int generation;

    // concrete gives way to bullets only when the server says so (off by default): ntm-gunfeel-server.properties
    public static final String CONCRETE_KEY = "bulletBreakConcrete";
    // bullets pierce machines (material machine) only when the server says so (off by default)
    public static final String MACHINES_KEY = "bulletPierceMachines";
    // vanilla concrete (hardness 1.8) about 1000 damage, NTM's (15) about 8000; only what passes 20 per bullet counts
    static final String CONCRETE_RULE = "break $concrete damage 550 min=20";
    private static boolean concreteOf;

    private static synchronized List<Rule> rules() {
        long now = System.currentTimeMillis();
        if (!builtinRead) {
            builtinRead = true;
            // the block review's materials first: the mod's bullet rules, then the config's, come after and win
            List<Rule> mine = new ArrayList<>();
            for (String name : List.of("/ntmgunfeel-materials.txt", "/ntmgunfeel-bullets.txt")) {
                try (InputStream in = BulletRules.class.getResourceAsStream(name)) {
                    if (in != null) mine.addAll(parse(lines(in)));
                } catch (Exception e) {
                    LOGGER.warn("Could not read the built-in bullet rules {}", name, e);
                }
            }
            builtin = mine;
            stamp = Long.MIN_VALUE + 1;
            lastPoll = 0;
        }
        ServerSettings.Snapshot remote = ServerSettings.remote();
        if (now - lastPoll > 1000L || remote != rulesOf) {
            lastPoll = now;
            // port: PortPaths
            Path f = PortPaths.config().resolve(FILE);
            try {
                if (!Files.exists(f)) Files.write(f, HELP.getBytes(StandardCharsets.UTF_8));
                long m = Files.getLastModifiedTime(f).toMillis();
                boolean concrete = ReaimTimes.flag(CONCRETE_KEY, false);
                if (m != stamp || remote != rulesOf || concrete != concreteOf) {
                    stamp = m;
                    rulesOf = remote;
                    concreteOf = concrete;
                    List<Rule> all = new ArrayList<>(builtin);
                    if (concrete) all.addAll(parse(List.of(CONCRETE_RULE)));
                    List<Rule> own = parse(Files.readAllLines(f, StandardCharsets.UTF_8));
                    if (remote == null) {
                        all.addAll(own);
                    } else {
                        // on another game's server: what the server acts on by its rules, what only this client draws
                        // by the player's
                        for (Rule r : own) if (!ServerSettings.serverKind(r.kind())) all.add(r);
                        for (Rule r : parse(Arrays.asList(remote.bullets().split("\\R", -1)))) if (ServerSettings.serverKind(r.kind())) all.add(r);
                    }
                    rules = all;
                    TestLog.here("rules", "own", own.size(), "remote", remote != null, "total", all.size(), "concrete", concrete);
                    BREAKS.clear();
                    HITS.clear();
                    RICOCHETS.clear();
                    PATHS.clear();
                    HOLES.clear();
                    generation++;
                    MATERIALS.clear();
                    materialRules = null;
                }
            } catch (Exception e) {
                if (rules.isEmpty()) rules = builtin;
            }
        }
        return rules;
    }

    private static List<String> lines(InputStream in) throws java.io.IOException {
        List<String> out = new ArrayList<>();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        for (String l; (l = r.readLine()) != null;) out.add(l);
        return out;
    }

    private static List<Rule> parse(List<String> lines) {
        List<Rule> out = new ArrayList<>();
        Pattern comment = Pattern.compile("\\s#(\\s|$)");
        for (String raw : lines) {
            String line = raw.trim();
            // a comment after a rule: " # text" (a lone #; "#minecraft:planks" is a tag)
            java.util.regex.Matcher hash = comment.matcher(line);
            if (hash.find()) line = line.substring(0, hash.start()).trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] w = line.split("\\s+");
            String kind = w[0].toLowerCase(Locale.ROOT);
            List<String> sel = new ArrayList<>();
            Map<String, String> opts = new HashMap<>();
            List<String> words = new ArrayList<>();
            int i = 1;
            // whizz and fly take no selectors
            if (!kind.equals("whizz") && !kind.equals("fly") && w.length > 1) {
                for (String s : w[1].split(",")) if (!s.isEmpty()) sel.add(s);
                i = 2;
            }
            for (; i < w.length; i++) {
                int eq = w[i].indexOf('=');
                if (eq > 0) opts.put(w[i].substring(0, eq).toLowerCase(Locale.ROOT), w[i].substring(eq + 1));
                else words.add(w[i].toLowerCase(Locale.ROOT));
            }
            out.add(new Rule(kind, sel, opts, words.toArray(new String[0])));
        }
        return out;
    }

    // ---- matching

    private static final Map<String, Pattern> PATTERNS = new HashMap<>();

    private static Pattern pattern(String sel) {
        Pattern p = PATTERNS.get(sel);
        if (p == null) {
            try {
                p = Pattern.compile(sel.substring(1));
            } catch (RuntimeException e) {
                p = Pattern.compile("(?!)");
            }
            PATTERNS.put(sel, p);
        }
        return p;
    }

    // port: an id selector against every name the thing has (BlockIds.names; an entity's one)
    private static boolean idMatches(String sel, List<String> names) {
        if (sel.startsWith("~")) {
            Pattern p = pattern(sel);
            for (String n : names) if (p.matcher(n).matches()) return true;
            return false;
        }
        String want = sel.indexOf(':') < 0 ? "minecraft:" + sel : sel;
        for (String n : names) if (want.equals(n)) return true;
        return false;
    }

    // port: (Block, meta) where it was met (the facts need the world: 1.7.10 has no shape without one)
    static boolean blockMatches(List<String> sel, Block block, int meta, World world, int x, int y, int z) {
        List<String> names = null;
        BlockFacts f = null;
        for (String s : sel) {
            if (s.equals("*")) return true;
            if (s.startsWith("#")) {
                if (f == null) f = facts(block, meta, world, x, y, z);
                String t = s.substring(1);
                if (f.tags().contains(t.indexOf(':') < 0 ? "minecraft:" + t : t)) return true;
            } else if (s.startsWith("@")) {
                if (f == null) f = facts(block, meta, world, x, y, z);
                String want = s.substring(1).toLowerCase(Locale.ROOT);
                for (String name : f.sound().split("/")) if (name.equals(want)) return true;
            } else if (s.startsWith("%")) {
                if (f == null) f = facts(block, meta, world, x, y, z);
                if (f.namespace().equals(s.substring(1)) && f.machineOrPartial()) return true;
            } else if (s.startsWith("$")) {
                if (material(block, meta, world, x, y, z).material().name().equalsIgnoreCase(s.substring(1))) return true;
            } else {
                if (names == null) names = BlockIds.names(Block.blockRegistry.getNameForObject(block), meta);
                if (idMatches(s, names)) return true;
            }
        }
        return false;
    }

    private static final PerMeta<BlockFacts> FACTS = new PerMeta<BlockFacts>();

    // port: the facts of a block and meta, once (where it was first met)
    public static synchronized BlockFacts facts(Block block, int meta, World world, int x, int y, int z) {
        BlockFacts f = FACTS.get(block, meta);
        if (f == null) {
            try {
                f = BlockFactsOf.of(block, meta, world, x, y, z);
            } catch (RuntimeException e) {
                f = new BlockFacts(BlockIds.id(Block.blockRegistry.getNameForObject(block), meta), List.of(), "?", -1F, 0F, false, true, 1,
                        false, true, java.util.Collections.<String>emptySet());
            }
            FACTS.put(block, meta, f);
        }
        return f;
    }

    // port: an entity's id as the 26.2 rules name it
    static String entityId(Entity e) {
        if (e instanceof EntityPlayer) return "minecraft:player";
        String s = EntityList.getEntityString(e);
        if (s == null) return "minecraft:unknown";
        int dot = s.indexOf('.');
        if (dot > 0) return s.substring(0, dot).toLowerCase(Locale.ROOT) + ":" + s.substring(dot + 1);
        String known = VANILLA_ENTITIES.get(s);
        return "minecraft:" + (known != null ? known : BlockFactsOf.snake(s));
    }

    private static final Map<String, String> VANILLA_ENTITIES = new HashMap<>();

    static {
        String[] pairs = {"VillagerGolem", "iron_golem", "PigZombie", "zombified_piglin", "LavaSlime", "magma_cube", "EntityHorse", "horse",
                "Ozelot", "ocelot", "MushroomCow", "mooshroom", "SnowMan", "snow_golem", "WitherBoss", "wither", "EnderDragon", "ender_dragon"};
        for (int i = 0; i < pairs.length; i += 2) VANILLA_ENTITIES.put(pairs[i], pairs[i + 1]);
    }

    static boolean entityMatches(List<String> sel, Entity e) {
        List<String> names = List.of(entityId(e));
        for (String s : sel) {
            if (s.equals("*")) return true;
            // port: no entity tags on 1.7.10
            if (s.startsWith("#")) continue;
            if (idMatches(s, names)) return true;
        }
        return false;
    }

    private static Rule last(String kind, java.util.function.Predicate<Rule> match) {
        List<Rule> all = rules();
        for (int i = all.size() - 1; i >= 0; i--) {
            Rule r = all.get(i);
            if (r.kind().equals(kind) && match.test(r)) return r;
        }
        return null;
    }

    // ---- answers

    // port: the block at (x, y, z)
    public static Break breakRule(World w, int x, int y, int z) {
        return breakRule(w.getBlock(x, y, z), w.getBlockMetadata(x, y, z), w, x, y, z);
    }

    public static synchronized Break breakRule(Block block, int meta, World world, int x, int y, int z) {
        rules();
        Object known = BREAKS.get(block, meta);
        if (known != null) return known == NONE ? null : (Break) known;
        Rule r = last("break", rule -> blockMatches(rule.selectors(), block, meta, world, x, y, z));
        Break out = null;
        if (r != null && r.words().length > 0) {
            String[] w = r.words();
            int mode;
            switch (w[0]) {
                case "instant": mode = 1; break;
                case "hits": mode = 2; break;
                case "damage": mode = 3; break;
                case "hp": mode = 4; break;
                default: mode = 0;
            }
            float amount = 1F;
            if ((mode == 2 || mode == 3 || mode == 4) && w.length > 1) {
                try {
                    amount = Float.parseFloat(w[1]);
                } catch (NumberFormatException e) {
                    // keep 1
                }
            }
            boolean drop = false, pass = false, through = false;
            for (String s : w) {
                if (s.equals("drop")) drop = true;
                if (s.equals("pass")) pass = true;
                if (s.equals("through")) through = true;
            }
            boolean pierce = number(r.opts().get("pierce"), 0F) > 0F;
            out = mode == 0 && !through && !pierce ? null : new Break(mode, amount, drop, pass || through, through, r.opts().get("replace"),
                    number(r.opts().get("pierce"), 0F), number(r.opts().get("keep"), 1F), Math.max(0F, number(r.opts().get("min"), 0F)));
        }
        BREAKS.put(block, meta, out == null ? NONE : out);
        return out;
    }

    private static final PerMeta<Materials.Verdict> MATERIALS = new PerMeta<Materials.Verdict>();
    private static List<Materials.Rule> materialRules;

    // what a bullet meets in this block, for piercing it: its material by the "material" lines, else as Materials
    // works it out from the block; once per block and meta (the rules' reload starts over)
    public static Materials.Verdict material(World w, int x, int y, int z) {
        return material(w.getBlock(x, y, z), w.getBlockMetadata(x, y, z), w, x, y, z);
    }

    public static synchronized Materials.Verdict material(Block block, int meta, World world, int x, int y, int z) {
        List<Rule> all = rules();
        Materials.Verdict known = MATERIALS.get(block, meta);
        if (known != null) return known;
        if (materialRules == null) {
            List<Materials.Rule> m = new ArrayList<>();
            for (Rule r : all) {
                if (!r.kind().equals("material") || r.words().length == 0) continue;
                Materials.Material mat = Materials.Material.named(r.words()[0]);
                if (mat != null) m.add(new Materials.Rule(List.copyOf(r.selectors()), mat));
            }
            materialRules = m;
        }
        Materials.Verdict v;
        try {
            BlockFacts f = facts(block, meta, world, x, y, z);
            // port: a material line may name the block by its 1.7.10 name too (Materials matches the facts' 26.2 id)
            v = classifyAny(block, meta, f);
        } catch (RuntimeException e) {
            // a block that can't tell its facts: stops bullets, as before materials
            v = new Materials.Verdict(Materials.Material.UNBREAKABLE, 1F, Materials.Source.UNBREAKABLE, "no facts");
        }
        MATERIALS.put(block, meta, v);
        return v;
    }

    // port: Materials.classify, with a material line naming any of the block's names (BlockIds.names) counted
    private static Materials.Verdict classifyAny(Block block, int meta, BlockFacts f) {
        List<String> names = BlockIds.names(Block.blockRegistry.getNameForObject(block), meta);
        List<Materials.Rule> rs = new ArrayList<>(materialRules.size());
        for (Materials.Rule r : materialRules) {
            boolean other = false;
            for (String s : r.selectors()) {
                if (s.startsWith("#") || s.startsWith("@") || s.startsWith("%") || s.equals("*")) continue;
                if (idMatches(s, names) && !idMatches(s, List.of(f.id()))) other = true;
            }
            rs.add(other ? new Materials.Rule(List.of("*"), r.material()) : r);
        }
        return Materials.classify(f, rs);
    }

    private static float number(String text, float or) {
        if (text == null) return or;
        try {
            return Float.parseFloat(text);
        } catch (NumberFormatException e) {
            return or;
        }
    }

    // bullets always fly through it (a "break ... through" or "path ... through" rule); it still counts the hits and
    // breaks by its break rule
    public static boolean through(Block block, int meta, World world, int x, int y, int z) {
        if (!anyThrough()) return false;
        if ("through".equals(path(block, meta, world, x, y, z))) return true;
        Break b = breakRule(block, meta, world, x, y, z);
        return b != null && b.through();
    }

    private static List<Rule> throughOf;
    private static boolean anyThrough;

    // some rule says "through" (else a bullet's path needs no walk through the blocks it passes)
    public static synchronized boolean anyThrough() {
        List<Rule> all = rules();
        if (all != throughOf) {
            throughOf = all;
            anyThrough = false;
            for (Rule r : all) {
                if (r.kind().equals("break") && Arrays.asList(r.words()).contains("through")) anyThrough = true;
                if (r.kind().equals("path") && r.words().length > 0 && r.words()[0].equals("through")) anyThrough = true;
            }
        }
        return anyThrough;
    }

    // a bullet may bounce off it (NTM's ricochet at a grazing angle); "ricochet <blocks> no": it stops in it instead
    public static synchronized boolean ricochet(Block block, int meta, World world, int x, int y, int z) {
        rules();
        Boolean known = RICOCHETS.get(block, meta);
        if (known != null) return known;
        Rule r = last("ricochet", rule -> blockMatches(rule.selectors(), block, meta, world, x, y, z));
        boolean out = r == null || !Arrays.asList(r.words()).contains("no");
        RICOCHETS.put(block, meta, out);
        return out;
    }

    // the words of a "path" rule: the four ways and the families (26.2: PassableClip.PATH_WORDS)
    public static final java.util.Set<String> PATH_WORDS = java.util.Set.of("hitbox", "pixels", "model", "through", "fence", "fence_gate",
            "wall", "bamboo_fence", "bamboo_fence_gate");

    // how a bullet meets the block, by a "path" line: hitbox, pixels (its texture's mask: bars, grates), model (its
    // model's cells: scaffold, doors), a family's premade cells (fence, fence_gate, wall, bamboo_fence,
    // bamboo_fence_gate: PassableClip.FAMILIES) or through; null: no line, PassableClip works it out
    // port: on 1.7.10 only hitbox and through change anything yet (no masks or cells: PassableClip)
    public static synchronized String path(Block block, int meta, World world, int x, int y, int z) {
        rules();
        Object known = PATHS.get(block, meta);
        if (known == null) {
            Rule r = last("path", rule -> rule.words().length > 0 && PATH_WORDS.contains(rule.words()[0])
                    && blockMatches(rule.selectors(), block, meta, world, x, y, z));
            known = r == null ? NONE : r.words()[0];
            PATHS.put(block, meta, known);
        }
        return known == NONE ? null : (String) known;
    }

    // how a hole on the block is drawn, by "hole" lines: {glass | plain, hitbox | pixels}, each from the last line
    // that names it, null where none does (Tracers works it out: glass by the material, the cut by the pixels)
    public static synchronized String[] hole(Block block, int meta, World world, int x, int y, int z) {
        rules();
        String[] known = HOLES.get(block, meta);
        if (known != null) return known;
        String[] out = new String[2];
        List<Rule> all = rules();
        for (int i = all.size() - 1; i >= 0 && (out[0] == null || out[1] == null); i--) {
            Rule r = all.get(i);
            if (!r.kind().equals("hole") || !blockMatches(r.selectors(), block, meta, world, x, y, z)) continue;
            for (String w : r.words()) {
                if (out[0] == null && (w.equals("glass") || w.equals("plain"))) out[0] = w;
                if (out[1] == null && (w.equals("hitbox") || w.equals("pixels"))) out[1] = w;
            }
        }
        HOLES.put(block, meta, out);
        return out;
    }

    public static synchronized int generation() {
        rules();
        return generation;
    }

    // port: decal() (Decals.Set) waits for the holes' port

    public static synchronized Effect hit(Block block, int meta, World world, int x, int y, int z) {
        rules();
        Effect got = HITS.get(block, meta);
        if (got != null) return got;
        Rule r = last("hit", rule -> blockMatches(rule.selectors(), block, meta, world, x, y, z));
        Effect out = r == null ? NO_EFFECT : effect(r);
        HITS.put(block, meta, out);
        return out;
    }

    public static synchronized Effect entity(String kind, Entity e) {
        Rule r = last(kind, x -> entityMatches(x.selectors(), e));
        return r == null ? NO_EFFECT : effect(r);
    }

    public static synchronized Whizz whizz() {
        Rule r = last("whizz", x -> true);
        if (r == null) return new Whizz(null, 0F, null, 0F);
        return new Whizz(sound(r.opts().get("sound")), num(r.opts().get("radius"), 3F), sound(r.opts().get("crack")),
                num(r.opts().get("crackradius"), 1.2F));
    }

    public static synchronized Sound fly() {
        Rule r = last("fly", x -> true);
        return r == null ? null : sound(r.opts().get("sound"));
    }

    private static Effect effect(Rule r) {
        List<Particle> ps = new ArrayList<>();
        String p = r.opts().get("particles");
        if (p != null && !p.equalsIgnoreCase("none")) {
            for (String s : p.split(",")) {
                if (s.isEmpty()) continue;
                int star = s.lastIndexOf('*');
                int count = 1;
                String id = s;
                if (star > 0) {
                    try {
                        count = Math.max(1, Integer.parseInt(s.substring(star + 1)));
                    } catch (NumberFormatException e) {
                        // 1
                    }
                    id = s.substring(0, star);
                }
                ps.add(new Particle(id, count));
            }
        }
        return new Effect(List.copyOf(ps), sound(r.opts().get("sound")), "all".equalsIgnoreCase(r.opts().get("to")),
                num(r.opts().get("range"), 16F));
    }

    // "id[/volume[/pitch]]", "none" = no sound
    private static Sound sound(String s) {
        if (s == null || s.isEmpty() || s.equalsIgnoreCase("none")) return null;
        String[] p = s.split("/");
        return new Sound(p[0], p.length > 1 ? num(p[1], 1F) : 1F, p.length > 2 ? num(p[2], 1F) : 1F);
    }

    private static float num(String s, float def) {
        if (s == null) return def;
        try {
            return Float.parseFloat(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // port: 26.2's help, with the 1.7.10 names (ids, "id:meta") and what the port has not yet (masks, cells, decals)
    private static final String HELP = """
            # NTM Reimagined Arms: what bullets do to blocks and creatures. One rule per line; these lines come
            # after the mod's own (ntmgunfeel-bullets.txt in the jar) and win over them: of the rules that match, the
            # last one counts. Changes apply within a second, no restart.
            #
            # Server and players: break, material, path, ricochet, entity and kill lines are the server's. On a server
            # the players go by the server's file for them (sent when they join and on every change), and by their own
            # for hit, decal, hole, whizz and fly, which only they draw. In single player and on a LAN host this file is
            # both.
            #
            # Minecraft 1.7.10: a block is named by its 26.2 name (minecraft:oak_planks, hbm:steel_wall), by its 1.7.10
            # one (minecraft:planks; NTM's hbm:tile.steel_wall without "tile.") or with its metadata (minecraft:wool:14).
            #
            # Who: a block id (minecraft:ice), a tag (#minecraft:leaves: on 1.7.10 from the Ore Dictionary and the block's
            # kind), a pattern (~minecraft:.*_glass), a block
            # sound type (@metal, @wood, @glass, @stone, @grass ...), a mod's machines and partial blocks (%hbm: its blocks
            # with a block entity, drawn by a renderer or not a full cube), a material ($stone, $steel ...: what a bullet
            # meets in it, as the material lines below decide; not in material lines), * = any; several with commas, no spaces.
            # For entity/kill lines: an entity id (minecraft:zombie, hbm:entity_x) or ~pattern or *.
            #
            # break <blocks> never | instant | hits <n> | damage <x> | hp <x> [drop] [pass] [replace=<block>[:meta]] [pierce=<x> [keep=<k>]] [min=<x>]
            #   damage <x>: breaks once the bullets' damage on it adds up to x times its hardness (hp <x>: to x); hits and damage
            #   show the cracks and are forgotten 20 s after the last hit. drop = drops itself; pass = the bullet
            #   flies on through the broken block; through = bullets always fly through it (no hole, no stop), it still
            #   counts the hits and breaks by its rule ("never through": flown through, never broken). A player's bullet breaks only where that player could break
            #   blocks (claims, spawn protection). pierce=<x>: a bullet goes through the block (a hole where it goes in and
            #   where it comes out) keeping k (default 1) of its damage less x times the block's hardness, both per block
            #   of the bullet's path through it (a door, 3/16 of a block, takes far less); one left with less than 1 damage
            #   stops in it. Its damage still counts towards breaking the block. min=<x>: a bullet with no more damage than
            #   x does it no harm; damage and hp count only the damage above x, hits only the bullets above it (sandbags
            #   shrug off a machine gun's light rounds, not a rifle's). Concrete breaks only with bulletBreakConcrete
            #   = true in ntm-gunfeel-server.properties (a built-in line "break $concrete damage 550 min=20", before this file's).
            # material <blocks> none | glass | tough_glass | reinforced_glass | laminate | wood | soft | soil | stone | concrete | metal | steel | machine | unbreakable
            #   what a bullet meets in the block when it pierces it (metal = soft metals, machine = a hollow steel casing,
            #   pierced only with bulletPierceMachines = true in ntm-gunfeel-server.properties;
            #   soft = wool, hay and the like, soil = packed ground: dirt, sand, gravel, sandbags);
            #   without a line the mod works it out from the block. A block with a break line's pierce= uses that instead
            # hit <blocks> [particles=<p>*<n>,...] [sound=<id>[/volume[/pitch]]] [range=<blocks>]   (each player's own game)
            # ricochet <blocks> yes | no   no: a bullet meeting it at a grazing angle stops in it instead of bouncing off
            #   (NTM's ricochet); yes (the default) bounces. Built in: no for soft ground, wood and glass
            # path <blocks> hitbox | through   how a bullet meets the block: its collision boxes (most blocks), or flown
            #   through (no stop, no hole). The 26.2 ways pixels, model and the fence families need the block masks and
            #   cells, which Minecraft 1.7.10 does not have yet: there they are as hitbox
            # hole <blocks> [glass | plain] [hitbox | pixels]   how a hole on it is drawn (each player's own game)
            # entity <who> [particles=...] [sound=...] [to=all]   a bullet hits a creature (sound heard by the shooter,
            #   to=all: by everyone near)
            # kill <who> [particles=...] [sound=...] [to=all]     a bullet kills it
            # whizz sound=<id>[/volume[/pitch]] radius=<blocks> [crack=<id>[/volume[/pitch]] crackradius=<blocks>]
            # fly sound=<id>[/volume[/pitch]]                    a sound that flies with every bullet
            #
            # Examples:
            # break minecraft:melon,minecraft:pumpkin damage 2 drop
            # break #minecraft:leaves never
            # entity * particles=block:minecraft:redstone_block*6
            """;
}
