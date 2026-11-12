package acup.gunfeel;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import com.hbm.util.DamageResistanceHandler;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * The balance's final scheme (server setting balance.final; BalanceFinalRule): a bullet or a beam of a pair of the
 * scheme's table on a player, or on anyone wearing a piece of the table's sets, does {@code 20 / N} HP a click whose
 * projectiles all hit, N worked out from the pair's tier, the set's, the pair's raw DPS and role (the TTK ladder of
 * balance.final.ttk). NTM's damage, the set's threshold and resistance and the formula C are not in it. The part hit
 * (head ×1.75, limb ×0.5), the damage cap and the pools of DNS, FAU and euphemium come after, as before; the hit's
 * share by range (balance.final.range, BalanceRangeRule) by the pair's role and the shooter's distance.
 *
 * <p>Blasts (balance.final.blast; Fable 12.3, BalanceBlastRule): a launcher's round's or an NTM hand grenade's blast on
 * the same targets takes the ladder's share of the health and pool, the GL-54's fragment grenades a burst's share; the
 * other blasts keep formula C (balanceBlast*). Out of it: an explosive bullet's blast on anyone but the creature it struck, the pairs of balance.final.table.pairs (their gun table lines and formula C, as X908), and shots of no
 * pair of the table (NTM's mobs' and turrets' shots of no gun, other games' guns): NTM's numbers (balance.final.mobs =
 * ntm). Off: everything as X908.
 */
public final class BalanceFinal {
    private BalanceFinal() {
    }

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("ntm-gunfeel");
    private static final String PAIRS = "/ntmgunfeel-balance-final-pairs.tsv";

    public static boolean on() {
        return ReaimTimes.flag("balance.final", true) && BalanceArmor.on();
    }

    // ---------------------------------------------------------------- the shot's pair

    /** the pair (gun.ammo) a projectile was fired with, by the projectile */
    private static final Map<Entity, String> PAIR = Collections.synchronizedMap(new WeakHashMap<>());

    /** the last pair each shooter fired, and when (an NTM blast of its shot, the Tesla Cannon's, has no projectile) */
    private record Last(String pair, long tick) {
    }

    private static final Map<Entity, Last> LAST = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * a shot of {@code shooter}'s {@code gunItem} with {@code ammo} has been made (BalanceGuns.shot): its pair by the
     * ammo's name, or by the round loaded in the gun when the shot's config is not an ammo of the table (the Shredder
     * fires a shell as plasma of a config of its own)
     */
    static void fired(Entity projectile, LivingEntity shooter, net.minecraft.world.item.Item gunItem, com.hbm.items.weapon.sedna.BulletConfig ammo) {
        if (projectile == null || shooter == null || shooter.level().isClientSide() || !on()) return;
        try {
            fired0(projectile, shooter, gunItem, ammo);
        } catch (RuntimeException | LinkageError e) {
            // the shot goes on without a pair: NTM's machine takes it
        }
    }

    private static void fired0(Entity projectile, LivingEntity shooter, net.minecraft.world.item.Item gunItem, com.hbm.items.weapon.sedna.BulletConfig ammo) {
        String gun = gunName(gunItem);
        if (gun == null || ammo == null) return;
        String id = gun + "." + ammoName(ammo);
        if (!state().pairs().containsKey(id)) {
            String loaded = loadedAmmo(shooter, gunItem);
            if (loaded != null && state().pairs().containsKey(gun + "." + loaded)) id = gun + "." + loaded;
        }
        PAIR.put(projectile, id);
        LAST.put(shooter, new Last(id, shooter.level().getGameTime()));
        if (BalanceLog.on()) BalanceLog.shot(projectile, shooter, gunItem);
    }

    /** the gun's name of the scheme: NTM's, or the GL-54's "gl54" (a launcher of balance.final.blast.launchers) */
    static String gunName(net.minecraft.world.item.Item item) {
        String g = BalanceGuns.gunName(item);
        if (g != null) return g;
        var id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
        return id != null && id.getNamespace().equals(acup.gunfeel.gl54.Gl54Items.NAMESPACE) && id.getPath().equals("gun_gl54") ? "gl54" : null;
    }

    /** the ammo's name of the scheme: NTM's, or a GL-54 grenade's kind (he, airburst, ap, inc, ...) */
    static String ammoName(com.hbm.items.weapon.sedna.BulletConfig ammo) {
        if (ammo instanceof acup.gunfeel.gl54.GrenadeType g) {
            var k = g.grenade();
            return k != null ? k.key : g.kind;
        }
        return BalanceGuns.ammoName(ammo);
    }

    /** a submunition of {@code thrower}'s (made from a shot of its, the Shredder's plasma): the pair it has just fired */
    public static void submunition(Entity projectile, LivingEntity thrower) {
        if (projectile == null || thrower == null || thrower.level().isClientSide() || !on() || PAIR.containsKey(projectile)) return;
        Last l = LAST.get(thrower);
        if (l != null && thrower.level().getGameTime() - l.tick() <= 2) PAIR.put(projectile, l.pair());
    }

