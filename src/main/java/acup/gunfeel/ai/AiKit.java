package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import org.jspecify.annotations.Nullable;

/**
 * What an AI mob is given by command besides its gun (/armsai spawn … and /armsai kit …), as typed: the words after
 * the gun, in any order.
 * <pre>
 * armor &lt;set&gt;                 the four pieces of a set of the balance's table, by the table's name (hev, sec, t51)
 *                             or NTM's own (security, steamsuit); none: no armour at all
 * armor &lt;slot&gt;=&lt;item&gt; …      pieces one by one, the other slots as they are: head, chest, legs, feet; an item's
 *                             id (hbm:hev_plate), a set's name (its piece of that slot) or none; after a set's
 *                             name they change that set's pieces
 * ammo &lt;round&gt;               the round its gun loads and reloads, of those the gun takes, by the balance's name
 *                             (r556_ap)
 * team &lt;name&gt;                the game's team it joins (spawn only)
 * </pre>
 * Words only here: the items and the gun's rounds are looked up where the kit is put on (AiKitCommand).
 */
public final class AiKit {
    private AiKit() {
    }

    /** the slots' names as typed, in the order of the pieces (helmet, chestplate, leggings, boots) */
    static final String[] SLOTS = {"head", "chest", "legs", "feet"};
    private static final Map<String, Integer> SLOT_OF = Map.of("head", 0, "helmet", 0, "chest", 1, "plate", 1, "legs", 2, "feet", 3, "boots", 3);
    /** NTM's names of the pieces of a set, by slot (BalanceArmor.setOf reads a set's name off them) */
    static final String[] SUFFIX = {"_helmet", "_plate", "_legs", "_boots"};
    static final String NONE = "none";
    private static final List<String> WORDS = List.of("armor", "ammo", "team");

