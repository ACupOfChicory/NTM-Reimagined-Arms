package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

// PierceWalk against BulletServer.clip's loop as it was (fde8bb3), its lines word for word below with the game's made
// fakes: Vec3 as 26.2 has it (subtract as add of the negation, length, scale as multiply, add, distanceToSqr: the same
// operations, from its bytecode), the hit and its miss, the paid set of places, BeamLasers.clip, PassableClip.exit,
// paidOnce and pierced, WaterTick.k, the bullet's damage, its penetration spent, the pierce (log, paid, wear, steps,
// damage) and stop. Both answered by one seeded fake whose every answer is drawn as it is asked: the same calls with
// the same numbers in the same order get the same answers, and a call or number out of place shows as the logs part.
// Random paths: misses, hits near and far, machines' windows, blocks already paid, no way out, a way out past the
// tick's end, water (none, all, some), held and pierced, a path of no length, 16 blocks.
class PierceWalkTest {
    // ---- Vec3 as 26.2's
    record Vec3(double x, double y, double z) {
        Vec3 subtract(Vec3 o) {
            return add(-o.x, -o.y, -o.z);
        }

        Vec3 add(double dx, double dy, double dz) {
            return new Vec3(x + dx, y + dy, z + dz);
        }

        Vec3 add(Vec3 o) {
            return add(o.x, o.y, o.z);
        }

        Vec3 scale(double f) {
            return multiply(f, f, f);
        }

        Vec3 multiply(double fx, double fy, double fz) {
            return new Vec3(x * fx, y * fy, z * fz);
        }

        double length() {
            return Math.sqrt(x * x + y * y + z * z);
        }

        double distanceToSqr(Vec3 o) {
            double dx = o.x - x, dy = o.y - y, dz = o.z - z;
            return dx * dx + dy * dy + dz * dz;
        }

        double distanceTo(Vec3 o) {
            return Math.sqrt(distanceToSqr(o));
        }
    }

    // a hit on a block (or a miss): where, the block's place, a machine's window, the side
    record Hit(boolean miss, Vec3 at, long pos, boolean glass, int side) {
    }

    // the one fake both answer from, and what was asked of it
    static final class Game {
        final SplittableRandom r;
        final List<String> log = new ArrayList<>();
        final Vec3 to;
        float damage;
        int steps;
        // a wall of thin blocks: every one pierced, none past the end (the 16 blocks' way)
        final boolean thin;

        Game(long seed, Vec3 to, float damage, boolean thin) {
            r = new SplittableRandom(seed);
            this.to = to;
            this.damage = damage;
            this.thin = thin;
        }

        void note(Object... what) {
            StringBuilder b = new StringBuilder();
            for (Object o : what) b.append(o instanceof Double d ? Long.toHexString(Double.doubleToRawLongBits(d))
                    : o instanceof Float f ? Integer.toHexString(Float.floatToRawIntBits(f)) : String.valueOf(o)).append(' ');
            log.add(b.toString());
        }

        Hit clip(Vec3 at) {
            note("clip", at.x, at.y, at.z);
            int kind = thin ? 3 : r.nextInt(10);
            if (kind == 0) return new Hit(true, to, 0, false, 1);
            double t = kind == 1 ? 0 : kind == 2 ? 1 : thin ? r.nextDouble(0, 0.01) : r.nextDouble();
            Vec3 p = new Vec3(at.x + (to.x - at.x) * t, at.y + (to.y - at.y) * t, at.z + (to.z - at.z) * t);
            return new Hit(false, p, r.nextInt(6), r.nextInt(7) == 0, r.nextInt(6));
        }

        @Nullable Hit exit(Hit in, Vec3 dir) {
            note("exit", in, dir.x, dir.y, dir.z);
            if (!thin && r.nextInt(20) == 0) return null;
            double d = thin ? r.nextDouble(0.001, 0.01) : r.nextInt(8) == 0 ? 0 : r.nextDouble(0.01, 1.8);
            return new Hit(false, in.at.add(dir.scale(d)), in.pos, false, in.side ^ 1);
        }

        float k(Vec3 at) {
            note("k", at.x, at.y, at.z);
            int kind = r.nextInt(10);
            return kind < 6 ? 1F : kind == 6 ? 0F : kind == 7 ? -0.5F : (float) r.nextDouble(0.05, 1);
        }

        float pierced(Hit in, Hit out, float damage) {
            note("pierced", in, out, damage);
            return !thin && r.nextInt(5) == 0 ? -1F : damage * (float) r.nextDouble(thin ? 0.97 : 0.3, 1);
        }

        double spent() {
            double s = r.nextDouble();
            note("spent", s);
            return s;
        }

        float damage() {
            note("damage", damage);
            return damage;
        }

        void pierce(Hit in, Hit out, float left, float k, double spentBefore) {
            note("pierce", in, out, damage, left, k, spentBefore);
            steps++;
            damage = left;
        }

        Hit stop(Hit hit, String why, float path) {
            note("stop", hit, why, path);
            return hit;
        }

        Hit miss(Vec3 at, int side) {
            note("miss", at.x, at.y, at.z, side);
            return new Hit(true, at, 0, false, side);
        }
    }

