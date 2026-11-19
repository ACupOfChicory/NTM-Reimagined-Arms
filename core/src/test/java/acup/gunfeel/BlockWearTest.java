package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

// BlockWear against BulletServer's wear as it was (84f78ee: its map of spots, the hit's ledger, forget and tick,
// copied below word for word with the game's level, place and block made fakes): random hits by every kind of break
// rule on a few places in two levels, blocks replaced, time going on, sweeps. The same answers (breaks or stands, the
// wear so far and needed, the cracks), the same cracks shown and cleared in each call (a call's own in the order of
// its map, which is the identity hash's in both: compared as a set per call), the same wear left.
class BlockWearTest {
    record Level(String name) {
    }

    // the world: per level its game tick, per place its block (null: unloaded)
    static final class World {
        final Map<Level, Long> time = new HashMap<>();
        final Map<String, Object> blocks = new HashMap<>();

        Object block(Level l, long pos) {
            return blocks.get(l.name() + pos);
        }
    }

    // ---- 84f78ee's BulletServer wear, word for word (the game's calls a fake's: cracks noted, the world's blocks)
    static final class Old {
        private record Wear(float done, long at, Object state) {
        }

        private record Spot(Level level, long pos) {
        }

        private final Map<Spot, Wear> WEAR = new HashMap<>();
        private static final long FORGET_TICKS = 400L;
        private int ticks;
        final List<String> cracks = new ArrayList<>();

        // from "long now = level.getGameTime();" on; true when it broke; out: done, need
        boolean wear(Level level, long pos, Object state, BulletRuleText.Break rule, float damage, float hardness, long now, float[] out) {
            forget(level, now);
            if (rule.worn()) {
                Spot spot = new Spot(level, pos);
                Wear w = WEAR.get(spot);
                if (w != null && w.state() != state) w = null;
                float add = rule.wear(damage);
                float need = rule.need(hardness);
                float done = (w == null ? 0F : w.done()) + add;
                if (need > 0F && done < need) {
                    WEAR.put(spot, new Wear(done, now, state));
                    out[0] = done;
                    out[1] = need;
                    cracks.add(level.name() + " " + pos + " " + BulletRuleText.Break.cracks(done, need));
                    return false;
                }
                WEAR.remove(spot);
                cracks.add(level.name() + " " + pos + " -1");
            }
            return true;
        }

        private void forget(Level level, long now) {
            Iterator<Map.Entry<Spot, Wear>> it = WEAR.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Spot, Wear> e = it.next();
                if (now - e.getValue().at() < FORGET_TICKS) continue;
                cracks.add(e.getKey().level().name() + " " + e.getKey().pos() + " -1");
                it.remove();
            }
        }

