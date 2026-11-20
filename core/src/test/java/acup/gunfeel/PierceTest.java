package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import acup.gunfeel.host.WorldView;

// Pierce.left against PassableClip.pierced as it was (84f78ee, its lines below word for word with the game's reads
// made a fake block's): random blocks (air or not, glass hits, machines, materials of every kind, break rules with
// and without pierce=, destroy speeds), damages, rounds, path lengths and spent penetration. The same damage left
// bit for bit, the same penetration spent after, the same questions asked of the block in the same order.
class PierceTest {
    // a block as both see it, and the questions asked of it
    static final class Fake implements Pierce.Block {
        boolean air, glassHit, machineState, machineRule;
        Materials.Verdict verdict;
        BulletRuleText.@Nullable Break rule;
        float destroySpeed;
        final List<String> asked = new ArrayList<>();

        @Override
        public Materials.Verdict material(int state) {
            assertEquals(7, state);
            asked.add("material");
            return verdict;
        }

        @Override
        public boolean machine(int state) {
            asked.add("machine");
            return machineState || machineRule;
        }

        @Override
        public BulletRuleText.@Nullable Break breakRule(int state) {
            asked.add("breakRule");
            return rule;
        }
    }

    // ---- 84f78ee's PassableClip.pierced, its game reads made the fake's (the order kept: the rule state, its
    // material, the machines, the break rule, the destroy speed)
    private static float old(Fake b, float damage, float negationOrNaN, float piercing, double path, Materials.Penetration penetration) {
        if (b.air || !(damage >= 1F)) return -1F;
        float negation = Float.isNaN(negationOrNaN) ? 0F : negationOrNaN;
        float pierce = Float.isNaN(negationOrNaN) ? 0F : piercing;
        if (b.glassHit) {
            float left = penetration.left(MachineGlass.VERDICT, path, damage, negation, pierce);
            return left >= 1F ? left : -1F;
        }
        b.asked.add("material");
        Materials.Verdict v = b.verdict;
        b.asked.add("machine");
        if ((b.machineState || b.machineRule || v.material() == Materials.Material.MACHINE)
                && !ReaimTimes.flag("bulletPierceMachines", false)) return -1F;
        b.asked.add("breakRule");
        BulletRuleText.Break rule = b.rule;
        if (rule != null && rule.pierce() > 0F) {
            b.asked.add("destroySpeed");
            return rule.pierced(damage, b.destroySpeed, path);
        }
        float left = penetration.left(v, path, damage, negation, pierce);
        return left >= 1F ? left : -1F;
    }

    // a world of one block at 4 5 6: the fake's air and destroy speed, its questions noted
    private static WorldView world(Fake b) {
        return new WorldView() {
            @Override
            public int state(int x, int y, int z) {
                assertEquals(4 + " " + 5 + " " + 6, x + " " + y + " " + z);
                return 7;
            }

            @Override
            public float destroySpeed(int state, int x, int y, int z) {
                // the number state gave, at the block's place
                assertEquals(7 + " " + 4 + " " + 5 + " " + 6, state + " " + x + " " + y + " " + z);
                b.asked.add("destroySpeed");
                return b.destroySpeed;
            }

            @Override
            public boolean air(int state) {
                return b.air;
            }

            @Override
            public BlockFacts facts(int state) {
                throw new AssertionError("not asked");
            }

            @Override
            public String key(int state) {
                throw new AssertionError("not asked");
            }

            @Override
            public boolean machineOrPartial(int state) {
                throw new AssertionError("not asked");
            }

            @Override
            public long gameTime() {
                throw new AssertionError("not asked");
            }

            @Override
            public boolean client() {
                throw new AssertionError("not asked");
            }

            @Override
            public boolean clip(double fx, double fy, double fz, double tx, double ty, double tz, int shape, int fluid,
                    acup.gunfeel.host.@org.jspecify.annotations.Nullable Body by, acup.gunfeel.host.RayHit out) {
                throw new AssertionError("not asked");
            }

            @Override
            public int boxes(int x, int y, int z, int kind, acup.gunfeel.host.@org.jspecify.annotations.Nullable Body by, double[] out) {
                throw new AssertionError("not asked");
            }

            @Override
            public boolean free(acup.gunfeel.host.@org.jspecify.annotations.Nullable Body by, double x0, double y0, double z0, double x1,
                    double y1, double z1) {
                throw new AssertionError("not asked");
            }
        };
    }