    // ---- fde8bb3's BulletServer.clip from "Vec3 at = from;" to its end, word for word (the game: Game; the set of
    // BlockPos: one of places)
    static Hit old(Game g, Vec3 from, Vec3 to, Set<Long> paid) {
        Vec3 at = from;
        for (int i = 0; i < 16; i++) {
            Hit hit = g.clip(at);
            if (hit.miss()) return hit;
            Vec3 d = to.subtract(at);
            double len = d.length();
            if (len < 1e-6) return hit;
            Vec3 dir = d.scale(1.0 / len);
            long pos = hit.pos();
            boolean once = !hit.glass(), known = once && paid.contains(pos);
            Hit out = g.exit(hit, dir);
            if (out == null) return g.stop(hit, "no_exit", Float.NaN);
            double spentBefore = g.spent();
            float k = g.k(hit.at());
            float left = known ? g.damage() : k == 1F ? g.pierced(hit, out, g.damage())
                    : k <= 0F ? -1F : g.pierced(hit, out, g.damage() * k) / k;
            if (left < 0F) return g.stop(hit, "held", (float) hit.at().distanceTo(out.at()));
            if (!known) {
                if (once) paid.add(pos);
                g.pierce(hit, out, left, k, spentBefore);
            }
            if (out.at().distanceToSqr(at) >= len * len) return g.miss(to, hit.side());
            at = out.at().add(dir.scale(1e-3));
        }
        return g.miss(to, 1);
    }

    static Hit now(Game g, Vec3 from, Vec3 to, PaidBlocks paid) {
        PierceWalk.Way<Hit> way = new PierceWalk.Way<>() {
            @Override
            public Hit first(double ax, double ay, double az, double tx, double ty, double tz) {
                assertEquals(to, new Vec3(tx, ty, tz));
                return g.clip(new Vec3(ax, ay, az));
            }

            @Override
            public boolean miss(Hit hit) {
                return hit.miss();
            }

            @Override
            public double x(Hit hit) {
                return hit.at().x;
            }

            @Override
            public double y(Hit hit) {
                return hit.at().y;
            }

            @Override
            public double z(Hit hit) {
                return hit.at().z;
            }

            @Override
            public long pos(Hit hit) {
                return hit.pos();
            }

            @Override
            public boolean once(Hit hit) {
                return !hit.glass();
            }

            @Override
            public @Nullable Hit exit(Hit hit, double dx, double dy, double dz) {
                return g.exit(hit, new Vec3(dx, dy, dz));
            }

            @Override
            public float water(Hit hit) {
                return g.k(hit.at());
            }

            @Override
            public Hit missAt(Hit last) {
                return g.miss(to, last.side());
            }

            @Override
            public Hit missUp() {
                return g.miss(to, 1);
            }
        };
        PierceWalk.Shot<Hit> shot = new PierceWalk.Shot<>() {
            @Override
            public float damage() {
                return g.damage();
            }

            @Override
            public double spent() {
                return g.spent();
            }

            @Override
            public float left(Hit in, Hit out, float damage) {
                return g.pierced(in, out, damage);
            }

            @Override
            public void pierce(Hit in, Hit out, float left, float k, double spentBefore) {
                g.pierce(in, out, left, k, spentBefore);
            }

            @Override
            public Hit stop(Hit hit, @Nullable Hit out, String why) {
                return g.stop(hit, why, out == null ? Float.NaN : (float) hit.at().distanceTo(out.at()));
            }
        };
        return PierceWalk.clip(way, shot, from.x, from.y, from.z, to.x, to.y, to.z, paid);
    }

    @Test
    void theSameAsBefore() {
        SplittableRandom r = new SplittableRandom(1120L);
        int pierced = 0, held = 0, noExit = 0, past = 0, sixteen = 0, known = 0, zero = 0, misses = 0;
        for (int run = 0; run < 100_000; run++) {
            Vec3 from = new Vec3(r.nextDouble(-50, 50), r.nextDouble(-10, 200), r.nextDouble(-50, 50));
            Vec3 to = r.nextInt(50) == 0 ? from : from.add(new Vec3(r.nextDouble(-4, 4), r.nextDouble(-2, 2), r.nextDouble(-4, 4)));
            float damage = (float) r.nextDouble(0.5, 60);
            long seed = r.nextLong();
            Set<Long> oldPaid = new HashSet<>();
            PaidBlocks nowPaid = new PaidBlocks();
            for (int i = 0; i < 6; i++) if (r.nextInt(4) == 0) {
                oldPaid.add((long) i);
                nowPaid.add(i);
            }
            int paidBefore = oldPaid.size();
            boolean thin = r.nextInt(20) == 0;
            Game a = new Game(seed, to, damage, thin), b = new Game(seed, to, damage, thin);
            Hit was = old(a, from, to, oldPaid), is = now(b, from, to, nowPaid);
            assertEquals(a.log, b.log, "run " + run);
            assertEquals(was, is, "run " + run);
            assertEquals(Float.floatToRawIntBits(a.damage), Float.floatToRawIntBits(b.damage));
            assertEquals(oldPaid.size(), nowPaid.size());
            for (long p : oldPaid) assertTrue(nowPaid.contains(p));
            pierced += a.steps;
            String last = a.log.get(a.log.size() - 1);
            if (last.startsWith("stop") && last.contains("held")) held++;
            if (last.startsWith("stop") && last.contains("no_exit")) noExit++;
            if (last.startsWith("miss")) past++;
            if (a.log.stream().filter(l -> l.startsWith("clip")).count() == 16) sixteen++;
            if (a.steps < a.log.stream().filter(l -> l.startsWith("pierced")).count() || oldPaid.size() == paidBefore && a.steps > 0) known++;
            if (from.equals(to)) zero++;
            if (was.miss() && !last.startsWith("miss")) misses++;
        }
        assertTrue(pierced > 50_000 && held > 10_000 && noExit > 2_000 && past > 10_000 && sixteen > 0 && zero > 1_000 && misses > 10_000,
                pierced + " pierced, " + held + " held, " + noExit + " no exit, " + past + " past the end, " + sixteen + " of 16, " + known
                        + " known, " + zero + " of no length, " + misses + " missed");
    }
}
