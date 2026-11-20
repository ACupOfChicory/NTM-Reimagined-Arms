package acup.gunfeel;

import java.util.concurrent.ConcurrentHashMap;

import com.hbm.items.weapon.sedna.BulletConfig;

/**
 * The mod's own number of NTM's round (RoundTable), by the BulletConfig object itself (BulletConfig keeps Object's
 * equals): NTM's id and its place in BulletConfig.configs are not the mod's to keep. From 1.
 */
public final class RoundNumbers {
    private static final ConcurrentHashMap<BulletConfig, Integer> NUMBERS = new ConcurrentHashMap<>();
    /** the rounds by number (RoundNames), written under add's lock */
    private static final ConcurrentHashMap<Integer, BulletConfig> ROUNDS = new ConcurrentHashMap<>();
    private static int next = 1;

    private RoundNumbers() {
    }

    public static int number(BulletConfig round) {
        Integer n = NUMBERS.get(round);
        return n != null ? n : add(round);
    }

    private static synchronized int add(BulletConfig round) {
        Integer n = NUMBERS.get(round);
        if (n != null) return n;
        int m = next++;
        ROUNDS.put(m, round);
        NUMBERS.put(round, m);
        return m;
    }

    /** the round with this number, null for a number none has */
    public static BulletConfig round(int number) {
        return ROUNDS.get(number);
    }
}
