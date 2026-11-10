package acup.gunfeel;

import java.util.Map;

import com.hbm.lib.ModDamageTypes;
import com.hbm.util.DamageResistanceHandler;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * The balance's armour (server settings balanceArmor*, balanceS.*): NTM's combat sets take bullets, beams and blasts
 * (NTM's categories PHYS, EN, EXPL) by the formula C of BalanceArmorRule instead of NTM's threshold and resistance.
 *
 * <p>Put where NTM takes its threshold and resistance off a hit ({@code EntityDamageUtil.calculateDamage}, called from
 * the head of {@code actuallyHurt} after NTM's shield, armour mods and the electric multiplier): what comes back is
 * the formula's, the rest of the hurt goes as before (vanilla's armour, the part multiplier, the damage cap).
 * Fire, radiation, gas, nuclear blasts, falling and the rest are NTM's as they are. Anyone wearing the sets: players,
 * NTM's mobs (FBI agents in Security armour), AI bots.
 *
 * <p>A piece from the table takes its share of the body (diamond's 3/8/6/3 of 20) with its own set's result; the rest
 * of the body what NTM leaves (for a body with no piece from the table, NTM's result alone). The joke sets (starmetal,
 * the dineutronium ingot set) keep NTM's numbers, but a hit keeps at least balanceRoflFloor of itself. The
 * GL-54's acid corrodes the rating as it does NTM's numbers: (1 − K) of S is left.
 */
public final class BalanceArmor {
    private BalanceArmor() {
    }

    /**
     * a set of the table: its rating by default, its tau (for b and d; balanceTau.<set>) by default and its tier on the
     * ladder (the blast power by tier: balanceBlastPowerLow, balanceBlastPowerRocketHigh)
     */
    record SetOf(float s, float tau, int tier) {
    }

    /** by the items' name before _helmet/_plate/_legs/_boots (S: fit4_result.json of the balance model, rounded) */
    static final Map<String, SetOf> SETS = Map.ofEntries(
            Map.entry("steel", new SetOf(8, 1.0F, 1)), Map.entry("cobalt", new SetOf(8, 1.0F, 1)), Map.entry("alloy", new SetOf(8, 1.0F, 1)),
            Map.entry("titanium", new SetOf(8, 1.3F, 1)), Map.entry("taurun", new SetOf(8, 1.2F, 1)),
            Map.entry("dieselsuit", new SetOf(13, 1.0F, 2)), Map.entry("t51", new SetOf(18, 2.0F, 3)),
            Map.entry("steamsuit", new SetOf(26, 2.0F, 4)), Map.entry("hev", new SetOf(26, 2.1F, 4)),
            Map.entry("ajr", new SetOf(25, 2.3F, 4)), Map.entry("ajro", new SetOf(25, 2.3F, 4)),
            Map.entry("security", new SetOf(22, 3.0F, 4)), Map.entry("trenchmaster", new SetOf(48, 3.0F, 6)), Map.entry("bismuth", new SetOf(48, 3.0F, 6)),
            Map.entry("bj", new SetOf(63, 4.0F, 6)), Map.entry("ncrpa", new SetOf(54, 4.5F, 6)),
            Map.entry("cmb", new SetOf(91, 5.0F, 7)), Map.entry("schrabidium", new SetOf(91, 5.0F, 7)),
            Map.entry("rpa", new SetOf(90, 5.5F, 7)), Map.entry("fau", new SetOf(148, 6.5F, 8)),
            Map.entry("dns", new SetOf(186, 7.5F, 8)), Map.entry("euphemium", new SetOf(280, 8.5F, 9)));
    /** the joke sets: NTM's numbers, with a floor */
    static final java.util.Set<String> ROFL = java.util.Set.of("starmetal", "dnt");
    private static final String[] SUFFIX = {"_helmet", "_plate", "_legs", "_boots"};
    private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    static boolean on() {
        return ReaimTimes.flag("balanceArmor", true);
    }

    /** the set's name of a piece worn in slot {@code i}, or null when it is not an NTM armour piece of that slot */
    static String setOf(ItemStack stack, int i) {
        if (stack.isEmpty()) return null;
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null || !id.getNamespace().equals("hbm")) return null;
        String path = id.getPath();
        // BJ's winged chestplate is a BJ chestplate (NTM registers a second full BJ set with it)
        if (i == 1 && path.endsWith("_plate_jetpack")) path = path.substring(0, path.length() - "_jetpack".length());
        if (!path.endsWith(SUFFIX[i])) return null;
        return path.substring(0, path.length() - SUFFIX[i].length());
    }

    /** the set's tau (balanceTau.<set>; its b and d), the table's by default */
    static float tau(String set) {
        SetOf def = SETS.get(set);
        if (def == null) return 1F;
        float v = ReaimTimes.number("balanceTau." + set, def.tau());
        return Float.isFinite(v) && v > 0F ? v : def.tau();
    }

    static float rating(String set) {
        SetOf def = SETS.get(set);
        if (def == null) return 0F;
        float v = ReaimTimes.number("balanceS." + set, def.s());
        return Float.isFinite(v) && v > 0F ? v : 0F;
    }

    private static float positive(String key, float def) {
        float v = ReaimTimes.number(key, def);
        return Float.isFinite(v) && v >= 0F ? v : def;
    }

    /** the category the formula takes (PHYS, EN, EXPL), or null when the hit is NTM's */
    static String category(DamageSource source) {
        String cat = categoryAny(source);
        // bullets and beams only: an NTM shot's damage type. Melee (a sword, the Crucible, a mob's blow), vanilla's
        // arrows, spikes and the rest are NTM's numbers, as before the balance
        return cat == null || DamageResistanceHandler.CATEGORY_EXPLOSION.equals(cat) || shot(source) ? cat : null;
    }

    /**
     * NTM's category of the hit as the balance reads it (PHYS, EN, EXPL), shot or not, or null: euphemium's pool is
     * drained by any such hit (melee too), as the formula would take it
     */
    static String categoryAny(DamageSource source) {
        if (source.is(DamageTypeTags.BYPASSES_EFFECTS)) return null;
        if (source.is(ModDamageTypes.NUCLEAR_BLAST) || source.is(ModDamageTypes.DIGAMMA) || source.is(ModDamageTypes.RADIATION)) return null;
        String cat = DamageResistanceHandler.typeToCategory(source);
        // an infrared beam (a gun's beam of NTM's fire class: the IR capacitors of the laser guns and Lacunae) is the
        // formula's as a beam (EN; balanceIrBeams), not NTM's fire numbers: they let it through the combat sets and the pool
        if (DamageResistanceHandler.CATEGORY_FIRE.equals(cat) && source.getDirectEntity() instanceof com.hbm.entity.projectile.EntityBulletBeamBase
                && ReaimTimes.flag("balanceIrBeams", true)) return DamageResistanceHandler.CATEGORY_ENERGY;
        // balanceArmorBlasts off: blasts take NTM's numbers, bullets and beams the formula's
        if (DamageResistanceHandler.CATEGORY_EXPLOSION.equals(cat)) return ReaimTimes.flag("balanceArmorBlasts", true) ? cat : null;
        return DamageResistanceHandler.CATEGORY_ENERGY.equals(cat) || DamageResistanceHandler.CATEGORY_PHYSICAL.equals(cat) ? cat : null;
    }

    /**
     * the hit is an NTM shot's (BalanceArmorRule.shotType): its damage type and whether a creature dealt it itself
     */
    static boolean shot(DamageSource source) {
        var key = source.typeHolder().unwrapKey().orElse(null);
        return key != null && BalanceArmorRule.shotType(key.identifier().getNamespace(), key.identifier().getPath(), source.getDirectEntity() instanceof LivingEntity);
    }

    /**
     * The hit is the balance's: NTM's early check ({@code allowsAttack}: its threshold or resistance would leave nothing,
     * the hit is called off before it is dealt) must not stop it, or no hit would ever get the formula's least share.
     */
    public static boolean covers(LivingEntity target, DamageSource source) {
        if (!on() || category(source) == null) return false;
        for (int i = 0; i < 4; i++) {
            String set = setOf(target.getItemBySlot(SLOTS[i]), i);
            if (set != null && (ROFL.contains(set) || rating(set) > 0F)) return true;
        }
        return false;
    }

    /**
     * NTM's {@code calculateDamage} has left {@code ntm} of {@code amount}; what the balance leaves instead (or
     * {@code ntm} itself).
     */
    public static float apply(LivingEntity target, DamageSource source, float amount, float neg, float pen, float ntm) {
        float out = apply0(target, source, amount, neg, pen, ntm);
        if (watch == target) {
            seenHits++;
            seenIn += amount;
            seenNtm += ntm;
            seenOut += out;
        }
        return out;
    }

    /** the stand: the hits on {@code watch} NTM's calculateDamage saw, their damage before, NTM's and ours */
    static volatile LivingEntity watch;
    static int seenHits;
    static float seenIn, seenNtm, seenOut;

    /** the hit's damage NTM's calculateDamage took last, and on whom (after NTM's shield: what the formula sees) */
    private static LivingEntity amountOf;
    private static float amount;

    /**
     * the damage this hurt on {@code target} came to NTM's armour with (after NTM's shield and armour mods), read once;
     * {@code fallback} when NTM's armour did not see it
     */
    static float takeAmount(LivingEntity target, float fallback) {
        float out = amountOf == target ? amount : fallback;
        amountOf = null;
        return out;
    }

    /** the power of the weapon's blast being dealt now (1: none, or not a weapon's), set around NTM's blast's hurt */
    static final ThreadLocal<Float> BLAST_POWER = ThreadLocal.withInitial(() -> 1F);

    /** the weapon's blast being dealt now is a rocket's on the creature it struck (set around NTM's blast's hurt) */
    static final ThreadLocal<Boolean> ROCKET_DIRECT = ThreadLocal.withInitial(() -> false);

    /**
     * the power a weapon's blast of {@code power} (balanceBlastPower) hits a set of {@code tier} with: up to tier 4
     * balanceBlastPowerLow, a rocket's direct hit on tier 7 and up balanceBlastPowerRocketHigh (each at most the power,
     * at least 1)
     */
    static float pieceBlastPower(int tier, float power) {
        if (tier <= 4) return clampPower("balanceBlastPowerLow", power);
        if (tier >= 7 && ROCKET_DIRECT.get()) return clampPower("balanceBlastPowerRocketHigh", power);
        return power;
    }

    private static float clampPower(String key, float power) {
        float v = ReaimTimes.number(key, power);
        return Float.isFinite(v) ? Math.max(1F, Math.min(power, v)) : power;
    }

    /** balanceBlastPower (2.7; 1: X858): how much stronger a weapon's blast hits a combat set */
    public static float blastPower() {
        float v = ReaimTimes.number("balanceBlastPower", 2.7F);
        return Float.isFinite(v) && v >= 1F ? v : 1F;
    }

    /**
     * NTM's blast hurts {@code target} with {@code amount}: a weapon's (a round of a launcher, or a blast of no bullet
     * of at most balanceExplRawLimit: hand grenades) takes the blast power; an explosive bullet's blast (its gun's
     * table multiplier) and big charges do not.
     */
    public static <T> T withBlastPower(boolean weapon, float amount, java.util.function.Supplier<T> hurt) {
        return withBlastPower(weapon, false, amount, hurt);
    }

    /** the same; {@code rocketDirect}: a rocket's blast on the creature it struck */
    public static <T> T withBlastPower(boolean weapon, boolean rocketDirect, float amount, java.util.function.Supplier<T> hurt) {
        // not a weapon's: 1, also when it goes off inside a weapon's blast's hurt
        float power = !weapon || !on() || amount > explRawLimit() ? 1F : blastPower();
        Float before = BLAST_POWER.get();
        Boolean direct = ROCKET_DIRECT.get();
        BLAST_POWER.set(power);
        ROCKET_DIRECT.set(weapon && rocketDirect);
        try {
            return hurt.get();
        } finally {
            BLAST_POWER.set(before);
            ROCKET_DIRECT.set(direct);
        }
    }

    /** balanceExplRawLimit: a blast of more raw damage is not squeezed, nor capped (DamageCap) */
    static float explRawLimit() {
        return positive("balanceExplRawLimit", 60F);
    }

    private static float apply0(LivingEntity target, DamageSource source, float amount, float neg, float pen, float ntm) {
        if (!target.level().isClientSide()) {
            amountOf = target;
            BalanceArmor.amount = amount;
        }
        if (!on() || !(amount > 0F)) return ntm;
        String cat = category(source);
        if (cat == null) return ntm;
        boolean en = DamageResistanceHandler.CATEGORY_ENERGY.equals(cat), expl = DamageResistanceHandler.CATEGORY_EXPLOSION.equals(cat);
        float[] byPiece = new float[4];
        boolean any = false, rofl = false;
        float k = acup.gunfeel.gas.GasServer.corrosion(target);
        float knee = positive("balanceFleshKnee", BalanceArmorRule.Tune.DEFAULT.knee()), most = positive("balanceFleshMax", BalanceArmorRule.Tune.DEFAULT.most());
        // a most not above the knee: the defaults
        var tune = most > knee ? new BalanceArmorRule.Tune(knee, most) : BalanceArmorRule.Tune.DEFAULT;
        // a blast of more than this raw damage (a mine, C4, a block of TNT: building, not a weapon) is not squeezed
        boolean squeeze = !expl || amount <= explRawLimit();
        // a weapon's blast (a grenade, a 40 mm shell, a rocket) hits the set stronger (balanceBlastPower): its damage
        // times the power into the formula, the limit above and the cap by its own; never more than with no armour
        float power = expl && squeeze ? BLAST_POWER.get() : 1F;
        var hit = new BalanceArmorRule.Hit(amount * power, neg, pen, squeeze);
        float enShare = Math.min(1F, positive("balanceEnShare", 0.75F));
        for (int i = 0; i < 4; i++) {
            byPiece[i] = Float.NaN;
            String set = setOf(target.getItemBySlot(SLOTS[i]), i);
            if (set == null) continue;
            if (ROFL.contains(set)) rofl = true;
            float s = rating(set);
            if (!(s > 0F)) continue;
            if (en) s *= enShare;
            if (k > 0F) s = acup.gunfeel.gas.GasModel.corrode(s, k);
            if (!(s > 0F)) s = 1e-3F;
            // the weapon's blast's power by the set's tier (balanceBlastPowerLow, balanceBlastPowerRocketHigh)
            float pk = power > 1F ? pieceBlastPower(SETS.get(set).tier(), power) : 1F;
            var pieceHit = pk == power ? hit : new BalanceArmorRule.Hit(amount * pk, neg, pen, squeeze);
            byPiece[i] = Math.min(amount, BalanceArmorRule.full(pieceHit, s, tau(set), tune));
            any = true;
        }
        float rest = ntm;
        if (rofl) rest = Math.max(rest, Math.min(1F, positive("balanceRoflFloor", 0.18F)) * amount);
        return any ? BalanceArmorRule.blend(byPiece, rest) : rest;
    }
}
