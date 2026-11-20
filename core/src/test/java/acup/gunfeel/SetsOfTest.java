package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

// BalanceArmorBase.SetsOf: the set of an item worn in a slot, remembered by the item's number, is setOfName's of its
// id every time, from the first ask to the last, in any order of numbers (rows growing), on threads at once; and
// setOfName is 739c43e's setOf on the id's namespace and path.
class SetsOfTest {
    static final List<String> NAMES = new ArrayList<>();

    static {
        NAMES.add("minecraft:air");
        for (String set : BalanceArmorBase.SETS.keySet())
            for (String suffix : BalanceArmorBase.SUFFIX) NAMES.add("hbm:" + set + suffix);
        for (String s : new String[] {"hbm:bj_plate_jetpack", "hbm:bj_helmet_jetpack", "hbm:dnt_helmet", "hbm:dnt_plate", "minecraft:iron_helmet",
                "other:steel_plate", "hbm:steel_plate_x", "hbm:_helmet", "hbm:helmet", "hbm:plate_jetpack", "hbm:x_plate_jetpack", "hbmx:steel_plate",
                "hbm:steel_legs", "hbm:steel_boots"})
            NAMES.add(s);
    }

    // 739c43e's setOf after its stack: the id's namespace and path
    static String old(String name, int i) {
        int c = name.indexOf(':');
        String ns = name.substring(0, c), path = name.substring(c + 1);
        if (!ns.equals("hbm")) return null;
        return BalanceArmorBase.setName(path, i);
    }

    @Test
    void nameIsTheOld() {
        for (String n : NAMES)
            for (int i = 0; i < 4; i++) assertEquals(old(n, i), BalanceArmorBase.setOfName(n, i), n + " " + i);
    }

    @Test
    void rememberedIsWorkedOut() throws Exception {
        var sets = new BalanceArmorBase.SetsOf(NAMES::get);
        // from the last number down (one row growth), then up, then again: each the same, the same object again
        for (int round = 0; round < 3; round++)
            for (int k = 0; k < NAMES.size(); k++) {
                int item = round == 0 ? NAMES.size() - 1 - k : k;
                for (int i = 0; i < 4; i++) {
                    String a = sets.of(item, i);
                    assertEquals(old(NAMES.get(item), i), a, NAMES.get(item) + " " + i);
                    assertSame(a, sets.of(item, i));
                }
            }
        // each item and slot asks the table once, a "no set" too; asked again, none
        int[] asks = {0};
        var counted = new BalanceArmorBase.SetsOf(n -> {
            asks[0]++;
            return NAMES.get(n);
        });
        for (int round = 0; round < 2; round++) {
            for (int item = 0; item < NAMES.size(); item++)
                for (int i = 0; i < 4; i++) counted.of(item, i);
            assertEquals(NAMES.size() * 4, asks[0]);
        }
        // a fresh one asked on 8 threads at once, numbers up from 0 (rows growing under them)
        var fresh = new BalanceArmorBase.SetsOf(NAMES::get);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Integer>> runs = new ArrayList<>();
            for (int t = 0; t < 8; t++)
                runs.add(pool.submit(() -> {
                    int wrong = 0;
                    for (int r = 0; r < 200; r++)
                        for (int item = 0; item < NAMES.size(); item++)
                            for (int i = 0; i < 4; i++)
                                if (!java.util.Objects.equals(old(NAMES.get(item), i), fresh.of(item, i))) wrong++;
                    return wrong;
                }));
            for (Future<Integer> f : runs) assertEquals(0, f.get());
        } finally {
            pool.shutdown();
        }
    }
}
