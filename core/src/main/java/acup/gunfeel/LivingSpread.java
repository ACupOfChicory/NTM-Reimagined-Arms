package acup.gunfeel;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.host.Body;

// The living spread's settings and steps: the shooter's state read off its Body (crawling, sneaking, the pace, in the
// air), the power armour's share and the bloom by the version (Shooter); the keys are the server's file's (ReaimTimes),
// <gun>.<key> before <key>; the arithmetic is SpreadModel's. Each step reads its keys in the order Spread did, and asks
// the version where Spread did.
public final class LivingSpread {
    private LivingSpread() {
    }

    /** a gun's setting: {@code <gun>.<key>}, else {@code <key>}, else {@code def} */
    public static float key(String gun, String key, float def) {
        return ReaimTimes.number(gun + "." + key, ReaimTimes.number(key, def));
    }

    /** NTM's spread after the choke, first, on NTM's spread alone: aimed by spreadAimKnee, from the hip by spreadHipKnee */
    public static float choked(String gun, boolean aim, float spread) {
        return aim ? SpreadModel.choke(spread, key(gun, "spreadAimKnee", 0F), key(gun, "spreadAimOver", 1F))
                : SpreadModel.choke(spread, key(gun, "spreadHipKnee", 0F), key(gun, "spreadHipOver", 1F));
    }

    /**
     * The body state's and aiming's multiplier, from the pace ({@code SpreadModel.pace}, 0 lying) and the power armour's
     * share; {@code out[0]} the pace moving adds spread by (pace × armour), {@code out[1]} the stance's multiplier
     */
    public static float mult(String gun, boolean aim, boolean crawl, boolean sneak, float pace, float armor, float[] out) {
        float live = pace * armor; // the pace moving adds spread by; standing is still told by the pace itself
        float spreadMove = key(gun, "spreadMove", 1F);
        // a gun steadier moving than standing (spreadMove < 1) keeps that in armour: the armour only takes off what is added
        float move = SpreadModel.moveMult(spreadMove, spreadMove > 1F ? live : pace);
        float stance = crawl ? key(gun, "spreadCrawl", 1F) : sneak ? key(gun, "spreadSneak", 1F) : 1F;
        float mult;
        if (key(gun, "spreadStack", 1F) >= 0.5F) {
            mult = stance * move;
            if (!crawl && !sneak && pace == 0F) mult *= key(gun, "spreadStand", 1F);
        } else {
            mult = crawl || sneak ? stance : pace > 0F ? move : key(gun, "spreadStand", 1F);
        }
        if (aim) mult *= key(gun, "spreadAim", 1F);
        out[0] = live;
        out[1] = stance;
        return mult;
    }

    /** what the version reads of a shooter beyond its body: no state of its own (one for the game) */
    public interface Shooter {
        /** its power armour's share of what moving, the air and the bloom add (PowerArmor.spread) */
        float armor(Body b, String gun);

        /** the bloom a shot of {@code gun} meets, {@code perShot} its spreadBloom (above 0); 0 on a client */
        float bloom(Body b, String gun, float perShot);
    }

    /**
     * NTM's {@code spread} for a shot of {@code gun} by the shooter {@code b}, aimed or not: the choke, the body
     * state's and aiming's multiplier, what moving, the air and the bloom add. {@code log}, when given: [0] NTM's spread
     * after the choke, [1] the pace, [2] the armour's share, [3] the multiplier, [4] the bloom, [5] crawling, [6]
     * sneaking (1 or 0).
     */
    public static float apply(Body b, String gun, boolean aim, float spread, Shooter s, float @Nullable [] log) {
        // the choke first, on NTM's spread alone: before the mod's multipliers and what it adds (the SPAS-12, which
        // has no aim, by spreadHipKnee)
        spread = choked(gun, aim, spread);
        boolean crawl = b.crawling();
        boolean sneak = !crawl && b.crouching();
        double[] mv = new double[3];
        b.knownSpeed(mv); // blocks moved last tick; for a player, what the client last sent
        // lying, moving adds nothing: TaCZ's lie comes before its move (InaccuracyType.getInaccuracyType)
        float pace = crawl ? 0F : SpreadModel.pace((float) Math.sqrt(mv[0] * mv[0] + mv[2] * mv[2]));
        float armor = s.armor(b, gun);
        float[] parts = new float[2];
        float mult = mult(gun, aim, crawl, sneak, pace, armor, parts);
        float live = parts[0], stance = parts[1];
        float air = inAir(b) ? key(gun, "spreadAir", 0F) * armor : 0F;
        float bloom = bloom(b, gun, s) * armor;
        float total = SpreadModel.total(spread, mult, key(gun, "spreadSpeed", 0F), live, air, bloom, stance);
        if (log != null) {
            log[0] = spread;
            log[1] = pace;
            log[2] = armor;
            log[3] = mult;
            log[4] = bloom;
            log[5] = crawl ? 1F : 0F;
            log[6] = sneak ? 1F : 0F;
        }
        return total;
    }

    /** in the air for the spread (spreadAir): not on the ground, riding, climbing, in water, gliding or flying */
    public static boolean inAir(Body b) {
        if (b.onGround() || b.passenger() || b.climbing() || b.inWater() || b.fallFlying()) return false;
        return !b.flying();
    }

    // NTM works the spread out once per projectile, so the first call in a tick is the shot and the rest are its pellets
    private static float bloom(Body b, String gun, Shooter s) {
        float perShot = key(gun, "spreadBloom", 0F);
        if (perShot <= 0F) return 0F;
        return s.bloom(b, gun, perShot);
    }

    /** a shooter's bloom: the game tick of its last shot, the bloom that shot met, and the bloom it left */
    public static final class Bloom {
        long tick = Long.MIN_VALUE;
        float met, left;

        /**
         * The bloom a shot at game tick {@code now} meets, {@code perShot} its spreadBloom (above 0); the first call in a
         * tick is the shot, the rest (its pellets) meet the same
         */
        public float shot(String gun, long now, float perShot) {
            if (tick != now) {
                float half = key(gun, "spreadBloomHalfLife", 0.25F);
                met = tick == Long.MIN_VALUE ? 0F : SpreadModel.decay(left, now - tick, half);
                left = SpreadModel.afterShot(met, perShot, key(gun, "spreadBloomMax", 0F));
                tick = now;
            }
            return met;
        }
    }
}
