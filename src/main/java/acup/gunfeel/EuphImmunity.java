package acup.gunfeel;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Euphemium's invulnerability with a pool (stage 2, balance-by-progression.md 21.7 E3; server settings
 * balance.immune.*): a hard kill, not an impossible one.
 *
 * <ul>
 * <li>{@code balance.immune.mode}: {@code pool} (the balance's) or {@code ntm} (every hit on a full euphemium set is
 * called off, as NTM does);
 * <li>{@code balance.immune.pool} (150; X865: 2000): bullets, beams and blasts (PHYS, EN, EXPL) on the full set are still called
 * off, but each takes from the pool what it would have done to a DNS-class set (formula C at S 186, tier 7.5; one hit
 * at most damageCapClick of the wearer's health). The pool comes back over {@code balance.immune.recover} once no
 * hit took from it for {@code balance.immune.refillPause} (5 s). Other damage (fire, falling, gas, radiation, nuclear blasts) is called off as NTM does and
 * takes nothing;
 * <li>{@code balance.immune.window} (15 s): the emptied pool opens a window: every hit goes through, to the set's
 * rating (balanceS.euphemium, 280) and the damage cap, and the set's Regeneration and Resistance (NTM: 128) are gone
 * for it, or the window would give nothing;
 * <li>{@code balance.immune.hold} (10 s; 0: X873): a weapon's hit in the window keeps it open at least this long
 * after it, for the three sets (DNS and FAU too): a gun that does not kill in 15 s goes on as long as it keeps hitting,
 * instead of a whole round of invulnerability and a new pool (the TTK had no pair between 60 and 120 s);
 * <li>the reload's hold (X896: none): a gun whose shot and reload from empty take longer than
 * the hold (Henry and Lincoln: 10.5 s) holds the window, and the pool's refill pause, for that and a second more, so
 * the reload does not close the window or give the pool back (DNS 1000-4800 s, euphemium never, before);
 * <li>{@code balance.immune.recover} (60 s): after the window the pool fills back from 0 over this time, the set
 * invulnerable meanwhile; then it is full and the round starts again.
 * <li>the tomb's radon gas (BlockGasRadonTomb) takes the invulnerability off entirely while the wearer is in it, with
 * the Regeneration and Resistance: its radiation then counts (NTM: 0.5 RAD a tick inside, 1000 RAD hurt for 1000).
 * </ul>
 * The state is kept by the player's id for the server's run (a relog does not refill the pool); with
 * balance.immune.shared one pool a player for the three kits (another kit put on keeps where the pool is).
 *
 * <p>DNS and FAU (the 240-hour sets) get the same round with their own numbers ({@link Kit}; keys
 * balance.immune.dns.* and balance.immune.fau.*: mode, pool, window, recover): the pool takes what the hit would do to the
 * set itself (its rating, balanceS.dns 186 and balanceS.fau 168), the window lets hits through to that rating. They
 * have no Regeneration or Resistance of their own to drop. Other damage (fire, falling, gas, radiation, nuclear) is not
 * touched by their pool: NTM's own numbers of the set, as without it. The tomb's radon takes their pool off too.
 *
 * <p>Our AI's soldiers ({@link #pooled}: AiBots, not other mobs) get the three pools too, with the same numbers, by the
 * mob's id while it lives (AiBots forgets it when the mob is gone). NTM gives a mob in euphemium no invulnerability at
 * all (its check is a player's), so a soldier's euphemium goes by {@link #kitOf} as DNS and FAU do. Its set's charge is
 * full from /armsai and NTM spends it on a player's tick only. The window's message is a player's only.
 */
public final class EuphImmunity extends EuphImmunityBase {
    private EuphImmunity() {
    }

    // ---------------------------------------------------------------- the game's side

    public static boolean on() {
        return on(Kit.EUPHEMIUM);
    }

    public static boolean on(Kit kit) {
        return onOf(kit, BalanceOn.ON);
    }

    /** NTM's own DNS checks (every hit but a blast called off, blasts down to 0.001) are off: modes formula and pool */
    public static boolean dnsFormula() {
        return dnsFormulaOf(BalanceOn.ON);
    }

    static State state(Kit kit, LivingEntity player, long now) {
        return stateOf(kit, EntityBody.of(player), now);
    }

    /** the tomb's radon gas touched the entity this tick */
    public static void inRadon(Entity entity, long now) {
        inRadonOf(EntityBody.of(entity), now);
    }

    static boolean radon(Entity entity, long now) {
        return radonOf(EntityBody.of(entity), now);
    }

    /** what the hit would do to the kit's drain set (formula C; one hit at most damageCapClick of the health), or 0 */
    static float drain(Kit kit, LivingEntity player, DamageSource source, float amount, String cat) {
        if (cat == null) return 0F;
        // the final scheme: the pool takes the scheme's damage of the hit (balance.final)
        float scheme = BalanceFinal.drain(player, source);
        return drainOf(kit, EntityBody.of(player), source, amount, cat, scheme);
    }

    /**
     * NTM's euphemium check found the full set on {@code player} for this hit: true calls it off (NTM's way), false
     * lets it through.
     */
    public static boolean absorbs(Player player, DamageSource source, float amount) {
        if (!on()) return true;
        return absorbs(Kit.EUPHEMIUM, player, source, amount);
    }

    /**
     * A hit on {@code player} in the full set of {@code kit} with its pool on: true calls it off. Euphemium calls off
     * every hit in the pool and the recovery; DNS and FAU only bullets, beams and blasts (the rest is NTM's).
     */
    public static boolean absorbs(Kit kit, LivingEntity player, DamageSource source, float amount) {
        return absorbsOf(kit, EntityBody.of(player), source, amount, BalanceFinalOn.ON);
    }

    /**
     * ticks the gun in the hitter's hand takes from a shot to the next one after a reload from empty (NTM's reload: the
     * begin, a cycle for each round after the first of a magazine loaded a round at a time, the end, and the empty
     * gun's cocking), the longest of its configs (akimbo); 0: no gun
     */
    static int reach(DamageSource source) {
        if (!(source.getEntity() instanceof net.minecraft.world.entity.LivingEntity hitter)) return 0;
        return GunsPort.GUNS.reach(EntityBody.of(hitter), acup.gunfeel.host.Body.MAIN_HAND);
    }

    /** the NtmGuns port, its reach in the hitter's hand */
    private static final class GunsPort {
        static final acup.gunfeel.host.NtmGuns GUNS = CoreServices.load(acup.gunfeel.host.NtmGuns.class, acup.gunfeel.host.NtmGuns.NONE);
    }

    /** the same of a gun's stack (0: not an NTM gun); NtmGunsPort.reach reads it so (NTM's reads stay in this class) */
    static int reachOf(net.minecraft.world.item.ItemStack stack) {
        if (!(stack.getItem() instanceof com.hbm.items.weapon.sedna.ItemGunBaseNT gun)) return 0;
        int most = 0;
        for (int c = 0; c < gun.getConfigCount(); c++) {
            com.hbm.items.weapon.sedna.Receiver[] recs = gun.getConfig(stack, c).getReceivers(stack);
            if (recs.length == 0) continue;
            com.hbm.items.weapon.sedna.Receiver rec = recs[0];
            com.hbm.items.weapon.sedna.mags.IMagazine<?> mag = rec.getMagazine(stack);
            int cycles = mag instanceof com.hbm.items.weapon.sedna.mags.MagazineSingleReload ? Math.max(0, mag.getCapacity(stack) - 1) : 0;
            int t = rec.getDelayAfterFire(stack) + rec.getReloadCockOnEmptyPre(stack) + rec.getReloadBeginDuration(stack)
                    + cycles * rec.getReloadCycleDuration(stack) + rec.getReloadEndDuration(stack) + rec.getReloadCockOnEmptyPost(stack);
            most = Math.max(most, t);
        }
        return most;
    }

    /**
     * the full set of DNS or FAU (charged, as NTM counts it) with its pool on, or null; an AI soldier's euphemium too (a
     * player's goes by NTM's own check, MixinEuphImmunity)
     */
    public static Kit kitOf(LivingEntity player) {
        return kitOfWorn(EntityBody.of(player), BalanceOn.ON);
    }

    /** NTM's reads for core's ArmorSuits (ArmorSuitsPort): the suits' full sets, a powered piece */
    static final class Reads {
        private Reads() {
        }

        /** NTM's piercing of the current hit, DT or DR */
        static float pierce(LivingEntity e, int which) {
            return switch (which) {
                case acup.gunfeel.host.HurtNow.PIERCE_DT -> ((com.hbm.interfaces.NtmDamageContext) e).hbm$pierceDT();
                case acup.gunfeel.host.HurtNow.PIERCE_DR -> ((com.hbm.interfaces.NtmDamageContext) e).hbm$pierceDR();
                default -> throw new IllegalArgumentException("no pierce " + which);
            };
        }

        /** NTM's own check of the full set of a suit (ArmorSuits.DNS, DIGAMMA, EUPHEMIUM), the pieces' charge with it */
        static boolean fullSet(LivingEntity e, int suit) {
            return com.hbm.items.armor.ArmorSuitEffects.hasFullSet(e, switch (suit) {
                case acup.gunfeel.host.ArmorSuits.DNS -> com.hbm.items.armor.ModArmorItem.Suit.DNS;
                case acup.gunfeel.host.ArmorSuits.DIGAMMA -> com.hbm.items.armor.ModArmorItem.Suit.DIGAMMA;
                case acup.gunfeel.host.ArmorSuits.EUPHEMIUM -> com.hbm.items.armor.ModArmorItem.Suit.EUPHEMIUM;
                default -> throw new IllegalArgumentException("no suit " + suit);
            });
        }

        /** an NTM armour piece with power */
        static boolean powered(net.minecraft.world.item.Item item) {
            return item instanceof com.hbm.items.armor.ModArmorItem armor && armor.maxPower() > 0L;
        }
    }

    /** the wearers the pools are for: players and our AI's soldiers (AiBots), not other mobs */
    public static boolean pooled(Entity e) {
        return e instanceof Player || acup.gunfeel.ai.AiBots.get(e) != null;
    }

    /** NTM's early check (allowsAttack) of a hit on DNS or FAU: true calls it off */
    public static boolean early(Kit kit, LivingEntity player, DamageSource source, float amount) {
        return earlyOf(kit, EntityBody.of(player), source, amount, BalanceFinalOn.ON);
    }

    /**
     * The hit reaches NTM's accepted damage (beams and blasts come with ignoreEarlyCancellation: the early check's
     * calling off does not stop them): true takes it to 0. The early check's decision on the same hit, or a new one.
     */
    public static boolean accepted(Kit kit, LivingEntity player, DamageSource source, float amount) {
        return acceptedOf(kit, EntityBody.of(player), source, amount, BalanceFinalOn.ON);
    }

    /** the set's Regeneration and Resistance must not be on the wearer now: the window, or the radon gas */
    public static boolean vulnerable(Player player) {
        if (!on()) return false;
        return vulnerableOf(EntityBody.of(player));
    }

    /** the set's own effects (NTM: amplifier 127) */
    public static void dropEffects(LivingEntity player) {
        dropSetEffects(EntityBody.of(player));
    }

    /** the stand: the wearer's pool now and the phase */
    static String describe(Player player) {
        return describe(Kit.EUPHEMIUM, player);
    }

    static String describe(Kit kit, LivingEntity player) {
        return describeOf(kit, EntityBody.of(player));
    }

    /** the stand: the pool now (NaN: no state, or not in the pool's phase) */
    static float poolOf(Kit kit, LivingEntity player) {
        return poolOf(kit, EntityBody.of(player));
    }

    public static void forget(LivingEntity player) {
        forgetOf(EntityBody.of(player));
    }

    public static void clear() {
        clearAll();
    }
}
