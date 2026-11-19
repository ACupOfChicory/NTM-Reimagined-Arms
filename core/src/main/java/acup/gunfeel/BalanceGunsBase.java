package acup.gunfeel;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * What BalanceGuns knows without the game: the gun table (the jar's, the server's, config/'s on the stands) and when
 * it is read again, the setting, and the sums on a shot's numbers (one ammo made as heavy as another, the floor by
 * tier). BalanceGuns extends it on each version and finds the gun and the ammo of a shot (NTM's items and bullet
 * configs); its callers name these members through it.
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
