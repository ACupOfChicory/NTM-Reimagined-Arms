package acup.gunfeel;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import com.hbm.interfaces.NtmDamageContext;
import com.hbm.util.DamageResistanceHandler;

import net.minecraft.network.chat.Component;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
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

    private static final Map<Kit, Map<UUID, State>> STATES = new java.util.EnumMap<>(Kit.class);
    private static final Map<Entity, Long> IN_RADON = new WeakHashMap<>();

    static {
        for (Kit k : Kit.values()) STATES.put(k, new HashMap<>());
    }

    public static boolean on() {
        return on(Kit.EUPHEMIUM);
    }

    public static boolean on(Kit kit) {
        return acup.gunfeel.compat.Feature.BALANCE.on() && kit.mode().equals("pool");
    }

    /** NTM's own DNS checks (every hit but a blast called off, blasts down to 0.001) are off: modes formula and pool */
    public static boolean dnsFormula() {
        return acup.gunfeel.compat.Feature.BALANCE.on() && !Kit.DNS.mode().equals("ntm");
    }

    /** the kit each player's pool was last of (balance.immune.shared) */
    private static final Map<UUID, Kit> LAST_KIT = new HashMap<>();

    /**
     * balance.immune.shared (true; false: X884): one pool a player for the three kits. The kit put on takes where the
     * last one was (carry), not a fresh pool of its own: DNS emptied, then FAU on, was a full pool of 45 at once.
     */
    static boolean shared() {
        return ReaimTimes.flag("balance.immune.shared", true);
    }

    static synchronized State state(Kit kit, LivingEntity player, long now) {
        UUID id = player.getUUID();
        Map<UUID, State> own = STATES.get(kit);
        if (shared()) {
            Kit was = LAST_KIT.put(id, kit);
            State from = was == null || was == kit ? null : STATES.get(was).get(id);
            if (from != null) own.put(id, carry(from, pool(was), pool(kit), now));
        } else {
            // off: no kit remembered, so turning it back on carries nothing stale
            LAST_KIT.remove(id);
        }
        return own.computeIfAbsent(id, u -> new State(pool(kit).size(), now));
    }

    /** the tomb's radon gas touched the entity this tick */
    public static synchronized void inRadon(Entity entity, long now) {
        IN_RADON.put(entity, now);
    }

    static synchronized boolean radon(Entity entity, long now) {
        Long t = IN_RADON.get(entity);
        return t != null && now - t <= 1;
    }

    /** what the hit would do to the kit's drain set (formula C; one hit at most damageCapClick of the health), or 0 */
    static float drain(Kit kit, LivingEntity player, DamageSource source, float amount, String cat) {
        if (cat == null) return 0F;
        // the final scheme: the pool takes the scheme's damage of the hit (balance.final)
        float scheme = BalanceFinal.drain(player, source);
        if (!Float.isNaN(scheme)) {
            float click = ReaimTimes.number("damageCapClick", 0.6F);
            return Float.isFinite(click) && click > 0F && !HeadDamage.onHead(player) ? Math.min(scheme, click * player.getMaxHealth()) : scheme;
        }
        float[] st = drainSet(kit);
        // NTM's accepted damage takes an electric hit on a powered chestplate five times (EntityDamageUtil)
        if (kit != Kit.EUPHEMIUM && source.is(com.hbm.lib.ModDamageTypes.ELECTRIC)
                && player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).getItem() instanceof com.hbm.items.armor.ModArmorItem armor && armor.maxPower() > 0L)
            amount *= BalanceArmor.electricPowered(5F);
        NtmDamageContext ctx = (NtmDamageContext) player;
        float s = DamageResistanceHandler.CATEGORY_ENERGY.equals(cat) ? st[0] * Math.min(1F, ReaimTimes.number("balanceEnShare", 0.75F)) : st[0];
        boolean squeeze = !DamageResistanceHandler.CATEGORY_EXPLOSION.equals(cat) || amount <= ReaimTimes.number("balanceExplRawLimit", 60F) || BalanceArmor.bulletBlastNow();
        // a weapon's blast as BalanceArmor takes it (balanceBlastPower, set around the blast's hurt)
        float power = DamageResistanceHandler.CATEGORY_EXPLOSION.equals(cat) && squeeze ? BalanceArmor.BLAST_POWER.get() : 1F;
        // by the kit's tier (8, 9): the power itself, a rocket's direct hit too
        int tier = kit == Kit.EUPHEMIUM ? 9 : 8;
        if (power > 1F) power = BalanceArmor.pieceBlastPower(tier, amount, power);
        // the gun table's floor of the shot's pair, as BalanceArmor takes it
        boolean expl = DamageResistanceHandler.CATEGORY_EXPLOSION.equals(cat);
        float floor = !expl || BalanceArmor.bulletBlastNow() ? BalanceGuns.floorOf(BalanceArmor.floorShot(source, expl), tier) : 1F;
        float drain = Math.min(amount, BalanceArmorRule.full(new BalanceArmorRule.Hit(amount * power, ctx.hbm$pierceDT(), ctx.hbm$pierceDR(), squeeze), s, st[1],
                BalanceArmorRule.Tune.DEFAULT, floor));
        float click = ReaimTimes.number("damageCapClick", 0.6F);
        if (Float.isFinite(click) && click > 0F) drain = Math.min(drain, click * player.getMaxHealth());
        return drain;
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
        // a soldier is not called off what nothing may be (the void, /kill): a player has its own ways out, it has none
        if (!(player instanceof Player) && source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY)) return false;
        long now = player.level().getGameTime();
        if (radon(player, now)) {
            // the set's tick takes them off too, but not before this hit
            if (kit == Kit.EUPHEMIUM) dropEffects(player);
            return false;
        }
        // the gas's damage takes the pool HP for HP, as a shot (Fable №2, 2.4)
        boolean gas = gasDrains();
        // DNS and FAU: shots and blasts only; euphemium calls off every hit, its pool drained by melee as before
        String cat = kit == Kit.EUPHEMIUM ? BalanceArmor.categoryAny(source) : BalanceArmor.category(source);
        if (kit != Kit.EUPHEMIUM && cat == null && !gas) return false;
        State s = state(kit, player, now);
        Pool p = pool(kit);
        float drain = gas ? amount * (float) BalanceFinalRule.HP / player.getMaxHealth() : drain(kit, player, source, amount, cat);
        // only a shot, an IR beam, a blast or the gas holds the window: for euphemium cat takes melee and the rest too
        boolean keeps = gas || (kit == Kit.EUPHEMIUM ? BalanceArmor.category(source) != null : cat != null);
        Phase before;
        boolean off;
        synchronized (EuphImmunity.class) {
            before = phase(s, now);
            off = hit(s, now, drain, gas || cat != null, keeps, p, keeps && !gas ? reach(source) : 0);
        }
        if (before == Phase.POOL && phase(s, now + 1) == Phase.WINDOW) {
            if (kit == Kit.EUPHEMIUM) dropEffects(player);
            if (player instanceof net.minecraft.server.level.ServerPlayer sp) sp.sendSystemMessage(Component.translatable(kit.message, p.window() / 20), true);
        }
        return off;
    }

    /** the hurt under way is the gas's and the pools take it, the final scheme on (Fable №2, 2.4) */
    static boolean gasDrains() {
        return acup.gunfeel.gas.GasServer.gasNow() && BalanceFinal.on();
    }

    /**
     * ticks the gun in the hitter's hand takes from a shot to the next one after a reload from empty (NTM's reload: the
     * begin, a cycle for each round after the first of a magazine loaded a round at a time, the end, and the empty
     * gun's cocking), the longest of its configs (akimbo); 0: no gun
     */
    static int reach(DamageSource source) {
        if (!(source.getEntity() instanceof net.minecraft.world.entity.LivingEntity hitter)) return 0;
        return reachOf(hitter.getMainHandItem());
    }

    /** the same of a gun's stack (0: not an NTM gun) */
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
        if (!acup.gunfeel.compat.Feature.BALANCE.on()) return null;
        if (com.hbm.items.armor.ArmorSuitEffects.hasFullSet(player, com.hbm.items.armor.ModArmorItem.Suit.DNS)) return on(Kit.DNS) ? Kit.DNS : null;
        if (com.hbm.items.armor.ArmorSuitEffects.hasFullSet(player, com.hbm.items.armor.ModArmorItem.Suit.DIGAMMA)) return on(Kit.FAU) ? Kit.FAU : null;
        if (!(player instanceof Player) && com.hbm.items.armor.ArmorSuitEffects.hasFullSet(player, com.hbm.items.armor.ModArmorItem.Suit.EUPHEMIUM))
            return on(Kit.EUPHEMIUM) ? Kit.EUPHEMIUM : null;
        return null;
    }

    /** the wearers the pools are for: players and our AI's soldiers (AiBots), not other mobs */
    public static boolean pooled(Entity e) {
        return e instanceof Player || acup.gunfeel.ai.AiBots.get(e) != null;
    }

    /** the last decision on a hit of DNS or FAU: the hit's source (by identity, not held), its tick, called off */
    private record Decided(int source, long tick, boolean off) {
    }

    // by the player's id, for the same hit's actuallyHurt: one drain a hit (the source not held: it holds its attacker)
    private static final Map<UUID, Decided> DECIDED = new HashMap<>();

    /** NTM's early check (allowsAttack) of a hit on DNS or FAU: true calls it off */
    public static boolean early(Kit kit, LivingEntity player, DamageSource source, float amount) {
        boolean off = absorbs(kit, player, source, amount);
        synchronized (EuphImmunity.class) {
            DECIDED.put(player.getUUID(), new Decided(System.identityHashCode(source), player.level().getGameTime(), off));
        }
        return off;
    }

    /**
     * The hit reaches NTM's accepted damage (beams and blasts come with ignoreEarlyCancellation: the early check's
     * calling off does not stop them): true takes it to 0. The early check's decision on the same hit, or a new one.
     */
    public static boolean accepted(Kit kit, LivingEntity player, DamageSource source, float amount) {
        Decided d;
        synchronized (EuphImmunity.class) {
            d = DECIDED.get(player.getUUID());
        }
        if (d != null && d.source() == System.identityHashCode(source) && d.tick() == player.level().getGameTime()) return d.off();
        return absorbs(kit, player, source, amount);
    }

    /** the set's Regeneration and Resistance must not be on the wearer now: the window, or the radon gas */
    public static boolean vulnerable(Player player) {
        if (!on()) return false;
        long now = player.level().getGameTime();
        if (radon(player, now)) return true;
        State s;
        synchronized (EuphImmunity.class) {
            s = STATES.get(Kit.EUPHEMIUM).get(player.getUUID());
        }
        return s != null && phase(s, now) == Phase.WINDOW;
    }

    /** the set's own effects (NTM: amplifier 127) */
    public static void dropEffects(LivingEntity player) {
        MobEffectInstance regen = player.getEffect(MobEffects.REGENERATION), res = player.getEffect(MobEffects.RESISTANCE);
        if (regen != null && regen.getAmplifier() >= 100) player.removeEffect(MobEffects.REGENERATION);
        if (res != null && res.getAmplifier() >= 100) player.removeEffect(MobEffects.RESISTANCE);
    }

    /** the stand: the wearer's pool now and the phase */
    static String describe(Player player) {
        return describe(Kit.EUPHEMIUM, player);
    }

    static synchronized String describe(Kit kit, LivingEntity player) {
        State s = STATES.get(kit).get(player.getUUID());
        if (s == null) return "нет";
        long now = player.level().getGameTime();
        return phase(s, now) + " " + Math.round(poolAt(s, now, pool(kit)));
    }

    /** the stand: the pool now (NaN: no state, or not in the pool's phase) */
    static synchronized float poolOf(Kit kit, LivingEntity player) {
        State s = STATES.get(kit).get(player.getUUID());
        if (s == null) return Float.NaN;
        long now = player.level().getGameTime();
        return phase(s, now) == Phase.POOL ? poolAt(s, now, pool(kit)) : Float.NaN;
    }

    public static synchronized void forget(LivingEntity player) {
        for (Map<UUID, State> m : STATES.values()) m.remove(player.getUUID());
        DECIDED.remove(player.getUUID());
        LAST_KIT.remove(player.getUUID());
    }

    public static synchronized void clear() {
        for (Map<UUID, State> m : STATES.values()) m.clear();
        IN_RADON.clear();
        DECIDED.clear();
        LAST_KIT.clear();
    }
}
