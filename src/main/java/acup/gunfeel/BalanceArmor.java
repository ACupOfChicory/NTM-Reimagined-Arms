package acup.gunfeel;

import java.util.Map;

import com.hbm.lib.ModDamageTypes;
import com.hbm.util.DamageResistanceHandler;

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
 * of the body what NTM leaves (for a body with no piece from the table, NTM's result alone). The joke set (the
 * dineutronium ingot set) keeps NTM's numbers, but a hit keeps at least balanceRoflFloor of itself. Starmetal is a
 * combat set of tier 6 (bismuth's numbers). The GL-54's acid corrodes the rating as it does NTM's numbers: (1 − K)
 * of S is left.
 */
public final class BalanceArmor extends BalanceArmorBase {
    private BalanceArmor() {
    }

    /** NTM's names of the sets the balance has numbers for: this table's and the scheme's (BalanceFinal.DEFAULT_SETS) */
    public static java.util.Set<String> knownSets() {
        java.util.Set<String> out = new java.util.TreeSet<>(SETS.keySet());
        out.addAll(BalanceFinal.DEFAULT_SETS.keySet());
        return out;
    }

    public static boolean knownSet(String set) {
        return SETS.containsKey(set) || BalanceFinal.DEFAULT_SETS.containsKey(set);
    }

    private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    /** the set's name of a piece worn in slot {@code i}, or null when it is not an NTM armour piece of that slot */
    static String setOf(ItemStack stack, int i) {
        // an empty stack's item is air's (BalanceArmorBase.setOfItem: no set)
        return setOfItem(ItemNumbers.number(stack.getItem()), i);
    }

    /** the category the formula takes (PHYS, EN, EXPL), or null when the hit is NTM's (BalanceArmorBase.categoryOf) */
    static String category(DamageSource source) {
        return categoryOf(source);
    }

    /** an infrared beam: a gun's beam of NTM's fire class, the formula's and the cap's as a beam */
    public static boolean irBeam(DamageSource source) {
        return irBeamOf(source);
    }

    /** NTM's category of the hit as the balance reads it, shot or not, or null (BalanceArmorBase.categoryAnyOf) */
    static String categoryAny(DamageSource source) {
        return categoryAnyOf(source);
    }

    /** the hit is an NTM shot's (BalanceArmorRule.shotName) */
    static boolean shot(DamageSource source) {
        return shotTypeOf(source);
    }

    /** NTM's reads of a hurt for core (HurtsPort): this system's (BALANCE) */
    static final class Reads {
        private Reads() {
        }

        /** NTM's fire type (an IR beam's) */
        static boolean ntmFire(DamageSource source) {
            return source.is(ModDamageTypes.FIRE);
        }

        /** NTM's electric type (its accepted damage takes it five times on a powered chestplate) */
        static boolean ntmElectric(DamageSource source) {
            return source.is(ModDamageTypes.ELECTRIC);
        }

        /** the direct entity: living (Hurts.LIVING), NTM's beam (Hurts.NTM_BEAM) */
        static int directKind(DamageSource source) {
            var direct = source.getDirectEntity();
            return (direct instanceof LivingEntity ? acup.gunfeel.host.Hurts.LIVING : 0)
                    | (direct instanceof com.hbm.entity.projectile.EntityBulletBeamBase ? acup.gunfeel.host.Hurts.NTM_BEAM : 0);
        }

        static String ntmCategory(DamageSource source) {
            return DamageResistanceHandler.typeToCategory(source);
        }
    }

    /**
     * The hit is the balance's: NTM's early check ({@code allowsAttack}: its threshold or resistance would leave nothing,
     * the hit is called off before it is dealt) must not stop it, or no hit would ever get the formula's least share.
     */
    public static boolean covers(LivingEntity target, DamageSource source) {
        if (!on() || category(source) == null) return false;
        // the final scheme: its hits get through, a shot it leaves to NTM meets NTM's own check
        if (BalanceFinal.takes(target, source)) return true;
        if (BalanceFinal.ntmShot(target, source)) return false;
        return wearsRated(EntityBody.of(target));
    }

    /**
     * NTM's {@code calculateDamage} has left {@code ntm} of {@code amount}; what the balance leaves instead (or
     * {@code ntm} itself).
     */
    public static float apply(LivingEntity target, DamageSource source, float amount, float neg, float pen, float ntm) {
        float out = apply0(target, source, amount, neg, pen, ntm);
        Seen seen = WATCHED.isEmpty() ? null : WATCHED.get(target);
        if (seen != null) {
            seen.hits++;
            seen.in += amount;
            seen.ntm += ntm;
            seen.out += out;
        }
        return out;
    }

    /** the stand: the hits on a watched creature NTM's calculateDamage saw, their damage before, NTM's and ours */
    static final class Seen {
        int hits;
        float in, ntm, out;
    }

    /** the creatures the stand watches, each with its own count (the balance stand's lanes: one each) */
    private static final java.util.Map<LivingEntity, Seen> WATCHED = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** the stand: {@code target}'s count from naught, {@code previous} (the lane's last victim) watched no more */
    static Seen watch(LivingEntity previous, LivingEntity target) {
        if (previous != null) WATCHED.remove(previous);
        Seen seen = new Seen();
        WATCHED.put(target, seen);
        return seen;
    }

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

    /**
     * an explosive bullet's blast is the formula's and the cap's at any raw damage. Its blast is the bullet's whole
     * damage (NTM's EntityProcessorCrossSmooth): up to X865 a heavy gun's HE round came past balanceExplRawLimit and
     * killed any set from full health with one click.
     */
    /**
     * the shot whose pair's floor a hit takes: the bullet itself, or for an explosive bullet's blast
     * the bullet whose impact set it off (the blast's source has the shooter, not the bullet)
     */
    static net.minecraft.world.entity.Entity floorShot(DamageSource source, boolean blast) {
        var bullet = blast ? ExplosiveDamage.bulletNow() : null;
        return bullet != null ? bullet : source.getDirectEntity();
    }

    private static float apply0(LivingEntity target, DamageSource source, float amount, float neg, float pen, float ntm) {
        if (!target.level().isClientSide()) {
            amountOf = target;
            BalanceArmor.amount = amount;
        }
        if (!on() || !(amount > 0F)) return ntm;
        String cat = category(source);
        if (cat == null) return ntm;
        // the final scheme (balance.final): its pairs' bullets and beams, an explosive bullet's blast on the creature it
        // struck; a shot of no pair of the table is NTM's
        float scheme = BalanceFinal.damage(target, source);
        if (!Float.isNaN(scheme)) return scheme;
        if (BalanceFinal.ntmShot(target, source)) return ntm;
        boolean en = DamageResistanceHandler.CATEGORY_ENERGY.equals(cat), expl = DamageResistanceHandler.CATEGORY_EXPLOSION.equals(cat);
        float[] byPiece = new float[4];
        boolean any = false, rofl = false;
        float k = acup.gunfeel.gas.GasServer.corrosion(target);
        float knee = positive("balanceFleshKnee", BalanceArmorRule.Tune.DEFAULT.knee()), most = positive("balanceFleshMax", BalanceArmorRule.Tune.DEFAULT.most());
        // a most not above the knee: the defaults
        var tune = most > knee ? new BalanceArmorRule.Tune(knee, most) : BalanceArmorRule.Tune.DEFAULT;
        // a blast of more than this raw damage (a mine, C4, a block of TNT: building, not a weapon) is not squeezed
        boolean squeeze = !expl || amount <= explRawLimit() || bulletBlastNow();
        // a weapon's blast (a grenade, a 40 mm shell, a rocket) hits the set stronger (balanceBlastPower): its damage
        // times the power into the formula, the limit above and the cap by its own; never more than with no armour
        float power = expl && squeeze ? BLAST_POWER.get() : 1F;
        var hit = new BalanceArmorRule.Hit(amount * power, neg, pen, squeeze);
        float enShare = Math.min(1F, positive("balanceEnShare", 0.75F));
        for (int i = 0; i < 4; i++) {
            byPiece[i] = Float.NaN;
            String set = setOf(target.getItemBySlot(SLOTS[i]), i);
            if (set == null) continue;
            if (rofl(set)) rofl = true;
            float s = rating(set);
            if (!(s > 0F)) continue;
            if (en) s *= enShare;
            if (k > 0F) s = acup.gunfeel.gas.GasModel.corrode(s, k);
            if (!(s > 0F)) s = 1e-3F;
            int tier = SETS.get(set).tier();
            // the weapon's blast's power by the set's tier (balanceBlastPowerLow, balanceBlastPowerRocketHigh,
            // balanceBlastLowFloor; Trenchmaster by balanceBlastTrenchTier)
            int bt = blastTier(set, tier);
            float pk = power > 1F ? pieceBlastPower(bt, amount, power) : 1F;
            float into = power > 1F ? blastInto(bt, amount, pk, s) : amount * power;
            // the floor of balanceBlastLowFloor lifts only the share that passes (rho), the flesh is the blast's own damage
            // times its power: with the floor's flesh too a set of a greater rating took more of a weak blast (X875)
            boolean share = power > 1F && into > amount * pk;
            float fleshOf = share ? amount * pk : into;
            var pieceHit = fleshOf == amount * power ? hit : new BalanceArmorRule.Hit(fleshOf, neg, pen, squeeze);
            // the gun table's floor of the shot's pair: its bullet's hit, an explosive bullet's blast
            float floor = !expl || bulletBlastNow() ? BalanceGuns.floorOf(floorShot(source, expl), tier) : 1F;
            // the pair's numbers below a tier (under=): a bullet's or a beam's hit, not a blast
            float[] u = expl ? null : BalanceGuns.underOf(source.getDirectEntity(), tier);
            float top = amount, pieceInto = into;
            if (u != null) {
                pieceHit = new BalanceArmorRule.Hit(pieceHit.damage() * u[0], Math.max(0F, neg + u[1]), Math.max(0F, Math.min(1F, pen + u[2])), squeeze);
                top = amount * u[0];
                pieceInto = into * u[0];
            }
            byPiece[i] = Math.min(top, BalanceArmorRule.full(pieceHit, s, tau(set), tune, floor, pieceInto));
            any = true;
        }
        float rest = ntm;
        if (rofl) rest = Math.max(rest, Math.min(1F, positive("balanceRoflFloor", 0.18F)) * amount);
        return any ? BalanceArmorRule.blend(byPiece, rest) : rest;
    }
}
