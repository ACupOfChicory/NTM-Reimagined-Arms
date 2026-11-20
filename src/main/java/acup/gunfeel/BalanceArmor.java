package acup.gunfeel;

import com.hbm.lib.ModDamageTypes;
import com.hbm.util.DamageResistanceHandler;

import net.minecraft.world.damagesource.DamageSource;
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

    public static java.util.Set<String> knownSets() { return BalanceArmorBase.knownSets(); }
    public static boolean knownSet(String set) { return BalanceArmorBase.knownSet(set); }

    static String setOf(ItemStack stack, int i) {
        return setOfItem(ItemNumbers.number(stack.getItem()), i);
    }

    static String category(DamageSource source) { return categoryOf(source); }
    public static boolean irBeam(DamageSource source) { return irBeamOf(source); }
    static String categoryAny(DamageSource source) { return categoryAnyOf(source); }
    static boolean shot(DamageSource source) { return shotTypeOf(source); }

    public static boolean covers(LivingEntity target, DamageSource source) {
        return coversOf(EntityBody.of(target), source);
    }

    public static float apply(LivingEntity target, DamageSource source, float amount, float neg, float pen, float ntm) {
        return applyOf(EntityBody.of(target), source, amount, neg, pen, ntm);
    }

    /** The stand's version type; its counters and watch state are core's. */
    static final class Seen extends BalanceArmorBase.Seen {
    }

    static Seen watch(LivingEntity previous, LivingEntity target) {
        return BalanceArmorBase.watch(previous, target, Seen::new);
    }

    static float takeAmount(LivingEntity target, float fallback) {
        return BalanceArmorBase.takeAmount(target, fallback);
    }

    static net.minecraft.world.entity.Entity floorShot(DamageSource source, boolean blast) {
        return (net.minecraft.world.entity.Entity) floorShotOf(source, blast);
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

}
