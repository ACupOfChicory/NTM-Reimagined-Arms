package acup.gunfeel;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import acup.gunfeel.host.GunBalance;

/**
 * What BalanceGuns knows without the game: the gun table (the jar's, the server's, config/'s on the stands) and when
 * it is read again, the setting, and the sums on a shot's numbers (one ammo made as heavy as another, the floor by
 * tier), the gun/ammo walk and its identity caches. BalanceGuns supplies each version's live reads through
 * GunBalance; its callers keep their typed entries there.
 */
public class BalanceGunsBase {
    BalanceGunsBase() {
    }

    public static final String FILE = "ntm-gunfeel-balance-guns.tsv";

    static final String RESOURCE = "/ntmgunfeel-balance-guns.tsv";

    static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("ntm-gunfeel");

    public static boolean on() {
        return ReaimTimes.flag("balanceGuns", true);
    }

    private static BalanceGunsRule.Table table = null;

    private static long tableStamp = Long.MIN_VALUE, lastPoll;

    /** the server's settings the table was last read from (null: the jar's, or this game's file on the stands) */
    private static ServerSettings.Snapshot tableRemote;

    /** the table read again at the next look, whatever the clock (ReaimTimes.reloadNow) */
    static synchronized void reloadNow() {
        lastPoll = Long.MIN_VALUE / 2;
        tableStamp = Long.MIN_VALUE;
    }

    /** goes up each time the table is read anew: what a version worked out of the old one starts over */
    static int tableGeneration;

    private static final class GunPort {
        static final GunBalance READS = CoreServices.load(GunBalance.class, GunBalance.MISSING);
    }

    /** The table generation SHOTS and FROM were worked out of. */
    private static int shotsOf;
    private static final Map<Object, Map<Object, BalanceGunsRule.Shot>> SHOTS = new IdentityHashMap<>();
    private static final Map<Object, Map<String, BalanceGunsRule.Resolved>> FROM = new IdentityHashMap<>();

    static synchronized BalanceGunsRule.Table table() {
        BalanceGunsRule.Table t = loadTable();
        if (shotsOf != tableGeneration) {
            shotsOf = tableGeneration;
            SHOTS.clear();
            FROM.clear();
        }
        return t;
    }

    static String gunName(Object item) { return gunName(item, GunPort.READS); }

    static String gunName(Object item, GunBalance reads) {
        Object id = reads.itemId(item);
        if (id == null || !reads.namespace(id).equals("hbm")) return null;
        String p = reads.path(id);
        return p.startsWith("gun_") ? p.substring(4) : p;
    }

    public static String ammoName(Object b) { return ammoName(b, GunPort.READS); }

    static String ammoName(Object b, GunBalance reads) {
        // A config without an ammo item cannot provide an ammo stack.
        if (reads.ammoItem(b) == null) return "config_" + reads.ammoId(b);
        Object s;
        try {
            s = reads.ammoStack(b);
        } catch (RuntimeException e) {
            return "config_" + reads.ammoId(b);
        }
        if (s == null || reads.empty(s)) return "config_" + reads.ammoId(b);
        Object id = reads.stackId(s);
        String p = reads.path(id);
        if (p.startsWith("ammo_standard_")) return p.substring("ammo_standard_".length());
        return p.startsWith("ammo_") ? p.substring(5) : p;
    }

    private static final GunBalance.Walk WALK = new GunBalance.Walk() {
        @Override public boolean begin(Object ammo, List<Object> out, Map<Object, Float> click) {
            if (click.containsKey(ammo)) return false;
            out.add(ammo);
            return true;
        }
        @Override public float damage(Object ammo, float base, GunBalance reads) {
            return base * reads.damageMult(ammo) * (reads.min(ammo) + reads.max(ammo)) / 2F;
        }
        @Override public void finish(Object ammo, float damage, float split, Map<Object, Float> click) {
            click.put(ammo, damage * split);
        }
    };

