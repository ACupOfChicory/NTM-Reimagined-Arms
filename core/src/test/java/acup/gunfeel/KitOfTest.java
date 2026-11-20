package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

import acup.gunfeel.EuphImmunityBase.Kit;
import acup.gunfeel.host.ArmorSuits;
import acup.gunfeel.host.Body;

// EuphImmunityBase.kitOfWorn against 9ddf0a7's kitOf on fake suits: every mix of the three full sets, a player or
// not, every answer of the balance's feature at its first and second ask; the same kit, the same questions in order
// (the suits' and the feature's). PoweredOf: remembered is asked, rows growing, on threads at once, once an item.
class KitOfTest {
    static Body body(boolean player) {
        return (Body) Proxy.newProxyInstance(Body.class.getClassLoader(), new Class<?>[] {Body.class}, (p, m, a) -> {
            if (m.getName().equals("player")) return player;
            throw new UnsupportedOperationException(m.getName());
        });
    }

    static final class Suits implements ArmorSuits {
        final boolean[] full;
        final List<String> calls;

        Suits(boolean[] full, List<String> calls) {
            this.full = full;
            this.calls = calls;
        }

        @Override
        public boolean fullSet(Body b, int suit) {
            calls.add("full " + suit);
            return full[suit];
        }

        @Override
        public boolean powered(int item) {
            throw new UnsupportedOperationException();
        }
    }

    // the feature's answers by its ask: bit k of pattern the k-th ask's
    static BooleanSupplier feature(int pattern, List<String> calls) {
        int[] n = {0};
        return () -> {
            calls.add("feature");
            return (pattern >> n[0]++ & 1) != 0;
        };
    }

    // 9ddf0a7's kitOf, its reads made the fakes'
    static Kit old(boolean player, Suits a, BooleanSupplier balance) {
        Body b = body(player);
        if (!balance.getAsBoolean()) return null;
        if (a.fullSet(b, ArmorSuits.DNS)) return balance.getAsBoolean() && Kit.DNS.mode().equals("pool") ? Kit.DNS : null;
        if (a.fullSet(b, ArmorSuits.DIGAMMA)) return balance.getAsBoolean() && Kit.FAU.mode().equals("pool") ? Kit.FAU : null;
        if (!player && a.fullSet(b, ArmorSuits.EUPHEMIUM)) return balance.getAsBoolean() && Kit.EUPHEMIUM.mode().equals("pool") ? Kit.EUPHEMIUM : null;
        return null;
    }

    @Test
    void kitIsTheOld() {
        int kits = 0;
        // the kits' modes: the defaults (pool), then NTM's (balance = ntm: ntm, ntm, formula; the feature's second ask
        // comes before the mode's, which is no pool then)
        try {
            for (boolean ntm : new boolean[] {false, true}) {
                ServerSettings.useRemote(ntm ? new ServerSettings.Snapshot("", java.util.Map.of(BalanceKeys.KEY, "ntm"), java.util.Map.of(), "") : null);
                assertEquals(ntm ? "ntm ntm formula" : "pool pool pool", Kit.EUPHEMIUM.mode() + " " + Kit.DNS.mode() + " " + Kit.FAU.mode());
                kits += kits();
            }
        } finally {
            ServerSettings.useRemote(null);
        }
        // each kit found somewhere
        assertEquals(true, kits > 3);
    }

    private static int kits() {
        int kits = 0;
        for (int sets = 0; sets < 8; sets++)
            for (boolean player : new boolean[] {false, true})
                for (int pattern = 0; pattern < 4; pattern++) {
                    boolean[] full = {(sets & 1) != 0, (sets & 2) != 0, (sets & 4) != 0};
                    List<String> a = new ArrayList<>(), b = new ArrayList<>();
                    Kit want = old(player, new Suits(full, a), feature(pattern, a));
                    Kit got = EuphImmunityBase.kitOfWorn(body(player), feature(pattern, b), new Suits(full, b));
                    String at = sets + " " + player + " " + pattern;
                    assertEquals(want, got, at);
                    assertEquals(a, b, at);
                    if (want != null) kits++;
                }
        return kits;
    }

    @Test
    void poweredIsAskedOnce() throws Exception {
        int[] asks = {0};
        ArmorSuits suits = new ArmorSuits() {
            @Override
            public boolean fullSet(Body b, int suit) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean powered(int item) {
                synchronized (asks) {
                    asks[0]++;
                }
                return item % 3 == 1;
            }
        };
        var p = new EuphImmunityBase.PoweredOf(suits);
        // from the last number down (one row growth), then up: each the suits' answer, asked once an item
        int n = 300;
        for (int round = 0; round < 2; round++)
            for (int k = 0; k < n; k++) {
                int item = round == 0 ? n - 1 - k : k;
                assertEquals(item % 3 == 1, p.of(item), "item " + item);
            }
        assertEquals(n, asks[0]);
        // a fresh one asked on 8 threads at once, numbers up from 0 (rows growing under them)
        var fresh = new EuphImmunityBase.PoweredOf(suits);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Integer>> runs = new ArrayList<>();
            for (int t = 0; t < 8; t++)
                runs.add(pool.submit(() -> {
                    int wrong = 0;
                    for (int r = 0; r < 100; r++)
                        for (int item = 0; item < 2000; item++)
                            if (fresh.of(item) != (item % 3 == 1)) wrong++;
                    return wrong;
                }));
            for (Future<Integer> f : runs) assertEquals(0, f.get());
        } finally {
            pool.shutdown();
        }
    }
}
