package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

// AiKitBase's words are 26.2's AiKitCommand's (copied here as they were, on the same names): the round's name as
// typed, and why a gun cannot load it
class AiKitBaseTest {
    private static final String[] TYPED = {"r556_ap", "hbm:ammo_standard_r556_ap", "ammo_standard_r556_ap", "hbm:ammo_r556",
            "ammo_r556", "hbm:r556", "ammo_", "hbm:", "", "hbm:ammo_standard_", "AMMO_r556", "hbm:hbm:ammo_x", "ammo_standard_ammo_x",
            "config_12", "m44_ap", "x"};

    private static String oldRoundName(String typed) {
        String p = typed.startsWith("hbm:") ? typed.substring(4) : typed;
        if (p.startsWith("ammo_standard_")) return p.substring("ammo_standard_".length());
        return p.startsWith("ammo_") ? p.substring(5) : p;
    }

    // AiKitCommand.cannotLoad on its stack's name, emptiness, gun and rounds by name (the map's keys, in its order)
    private static String oldCannotLoad(String name, boolean empty, boolean gun, Map<String, ?> rounds, String round) {
        if (!gun) return (empty ? "an empty hand" : name) + " is no NTM gun: no round to choose";
        if (rounds.isEmpty()) return name + " has no rounds to choose from";
        if (!rounds.containsKey(oldRoundName(round))) return name + " does not take " + round + "; it takes: " + String.join(", ", rounds.keySet());
        return null;
    }

    @Test
    void roundNameIsAsItWas() {
        for (String t : TYPED) assertEquals(oldRoundName(t), AiKitBase.roundName(t), t);
    }

    @Test
    void cannotLoadIsAsItWas() {
        SplittableRandom r = new SplittableRandom(20261120);
        String[] names = {"r556_ap", "r556", "r556_fmj", "m44_ap", "config_12", "12g_buck", "x"};
        for (int i = 0; i < 20_000; i++) {
            Map<String, Integer> rounds = new LinkedHashMap<>();
            int n = r.nextInt(5);
            for (int k = 0; k < n; k++) rounds.putIfAbsent(names[r.nextInt(names.length)], r.nextInt(100));
            boolean empty = r.nextInt(4) == 0, gun = r.nextInt(5) != 0;
            String name = empty ? "minecraft:air" : r.nextBoolean() ? "hbm:gun_m2" : "minecraft:stick";
            String round = TYPED[r.nextInt(TYPED.length)];
            assertEquals(oldCannotLoad(name, empty, gun, rounds, round), AiKitBase.cannotLoad(name, empty, gun, rounds, round),
                    name + " " + empty + " " + gun + " " + rounds + " " + round);
        }
    }
}
