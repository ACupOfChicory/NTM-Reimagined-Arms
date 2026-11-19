package acup.gunfeel;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The bullets' rule file as text: its lines (ntm-gunfeel-bullets.txt in the config folder, the mod's own in the jar,
 * the server's copy), what each kind of line says, and its help. Which blocks and creatures a line's selectors match
 * is the version's (BulletRules on 26.2: ids, tags and sound types of the game); so is where the file is and when it is
 * read again.
 */
public class BulletRuleText {
    BulletRuleText() {
    }

    public static final String FILE = "ntm-gunfeel-bullets.txt";

    private static List<Rule> rules = List.of();

    private static long stamp = Long.MIN_VALUE, lastPoll;

    // the file is looked at, at the next use: set by tick a second after the last look, by reloadNow, at the start
    private static volatile boolean pollDue = true;

    // the server's settings the rules were last read with (null: none, this game's own file alone)
    private static ServerSettings.@Nullable Snapshot rulesOf;

    private static boolean builtinRead;

    private static List<Rule> builtin = List.of();

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("ntm-gunfeel");

    // concrete gives way to bullets only when the server says so (off by default): ntm-gunfeel-server.properties
    public static final String CONCRETE_KEY = "bulletBreakConcrete";

    private static boolean concreteOf;

    /** the file and the concrete key looked at again at the next use, whatever the clock (ReaimTimes.reloadNow) */
    static synchronized void reloadNow() {
        pollDue = true;
    }

    /**
     * The game ticked ({@code nowMillis}: the wall clock): a second after the last look, the file and the concrete
     * key are looked at again at the next use. The clock is read here, once a tick, not by each question to the
     * rules (two of them a pierced block, each a clock read dearer than the rest of the question). 26.2's caller:
     * BulletRules' own listener on every level's tick, both sides (no other one is needed, not in GunFeelMod either).
     * The listener is set up by BulletRules' class initialisation, and load() is called only from BulletRules.rules():
     * another way to load() must touch BulletRules first, or the file is never looked at again.
     */
    public static synchronized void tick(long nowMillis) {
        if (nowMillis - lastPoll > 1000L) {
            lastPoll = nowMillis;
            pollDue = true;
        }
    }

    // goes up each time the rules are read anew: what a version worked out of the old ones starts over
    static int readings;