    /** Every receiver of every config, keeping the first occurrence of each ammo. */
    static List<Object> ammoOf(Object gun, Object stack, Map<Object, Float> click, GunBalance reads) {
        List<Object> out = new ArrayList<>();
        reads.walk(gun, stack, WALK, out, click);
        return out;
    }

    static synchronized BalanceGunsRule.Shot pair(Object gunItem, Object ammo) {
        return pair(gunItem, ammo, GunPort.READS);
    }

    static synchronized BalanceGunsRule.Shot pair(Object gunItem, Object ammo, GunBalance reads) {
        BalanceGunsRule.Table t = table();
        if (!reads.isGun(gunItem) || ammo == null) return BalanceGunsRule.Shot.NONE;
        Map<Object, BalanceGunsRule.Shot> m = SHOTS.get(gunItem);
        if (m == null || !m.containsKey(ammo)) {
            String name = gunName(gunItem, reads);
            m = new IdentityHashMap<>();
            if (name != null) {
                Map<Object, Float> click = new IdentityHashMap<>();
                List<Object> all = ammoOf(gunItem, reads.freshStack(gunItem), click, reads);
                // Some shots have a magazine of their own.
                if (!click.containsKey(ammo)) {
                    all.add(ammo);
                    click.put(ammo, reads.damageMult(ammo) * (reads.min(ammo) + reads.max(ammo)) / 2F);
                }
                List<BalanceGunsRule.Ammo> named = new ArrayList<>();
                for (Object b : all) named.add(new BalanceGunsRule.Ammo(ammoName(b, reads), click.get(b), (reads.min(b) + reads.max(b)) / 2F));
                Map<String, BalanceGunsRule.Resolved> r = BalanceGunsRule.resolve(t, name, named);
                for (Object b : all) m.put(b, r.get(ammoName(b, reads)).shot());
                FROM.put(gunItem, r);
            }
            m.putIfAbsent(ammo, BalanceGunsRule.Shot.NONE);
            SHOTS.put(gunItem, m);
        }
        return m.get(ammo);
    }

    static float as(Object gunItem, Object from, Object as) { return as(gunItem, from, as, GunPort.READS); }

    static float as(Object gunItem, Object from, Object as, GunBalance reads) {
        if (!on() || gunItem == null || from == null || as == null) return 1F;
        return share(reads.damageMult(from), pair(gunItem, from, reads), reads.damageMult(as), pair(gunItem, as, reads));
    }

    static synchronized Map<String, BalanceGunsRule.Resolved> from(Object gunItem) { return from(gunItem, GunPort.READS); }

    static synchronized Map<String, BalanceGunsRule.Resolved> from(Object gunItem, GunBalance reads) {
        Object first = firstAmmo(gunItem, reads);
        if (first != null) pair(gunItem, first, reads);
        return FROM.getOrDefault(gunItem, Map.of());
    }

    static Object firstAmmo(Object item, GunBalance reads) {
        if (!reads.isGun(item)) return null;
        List<Object> all = ammoOf(item, reads.freshStack(item), new IdentityHashMap<>(), reads);
        return all.isEmpty() ? null : all.get(0);
    }

    static Object gunOf(Object shooter) { return gunOf(shooter, GunPort.READS); }

    static Object gunOf(Object shooter, GunBalance reads) {
        Object main = reads.item(reads.mainHand(shooter));
        if (reads.isGun(main)) return main;
        Object off = reads.item(reads.offHand(shooter));
        return reads.isGun(off) ? off : null;
    }

