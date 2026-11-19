package acup.gunfeel;

import java.util.Map;

/**
 * What BalanceArmor knows without the game: NTM's combat sets the balance has numbers for (rating, tau, tier), the
 * set's name in a piece's item name, the balance's settings of the armour and of a weapon's blast on it (balanceArmor*,
 * balanceS.*, balanceBlast*), and the blast being dealt now (set around NTM's blast's hurt, read inside it).
 * BalanceArmor extends it on each version and reads the hit (its damage source, the pieces worn) off the game: its
 * callers name these members through it, BalanceArmor.SETS, BalanceArmor.rating and so on.
 */
public class BalanceArmorBase {
    BalanceArmorBase() {
    }

    /**
     * a set of the table: its rating by default, its tau (for b and d) by default and its tier on the
     * ladder (the blast power by tier: balanceBlastPowerLow, balanceBlastPowerRocketHigh)
     */
    record SetOf(float s, float tau, int tier) {
    }

    /** by the items' name before _helmet/_plate/_legs/_boots (S: fit4_result.json of the balance model, rounded) */
    static final Map<String, SetOf> SETS = Map.ofEntries(
            Map.entry("steel", new SetOf(8, 1.0F, 1)), Map.entry("cobalt", new SetOf(8, 1.0F, 1)), Map.entry("alloy", new SetOf(8, 1.0F, 1)),
            Map.entry("titanium", new SetOf(8, 1.3F, 1)), Map.entry("taurun", new SetOf(8, 1.2F, 1)),
            Map.entry("dieselsuit", new SetOf(13, 3.0F, 2)), Map.entry("t51", new SetOf(23, 4.0F, 3)),
            // tier 4 (review 3, X875: steam 66/3, HEV 46/3, AJR 38/4, Security 56/3): tau 4 as T-51b's, or the least
            // share b of tau 3 (0.13 against T-51b's 0.10) let small rounds through more than T-51b; no cell is weaker
            // than T-51b, the median clicks against it ×1.33
            Map.entry("steamsuit", new SetOf(66, 4.0F, 4)), Map.entry("hev", new SetOf(60, 4.0F, 4)),
            Map.entry("ajr", new SetOf(55, 4.0F, 4)), Map.entry("ajro", new SetOf(55, 4.0F, 4)),
            Map.entry("security", new SetOf(56, 4.0F, 4)), Map.entry("trenchmaster", new SetOf(72, 4.0F, 6)), Map.entry("bismuth", new SetOf(96, 5.0F, 6)),
            // starmetal (review 3): a combat set of tier 6 as bismuth
            Map.entry("starmetal", new SetOf(96, 5.0F, 6)),
            Map.entry("bj", new SetOf(120, 5.0F, 6)), Map.entry("ncrpa", new SetOf(120, 5.0F, 6)),
            Map.entry("cmb", new SetOf(159, 6.0F, 7)), Map.entry("schrabidium", new SetOf(159, 6.0F, 7)),
            Map.entry("rpa", new SetOf(158, 6.0F, 7)), Map.entry("fau", new SetOf(168, 7.0F, 8)),
            Map.entry("dns", new SetOf(186, 7.5F, 8)), Map.entry("euphemium", new SetOf(280, 8.5F, 9)));

    /** the joke set: NTM's numbers, with a floor */
    static final java.util.Set<String> ROFL = java.util.Set.of("dnt");

    /** a joke set as worn now: one of ROFL with no rating of its own */
    static boolean rofl(String set) {
        return ROFL.contains(set) && !(rating(set) > 0F);
    }

    static final String[] SUFFIX = {"_helmet", "_plate", "_legs", "_boots"};

    /** the set's name in NTM's item path {@code path} of a piece for slot {@code i} (0 head .. 3 feet), or null when it is none */
    static String setName(String path, int i) {
        // BJ's winged chestplate is a BJ chestplate (NTM registers a second full BJ set with it)
        if (i == 1 && path.endsWith("_plate_jetpack")) path = path.substring(0, path.length() - "_jetpack".length());
        if (!path.endsWith(SUFFIX[i])) return null;
        return path.substring(0, path.length() - SUFFIX[i].length());
    }

