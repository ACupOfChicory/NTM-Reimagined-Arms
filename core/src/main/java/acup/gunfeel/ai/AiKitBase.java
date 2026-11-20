package acup.gunfeel.ai;

import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.CoreServices;
import acup.gunfeel.host.Body;
import acup.gunfeel.host.ItemTable;
import acup.gunfeel.host.NtmGuns;
import acup.gunfeel.host.RoundTable;

/**
 * The kit's round (/armsai kit … ammo, /armsai spawn … ammo), what core decides of it: the rounds a gun takes by
 * their names, why it cannot load one, in words, and the loading of a mob's gun. AiKitCommand in 26.2: the command's
 * words, the armour, and the same words for a gun no mob holds yet.
 */
public final class AiKitBase {
    private static final NtmGuns GUNS = CoreServices.load(NtmGuns.class, NtmGuns.NONE);
    private static final ItemTable ITEMS = CoreServices.load(ItemTable.class, ItemTable.MISSING);
    private static final RoundTable ROUNDS = CoreServices.load(RoundTable.class, RoundTable.MISSING);

    private AiKitBase() {
    }

    /** a round as typed: the balance's name, or the ammo item's id (hbm:ammo_standard_r556_ap) */
    public static String roundName(String typed) {
        String p = typed.startsWith("hbm:") ? typed.substring(4) : typed;
        if (p.startsWith("ammo_standard_")) return p.substring("ammo_standard_".length());
        return p.startsWith("ammo_") ? p.substring(5) : p;
    }

    /**
     * The rounds by their names ({@code count} of {@code rounds}, as {@link NtmGuns#rounds} gave them; none for -1), in
     * NTM's order, a name for its first round
     */
    public static Map<String, Integer> named(int[] rounds, int count) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) out.putIfAbsent(ROUNDS.name(rounds[i]), rounds[i]);
        return out;
    }

    /** the rounds the gun in the body's hand takes by their names (none for no gun), its first magazine found once */
    static Map<String, Integer> named(Body body, int hand) {
        int[] r = new int[64];
        int n = GUNS.rounds(body, hand, r);
        if (n > r.length) {
            r = new int[n];
            n = GUNS.rounds(body, hand, r);
        }
        return named(r, n);
    }

    /**
     * Why the gun ({@code gun}, its registry id; {@code empty}: the hand is empty; {@code ntmGun}: one of NTM's) with
     * these rounds by name cannot load the round as typed, in words, or null when it can
     */
    public static @Nullable String cannotLoad(String gun, boolean empty, boolean ntmGun, Map<String, Integer> rounds, String round) {
        if (!ntmGun) return (empty ? "an empty hand" : gun) + " is no NTM gun: no round to choose";
        if (rounds.isEmpty()) return gun + " has no rounds to choose from";
        if (!rounds.containsKey(roundName(round))) return gun + " does not take " + round + "; it takes: " + String.join(", ", rounds.keySet());
        return null;
    }

    /**
     * The mob's gun loads this round from now on, and is full of it now (from nothing, not from its reserve). What
     * went wrong in words, or null.
     */
    public static @Nullable String load(AiGunnerBase gunner, String round) {
        Body body = gunner.body;
        int item = body.held(Body.MAIN_HAND);
        String gun = ITEMS.name(item);
        boolean ntmGun = GUNS.isGun(body, Body.MAIN_HAND);
        Map<String, Integer> rounds = ntmGun ? named(body, Body.MAIN_HAND) : Map.of();
        String why = cannotLoad(gun, item == ItemTable.AIR, ntmGun, rounds, round);
        if (why != null) return why;
        int loaded = GUNS.loadRound(body, Body.MAIN_HAND, rounds.get(roundName(round)));
        if (loaded == NtmGuns.LOAD_UNLISTED) return gun + " cannot be told to load " + round + " (NTM does not list the round)";
        if (loaded == NtmGuns.LOAD_OTHER) return gun + " has no rounds to choose from";
        gunner.loadFree();
        return null;
    }
}