    /**
     * the balance table's names of the sets (the columns of the table of clicks) and NTM's name of each; a set of the
     * table with NTM's name alone (cobalt, alloy, ajro, envsuit) goes by that
     */
    static final Map<String, String> TABLE_SETS;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        for (String s : List.of("steel", "taurun", "t51", "ajr", "hev", "bismuth", "bj", "ncrpa", "cmb", "rpa", "dns", "fau")) m.put(s, s);
        m.put("titan", "titanium");
        m.put("diesel", "dieselsuit");
        m.put("sec", "security");
        m.put("steam", "steamsuit");
        m.put("star", "starmetal");
        m.put("trench", "trenchmaster");
        m.put("schrab", "schrabidium");
        m.put("euph", "euphemium");
        TABLE_SETS = Map.copyOf(m);
    }

    /** NTM's name of a set as typed (the table's name or NTM's own), or null when the balance has no such set */
    static @Nullable String ntmSet(String typed) {
        String own = TABLE_SETS.get(typed);
        if (own != null) return own;
        return acup.gunfeel.BalanceArmor.knownSet(typed) ? typed : null;
    }

    /** every name a set may be typed by, in order */
    static List<String> setNames() {
        TreeSet<String> out = new TreeSet<>(TABLE_SETS.keySet());
        out.addAll(acup.gunfeel.BalanceArmor.knownSets());
        return List.copyOf(out);
    }

    /** a kit as typed did not make sense: the message is for whoever typed it */
    public static final class Bad extends Exception {
        private static final long serialVersionUID = 1L;

        Bad(String message) {
            super(message);
        }
    }

    /**
     * a kit as typed. armor: by slot, an item's id, "" for a slot emptied, null for a slot left as it is; null when no
     * armour was named at all
     */
    public record Parsed(String @Nullable [] armor, @Nullable String ammo, @Nullable String team) {
        public boolean empty() {
            return armor == null && ammo == null && team == null;
        }
    }

    static final Parsed NOTHING = new Parsed(null, null, null);

    /** the words after the gun; {@code team}: whether a team may be named here */
    public static Parsed parse(@Nullable String text, boolean team) throws Bad {
        String[] tokens = text == null || text.isBlank() ? new String[0] : text.trim().split("\\s+");
        String[] armor = null;
        String ammo = null, side = null;
        int i = 0;
        while (i < tokens.length) {
            String word = tokens[i].toLowerCase(Locale.ROOT);
            int from = ++i;
            while (i < tokens.length && !WORDS.contains(tokens[i].toLowerCase(Locale.ROOT))) i++;
            List<String> values = List.of(tokens).subList(from, i);
            switch (word) {
                case "armor" -> {
                    if (armor != null) throw new Bad("armor is named twice");
                    armor = armor(values);
                }
                case "ammo" -> {
                    if (ammo != null) throw new Bad("ammo is named twice");
                    ammo = one("ammo", "a round, as in: ammo r556_ap", values).toLowerCase(Locale.ROOT);
                }
                case "team" -> {
                    if (!team) throw new Bad("a team is named when the mobs are spawned; here: armor …, ammo …");
                    if (side != null) throw new Bad("team is named twice");
                    side = one("team", "a team's name", values);
                }
                default -> throw new Bad("unknown word '" + tokens[from - 1] + "'; the words are: armor <set>, armor <slot>=<item> …, ammo <round>"
                        + (team ? ", team <name>" : ""));
            }
        }
        return new Parsed(armor, ammo, side);
    }

    private static String one(String word, String what, List<String> values) throws Bad {
        if (values.isEmpty()) throw new Bad(word + " needs " + what);
        if (values.size() > 1) throw new Bad(word + " takes one word, not '" + String.join(" ", values) + "'");
        return values.get(0);
    }

    private static String[] armor(List<String> values) throws Bad {
        if (values.isEmpty()) throw new Bad("armor needs a set (" + String.join(", ", setNames()) + ", none) or pieces as <slot>=<item>");
        String[] out = new String[4];
        boolean[] named = new boolean[4];
        for (int n = 0; n < values.size(); n++) {
            String v = values.get(n).toLowerCase(Locale.ROOT);
            int eq = v.indexOf('=');
            if (eq < 0) {
                // a whole set: once, and before the pieces that change it
                if (n > 0) throw new Bad("armor takes one set, then pieces as <slot>=<item>; '" + values.get(n) + "' is neither");
                for (int s = 0; s < 4; s++) out[s] = piece(v, s);
                continue;
            }
            Integer slot = SLOT_OF.get(v.substring(0, eq));
            if (slot == null) throw new Bad("unknown armour slot '" + v.substring(0, eq) + "'; slots: " + String.join(", ", SLOTS));
            if (named[slot]) throw new Bad("armour slot " + SLOTS[slot] + " is named twice");
            named[slot] = true;
            String item = v.substring(eq + 1);
            if (item.isEmpty()) throw new Bad("armour slot " + SLOTS[slot] + " needs an item, a set's name or none");
            out[slot] = item.indexOf(':') >= 0 ? item : piece(item, slot);
        }
        return out;
    }

    /** the id of a set's piece of a slot, "" for none */
    private static String piece(String set, int slot) throws Bad {
        if (set.equals(NONE)) return "";
        String own = ntmSet(set);
        if (own == null) throw new Bad("unknown armour set '" + set + "'; sets: " + String.join(", ", setNames()) + ", none");
        return "hbm:" + own + SUFFIX[slot];
    }

    /** what may come next, typed so far: {@code from} is where the word being typed starts in the text, {@code words} what it may be */
    public record Next(int from, List<String> words) {
    }

    /**
     * What the word being typed may be (the last of {@code text}, empty after a space): {@code ammo} the rounds the gun
     * takes, {@code teams} the teams there are (null: no team here). Whole words, the one typed so far is not looked at.
     */
    public static Next next(String text, Collection<String> ammo, @Nullable Collection<String> teams) {
        int from = text.lastIndexOf(' ') + 1;
        String[] before = text.substring(0, from).isBlank() ? new String[0] : text.substring(0, from).trim().split("\\s+");
        String typing = text.substring(from).toLowerCase(Locale.ROOT);
        String word = null;
        List<String> used = new ArrayList<>(), values = new ArrayList<>();
        for (String t : before) {
            String low = t.toLowerCase(Locale.ROOT);
            if (WORDS.contains(low)) {
                word = low;
                used.add(low);
                values.clear();
            } else values.add(low);
        }
        List<String> out = new ArrayList<>();
        if ("ammo".equals(word) && values.isEmpty()) out.addAll(ammo);
        else if ("team".equals(word) && values.isEmpty()) {
            if (teams != null) out.addAll(teams);
        } else if ("armor".equals(word)) {
            int eq = typing.indexOf('=');
            if (eq >= 0) {
                // a piece: the sets' names (their piece of that slot) and none
                String slot = typing.substring(0, eq + 1);
                for (String s : setNames()) out.add(slot + s);
                out.add(slot + NONE);
            } else {
                if (values.isEmpty()) {
                    out.addAll(setNames());
                    out.add(NONE);
                }
                for (int s = 0; s < 4; s++) {
                    boolean named = false;
                    for (String v : values) named |= v.startsWith(SLOTS[s] + "=");
                    if (!named) out.add(SLOTS[s] + "=");
                }
                if (!values.isEmpty()) more(out, used, teams);
            }
        } else more(out, used, teams);
        return new Next(from, out);
    }

    private static void more(List<String> out, List<String> used, @Nullable Collection<String> teams) {
        for (String w : WORDS) if (!used.contains(w) && (teams != null || !w.equals("team"))) out.add(w);
    }
}