    /** the table as it is now: the server's, the jar's, or config/'s on the stands (looked at every 2 s) */
    static synchronized BalanceGunsRule.Table loadTable() {
        ServerSettings.Snapshot remote = ServerSettings.remote();
        if (remote != null) {
            if (remote != tableRemote) {
                tableRemote = remote;
                // an empty one: the server had no table to send, the jar's counts
                table = remote.guns().isBlank() ? read(null) : parse(remote.guns().lines().toList(), "the server's");
                tableGeneration++;
            }
            return table;
        }
        if (tableRemote != null) {
            // left the server: this game's own table again
            tableRemote = null;
            table = null;
        }
        long now = System.currentTimeMillis();
        if (table == null || now - lastPoll > 2000L) {
            lastPoll = now;
            Path f = GameDirs.get().config().resolve(FILE);
            long stamp = -1L;
            try {
                // config/'s table only on the stands (BalanceKeys.dev): the balance is fixed in the mod
                if (Files.exists(f)) {
                    if (BalanceKeys.dev()) stamp = Files.getLastModifiedTime(f).toMillis();
                    else if (!ignoredLogged) {
                        ignoredLogged = true;
                        LOGGER.info("{} is not read any more: the balance's gun table is the mod's", f);
                    }
                }
            } catch (IOException e) {
                stamp = -1L;
            }
            if (table == null || stamp != tableStamp) {
                tableStamp = stamp;
                table = read(stamp >= 0 ? f : null);
                tableGeneration++;
            }
        }
        return table;
    }

    private static boolean ignoredLogged;

    private static BalanceGunsRule.Table read(Path f) {
        List<String> lines;
        try {
            if (f != null) lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            else try (InputStream in = BalanceGunsBase.class.getResourceAsStream(RESOURCE)) {
                if (in == null) return BalanceGunsRule.Table.EMPTY;
                lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
            }
        } catch (IOException e) {
            LOGGER.warn("Could not read the balance's gun table {}", f, e);
            return BalanceGunsRule.Table.EMPTY;
        }
        return parse(lines, f != null ? f.toString() : "the jar's");
    }

    private static BalanceGunsRule.Table parse(List<String> lines, String where) {
        List<String> bad = new ArrayList<>();
        BalanceGunsRule.Table t = BalanceGunsRule.parse(lines, bad);
        for (String b : bad) LOGGER.warn("The balance's gun table ({}): a line not read: {}", where, b);
        return t;
    }

    /** the table as this game's server has it (the jar's; its file on the stands), for the players */
    static String text() throws IOException {
        Path f = GameDirs.get().config().resolve(FILE);
        if (BalanceKeys.dev() && Files.exists(f)) return Files.readString(f, StandardCharsets.UTF_8);
        try (InputStream in = BalanceGunsBase.class.getResourceAsStream(RESOURCE)) {
            return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** an ammo of NTM's damage {@code fromMult} and the table's line {@code a} made as heavy as one of {@code asMult} and {@code b} */
    static float share(float fromMult, BalanceGunsRule.Shot a, float asMult, BalanceGunsRule.Shot b) {
        float own = fromMult * (a.none() ? 1F : a.mult());
        return own > 0F ? asMult * (b.none() ? 1F : b.mult()) / own : 1F;
    }

    /** the floor of a shot whose extras are {@code e} (BalanceGuns.extra: null or short, none) on a set of {@code tier} */
    static float floorOf(float[] e, int tier) {
        if (e == null || e.length < 4) return 1F;
        float own = e[2] == 1F || tier < e[3] ? 1F : e[2];
        float k = e.length > 4 ? BalanceGunsRule.tierFloor(tierFloorRules(), (int) e[4], tier) : 1F;
        return k < 1F ? Math.max(BalanceGunsRule.LEAST_FLOOR, own * k) : own;
    }

    /**
     * balanceArmorTierFloor ("2:4:0.5 5:8:0.5"; "" or off: X884): a pair of tier 2 or below hits a set of tier 4 and
     * up with half the formula's floor, a pair of tier 5 or below a set of tier 8 and up (DNS, FAU, euphemium, their
     * pools too) the same (notes: the cheese review, item A)
     */
    static String tierFloorRules() {
        String v = ReaimTimes.text("balanceArmorTierFloor", TIER_FLOOR).strip();
        return v.equalsIgnoreCase("off") || v.equalsIgnoreCase("none") ? "" : v;
    }

    static final String TIER_FLOOR = "2:4:0.5 5:8:0.5";
}