    private static Fake fake(SplittableRandom r) {
        Fake b = new Fake();
        b.air = r.nextInt(10) == 0;
        b.glassHit = r.nextInt(6) == 0;
        b.machineState = r.nextInt(10) == 0;
        b.machineRule = r.nextInt(10) == 0;
        Materials.Material[] ms = Materials.Material.values();
        Materials.Source[] ss = Materials.Source.values();
        b.verdict = new Materials.Verdict(ms[r.nextInt(ms.length)], (float) r.nextDouble(0, 3), ss[r.nextInt(ss.length)], "test");
        if (r.nextInt(3) == 0) {
            b.rule = new BulletRuleText.Break(r.nextInt(3), (float) r.nextDouble(0, 5), r.nextBoolean(), r.nextBoolean(), r.nextInt(5) == 0, null,
                    r.nextInt(3) == 0 ? 0F : (float) r.nextDouble(0, 4), (float) r.nextDouble(0, 1), 0F);
        }
        b.destroySpeed = r.nextInt(8) == 0 ? -1F : (float) r.nextDouble(0, 50);
        return b;
    }

    @Test
    void theSameAsBefore() {
        SplittableRandom r = new SplittableRandom(1119L);
        int through = 0, stops = 0, rules = 0;
        for (int i = 0; i < 200_000; i++) {
            long seed = r.nextLong();
            Fake a = fake(new SplittableRandom(seed)), b = fake(new SplittableRandom(seed));
            float damage = r.nextInt(10) == 0 ? (float) r.nextDouble(0, 1.2) : (float) r.nextDouble(0, 120);
            boolean noRound = r.nextInt(5) == 0;
            float negation = (float) r.nextDouble(0, 30), piercing = (float) r.nextDouble(0, 1);
            double path = r.nextInt(10) == 0 ? 0 : r.nextDouble(0, 1.8);
            double spent = r.nextInt(3) == 0 ? 0 : r.nextDouble(0, 40);
            Materials.Penetration pa = new Materials.Penetration(), pb = new Materials.Penetration();
            pa.restore(spent);
            pb.restore(spent);
            RoundSpec spec = new RoundSpec().clear();
            if (!noRound) {
                spec.armorThresholdNegation = negation;
                spec.armorPiercingPercent = piercing;
            }
            float before = old(a, damage, noRound ? Float.NaN : negation, piercing, path, pa);
            float now = Pierce.left(world(b), 4, 5, 6, damage, spec, b.glassHit, path, pb, b);
            String at = "case " + i;
            assertEquals(Float.floatToRawIntBits(before), Float.floatToRawIntBits(now), at);
            assertEquals(Double.doubleToRawLongBits(pa.spent()), Double.doubleToRawLongBits(pb.spent()), at);
            assertEquals(a.asked, b.asked, at);
            if (now >= 1F) through++;
            else stops++;
            if (a.asked.contains("destroySpeed")) rules++;
        }
        assertTrue(through > 20_000 && stops > 20_000 && rules > 5_000, through + " through, " + stops + " stops, " + rules + " by rules");
    }

    @Test
    void noRoundIsZero() {
        RoundSpec s = new RoundSpec();
        s.armorThresholdNegation = 3F;
        s.armorPiercingPercent = .5F;
        s.clear();
        assertEquals(0F, s.armorThresholdNegation);
        assertEquals(0F, s.armorPiercingPercent);
    }
}