    static boolean on() {
        return ReaimTimes.flag("balanceArmor", true);
    }

    /** the set's tau (its b and d), the table's */
    static float tau(String set) {
        SetOf def = SETS.get(set);
        return def == null ? 1F : def.tau();
    }

    static float rating(String set) {
        SetOf def = SETS.get(set);
        if (def == null) return 0F;
        float v = ReaimTimes.number("balanceS." + set, def.s());
        return Float.isFinite(v) && v > 0F ? v : 0F;
    }

    static float positive(String key, float def) {
        float v = ReaimTimes.number(key, def);
        return Float.isFinite(v) && v >= 0F ? v : def;
    }

    /** the power of the weapon's blast being dealt now (1: none, or not a weapon's), set around NTM's blast's hurt */
    static final ThreadLocal<Float> BLAST_POWER = ThreadLocal.withInitial(() -> 1F);

    /** the weapon's blast being dealt now is a rocket's on the creature it struck (set around NTM's blast's hurt) */
    static final ThreadLocal<Boolean> ROCKET_DIRECT = ThreadLocal.withInitial(() -> false);

    /**
     * the power a weapon's blast of {@code power} (balanceBlastPower) hits a set of {@code tier} with: up to tier 4
     * balanceBlastPowerLow, a rocket's direct hit on tier 7 balanceBlastPowerRocketHigh (each at most the power, at
     * least 1; DNS, FAU and euphemium the power itself)
     */
    static float pieceBlastPower(int tier, float raw, float power) {
        return blastPowerFor(tier, ROCKET_DIRECT.get(), raw, power);
    }

    /**
     * the same for a rocket's direct hit or not ({@code rocketDirect}) of {@code raw} damage, as the stand's model takes
     * it: balanceBlastPowerRocketHigh only for a rocket of more raw damage than balanceBlastRocketHighFrom (30; 0: X873,
     * every rocket; Panzerschreck's 25 hit tier 7 weaker than a 40 mm shell of the same damage)
     */
    static float blastPowerFor(int tier, boolean rocketDirect, float raw, float power) {
        if (tier <= 4) return clampPower("balanceBlastPowerLow", 1.7F, power);
        if (tier == 7 && rocketDirect && raw > rocketHighFrom()) return clampPower("balanceBlastPowerRocketHigh", 2.2F, power);
        return power;
    }

    /**
     * the tier a set of {@code set} takes a weapon's blast at (its power and balanceBlastLowFloor): its own, but
     * Trenchmaster's balanceBlastTrenchTier (4; 6: X877). NTM gives it HEV's explosion resistance (DT 5, 25 %); at its
     * tier 6 it took the full blast power with no floor, three hits of MK108's or a rocket's direct blast against HEV's
     * four and nine to fifteen of a weak blast against HEV's four (notes: review 4, item 5)
     */
    static int blastTier(String set, int tier) {
        if (!"trenchmaster".equals(set)) return tier;
        float v = ReaimTimes.number("balanceBlastTrenchTier", 4F);
        return Float.isFinite(v) && v >= 1F ? Math.round(v) : tier;
    }

    static float rocketHighFrom() {
        float v = ReaimTimes.number("balanceBlastRocketHighFrom", 30F);
        return Float.isFinite(v) && v > 0F ? v : 0F;
    }

    /**
     * balanceBlastLowFloor (0.9; X875 0.83; 0: X873): a weapon's blast on a set of tier 4 and below hits it with at
     * least this share of its rating as its power (damage times blast power): a weak blast (a gunpowder or DEMO grenade,
     * a 40 mm shell's splash) took 9 to 15 hits on tier 4 against 3 bare; with it 3 to 4, the HE grenade's 3 as it was.
     * A blast weaker than 7 raw (a blast's far edge) gets the floor in its share, so the set still takes some off it
     */
    static float blastLowFloor() {
        float v = ReaimTimes.number("balanceBlastLowFloor", 0.9F);
        return Float.isFinite(v) && v > 0F ? Math.min(v, 3F) : 0F;
    }

