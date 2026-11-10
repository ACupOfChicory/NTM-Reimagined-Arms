package acup.gunfeel;

/**
 * Stage 3's model of NTM's blasts on the balance's armour (balance-by-progression.md 10.3), without the game: what the
 * stand (BalanceCheck's pass expl) puts its numbers against.
 *
 * <ul>
 * <li>NTM's falloff (EntityProcessorCross): {@code 1 − d/(2·size)}, {@code d} from the blast to the near side of the
 * target's box (a player's half width 0.3: {@code r − 0.3} for a blast {@code r} blocks from its middle line; 10.3 had
 * {@code 1 − r/size}, half the range and from the middle);</li>
 * <li>a hand grenade at {@code r} blocks: one hit of {@code D} times the falloff;</li>
 * <li>a 40 mm shell or a rocket on the target: its contact {@code 0.6·D} and its blast {@code 0.4·D} ({@code d} 0) in
 * one hurt (ExplosiveDamage adds the contact to the blast's hurt on the target it hit): one hit of {@code D} through the
 * armour, one click (10.3 had two hits, two clicks);</li>
 * <li>one near: its blast alone, {@code 0.4·D} times the falloff;</li>
 * <li>each hit through the set by the formula C (BalanceArmorRule, the set's S and tier; a hit of more than the raw
 * limit neither squeezed nor capped), at most the click's cap; no set: the hit itself;</li>
 * <li>Trenchmaster's block of one in three (NTM's filterAttack) stops none of these: NTM's blasts are dealt past that
 * early check ({@code allowSpecialCancel} false), the contact with them (10.3: × 1.5 hits);</li>
 * <li>the blast power {@code k} (balanceBlastPower): the set's formula takes {@code k·x} (its power and its flesh),
 * at most {@code x}; the raw limit and the cap by {@code x} itself; no set: {@code x}; on tier 4 and below at least
 * {@code balanceBlastLowFloor·S} (BalanceArmor.blastInto);</li>
 * <li>hits to die {@code ⌈HP / damage⌉}.</li>
 * </ul>
 */
final class BalanceBlasts {
    private BalanceBlasts() {
    }

    static final float HP = 20F, CLICK = 0.6F, RAW_LIMIT = 60F, HALF_WIDTH = 0.3F;

    /** an explosive: hand ({@code launched} false) or a launcher's round; damage, range, threshold negation, piercing */
    record Boom(String name, boolean launched, float d, float size, float neg, float pen) {
    }

    /** the damage of one hit through the set (null: no set), capped by a click */
    static float hit(float x, float neg, float pen, String set, float s, float tau) {
        return hit(x, neg, pen, set, s, tau, 1F);
    }

    /** the same with the blast power {@code k} */
    static float hit(float x, float neg, float pen, String set, float s, float tau, float k) {
        return hit(x, neg, pen, set, s, tau, k, 0F);
    }

    /** the same, the power at least {@code floor·s} (balanceBlastLowFloor on tier 4 and below; 0: none) */
    static float hit(float x, float neg, float pen, String set, float s, float tau, float k, float floor) {
        if (!(x > 0F)) return 0F;
        float kx = x <= RAW_LIMIT ? x * k : x;
        if (x <= RAW_LIMIT && k > 1F && floor > 0F) kx = Math.max(kx, BalanceArmor.lowFloorPower(floor, s, x));
        float out = set == null ? x
                : Math.min(x, BalanceArmorRule.full(new BalanceArmorRule.Hit(kx, neg, pen, x <= RAW_LIMIT), s, tau, BalanceArmorRule.Tune.DEFAULT));
        // more raw damage than the limit is not capped either
        return x > RAW_LIMIT ? out : Math.min(out, CLICK * HP);
    }

    /** NTM's falloff of a blast {@code r} blocks from the target's middle line */
    static float falloff(Boom b, float r) {
        return Math.max(0F, 1F - Math.max(0F, r - HALF_WIDTH) / (2F * b.size()));
    }

    /** the raw damage of one blast's hurt at {@code r} blocks ({@code r} &lt; 0: a round on the target) */
    static float raw(Boom b, float r) {
        if (!b.launched()) return b.d() * falloff(b, r);
        if (r < 0F) return 0.6F * b.d() + 0.4F * b.d();
        return 0.4F * b.d() * falloff(b, r);
    }

    /** what one blast takes off the target */
    static float damage(Boom b, float r, String set, float s, float tau) {
        return damage(b, r, set, s, tau, 1F);
    }

    /** the same with the blast power {@code k} */
    static float damage(Boom b, float r, String set, float s, float tau, float k) {
        return damage(b, r, set, s, tau, k, 0F);
    }

    /** the same with the power's floor (balanceBlastLowFloor) */
    static float damage(Boom b, float r, String set, float s, float tau, float k, float floor) {
        return hit(raw(b, r), b.neg(), b.pen(), set, s, tau, k, floor);
    }

    /** blasts to die (Integer.MAX_VALUE: none ever kills) */
    static int hits(float damage) {
        if (!(damage > 1e-6F)) return Integer.MAX_VALUE;
        return (int) Math.ceil(HP / damage - 1e-6);
    }
}
