package acup.gunfeel;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.host.Body;
import acup.gunfeel.host.Hurts;
import acup.gunfeel.host.ItemTable;

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

    // ---------------------------------------------------------------- the hurt (Hurts: the version's source as it is)

    /** NTM's categories as NTM names them (DamageResistanceHandler.CATEGORY_*: compile-time constants of NTM's) */
    static final String CAT_EXPLOSION = "EXPL", CAT_FIRE = "FIRE", CAT_PHYSICAL = "PHYS", CAT_ENERGY = "EN";

    /** the version's port; its methods are asked only from the hurt's members below */
    static final class HurtPort {
        static final Hurts HURTS = CoreServices.load(Hurts.class, Hurts.MISSING);
    }

    /** the category the formula takes (PHYS, EN, EXPL), or null when the hit is NTM's */
    static @Nullable String categoryOf(Object hurt) {
        return categoryOf(HurtPort.HURTS, hurt);
    }

    /** {@link #categoryOf(Object)} on the port {@code h} (a test's own) */
    static @Nullable String categoryOf(Hurts h, Object hurt) {
        String cat = categoryAnyOf(h, hurt);
        // bullets and beams only: an NTM shot's damage type. Melee (a sword, the Crucible, a mob's blow), vanilla's
        // arrows, spikes and the rest are NTM's numbers, as before the balance
        return cat == null || CAT_EXPLOSION.equals(cat) || shotTypeOf(h, hurt) || irBeamOf(h, hurt) ? cat : null;
    }

    /** an infrared beam: a gun's beam of NTM's fire class, the formula's and the cap's as a beam */
    static boolean irBeamOf(Object hurt) {
        return irBeamOf(HurtPort.HURTS, hurt);
    }

    static boolean irBeamOf(Hurts h, Object hurt) {
        return (h.directKind(hurt) & Hurts.NTM_BEAM) != 0 && h.is(hurt, Hurts.NTM_FIRE);
    }

    /**
     * NTM's category of the hit as the balance reads it (PHYS, EN, EXPL), shot or not, or null: euphemium's pool is
     * drained by any such hit (melee too), as the formula would take it
     */
    static @Nullable String categoryAnyOf(Object hurt) {
        return categoryAnyOf(HurtPort.HURTS, hurt);
    }

    static @Nullable String categoryAnyOf(Hurts h, Object hurt) {
        if (h.is(hurt, Hurts.BYPASSES_EFFECTS)) return null;
        if (h.is(hurt, Hurts.NTM_NUCLEAR_BLAST) || h.is(hurt, Hurts.NTM_DIGAMMA) || h.is(hurt, Hurts.NTM_RADIATION)) return null;
        String cat = h.ntmCategory(hurt);
        // an infrared beam (a gun's beam of NTM's fire class: the IR capacitors of the laser guns and Lacunae) is the
        // formula's as a beam (EN), not NTM's fire numbers: they let it through the combat sets and the pool
        if (CAT_FIRE.equals(cat) && irBeamOf(h, hurt)) return CAT_ENERGY;
        // balanceArmorBlasts off: blasts take NTM's numbers, bullets and beams the formula's
        if (CAT_EXPLOSION.equals(cat)) return ReaimTimes.flag("balanceArmorBlasts", true) ? cat : null;
        return CAT_ENERGY.equals(cat) || CAT_PHYSICAL.equals(cat) ? cat : null;
    }

    /** the hit is an NTM shot's (BalanceArmorRule.shotName): its damage type and whether a creature dealt it itself */
    static boolean shotTypeOf(Object hurt) {
        return shotTypeOf(HurtPort.HURTS, hurt);
    }

    static boolean shotTypeOf(Hurts h, Object hurt) {
        String name = h.typeName(hurt);
        return name != null && BalanceArmorRule.shotName(name, (h.directKind(hurt) & Hurts.LIVING) != 0);
    }
    // ---------------------------------------------------------------- the sets worn (Body.worn, ItemTable)

    /**
     * The set of an item worn in each slot, by the item's number (ItemTable), worked out once: the answer is
     * {@link #setOfName}'s of the item's id, which hangs on the id and the slot alone (setName reads no key and no table
     * read again), and an item's id is the same for its number while the game runs. A race is harmless: a null cell is
     * "not worked out yet" (worked out again); "no set" is {@link #NO_SET}, never null; the answers are Strings
     * (immutable, safe to see through a race). A row grown is a copy put in place of the old one: a reader of the new
     * row before its copy is done sees null and works it out again, never a wrong answer.
     */
    static final class SetsOf {
        /** the mark of "no set" (setOfName's null) */
        private static final String NO_SET = new String("no set");
        private final ItemTable items;
        private final String[][] rows = {new String[0], new String[0], new String[0], new String[0]};

        SetsOf(ItemTable items) {
            this.items = items;
        }

        /** the set of the item numbered {@code item} worn in slot {@code slot} (0 head .. 3 feet), or null */
        @Nullable String of(int item, int slot) {
            String[] row = rows[slot];
            String got = item >= 0 && item < row.length ? row[item] : null;
            if (got == null) {
                got = setOfName(items.name(item), slot);
                if (got == null) got = NO_SET;
                remember(slot, item, got);
            }
            return got == NO_SET ? null : got;
        }

        private synchronized void remember(int slot, int item, String set) {
            String[] row = rows[slot];
            if (item >= row.length) rows[slot] = row = java.util.Arrays.copyOf(row, Math.max(item + 1, row.length * 2 + 16));
            row[item] = set;
        }
    }

    /** the game's items (ItemTable), asked the first time a set is */
    static final class WornPort {
        static final SetsOf SETS_OF = new SetsOf(CoreServices.load(ItemTable.class, ItemTable.MISSING));
    }

    /** the set of an item by its registry id {@code name} worn in slot {@code slot}: NTM's piece by its path (setName), else null */
    static @Nullable String setOfName(String name, int slot) {
        return name.startsWith("hbm:") ? setName(name.substring(4), slot) : null;
    }

    /** the set of the item numbered {@code item} (ItemTable) worn in slot {@code slot} (0 head .. 3 feet), or null */
    static @Nullable String setOfItem(int item, int slot) {
        return WornPort.SETS_OF.of(item, slot);
    }

    /** the set of what {@code b} wears in slot {@code slot} (Body.worn), or null */
    static @Nullable String setOfWorn(Body b, int slot) {
        return setOfItem(b.worn(slot), slot);
    }

    /** {@code b} wears a piece of a set with a rating, or of the joke set: the balance's armour takes the hit (covers) */
    static boolean wearsRated(Body b) {
        for (int i = 0; i < 4; i++) {
            String set = setOfWorn(b, i);
            if (set != null && (rofl(set) || rating(set) > 0F)) return true;
        }
        return false;
    }
}
