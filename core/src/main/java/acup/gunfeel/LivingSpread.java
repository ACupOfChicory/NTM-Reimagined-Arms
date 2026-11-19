package acup.gunfeel;

// The living spread's settings and steps without the shooter: Spread (on each version) reads the shooter's state off
// the game (crawling, sneaking, the pace, the power armour's share, in the air) and gives it here; the keys are the
// server's file's (ReaimTimes), <gun>.<key> before <key>; the arithmetic is SpreadModel's. Each step reads its keys in
// the order Spread did.
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