        void tick(World world) {
            if (WEAR.isEmpty() || ++ticks % 5 != 0) return;
            Iterator<Map.Entry<Spot, Wear>> it = WEAR.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Spot, Wear> e = it.next();
                Level level = e.getKey().level();
                long pos = e.getKey().pos();
                Object there = world.block(level, pos);
                boolean gone = there == null || there != e.getValue().state();
                if (!gone && world.time.get(level) - e.getValue().at() < FORGET_TICKS) continue;
                cracks.add(level.name() + " " + pos + " -1");
                it.remove();
            }
        }

        int size() {
            return WEAR.size();
        }
    }

    private static List<String> sorted(List<String> l) {
        List<String> s = new ArrayList<>(l);
        java.util.Collections.sort(s);
        return s;
    }

    @Test
    void theSameAsBefore() {
        SplittableRandom r = new SplittableRandom(1119L);
        Level[] levels = {new Level("overworld"), new Level("nether")};
        Object[] kinds = {"stone", "planks", "sandbag"};
        int broke = 0, stood = 0, swept = 0;
        for (int run = 0; run < 300; run++) {
            World world = new World();
            for (Level l : levels) world.time.put(l, r.nextLong(0, 1_000_000));
            Old old = new Old();
            BlockWear<Level> now = new BlockWear<>();
            List<String> cracks = new ArrayList<>();
            BlockWear.Cracks<Level> show = (level, pos, stage) -> cracks.add(level.name() + " " + pos + " " + stage);
            BlockWear.Gone<Level> gone = new BlockWear.Gone<>() {
                @Override
                public boolean gone(Level level, long pos, Object block) {
                    Object there = world.block(level, pos);
                    return there == null || there != block;
                }

                @Override
                public long time(Level level) {
                    return world.time.get(level);
                }
            };
            for (int i = 0; i < 6; i++) world.blocks.put(levels[i % 2].name() + (long) i, kinds[r.nextInt(kinds.length)]);
            for (int step = 0; step < 400; step++) {
                Level level = levels[r.nextInt(2)];
                for (Level l : levels) world.time.put(l, world.time.get(l) + (r.nextInt(4) == 0 ? r.nextInt(300) : r.nextInt(3)));
                long pos = r.nextInt(6);
                if (r.nextInt(15) == 0) world.blocks.put(level.name() + pos, r.nextInt(5) == 0 ? null : kinds[r.nextInt(kinds.length)]);
                if (r.nextInt(3) == 0) {
                    old.tick(world);
                    now.tick(gone, show);
                } else {
                    Object block = world.block(level, pos);
                    if (block == null) continue;
                    BulletRuleText.Break rule = new BulletRuleText.Break(r.nextInt(5), (float) r.nextDouble(0, 6), false, false, false, null, 0F, 1F,
                            r.nextInt(3) == 0 ? (float) r.nextDouble(0, 10) : 0F);
                    float damage = (float) r.nextDouble(0, 30), hardness = r.nextInt(6) == 0 ? 0F : (float) r.nextDouble(0, 4);
                    long t = world.time.get(level);
                    float[] a = new float[2], b = new float[2];
                    boolean oldBroke = old.wear(level, pos, block, rule, damage, hardness, t, a);
                    int stage = now.hit(level, pos, block, damage, hardness, rule, t, show, b);
                    if (stage != BlockWear.BREAKS) show.show(level, pos, stage);
                    assertEquals(oldBroke, stage == BlockWear.BREAKS, "run " + run + " step " + step);
                    if (!oldBroke) {
                        assertEquals(Float.floatToRawIntBits(a[0]), Float.floatToRawIntBits(b[0]));
                        assertEquals(Float.floatToRawIntBits(a[1]), Float.floatToRawIntBits(b[1]));
                        stood++;
                    } else broke++;
                }
                if (!old.cracks.isEmpty() || !cracks.isEmpty()) swept++;
                assertEquals(sorted(old.cracks), sorted(cracks), "run " + run + " step " + step);
                old.cracks.clear();
                cracks.clear();
                assertEquals(old.size(), ((Map<?, ?>) now.now()).size());
            }
        }
        assertTrue(broke > 5_000 && stood > 5_000 && swept > 5_000, broke + " broke, " + stood + " stood, " + swept + " with cracks");
    }

    @Test
    void backToAsItWas() {
        BlockWear<String> w = new BlockWear<>();
        float[] out = new float[2];
        BulletRuleText.Break hits = new BulletRuleText.Break(2, 3F, false, false, false, null, 0F, 1F, 0F);
        BlockWear.Cracks<String> none = (l, p, s) -> {
        };
        assertEquals(3, w.hit("l", 1L, "stone", 5F, 1F, hits, 0, none, out));
        Object was = w.now();
        assertEquals(6, w.hit("l", 1L, "stone", 5F, 1F, hits, 1, none, out));
        w.back(was);
        assertEquals(6, w.hit("l", 1L, "stone", 5F, 1F, hits, 2, none, out));
        assertEquals(BlockWear.BREAKS, w.hit("l", 1L, "stone", 5F, 1F, hits, 3, none, out));
        w.clear();
        assertEquals(3, w.hit("l", 1L, "stone", 5F, 1F, hits, 4, none, out));
    }
}