    /** the ammo name of the round loaded in the shooter's gun (its first receiver that has one), or null */
    private static String loadedAmmo(LivingEntity shooter, net.minecraft.world.item.Item gunItem) {
        var stack = shooter.getMainHandItem().getItem() == gunItem ? shooter.getMainHandItem() : shooter.getOffhandItem();
        if (!(stack.getItem() instanceof com.hbm.items.weapon.sedna.ItemGunBaseNT gun)) return null;
        try {
            for (int c = 0; c < gun.getConfigCount(); c++)
                for (var rec : gun.getConfig(stack, c).getReceivers(stack))
                    if (rec.getMagazine(stack).getType(stack, null) instanceof com.hbm.items.weapon.sedna.BulletConfig b) return BalanceGuns.ammoName(b);
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    /** a blast of a shot (not a launcher's round) the scheme takes: the pair its shooter fired in the last ticks */
    private static String blastPair(DamageSource source) {
        Entity shooter = source.getEntity();
        Last l = shooter == null ? null : LAST.get(shooter);
        if (l == null || shooter.level().getGameTime() - l.tick() > 5) return null;
        // a beam's pair alone: an NTM blast after a bullet's shot (a fire's, a launcher's) is not the pair's
        var pr = state().pairs().get(l.pair());
        return pr != null && !pr.cat().equals("PHYS") ? l.pair() : null;
    }

    /** a blast's hit of the scheme on a target: the blast (by its source) and its tick; one blast a click counts */
    private record BlastHit(int source, int shooter, long tick) {
    }

    private static final Map<Entity, BlastHit> BLAST_HITS = Collections.synchronizedMap(new WeakHashMap<>());

    /** a second blast of the same shooter's on the target in the tick (the Tesla Cannon's arc again): nothing */
    private static boolean againThisTick(LivingEntity target, DamageSource source) {
        // the Tesla Cannon's arc (a beam's class, not an explosion): an explosive shell's pellets each have their own blast
        if (!BalanceArmor.vntBlastNow() || source.is(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION)
                || DamageResistanceHandler.CATEGORY_EXPLOSION.equals(BalanceArmor.category(source))) return false;
        long now = target.level().getGameTime();
        BlastHit was = BLAST_HITS.get(target);
        int id = System.identityHashCode(source);
        int shooter = source.getEntity() == null ? Integer.MIN_VALUE : source.getEntity().getId();
        if (was != null && was.tick() == now && was.shooter() == shooter && was.source() != id) return true;
        BLAST_HITS.put(target, new BlastHit(id, shooter, now));
        return false;
    }

    /** the pair the projectile was fired with, or null */
    static String pairOf(Entity projectile) {
        return projectile == null ? null : PAIR.get(projectile);
    }

    // ---------------------------------------------------------------- the keys

    /** what the keys say now, worked out again when the settings change */
    private record State(Object version, BalanceFinalRule.Params params, Map<String, BalanceFinalRule.Pair> pairs, BalanceFinalRule.Refs refs,
            Set<String> table, String explosive, Map<String, BalanceFinalRule.Armor> sets, double[] weights, double empty,
            Map<CacheKey, Double> cache, BalanceBlastRule.Rule blast, BalanceRangeRule.Rule range) {
    }

    /** a cell of the cache: the pair and the set it meets */
    private record CacheKey(String pair, BalanceFinalRule.Armor armor) {
    }

    private static volatile State state;
    private static List<String> lines;

    static State state() {
        Object v = ReaimTimes.version();
        State s = state;
        if (s != null && s.version().equals(v)) return s;
        synchronized (BalanceFinal.class) {
            s = state;
            if (s != null && s.version().equals(v)) return s;
            state = s = build(v);
            return s;
        }
    }

    private static List<String> lines() {
        if (lines == null) {
            try (InputStream in = BalanceFinal.class.getResourceAsStream(PAIRS)) {
                lines = in == null ? List.of() : new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
            } catch (IOException e) {
                LOGGER.warn("Could not read the balance's final pairs", e);
                lines = List.of();
            }
        }
        return lines;
    }

    private static double num(String key, double def, double min, double max) {
        float v = ReaimTimes.number(key, (float) def);
        return Float.isFinite(v) && v >= min && v <= max ? v : def;
    }

    private static State build(Object version) {
        var d = BalanceFinalRule.Params.DEFAULT;
        String[] ch = ReaimTimes.text("balance.final.character", d.beta() + ":" + d.lo() + ":" + d.hi()).trim().split(":");
        double beta = d.beta(), lo = d.lo(), hi = d.hi();
        try {
            if (ch.length == 3) {
                double b = Double.parseDouble(ch[0]), l = Double.parseDouble(ch[1]), h = Double.parseDouble(ch[2]);
                if (b >= 0 && l > 0 && h >= l) {
                    beta = b;
                    lo = l;
                    hi = h;
                }
            }
        } catch (NumberFormatException ignored) {
            // the defaults
        }
        double ammoK = d.ammoK(), from = d.ammoFrom(), most = d.ammoMax(), jhp = d.jhp();
        for (String e : ReaimTimes.text("balance.final.ammo", "").trim().split("\\s+")) {
            int c = e.indexOf('=');
            if (c <= 0) continue;
            try {
                double x = Double.parseDouble(e.substring(c + 1));
                switch (e.substring(0, c)) {
                    case "pen" -> ammoK = x;
                    case "from" -> from = x;
                    case "max" -> most = x;
                    case "jhp" -> jhp = x;
                    default -> {
                    }
                }
            } catch (NumberFormatException ignored) {
                // left out
            }
        }
        String explosive = ReaimTimes.text("balance.final.explosive", "C").trim().toUpperCase(java.util.Locale.ROOT);
        if (!explosive.equals("A") && !explosive.equals("B")) explosive = "C";
        Set<String> heads = new HashSet<>();
        for (String g : ReaimTimes.text("balance.final.head.guns", String.join(",", d.headGuns())).split("[,\\s]+")) if (!g.isBlank()) heads.add(g.trim());
        Map<String, Double> roles = new HashMap<>(d.roles());
        roles.putAll(BalanceFinalRule.roles(ReaimTimes.text("balance.final.roles", BalanceFinalRule.Params.ROLES)));
        var params = new BalanceFinalRule.Params(BalanceFinalRule.anchors(ReaimTimes.text("balance.final.ttk", BalanceFinalRule.Params.TTK)),
                num("balance.final.ttk.min", d.tmin(), 0.05, 60), num("balance.final.ttk.max", d.tmax(), 1, 1e6), num("balance.final.ttk.below", d.below(), 1, 100),
                beta, lo, hi, num("balance.final.energy", d.energy(), 0.01, 100), ammoK, from, most, jhp, num("balance.final.ped", d.ped(), -5, 5),
                explosive.equals("C") ? 1.0 : 0.0, num("balance.final.dnsOverFau", d.dnsOverFau(), 0, 100), Set.copyOf(heads),
                num("balance.final.head.gap", d.headGap(), -10, 10), d.laserTrench(), Map.copyOf(roles), num("balance.final.slug.pellets", d.slugPellets(), 0, 1000),
                BalanceFinalRule.MagRule.parse(ReaimTimes.text("balance.final.mag", ""), d.mag()));
        // the pairs, a tier of balance.final.pairTier.<gun>.<ammo> in place of their own
        Map<String, Double> tiers = new HashMap<>();
        for (String l : lines()) {
            var pr = BalanceFinalRule.parse(l);
            if (pr == null) continue;
            String key = "balance.final.pairTier." + pr.id();
            if (ReaimTimes.inFile(key)) tiers.put(pr.id(), num(key, pr.tier(), -5, 20));
        }
        List<String> bad = new ArrayList<>();
        var pairs = BalanceFinalRule.pairs(lines(), tiers, bad);
        for (String b : bad) LOGGER.warn("The balance's final pairs: a line not read: {}", b);
        Set<String> table = new HashSet<>();
        for (String t : ReaimTimes.text("balance.final.table.pairs", "").split("[,\\s]+")) if (!t.isBlank()) table.add(t.trim());
        // the refs of the pairs the formula takes (the table's are not)
        List<BalanceFinalRule.Pair> formula = new ArrayList<>();
        for (var pr : pairs.values()) if (!inTable(table, pr.id())) formula.add(pr);
        Map<String, BalanceFinalRule.Armor> sets = new HashMap<>();
        DEFAULT_SETS.forEach((name, a) -> {
            var kit = kitOf(name);
            // the kits' pools the scheme counts in (balance.final.pools), whatever the pools' own keys: the stand makes them 0.0001
            sets.put(name, armorKey(name, kit == null ? a : new BalanceFinalRule.Armor(a.kind(), a.tier(), a.hours(), a.en(), pools(kit))));
        });
        double[] weights = {3, 8, 6, 3};
        String[] w = ReaimTimes.text("balance.final.armor.weights", "3,8,6,3").split(",");
        if (w.length == 4) try {
            double[] x = new double[4];
            for (int i = 0; i < 4; i++) x[i] = Double.parseDouble(w[i].trim());
            if (x[0] >= 0 && x[1] >= 0 && x[2] >= 0 && x[3] >= 0 && x[0] + x[1] + x[2] + x[3] > 0) weights = x;
        } catch (NumberFormatException ignored) {
            // the defaults
        }
        var blast = new BalanceBlastRule.Rule(ReaimTimes.flag("balance.final.blast", true),
                parsed("balance.final.blast.launchers", BalanceBlastRule.LAUNCHERS, BalanceBlastRule::launchers),
                parsed("balance.final.blast.rounds", BalanceBlastRule.ROUNDS, BalanceBlastRule::tiers),
                parsed("balance.final.blast.grenades", BalanceBlastRule.GRENADES, BalanceBlastRule::tiers),
                num("balance.final.blast.shift", 1, -5, 5), BalanceBlastRule.cap(ReaimTimes.text("balance.final.blast.pair", "cap:2"), 2),
                BalanceBlastRule.hits(ReaimTimes.text("balance.final.blast.hits", ""), BalanceBlastRule.Hits.DEFAULT),
                parsed("balance.final.blast.frags", BalanceBlastRule.FRAGS, BalanceBlastRule::frags));
        var range = new BalanceRangeRule.Rule(parsed("balance.final.range", BalanceRangeRule.LINES, BalanceRangeRule::lines),
                num("balance.final.range.close", 8, 0, 256), Map.copyOf(BalanceFinalRule.roles(ReaimTimes.text("balance.final.range.home", BalanceRangeRule.HOME))));
        return new State(version, params, pairs, BalanceFinalRule.refs(formula), Set.copyOf(table), explosive, Map.copyOf(sets), weights,
                num("balance.final.armor.empty", 0.5, 0, 20), new ConcurrentHashMap<>(), blast, range);
    }

    /** a pair in balance.final.table.pairs: by its gun (all its ammo) or gun.ammo */
    static boolean inTable(Set<String> table, String id) {
        int dot = id.indexOf('.');
        return table.contains(id) || (dot > 0 && table.contains(id.substring(0, dot)));
    }

    /** a set's numbers of balance.final.armor.<set> = tier:hours:EN, the scheme's by default */
    private static BalanceFinalRule.Armor armorKey(String name, BalanceFinalRule.Armor def) {
        String v = ReaimTimes.text("balance.final.armor." + name, "").trim();
        if (v.isEmpty()) return def;
        String[] p = v.split(":");
        try {
            double t = Double.parseDouble(p[0]);
            double h = p.length > 1 ? Double.parseDouble(p[1]) : def.hours();
            double en = p.length > 2 ? Double.parseDouble(p[2]) : def.en();
            if (Double.isFinite(t) && Double.isFinite(h) && Double.isFinite(en)) return new BalanceFinalRule.Armor(def.kind(), t, h, en, def.pool());
        } catch (NumberFormatException ignored) {
            // the default
        }
        return def;
    }

    /** the kit of a full set of {@code name} (NTM's name), or null */
    private static EuphImmunity.Kit kitOf(String name) {
        for (var k : EuphImmunity.Kit.values()) if (k.set.equals(name)) return k;
        return null;
    }

    /** balance.final.pools: the pools of DNS, FAU and euphemium the scheme counts in, "dns:45 fau:40 euph:150" */
    public static final String POOLS = "dns:45 fau:40 euph:150";

    /** the pool of {@code kit} by balance.final.pools (Fable 2.6: DNS 45, FAU 40; X912: dns:40 fau:45) */
    static double pools(EuphImmunity.Kit kit) {
        String name = kit == EuphImmunity.Kit.EUPHEMIUM ? "euph" : kit.set;
        var byKey = BalanceFinalRule.roles(ReaimTimes.text("balance.final.pools", POOLS));
        Double v = byKey.get(name);
        if (v == null) v = BalanceFinalRule.roles(POOLS).get(name);
        return v == null ? kit.size : v;
    }

    /** the size of a kit's pool when its key balance.immune*.pool is not set: balance.final.pools with the scheme on, X908's own off */
    static float poolOf(EuphImmunity.Kit kit) {
        try {
            return on() ? (float) pools(kit) : kit.size;
        } catch (RuntimeException e) {
            return kit.size;
        }
    }

    private static BalanceFinalRule.Armor a(String kind, double t, double h, double en, double pool) {
        return new BalanceFinalRule.Armor(kind, t, h, en, pool);
    }

    /**
     * the scheme's sets by NTM's names (the items' names before _helmet and the rest): tier, tier by the hours, beam
     * shift, the pool of a full set (the kits' own sizes, whatever the pools' keys: the stand makes them 0.0001; a player's kit in the mode pool alone has one, armorOf)
     */
    static final Map<String, BalanceFinalRule.Armor> DEFAULT_SETS = Map.ofEntries(Map.entry("steel", a("steel", 1, 1, -1, 0)),
            Map.entry("cobalt", a("cobalt", 1, 1, -1, 0)), Map.entry("alloy", a("alloy", 1, 1, -1, 0)), Map.entry("titanium", a("titanium", 1, 1, -1, 0)),
            Map.entry("taurun", a("taurun", 1, 1, -1, 0)), Map.entry("dieselsuit", a("dieselsuit", 2, 2, 0, 0)), Map.entry("t51", a("t51", 3, 3, 0, 0)),
            Map.entry("steamsuit", a("steamsuit", 2.5, 4, 0, 0)), Map.entry("security", a("security", 2.5, 4, -1, 0)), Map.entry("hev", a("hev", 4, 4, 0, 0)),
            Map.entry("ajr", a("ajr", 4, 4, 0, 0)), Map.entry("ajro", a("ajro", 4, 4, 0, 0)), Map.entry("envsuit", a("envsuit", 4, 4, 0, 0)),
            Map.entry("trenchmaster", a("trench", 6.5, 6, 0, 0)), Map.entry("bismuth", a("bismuth", 6, 6, 0, 0)), Map.entry("starmetal", a("starmetal", 6, 6, 0, 0)),
            Map.entry("bj", a("bj", 6, 6, 0, 0)), Map.entry("ncrpa", a("ncrpa", 6, 6, 0, 0)), Map.entry("cmb", a("cmb", 7, 7, 0, 0)),
            Map.entry("schrabidium", a("schrabidium", 7, 7, 0, 0)), Map.entry("rpa", a("rpa", 7, 7, 0, 0)),
            Map.entry("fau", a("fau", 8, 8, 0, EuphImmunity.Kit.FAU.size)), Map.entry("dns", a("dns", 9, 8, 0, EuphImmunity.Kit.DNS.size)),
            Map.entry("euphemium", a("euph", 9, 9, 0, EuphImmunity.Kit.EUPHEMIUM.size)));

    private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    // ---------------------------------------------------------------- a hit

    /** the scheme's view of one hit: its pair, the set it meets, the head's rule */
    record Hit(BalanceFinalRule.Pair pair, BalanceFinalRule.Armor armor, double clickDamage, boolean oneShotHead) {
        /** the damage of one projectile on the body (before the part hit's multiplier) */
        float projectile() {
            return (float) (clickDamage / pair.projectiles());
        }
    }

    /**
     * the hit the scheme takes, or null when it is not the scheme's (blasts, pairs of no line or of the table, targets
     * that are not players and wear nothing of the table)
     */
    static Hit hit(LivingEntity target, DamageSource source) {
        if (target.level().isClientSide() || !on()) return null;
        String cat = BalanceArmor.category(source);
        if (cat == null) return null;
        State s = state();
        Entity projectile;
        if (DamageResistanceHandler.CATEGORY_EXPLOSION.equals(cat)) {
            // an explosive bullet's blast on the creature it struck: its direct hit (variant C, B); the rest of the blast as before
            if (s.explosive().equals("A") || !BalanceArmor.bulletBlastNow() || ExplosiveDamage.struckNow() != target) return null;
            projectile = ExplosiveDamage.bulletNow();
        } else projectile = source.getDirectEntity();
        String id = pairOf(projectile);
        // an NTM blast of a beam or a bullet of a pair (the Tesla Cannon's arc): the pair its shooter just fired
        if (id == null && BalanceArmor.vntBlastNow() && !DamageResistanceHandler.CATEGORY_EXPLOSION.equals(cat)) id = blastPair(source);
        if (id == null || inTable(s.table(), id)) return null;
        var pr = s.pairs().get(id);
        if (pr == null || (pr.explosive() && s.explosive().equals("A"))) return null;
        var armor = armorOf(target, s);
        if (armor == null) return null;
        BalanceFinalRule.Armor fau = s.sets().get("fau");
        double click = s.cache().computeIfAbsent(new CacheKey(id, armor), k -> BalanceFinalRule.clickDamage(pr, armor, fau, s.refs(), s.params()));
        return new Hit(pr, armor, click, BalanceFinalRule.headOneShot(pr, armor.hours(), s.params()));
    }

    /**
     * the set the scheme takes on {@code target}: a full set of one kind its own (with its pool), a mixed or incomplete
     * one by the slots' weights (no pool), nothing of the table: bare (players only); null when not the scheme's
     */
    static BalanceFinalRule.Armor armorOf(LivingEntity target, State s) {
        BalanceFinalRule.Armor[] slots = new BalanceFinalRule.Armor[4];
        String first = null;
        boolean same = true, any = false;
        for (int i = 0; i < 4; i++) {
            String set = BalanceArmor.setOf(target.getItemBySlot(SLOTS[i]), i);
            var a = set == null ? null : s.sets().get(set);
            slots[i] = a;
            if (a != null) any = true;
            if (i == 0) first = set;
            if (a == null || set == null || !set.equals(first)) same = false;
        }
        if (!any) return target instanceof Player ? BalanceFinalRule.MODEL_SETS.get("none") : null;
        if (same) return slots[0].pool() > 0 && !pooled(target, first) ? new BalanceFinalRule.Armor(slots[0].kind(), slots[0].tier(), slots[0].hours(), slots[0].en(), 0) : slots[0];
        return BalanceFinalRule.mix(slots, s.weights(), s.empty());
    }

    /** the full set's pool is there: a player's, its kit in the mode pool (balance.immune*.mode) */
    private static boolean pooled(LivingEntity target, String set) {
        if (!(target instanceof Player)) return false;
        for (var kit : EuphImmunity.Kit.values()) if (kit.set.equals(set)) return EuphImmunity.on(kit);
        return false;
    }

    /** a one-shot on the head is under way (the damage cap lets it through as a sniper's head) */
    private record OneShot(LivingEntity target, DamageSource source) {
    }

    private static final ThreadLocal<OneShot> ONE_SHOT = new ThreadLocal<>();

    /** the one-shot on the head of the hurt under way on {@code target} by {@code source} (that hurt's alone), read once (DamageCap) */
    static boolean takeOneShot(LivingEntity target, DamageSource source) {
        OneShot o = ONE_SHOT.get();
        ONE_SHOT.remove();
        return o != null && o.target() == target && o.source() == source;
    }

    /** the hit is the scheme's one-shot on the head (the pool's drain takes the whole pool) */
    static boolean oneShotHead(LivingEntity target, DamageSource source) {
        Hit h = hit(target, source);
        return h != null && h.oneShotHead() && HeadDamage.onHead(target);
    }

    /**
     * what the scheme leaves of a hit on {@code target} (in place of NTM's threshold and resistance and formula C), or
     * NaN when the hit is not the scheme's
     */
    static float damage(LivingEntity target, DamageSource source) {
        float b = blastOrFrag(target, source);
        if (!Float.isNaN(b)) return b * target.getMaxHealth() / (float) BalanceFinalRule.HP;
        Hit h = hit(target, source);
        if (h == null) return Float.NaN;
        if (againThisTick(target, source)) return 0F;
        if (BalanceLog.on()) BalanceLog.hit(target, source, null, h.pair().id(), h.pair().tier(), HeadDamage.onHead(target));
        float scale = target.getMaxHealth() / (float) BalanceFinalRule.HP;
        if (h.oneShotHead() && HeadDamage.onHead(target)) {
            // the head's rule: the whole health (and pool) in one click, the head's multiplier after it
            ONE_SHOT.set(new OneShot(target, source));
            return (float) ((BalanceFinalRule.HP + h.armor().pool()) * scale / ModelHeads.multiplier(true, false) / h.pair().projectiles());
        }
        ONE_SHOT.remove();
        return h.projectile() * scale * range(target, source, h);
    }

    /**
     * the hit's share by range (balance.final.range; Fable 10.4): by the pair's role and the shooter's distance to the
     * target now; 1 for a blast (an explosive shell's direct hit), the Tesla Cannon's arc, no shooter, the rule off
     */
    private static float range(LivingEntity target, DamageSource source, Hit h) {
        var r = state().range();
        if (!r.on() || BalanceArmor.vntBlastNow() || expl(source) || source.getEntity() == null) return 1F;
        double d = source.getEntity().position().distanceTo(target.position());
        return (float) BalanceRangeRule.damage(r, BalanceFinalRule.roleOf(h.pair(), state().params()), d);
    }

    /** what a hit takes from a full set's pool (EuphImmunity), or NaN when it is not the scheme's */
    static float drain(LivingEntity target, DamageSource source) {
        float b = blastOrFrag(target, source);
        if (!Float.isNaN(b)) return b;
        Hit h = hit(target, source);
        if (h == null) return Float.NaN;
        if (againThisTick(target, source)) return 0F;
        boolean head = HeadDamage.onHead(target);
        if (BalanceLog.on()) BalanceLog.hit(target, source, null, h.pair().id(), h.pair().tier(), head);
        if (h.oneShotHead() && head) return (float) ((BalanceFinalRule.HP + h.armor().pool()) / h.pair().projectiles());
        float d = h.projectile() * range(target, source, h);
        return head ? Math.min(d * ModelHeads.multiplier(true, false), DamageCap.limits().head() * (float) BalanceFinalRule.HP) : d;
    }

    /**
     * a shot or a beam the scheme leaves to NTM's numbers: a mob's or a turret's of no pair of the table (owner
     * 2026-11-12: NTM's damage as it is): NTM's threshold and resistance, then vanilla's armour, no formula C
     */
    static boolean ntmShot(LivingEntity target, DamageSource source) {
        if (!on()) return false;
        String cat = BalanceArmor.category(source);
        if (cat == null || DamageResistanceHandler.CATEGORY_EXPLOSION.equals(cat)) return false;
        // a player's shot of no pair (a launcher's submunition, another game's gun) keeps X908's machine
        if (!(source.getEntity() instanceof LivingEntity shooter) || shooter instanceof Player) return false;
        String id = pairOf(source.getDirectEntity());
        if (id == null && BalanceArmor.vntBlastNow()) id = blastPair(source);
        if (id == null) return true;
        State s = state();
        // a GL-54 fragment grenade's (BalanceFinal.frag): the scheme's, like a pair's
        if (s.blast().on() && id.startsWith("gl54.") && s.blast().frags().containsKey(id.substring(5))) return false;
        return !inTable(s.table(), id) && !s.pairs().containsKey(id);
    }

    // ---------------------------------------------------------------- blasts (balance.final.blast; Fable 11.2, 12.3)

    /** a blast of the ladder's under way on one target: what one hit of it takes (of 20 HP and the pool), its pair */
    private record BlastNow(LivingEntity target, double take, String id, double tier, Entity projectile) {
    }

    private static final ThreadLocal<BlastNow> BLAST_NOW = new ThreadLocal<>();

    /** a hand grenade going off (ItemGrenadeFilling.explode): its filling's name */
    private static final ThreadLocal<String> GRENADE = new ThreadLocal<>();

    /** a hand grenade goes off: its blasts are dealt with its filling known */
    public static void grenade(com.hbm.entity.grenade.EntityGrenadeUniversal grenade, Runnable explode) {
        if (grenade.level().isClientSide() || !on()) {
            explode.run();
            return;
        }
        String was = GRENADE.get(), filling;
        try {
            filling = grenade.getFilling().name().toLowerCase(java.util.Locale.ROOT);
        } catch (RuntimeException e) {
            filling = null;
        }
        if (filling != null) GRENADE.set(filling);
        else GRENADE.remove();
        try {
            explode.run();
        } finally {
            if (was != null) GRENADE.set(was);
            else GRENADE.remove();
        }
    }

    /**
     * an NTM blast's hit on {@code target} (MixinExplosiveProcessor): with a launcher's round's or a hand grenade's blast
     * on a target of the scheme, the hurt deals the ladder's share instead (BalanceArmor.apply, EuphImmunity.drain)
     */
    public static boolean blastAt(com.hbm.explosion.vanillant.ExplosionVNT explosion, ExplosiveDamage.Blast blast, LivingEntity target, boolean struck,
            boolean explosive, java.util.function.Supplier<Boolean> hurt) {
        if (!explosive || explosion == null || target == null || target.level().isClientSide() || !on()) return hurt.get();
        BlastNow b;
        try {
            b = blastOf(explosion, blast, target, struck);
        } catch (RuntimeException e) {
            b = null;
        }
        if (b == null) return hurt.get();
        BlastNow was = BLAST_NOW.get();
        BLAST_NOW.set(b);
        try {
            return hurt.get();
        } finally {
            if (was != null) BLAST_NOW.set(was);
            else BLAST_NOW.remove();
        }
    }

    /** the blast's hurt under way on {@code target} is the ladder's (no part's multiplier on it, NTM's early check passed) */
    public static boolean blastNow(LivingEntity target) {
        BlastNow b = BLAST_NOW.get();
        return b != null && b.target() == target;
    }

    /** a hit the scheme takes, a blast's or a GL-54 fragment's among them (BalanceArmor.covers) */
    static boolean takes(LivingEntity target, DamageSource source) {
        return hit(target, source) != null || blastNow(target) && expl(source) || !Float.isNaN(frag(target, source, false));
    }

    private static boolean expl(DamageSource source) {
        return DamageResistanceHandler.CATEGORY_EXPLOSION.equals(BalanceArmor.category(source));
    }

    /** a key's entries; a value of which nothing reads (not empty, not "off") is the default's, not none */
    private static <T> Map<String, T> parsed(String key, String def, java.util.function.Function<String, Map<String, T>> parse) {
        String text = ReaimTimes.text(key, def);
        Map<String, T> out = parse.apply(text);
        if (out.isEmpty() && text != null && !text.isBlank() && !text.trim().equalsIgnoreCase("off")) out = parse.apply(def);
        return Map.copyOf(out);
    }

    private static BlastNow blastOf(com.hbm.explosion.vanillant.ExplosionVNT explosion, ExplosiveDamage.Blast blast, LivingEntity target, boolean struck) {
        State s = state();
        var r = s.blast();
        // balanceArmorBlasts off: the blast is NTM's (BalanceArmor.category), so not the ladder's either
        if (!r.on() || !ReaimTimes.flag("balanceArmorBlasts", true)) return null;
        var armor = armorOf(target, s);
        if (armor == null) return null;
        String id;
        double tier, near;
        Entity owner, projectile;
        if (blast != null) {
            // a launcher's round (its gun in balance.final.blast.launchers): its pair by the shot
            var bullet = ExplosiveDamage.bulletOf(blast);
            id = pairOf(bullet);
            int dot = id == null ? -1 : id.indexOf('.');
            if (dot <= 0) return null;
            var l = r.launchers().get(id.substring(0, dot));
            Double round = r.rounds().get(id.substring(dot + 1));
            if (l == null || round == null) return null;
            tier = BalanceBlastRule.pairTier(l, round, r.cap());
            double t = tier;
            near = s.cache().computeIfAbsent(new CacheKey("blast " + id, armor), k -> (double) BalanceBlastRule.nearLauncher(l, BalanceBlastRule.target(t, armor, s.params(), r)));
            owner = bullet.getOwner();
            projectile = bullet;
        } else {
            // a hand grenade (its filling in balance.final.blast.grenades), not inside a shot's impact
            String filling = GRENADE.get();
            Double t = filling == null || ExplosiveDamage.current() != null ? null : r.grenades().get(filling);
            if (t == null) return null;
            id = "grenade." + filling;
            tier = t;
            near = s.cache().computeIfAbsent(new CacheKey("blast " + id, armor), k -> BalanceBlastRule.nearGrenade(BalanceBlastRule.target(t, armor, s.params(), r), s.params(), r));
            owner = explosion.exploder;
            // a mob's or a machine's grenade: NTM's numbers, as their shots
            if (owner != null && !(owner instanceof net.minecraft.world.entity.player.Player)) return null;
            projectile = null;
        }
        // where the target is: struck, or its box's distance from the blast against NTM's reach (twice the size)
        var box = target.getBoundingBox();
        double dx = Math.max(Math.max(box.minX - explosion.posX, 0), explosion.posX - box.maxX);
        double dy = Math.max(Math.max(box.minY - explosion.posY, 0), explosion.posY - box.maxY);
        double dz = Math.max(Math.max(box.minZ - explosion.posZ, 0), explosion.posZ - box.maxZ);
        double where = BalanceBlastRule.where(struck, Math.sqrt(dx * dx + dy * dy + dz * dz), 2.0 * explosion.size, r.hits());
        double share = BalanceBlastRule.share(near, where);
        // one's own blast: half
        if (owner == target) share *= 0.5;
        return new BlastNow(target, share * (BalanceFinalRule.HP + armor.pool()), id, tier, projectile);
    }

    /** a blast's or a GL-54 fragment's take of 20 HP and the pool, or NaN when the hit is neither (the log counts it) */
    private static float blastOrFrag(LivingEntity target, DamageSource source) {
        BlastNow b = BLAST_NOW.get();
        if (b != null && b.target() == target && expl(source)) {
            if (BalanceLog.on()) BalanceLog.hit(target, source, b.projectile(), b.id(), b.tier(), false);
            return (float) b.take();
        }
        return frag(target, source, true);
    }

    /**
     * a GL-54 fragment's (airburst, AP, incendiary; Fable 12.3) take of 20 HP and the pool: a burst whose cone's
     * fragments all hit takes BalanceBlastRule.fragClick; NaN when the hit is not one
     */
    private static float frag(LivingEntity target, DamageSource source, boolean log) {
        if (target.level().isClientSide() || !on()) return Float.NaN;
        String cat = BalanceArmor.category(source);
        if (cat == null || DamageResistanceHandler.CATEGORY_EXPLOSION.equals(cat)) return Float.NaN;
        Entity projectile = source.getDirectEntity();
        String id = pairOf(projectile);
        if (id == null || !id.startsWith("gl54.")) return Float.NaN;
        State s = state();
        var r = s.blast();
        String kind = id.substring(5);
        var f = r.frags().get(kind);
        var l = r.launchers().get("gl54");
        Double round = r.rounds().get(kind);
        var spec = acup.gunfeel.gl54.frag.FragSpec.of(acup.gunfeel.gl54.GrenadeKind.of(kind));
        if (!r.on() || f == null || l == null || round == null || spec == null) return Float.NaN;
        var armor = armorOf(target, s);
        if (armor == null) return Float.NaN;
        double tier = BalanceBlastRule.pairTier(l, round, r.cap());
        double role = s.params().roles().getOrDefault("airburst", 1.2);
        double click = s.cache().computeIfAbsent(new CacheKey("frag " + id, armor), k -> BalanceBlastRule.fragClick(l, f, tier, armor, s.params(), role));
        if (log && BalanceLog.on()) BalanceLog.hit(target, source, projectile, id, tier, false);
        // the click is a burst whose cone all hits (the ring's fragments are the side spray, each the same)
        return (float) (click / Math.max(1, spec.cone()));
    }

    /** the stand: a blast's hits to kill near (up to a block) of a launcher's pair or "grenade.<filling>" on a set (NaN: none) */
    static double blastNear(String pair, String set) {
        State s = state();
        var r = s.blast();
        var a = s.sets().get(set);
        if (a == null) a = BalanceFinalRule.MODEL_SETS.get(set);
        int dot = pair.indexOf('.');
        if (a == null || dot <= 0) return Double.NaN;
        if (pair.startsWith("grenade.")) {
            Double t = r.grenades().get(pair.substring(dot + 1));
            return t == null ? Double.NaN : BalanceBlastRule.nearGrenade(BalanceBlastRule.target(t, a, s.params(), r), s.params(), r);
        }
        var l = r.launchers().get(pair.substring(0, dot));
        Double round = r.rounds().get(pair.substring(dot + 1));
        if (l == null || round == null) return Double.NaN;
        return BalanceBlastRule.nearLauncher(l, BalanceBlastRule.target(BalanceBlastRule.pairTier(l, round, r.cap()), a, s.params(), r));
    }

    /** the stand: the hit clicks of a pair on a set by the model's or NTM's names (NaN: no such pair or set) */
    static double hitClicks(String pair, String set) {
        State s = state();
        var pr = s.pairs().get(pair);
        var a = s.sets().get(set);
        if (a == null) a = BalanceFinalRule.MODEL_SETS.get(set);
        if (pr == null || a == null) return Double.NaN;
        return BalanceFinalRule.HP / BalanceFinalRule.clickDamage(pr, a, s.sets().get("fau"), s.refs(), s.params());
    }
}
