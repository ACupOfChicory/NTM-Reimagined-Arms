package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** Fable 2026-11-12, 12.3: the launchers' and the hand grenades' blasts and the GL-54's fragments on the ladder */
class BalanceBlastRuleTest {
    private static final BalanceFinalRule.Params P = BalanceFinalRule.Params.DEFAULT;
    private static final BalanceBlastRule.Rule R = new BalanceBlastRule.Rule(true, BalanceBlastRule.launchers(BalanceBlastRule.LAUNCHERS),
            BalanceBlastRule.tiers(BalanceBlastRule.ROUNDS), BalanceBlastRule.tiers(BalanceBlastRule.GRENADES), 1, 2, BalanceBlastRule.Hits.DEFAULT,
            BalanceBlastRule.frags(BalanceBlastRule.FRAGS));
    /** the sets of table 12.3: bare, T-51, HEV, BJ, CMB, FAU, DNS */
    private static final BalanceFinalRule.Armor[] SETS = {a(0, 0), a(3, 0), a(4, 0), a(6, 0), a(7, 0), a(8, 40), a(9, 45)};

    private static BalanceFinalRule.Armor a(double tier, double pool) {
        return new BalanceFinalRule.Armor("t" + tier, tier, tier, 0, pool);
    }

    private static int near(String launcher, String round, BalanceFinalRule.Armor a) {
        var l = R.launchers().get(launcher);
        return BalanceBlastRule.nearLauncher(l, BalanceBlastRule.target(BalanceBlastRule.pairTier(l, R.rounds().get(round), R.cap()), a, P, R));
    }

    @Test
    void launchersNearByTable123() {
        Map<String, int[]> table = Map.of("gl54 he", new int[] {3, 5, 6, 16, 31, 124, 168}, "congolake g40_he", new int[] {2, 4, 5, 16, 21, 77, 99},
                "mk108 g40_he", new int[] {3, 6, 8, 28, 43, 173, 231}, "mk108 g40_demo", new int[] {3, 5, 6, 15, 28, 121, 181},
                "panzerschreck rocket_he", new int[] {2, 2, 2, 6, 12, 49, 66}, "panzerschreck rocket_demo", new int[] {2, 2, 2, 4, 6, 35, 53},
                "missile_launcher rocket_he", new int[] {2, 2, 2, 7, 13, 51, 69}, "missile_launcher rocket_demo", new int[] {2, 2, 2, 2, 4, 18, 39});
        table.forEach((k, want) -> {
            String[] p = k.split(" ");
            for (int i = 0; i < SETS.length; i++) assertEquals(want[i], near(p[0], p[1], SETS[i]), k + " on " + SETS[i].kind());
        });
        // GL-54 HE direct (×1.5): 2, 4, 4, 11, 21, 83, 112
        int[] direct = {2, 4, 4, 11, 21, 83, 112};
        for (int i = 0; i < SETS.length; i++) {
            double share = BalanceBlastRule.share(near("gl54", "he", SETS[i]), BalanceBlastRule.where(true, 0, 3, R.hits()));
            assertEquals(direct[i], BalanceBlastRule.hitsOf(share), "gl54 he direct on " + SETS[i].kind());
        }
    }

    @Test
    void pairTierCapAndMax() {
        var gl54 = R.launchers().get("gl54");
        assertEquals(3, BalanceBlastRule.pairTier(gl54, 1, 2));
        assertEquals(5, BalanceBlastRule.pairTier(gl54, 4, 2));
        assertEquals(5, BalanceBlastRule.pairTier(gl54, 1, BalanceBlastRule.cap("max", 2)));
        assertEquals(2, BalanceBlastRule.cap("cap:2", 9));
        assertEquals(9, BalanceBlastRule.cap("junk", 9));
        assertEquals(0.0, R.grenades().get("powder"));
    }