    // the rules as they are now: the jar's, then config/'s (the server's lines of its kinds on another game's
    // server), the file looked at again at the first use after a tick a second on (tick), at once when the server's
    // settings change
    static synchronized List<Rule> load() {
        if (!builtinRead) {
            builtinRead = true;
            // the block review's materials first: the mod's bullet rules, then the config's, come after and win
            List<Rule> mine = new ArrayList<>();
            for (String name : List.of("/ntmgunfeel-materials.txt", "/ntmgunfeel-bullets.txt")) {
                try (var in = BulletRuleText.class.getResourceAsStream(name)) {
                    if (in != null) mine.addAll(parse(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines().toList()));
                } catch (Exception e) {
                    LOGGER.warn("Could not read the built-in bullet rules {}", name, e);
                }
            }
            builtin = mine;
            stamp = Long.MIN_VALUE + 1;
            pollDue = true;
        }
        ServerSettings.Snapshot remote = ServerSettings.remote();
        if (pollDue || remote != rulesOf) {
            pollDue = false;
            Path f = GameDirs.get().config().resolve(FILE);
            try {
                if (!Files.exists(f)) Files.writeString(f, HELP);
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
                        for (Rule r : parse(remote.bullets().lines().toList())) if (ServerSettings.serverKind(r.kind())) all.add(r);
                    }
                    rules = all;
                    TestLog.here("rules", "own", own.size(), "remote", remote != null, "total", all.size(), "concrete", concrete);
                    readings++;
                }
            } catch (Exception e) {
                if (rules.isEmpty()) rules = builtin;
            }
        }
        return rules;
    }

    // break: how a block gives way. mode: 0 never, 1 instant, 2 after `amount` hits, 3 after `amount` x hardness damage,
    // 4 after `amount` damage whatever the hardness (hp)
    // pierce: a bullet goes through it, keeping `keep` of its damage less `pierce` x hardness per block of its path
    // through it (0 = never pierced): a door, 3/16 of a block, takes far less than a block of planks
    // min: damage a bullet must bring for any wear: a hit counts only above it, a damage or hp rule takes only the part
    // above it (a machine gun's light rounds barely scratch a sandbag, a rifle's tear it)
    public record Break(int mode, float amount, boolean drop, boolean pass, boolean through, @Nullable String replace, float pierce, float keep,
            float min) {
        // the bullet's damage after piercing a block of this hardness, or -1 when it stops in it
        public float pierced(float damage, float hardness, double length) {
            if (pierce <= 0F || through || hardness < 0F) return -1F;
            double len = Math.max(0.0, length);
            float left = (float) (damage * Math.pow(keep, len) - pierce * hardness * len);
            return left >= 1F ? left : -1F;
        }

        // a bullet of this damage does the block no harm (min=)
        public boolean tooWeak(float damage) {
            return min > 0F && !(damage > min);
        }

        // the block gives way after some wear (hits, damage, hp), not at the first hit
        public boolean worn() {
            return mode == 2 || mode == 3 || mode == 4;
        }

        // the wear one hit of this damage adds: a hit, or the damage above min=
        public float wear(float damage) {
            return mode == 2 ? 1F : Math.max(0F, damage - min);
        }

        // the wear the block of this hardness takes before it breaks
        public float need(float hardness) {
            return mode == 3 ? amount * hardness : amount;
        }

        // the cracks shown for {@code done} of {@code need} (0..9)
        public static int cracks(float done, float need) {
            return Math.min(9, (int) (done / need * 10F));
        }
    }

    // a particle: "block" (the hit block's crack), "block:<id>" (that block's crack) or a plain particle id
    public record Particle(String id, int count) {
    }

    // a sound: "block" (the hit block's own hit sound) or a sound id
    public record Sound(String id, float volume, float pitch) {
    }

    // range: blocks the sound is heard over (a block's hit sound alone fades out within 16)
    public record Effect(List<Particle> particles, @Nullable Sound sound, boolean toAll, float range) {
        public boolean empty() {
            return particles.isEmpty() && sound == null;
        }
    }

    // crack: the supersonic snap on top of the whizz when it passes within crackRadius
    public record Whizz(@Nullable Sound sound, float radius, @Nullable Sound crack, float crackRadius) {
    }

    // a line of the file: its kind, its selectors (who), its key=value options and its other words
    record Rule(String kind, List<String> selectors, Map<String, String> opts, String[] words) {
    }

    static final Effect NO_EFFECT = new Effect(List.of(), null, false, 16F);

    // vanilla concrete (hardness 1.8) about 1000 damage, NTM's (15) about 8000; only what passes 20 per bullet counts
    static final String CONCRETE_RULE = "break $concrete damage 550 min=20";

    static List<Rule> parse(List<String> lines) {
        List<Rule> out = new ArrayList<>();
        for (String raw : lines) {
            String line = raw.strip();
            // a comment after a rule: " # text" (a lone #; "#minecraft:planks" is a tag)
            java.util.regex.Matcher hash = java.util.regex.Pattern.compile("\\s#(\\s|$)").matcher(line);
            if (hash.find()) line = line.substring(0, hash.start()).strip();
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
            out.add(new Rule(kind, sel, opts, words.toArray(String[]::new)));
        }
        return out;
    }

    // the last rule of this kind that matches (of the rules that match, the last one counts), or null
    static @Nullable Rule last(List<Rule> all, String kind, java.util.function.Predicate<Rule> match) {
        for (int i = all.size() - 1; i >= 0; i--) {
            Rule r = all.get(i);
            if (r.kind().equals(kind) && match.test(r)) return r;
        }
        return null;
    }

    // how a block gives way by its last matching break rule {@code r} (null: none), or null when it never does
    static @Nullable Break breakOf(@Nullable Rule r) {
        Break out = null;
        if (r != null && r.words().length > 0) {
            String[] w = r.words();
            int mode = switch (w[0]) {
                case "instant" -> 1;
                case "hits" -> 2;
                case "damage" -> 3;
                case "hp" -> 4;
                default -> 0;
            };
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
        return out;
    }

    // the "material" lines, in their order, as Materials takes them
    static List<Materials.Rule> materialRules(List<Rule> all) {
        List<Materials.Rule> m = new ArrayList<>();
        for (Rule r : all) {
            if (!r.kind().equals("material") || r.words().length == 0) continue;
            Materials.Material mat = Materials.Material.named(r.words()[0]);
            if (mat != null) m.add(new Materials.Rule(List.copyOf(r.selectors()), mat));
        }
        return m;
    }

    // some rule says "through" (a "break ... through" or "path ... through" line)
    static boolean anyThrough(List<Rule> all) {
        boolean anyThrough = false;
        for (Rule r : all) {
            if (r.kind().equals("break") && java.util.Arrays.asList(r.words()).contains("through")) anyThrough = true;
            if (r.kind().equals("path") && r.words().length > 0 && r.words()[0].equals("through")) anyThrough = true;
        }
        return anyThrough;
    }

    // a bullet may bounce off the block of the last matching ricochet rule {@code r} (null: none): not when it says "no"
    static boolean ricochet(@Nullable Rule r) {
        return r == null || !java.util.Arrays.asList(r.words()).contains("no");
    }

    // how a hole is drawn by the "hole" lines that match ({@code matches}, asked of hole lines only, from the last):
    // {glass | plain, hitbox | pixels}, each from the last line that names it, null where none does
    static String[] hole(List<Rule> all, java.util.function.Predicate<Rule> matches) {
        String[] out = new String[2];
        for (int i = all.size() - 1; i >= 0 && (out[0] == null || out[1] == null); i--) {
            Rule r = all.get(i);
            if (!r.kind().equals("hole") || !matches.test(r)) continue;
            for (String w : r.words()) {
                if (out[0] == null && (w.equals("glass") || w.equals("plain"))) out[0] = w;
                if (out[1] == null && (w.equals("hitbox") || w.equals("pixels"))) out[1] = w;
            }
        }
        return out;
    }

    // the whizz of the last "whizz" line {@code r} (null: none, no whizz)
    static Whizz whizz(@Nullable Rule r) {
        if (r == null) return new Whizz(null, 0F, null, 0F);
        return new Whizz(sound(r.opts().get("sound")), num(r.opts().get("radius"), 3F), sound(r.opts().get("crack")),
                num(r.opts().get("crackradius"), 1.2F));
    }

    // the sound of the last "fly" line {@code r} (null: none)
    static @Nullable Sound fly(@Nullable Rule r) {
        return r == null ? null : sound(r.opts().get("sound"));
    }

    static Effect effect(Rule r) {
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
    static @Nullable Sound sound(@Nullable String s) {
        if (s == null || s.isEmpty() || s.equalsIgnoreCase("none")) return null;
        String[] p = s.split("/");
        return new Sound(p[0], p.length > 1 ? num(p[1], 1F) : 1F, p.length > 2 ? num(p[2], 1F) : 1F);
    }

    static float num(@Nullable String s, float def) {
        if (s == null) return def;
        try {
            return Float.parseFloat(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static float number(@Nullable String text, float or) {
        if (text == null) return or;
        try {
            return Float.parseFloat(text);
        } catch (NumberFormatException e) {
            return or;
        }
    }

    static final String HELP = """
            # NTM Reimagined Arms: what bullets do to blocks and creatures. One rule per line; these lines come
            # after the mod's own (ntmgunfeel-bullets.txt in the jar) and win over them: of the rules that match, the
            # last one counts. Changes apply within a second, no restart.
            #
            # Server and players: break, material, path, ricochet, entity and kill lines are the server's. On a server
            # the players go by the server's file for them (sent when they join and on every change), and by their own
            # for hit, decal, hole, whizz and fly, which only they draw. In single player and on a LAN host this file is
            # both.
            #
            # Who: a block id (minecraft:ice), a tag (#minecraft:leaves), a pattern (~minecraft:.*_glass), a block
            # sound type (@metal, @wood, @glass, @stone, @grass ...), a mod's machines and partial blocks (%hbm: its blocks
            # with a block entity, drawn by a renderer or not a full cube), a material ($stone, $steel ...: what a bullet
            # meets in it, as the material lines below decide; not in material lines), * = any; several with commas, no spaces.
            # For entity/kill lines: an entity id, #tag, ~pattern or *.
            #
            # break <blocks> never | instant | hits <n> | damage <x> | hp <x> [drop] [pass] [replace=<block state>] [pierce=<x> [keep=<k>]] [min=<x>]
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
            #   particle: block (the hit block's crumbs), block:<id> (that block's crumbs), a particle id
            #   (minecraft:smoke); sound: block (the hit block's own hit sound), a sound id; none = nothing;
            #   range: heard over that many blocks, fading out to it (Minecraft's own: 16)
            # ricochet <blocks> yes | no   no: a bullet meeting it at a grazing angle stops in it instead of bouncing off
            #   (NTM's ricochet); yes (the default) bounces. Built in: no for soft ground, wood and glass
            # path <blocks> hitbox | pixels | model | fence | fence_gate | wall | bamboo_fence | bamboo_fence_gate | through
            #   how a bullet meets the block (overrides what the mod works out):
            #   hitbox   its collision box: where the box is, the bullet stops (stone, planks: most blocks)
            #   pixels   the pixels of its texture on the box's faces: a clear pixel lets the bullet through (copper grate,
            #            iron bars, NTM's chain-link fence). Needs the block's mask (tools/bullet_masks.py makes it from
            #            the texture; vanilla and NTM have theirs), else as hitbox
            #   model    the cells of its 3D model: the bullet meets only what is drawn (NTM's scaffold, chains, doors,
            #            lanterns). A model that closes its box all round (a door, a lantern) is decided where the bullet
            #            comes in: a window lets it through the whole block. The client makes the cells on joining a
            #            world (single player: at once; a server gets them from config/ntm-gunfeel-bullet-shapes.properties
            #            copied over), else as hitbox
            #   fence, fence_gate, wall, bamboo_fence, bamboo_fence_gate
            #            the posts and rails of that vanilla model, with the block's own sides and facing where it has
            #            them; the gaps are flown through. Without a line: fences, gates and walls of any mod
            #   through  bullets always fly through it, no stop, no hole (as "break ... through")
            #   Without a line: a mask = pixels, a listed model = model, a fence, gate or wall = its family, the rest hitbox
            # hole <blocks> [glass | plain] [hitbox | pixels]   how a hole on it is drawn (each player's own game):
            #   glass  seen from both sides and drawn after the glass behind it (a window); plain: on its face only.
            #          Without: glass for the glass materials ($glass, $tough_glass, $reinforced_glass, $laminate)
            #   hitbox the hole covers the box's face whole; pixels: only the texture's shown pixels (a grate's bars).
            #          Without: pixels (the mod has glass of any mod, ~.*glass.*, by the hitbox)
            # decal <blocks> [hole=<style>] [scratch=<style>] [scorch=<style>]   the marks bullets leave on it (each player's
            #   own game): hole = old | spall | shatter | soot, scratch (a ricochet) = gouge | burn | lines, scorch
            #   (explosive and incendiary rounds) = star | blot | crater; each from the last matching line that names it,
            #   else by the block's material: stone, concrete spall/gouge/crater; glass shatter/lines/star; soft
            #   soot/burn/blot; metal, steel, machine soot/lines/star; the rest soot/gouge/blot
            # entity <who> [particles=...] [sound=...] [to=all]   a bullet hits a creature (sound heard by the shooter,
            #   to=all: by everyone near)
            # kill <who> [particles=...] [sound=...] [to=all]     a bullet kills it
            # whizz sound=<id>[/volume[/pitch]] radius=<blocks> [crack=<id>[/volume[/pitch]] crackradius=<blocks>]
            #   someone else's bullet flies past your head; crack: the snap of a fast bullet passing very close
            # fly sound=<id>[/volume[/pitch]]                    a sound that flies with every bullet
            #
            # Examples:
            # break minecraft:melon,minecraft:pumpkin damage 2 drop
            # break #minecraft:leaves never
            # hit @metal particles=block*4,minecraft:crit*3 sound=hbm:metal_impact/0.6
            # entity * particles=block:minecraft:redstone_block*6
            # kill minecraft:zombie sound=minecraft:entity.experience_orb.pickup/0.5/0.6
            # whizz sound=none
            # decal minecraft:obsidian hole=shatter
            """;
}
