package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

// PierceSteps on PierceLedger against PierceSteps as it was before (30ad1fa: its list of steps and its paid count,
// copied below word for word with the bullet made numbers): random ticks of blocks pierced, creatures met on the way
// (before, between, on and past the blocks) and the tick's end, each block's pay a cost or a stop. The same damage,
// the same penetration restored, the same blocks paid in the same order and the same stop, every step.
class PierceLedgerTest {
    // the bullet as PierceSteps sees it, and what happened to it
    private static final class Bullet {
        float damage;
        boolean removed;
        final List<String> log = new ArrayList<>();
    }

    // a block's way in: its cost (-1: it stops the bullet)
    private record Way(int id, float cost) {
    }

    private static float pierce(Bullet b, Way w) {
        return w.cost() < 0F ? -1F : b.damage - w.cost();
    }

    // ---- 30ad1fa's PierceSteps, its bookkeeping word for word
    private static final class Old {
        private record Step(double at, float before, double spentBefore, Way in, Way out) {
        }

        private final List<Step> steps = new ArrayList<>();
        private int paid;

        void start() {
            steps.clear();
            paid = 0;
        }

        void pierced(double at, Way in, float before, double spentBefore) {
            steps.add(new Step(at, before, spentBefore, in, in));
            paid = steps.size();
        }

        void enter(Bullet bullet, double d) {
            if (steps.isEmpty()) return;
            int k = 0;
            while (k < steps.size() && steps.get(k).at() < d) k++;
            if (paid > k) {
                bullet.damage = steps.get(k).before();
                bullet.log.add("restore " + steps.get(k).spentBefore() + " later " + (steps.size() - k));
                paid = k;
            } else {
                payTo(bullet, k);
            }
        }

        void finish(Bullet bullet) {
            payTo(bullet, steps.size());
            steps.clear();
            paid = 0;
        }

        private void payTo(Bullet bullet, int k) {
            while (paid < k && !bullet.removed) {
                Step s = steps.get(paid++);
                float left = pierce(bullet, s.in());
                bullet.log.add("pay " + s.in().id() + " " + left);
                if (left < 0F) {
                    bullet.removed = true;
                    return;
                }
                bullet.damage = left;
            }
        }
    }

    // ---- PierceSteps now, its lines around the ledger
    private static final class Now {
        private final PierceLedger<Way> steps = new PierceLedger<>();

        void start() {
            steps.clear();
        }

        void pierced(double at, Way in, float before, double spentBefore) {
            steps.pierced(at, before, spentBefore, in, in);
        }

        void enter(Bullet bullet, double d) {
            if (steps.size() == 0) return;
            int k = steps.firstFrom(d);
            if (steps.paidPast(k)) {
                bullet.damage = steps.damageBefore(k);
                bullet.log.add("restore " + steps.spentBefore(k) + " later " + (steps.size() - k));
                steps.unpayTo(k);
            } else {
                payTo(bullet, k);
            }
        }

        void finish(Bullet bullet) {
            payTo(bullet, steps.size());
            steps.clear();
        }

        private void payTo(Bullet bullet, int k) {
            while (steps.owes(k) && !bullet.removed) {
                int i = steps.payNext();
                Way in = steps.wayIn(i);
                float left = pierce(bullet, in);
                bullet.log.add("pay " + in.id() + " " + left);
                if (left < 0F) {
                    bullet.removed = true;
                    return;
                }
                bullet.damage = left;
            }
        }
    }

    @Test
    void theSameAsBefore() {
        SplittableRandom r = new SplittableRandom(1119L);
        int restores = 0, stops = 0, pays = 0;
        for (int run = 0; run < 20_000; run++) {
            Old o = new Old();
            Now n = new Now();
            Bullet a = new Bullet(), b = new Bullet();
            a.damage = b.damage = (float) r.nextDouble(5, 100);
            for (int tick = 0; tick < 1 + r.nextInt(4) && !a.removed; tick++) {
                o.start();
                n.start();
                double at = 0;
                int blocks = r.nextInt(12), id = 0;
                for (int i = 0; i < blocks && !a.removed; i++) {
                    at += r.nextInt(5) == 0 ? 0 : r.nextDouble(0, 3);
                    // the block is paid at once, by the bullet's damage: then a creature may be met on the way
                    Way w = new Way(id++, r.nextInt(10) == 0 ? -1F : (float) r.nextDouble(0, 20));
                    float before = a.damage;
                    double spent = r.nextDouble(0, 50);
                    o.pierced(at, w, before, spent);
                    n.pierced(at, w, before, spent);
                    float left = pierce(a, w);
                    if (left >= 0F) a.damage = b.damage = left;
                    if (r.nextInt(3) == 0) {
                        double d = switch (r.nextInt(4)) {
                            case 0 -> at;
                            case 1 -> r.nextDouble(0, at + 1);
                            case 2 -> 0;
                            default -> at + r.nextDouble(0, 3);
                        };
                        o.enter(a, d);
                        n.enter(b, d);
                    }
                }
                o.finish(a);
                n.finish(b);
                assertEquals(a.log, b.log, "run " + run);
                assertEquals(Float.floatToRawIntBits(a.damage), Float.floatToRawIntBits(b.damage), "run " + run);
                assertEquals(a.removed, b.removed, "run " + run);
            }
            for (String s : a.log) {
                if (s.startsWith("restore")) restores++;
                if (s.startsWith("pay")) pays++;
                if (s.endsWith(" -1.0")) stops++;
            }
        }
        assertTrue(restores > 1000 && pays > 10_000 && stops > 1000, restores + " restores, " + pays + " pays, " + stops + " stops");
    }

    @Test
    void ledger() {
        PierceLedger<String> l = new PierceLedger<>();
        for (int i = 0; i < 9; i++) l.pierced(i, 10 - i, i * 2, "in" + i, "out" + i);
        assertEquals(9, l.size());
        assertEquals(3, l.firstFrom(2.5));
        assertEquals(3, l.firstFrom(3));
        assertEquals(9, l.firstFrom(100));
        assertTrue(l.paidPast(3));
        l.unpayTo(3);
        assertTrue(l.owes(4));
        assertEquals(3, l.payNext());
        assertEquals("in3", l.wayIn(3));
        assertEquals("out8", l.wayOut(8));
        assertEquals(7F, l.damageBefore(3));
        assertEquals(6.0, l.spentBefore(3));
        l.clear();
        assertEquals(0, l.size());
        assertEquals(0, l.firstFrom(5));
    }
}