    /** a weapon's blast's damage times its power into the formula on a set of {@code tier}, rating {@code s} */
    static float blastInto(int tier, float amount, float pk, float s) {
        float y = amount * pk;
        return pk > 1F && tier <= 4 ? Math.max(y, BalanceArmorRule.lowFloorPower(blastLowFloor(), s, amount)) : y;
    }

    static float clampPower(String key, float def, float power) {
        float v = ReaimTimes.number(key, def);
        return Float.isFinite(v) ? Math.max(1F, Math.min(power, v)) : power;
    }

    /** balanceBlastPower (3; 2.7: X865, 1: X858): how much stronger a weapon's blast hits a combat set */
    public static float blastPower() {
        float v = ReaimTimes.number("balanceBlastPower", 3F);
        return Float.isFinite(v) && v >= 1F ? v : 1F;
    }

    /** an NTM blast (ExplosionVNT's EntityProcessorCrossSmooth) being dealt now, of any damage class */
    static final ThreadLocal<Boolean> VNT_BLAST = ThreadLocal.withInitial(() -> false);

    static boolean vntBlastNow() {
        return VNT_BLAST.get();
    }

    /** an explosive bullet's blast being dealt now (not a launcher's round): squeezed and capped at any raw damage */
    static final ThreadLocal<Boolean> BULLET_BLAST = ThreadLocal.withInitial(() -> false);

    static boolean bulletBlastNow() {
        return BULLET_BLAST.get();
    }

    /**
     * NTM's blast hurts {@code target} with {@code amount}: a weapon's (a round of a launcher, or a blast of no bullet
     * of at most balanceExplRawLimit: hand grenades) takes the blast power; an explosive bullet's blast (its gun's
     * table multiplier) and big charges do not. {@code rocketDirect}: a rocket's blast on the creature it struck;
     * {@code bullet}: the blast is an explosive bullet's (bound to a bullet, not a launcher's round)
     */
    public static <T> T withBlastPower(boolean weapon, boolean rocketDirect, boolean bullet, float amount, java.util.function.Supplier<T> hurt) {
        // not a weapon's: 1, also when it goes off inside a weapon's blast's hurt
        float power = !weapon || !on() || amount > explRawLimit() ? 1F : blastPower();
        Float before = BLAST_POWER.get();
        Boolean direct = ROCKET_DIRECT.get();
        Boolean wasBullet = BULLET_BLAST.get(), wasVnt = VNT_BLAST.get();
        VNT_BLAST.set(true);
        BLAST_POWER.set(power);
        ROCKET_DIRECT.set(weapon && rocketDirect);
        BULLET_BLAST.set(!weapon && bullet);
        try {
            return hurt.get();
        } finally {
            BLAST_POWER.set(before);
            ROCKET_DIRECT.set(direct);
            BULLET_BLAST.set(wasBullet);
            VNT_BLAST.set(wasVnt);
        }
    }

    /**
     * balanceElectricPowered (1.5; 5: NTM's, X865): how many times an electric hit (the Tesla Cannon's) counts on a
     * powered chestplate, with the balance's armour on; {@code ntm} (NTM's 5) with it off
     */
    public static float electricPowered(float ntm) {
        if (!on()) return ntm;
        float v = ReaimTimes.number("balanceElectricPowered", 1.5F);
        return Float.isFinite(v) && v >= 1F ? v : ntm;
    }

    /** balanceExplRawLimit: a blast of more raw damage is not squeezed, nor capped (DamageCap) */
    static float explRawLimit() {
        return positive("balanceExplRawLimit", 60F);
    }
}