    @Test
    void handGrenadesNearAndWhere() {
        // Fable 10.2 (blasts.py): 3 x T(d)/T(0) x (1 + pool/20) near, x1 at a block, x0.75 at 3, at least two
        double heHev = BalanceBlastRule.nearGrenade(BalanceBlastRule.target(1, SETS[2], P, R), P, R);
        assertEquals(3 * BalanceFinalRule.ttkOf(-2, P) / BalanceFinalRule.ttkOf(0, P), heHev, 1e-9);
        assertEquals(12, BalanceBlastRule.hitsOf(BalanceBlastRule.share(heHev, BalanceBlastRule.where(false, 0.7, 15, R.hits()))));
        assertEquals(16, BalanceBlastRule.hitsOf(BalanceBlastRule.share(heHev, BalanceBlastRule.where(false, 3, 15, R.hits()))));
        // never more than half: two hits at least
        assertEquals(2, BalanceBlastRule.hitsOf(BalanceBlastRule.share(0.5, 1.5)));
        // the edge of the reach
        assertEquals(0.4, BalanceBlastRule.where(false, 15, 15, R.hits()), 1e-9);
        assertTrue(BalanceBlastRule.where(false, 9, 15, R.hits()) < 0.75 && BalanceBlastRule.where(false, 9, 15, R.hits()) > 0.4);
    }

    @Test
    void fragmentsByTable123() {
        // bursts whose cone all hits at the share of 12.3: AB (tier 3) and AP (tier 5, piercing 0.15); bare, diesel, T-51, HEV, BJ, CMB, FAU, DNS
        BalanceFinalRule.Armor[] sets = {a(0, 0), a(2, 0), a(3, 0), a(4, 0), a(6, 0), a(7, 0), a(8, 40), a(9, 45)};
        int[] ab = {5, 6, 7, 10, 36, 50, 185, 240}, ap = {3, 3, 4, 5, 8, 13, 68, 129};
        var gl54 = R.launchers().get("gl54");
        double role = P.roles().get("airburst");
        assertEquals(1.2, role);
        for (int i = 0; i < sets.length; i++) {
            var f = R.frags().get("airburst");
            double total = BalanceFinalRule.HP + sets[i].pool();
            double click = BalanceBlastRule.fragClick(gl54, f, BalanceBlastRule.pairTier(gl54, R.rounds().get("airburst"), 2), sets[i], P, role);
            assertEquals(ab[i], (int) Math.round(total / (click * f.share())), "airburst on " + sets[i].kind());
            assertTrue(click <= 12 + 1e-9);
            f = R.frags().get("ap");
            click = BalanceBlastRule.fragClick(gl54, f, BalanceBlastRule.pairTier(gl54, R.rounds().get("ap"), 2), sets[i], P, role);
            assertEquals(ap[i], (int) Math.round(total / (click * f.share())), "ap on " + sets[i].kind());
        }
    }

    @Test
    void keysText() {
        var l = BalanceBlastRule.launchers("a:2:1/4/3 b:4:2.5 broken c:x:1");
        assertEquals(2, l.size());
        assertEquals(0, l.get("b").mag());
        var h = BalanceBlastRule.hits("near=4 far=0.5@4 edge=junk", BalanceBlastRule.Hits.DEFAULT);
        assertEquals(4, h.near());
        assertEquals(0.5, h.far());
        assertEquals(4, h.farAt());
        assertEquals(0.4, h.edge());
        var f = BalanceBlastRule.frags("airburst:0.35 ap:0.6/0.15 bad:2");
        assertEquals(2, f.size());
        assertEquals(0.15, f.get("ap").pen());
    }

    @Test
    void brokenNumbersLeftOut() {
        var h = BalanceBlastRule.hits("far=0.75@NaN near=Infinity direct=NaN", BalanceBlastRule.Hits.DEFAULT);
        org.junit.jupiter.api.Assertions.assertEquals(BalanceBlastRule.Hits.DEFAULT, h);
        org.junit.jupiter.api.Assertions.assertTrue(BalanceBlastRule.launchers("mk108:4:Infinity/30/6.75 gl54:5:0.7/NaN/2.25").isEmpty());
        org.junit.jupiter.api.Assertions.assertEquals(4.0, BalanceBlastRule.hits("far=0.75@4", BalanceBlastRule.Hits.DEFAULT).farAt(), 1e-9);
    }
}
