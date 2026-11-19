package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

// LivingSpread's steps (core) against Spread.apply's as they were before they went there (30ad1fa, copied below word
// for word, get() for ReaimTimes), on the shipped file (here, on 26.2) and the balance's keys: every gun with spread
// keys of its own and one with none, random
// states (aimed, crawling, sneaking, the pace, the power armour's share, NTM's spread) and bursts at random gaps: the
// same numbers, bit for bit.
class LivingSpreadTest {
    // ---- 30ad1fa's Spread, the arithmetic of apply and bloom
    private static float get(String gun, String key, float def) {
        return ReaimTimes.number(gun + "." + key, ReaimTimes.number(key, def));
    }

    private static final class OldBloom {
        long tick = Long.MIN_VALUE;
        float met, left;
    }

    // apply from the choke to the total, the shooter's state given; out: spread after the choke, mult
    private static float oldApply(String gun, boolean aim, boolean crawl, boolean sneak, float pace, float armor, boolean inAir, float bloomMet, float spread, float[] out) {
        spread = aim ? SpreadModel.choke(spread, get(gun, "spreadAimKnee", 0F), get(gun, "spreadAimOver", 1F))
                : SpreadModel.choke(spread, get(gun, "spreadHipKnee", 0F), get(gun, "spreadHipOver", 1F));
        float live = pace * armor;
        float spreadMove = get(gun, "spreadMove", 1F);
        float move = SpreadModel.moveMult(spreadMove, spreadMove > 1F ? live : pace);
        float stance = crawl ? get(gun, "spreadCrawl", 1F) : sneak ? get(gun, "spreadSneak", 1F) : 1F;
        float mult;
        if (get(gun, "spreadStack", 1F) >= 0.5F) {
            mult = stance * move;
            if (!crawl && !sneak && pace == 0F) mult *= get(gun, "spreadStand", 1F);
        } else {
            mult = crawl || sneak ? stance : pace > 0F ? move : get(gun, "spreadStand", 1F);
        }
        if (aim) mult *= get(gun, "spreadAim", 1F);
        float air = inAir ? get(gun, "spreadAir", 0F) * armor : 0F;
        float bloom = bloomMet * armor;
        out[0] = spread;
        out[1] = mult;
        return SpreadModel.total(spread, mult, get(gun, "spreadSpeed", 0F), live, air, bloom, stance);
    }

    private static float oldBloom(OldBloom b, String gun, long now) {
        float perShot = get(gun, "spreadBloom", 0F);
        if (perShot <= 0F) return 0F;
        if (b.tick != now) {
            float half = get(gun, "spreadBloomHalfLife", 0.25F);
            b.met = b.tick == Long.MIN_VALUE ? 0F : SpreadModel.decay(b.left, now - b.tick, half);
            b.left = SpreadModel.afterShot(b.met, perShot, get(gun, "spreadBloomMax", 0F));
            b.tick = now;
        }
        return b.met;
    }

    // ---- now: Spread's own lines around LivingSpread
    private static float newApply(String gun, boolean aim, boolean crawl, boolean sneak, float pace, float armor, boolean inAir, float bloomMet, float spread, float[] out) {
        spread = LivingSpread.choked(gun, aim, spread);
        float[] parts = new float[2];
        float mult = LivingSpread.mult(gun, aim, crawl, sneak, pace, armor, parts);
        float live = parts[0], stance = parts[1];
        float air = inAir ? LivingSpread.key(gun, "spreadAir", 0F) * armor : 0F;
        float bloom = bloomMet * armor;
        out[0] = spread;
        out[1] = mult;
        return SpreadModel.total(spread, mult, LivingSpread.key(gun, "spreadSpeed", 0F), live, air, bloom, stance);
    }

    private static float newBloom(LivingSpread.Bloom b, String gun, long now) {
        float perShot = LivingSpread.key(gun, "spreadBloom", 0F);
        if (perShot <= 0F) return 0F;
        return b.shot(gun, now, perShot);
    }

    private static List<String> guns() {
        TreeSet<String> guns = new TreeSet<>();
        TreeSet<String> keys = new TreeSet<>(ReaimTimes.values().keySet());
        keys.addAll(BalanceKeys.ARMS.keySet());
        for (String k : keys) {
            int i = k.lastIndexOf(".spread");
            if (i > 0) guns.add(k.substring(0, i));
        }
        List<String> out = new ArrayList<>(guns);
        out.add("hbm:no_such_gun");
        return out;
    }

    private static float pace(SplittableRandom r) {
        return switch (r.nextInt(4)) {
            case 0 -> 0F;
            case 1 -> SpreadModel.pace((float) r.nextDouble(0, 0.4));
            case 2 -> 1F;
            default -> (float) r.nextDouble(0, 1.3);
        };
    }

    @Test
    void theSameAsBefore() {
        List<String> guns = guns();
        assertTrue(guns.size() > 5, "guns with spread keys: " + guns);
        SplittableRandom r = new SplittableRandom(1119L);
        float[] a = new float[2], b = new float[2];
        for (String gun : guns) {
            OldBloom ob = new OldBloom();
            LivingSpread.Bloom nb = new LivingSpread.Bloom();
            long now = r.nextLong(0, 1_000_000);
            for (int i = 0; i < 20_000; i++) {
                // a shot now and then a gap: 0 (a pellet of the same shot) to a few seconds
                now += switch (r.nextInt(4)) {
                    case 0 -> 0;
                    case 1 -> 1 + r.nextInt(4);
                    case 2 -> r.nextInt(20);
                    default -> r.nextInt(200);
                };
                float om = oldBloom(ob, gun, now), nm = newBloom(nb, gun, now);
                assertEquals(Float.floatToRawIntBits(om), Float.floatToRawIntBits(nm), gun + " bloom at " + now);
                boolean aim = r.nextBoolean(), crawl = r.nextInt(4) == 0, sneak = !crawl && r.nextInt(3) == 0, air = r.nextInt(4) == 0;
                float pace = crawl ? 0F : pace(r), armor = r.nextInt(3) == 0 ? (float) r.nextDouble(0, 1.5) : 1F;
                float spread = r.nextInt(5) == 0 ? 0F : (float) r.nextDouble(0, 0.2);
                float ot = oldApply(gun, aim, crawl, sneak, pace, armor, air, om, spread, a);
                float nt = newApply(gun, aim, crawl, sneak, pace, armor, air, nm, spread, b);
                String at = gun + " aim " + aim + " crawl " + crawl + " sneak " + sneak + " pace " + pace + " armor " + armor + " air " + air + " spread " + spread;
                assertEquals(Float.floatToRawIntBits(ot), Float.floatToRawIntBits(nt), at);
                assertEquals(Float.floatToRawIntBits(a[0]), Float.floatToRawIntBits(b[0]), at);
                assertEquals(Float.floatToRawIntBits(a[1]), Float.floatToRawIntBits(b[1]), at);
            }
        }
    }

    @Test
    void keysGunFirst() {
        // a key of no gun: the plain key, else the default
        assertEquals(ReaimTimes.number("spreadMove", 1F), LivingSpread.key("hbm:no_such_gun", "spreadMove", 1F));
        assertEquals(7F, LivingSpread.key("hbm:no_such_gun", "no.such.key", 7F));
    }
}
