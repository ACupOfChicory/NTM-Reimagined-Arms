package acup.gunfeel;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.GunConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.Receiver;
import com.hbm.items.weapon.sedna.mags.IMagazine;
import com.hbm.items.weapon.sedna.mags.MagazineSingleTypeBase;

import acup.gunfeel.mixin.ReceiverAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import acup.gunfeel.host.GunBalance;

/**
 * The balance's gun damage (stage 1b, server setting balanceGuns): a shot of an NTM gun does NTM's damage times its
 * gun and ammo's mult, and its ammo's armour threshold negation and piercing get the pair's additions
 * (BalanceGunsRule, the table {@code ntmgunfeel-balance-guns.tsv}; on the stands, BalanceKeys.dev, a file of the same
 * name in config/ is read instead).
 * On another game's server the client goes by that server's table (ServerSettings), its own file aside.
 *
 * <p>Put where a gun's shot is made (the bullet's and the beam's shot constructors: NTM's standard fire, the Tau's
 * charged shot, the accelerators'): the shooter's gun is the one in its hand. Everything NTM works out of the shot's
 * damage goes with it: the hit, an explosive round's blast, a beam's. Submunitions (made from a shot's damage) are not
 * multiplied again. The pair's additions go with the shot's own hits only: an explosive round's blast and the Tesla
 * Cannon's chained beams have their ammo's own (the blast's damage source is the shooter, not the shot; no pair of
 * explosive ammo has additions). Whoever fires: players, NTM's mobs (FBI agents), AI bots.
 *
 * <p>A gun's own line changes its receivers (the pause after a shot, auto fire, the magazine, the base damage) on
 * both sides; the setting turns them back.
 */
public final class BalanceGuns extends BalanceGunsBase {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("ntm-gunfeel");

    private BalanceGuns() {
    }

    // ---------------------------------------------------------------- the table

    static BalanceGunsRule.Table table() { return BalanceGunsBase.table(); }

    static String gunName(Item item) {
        return BalanceGunsBase.gunName(item);
    }

    public static String ammoName(BulletConfig b) {
        return BalanceGunsBase.ammoName(b);
    }

    /** what the pair does to a shot of {@code gun} with {@code ammo}, whether the setting is on or not */
    static BalanceGunsRule.Shot pair(Item gunItem, BulletConfig ammo) {
        return BalanceGunsBase.pair(gunItem, ammo);
    }

    /**
     * a shot of {@code gunItem}'s with {@code from} as heavy as one with {@code as}: the share of its damage that makes
     * it so (the ammo's own and the table's), 1 when the balance is off
     */
    static float as(Item gunItem, BulletConfig from, BulletConfig as) {
        return BalanceGunsBase.as(gunItem, from, as);
    }

    /** where a gun's ammo got its numbers (BalanceGunsRule.Resolved.from), after a shot or pair() */
    static Map<String, BalanceGunsRule.Resolved> from(Item gunItem) {
        return BalanceGunsBase.from(gunItem);
    }

    /** the gun a shot of {@code shooter}'s comes from: the NTM gun in its main hand, else in its off hand */
    static Item gunOf(LivingEntity shooter) {
        return (Item) BalanceGunsBase.gunOf(shooter);
    }

    /** The table's live game and NTM reads, still owned by BALANCE. */
    static final class Reads implements GunBalance {
        @Override public Object itemId(Object item) { return BuiltInRegistries.ITEM.getKey((Item) item); }
        @Override public Object stackId(Object stack) { return BuiltInRegistries.ITEM.getKey(((ItemStack) stack).getItem()); }
        @Override public String namespace(Object id) { return ((Identifier) id).getNamespace(); }
        @Override public String path(Object id) { return ((Identifier) id).getPath(); }
        @Override public boolean isGun(Object item) { return item instanceof ItemGunBaseNT; }
        @Override public Object freshStack(Object item) { return new ItemStack((Item) item); }
        @Override public void walk(Object item, Object rawStack, Walk visit, List<Object> out, Map<Object, Float> click) {
            ItemGunBaseNT gun = (ItemGunBaseNT) item;
            ItemStack stack = (ItemStack) rawStack;
            for (int c = 0; c < gun.getConfigCount(); c++) {
                GunConfig config = gun.getConfig(stack, c);
                for (Receiver rec : config.getReceivers(stack)) {
                    for (BulletConfig b : AmmoLock.accepted(rec.getMagazine(stack))) {
                        if (!visit.begin(b, out, click)) continue;
                        float damage = visit.damage(b, rec.getBaseDamage(stack), this);
                        visit.finish(b, damage, rec.getSplitProjectiles(stack), click);
                    }
                }
            }
        }
        @Override public Object ammoItem(Object ammo) { return ((BulletConfig) ammo).ammoItem; }
        @Override public Object ammoStack(Object ammo) { return ((BulletConfig) ammo).ammoStack(); }
        @Override public int ammoId(Object ammo) { return ((BulletConfig) ammo).id; }
        @Override public boolean empty(Object stack) { return ((ItemStack) stack).isEmpty(); }
        @Override public Object item(Object stack) { return ((ItemStack) stack).getItem(); }
        @Override public float damageMult(Object ammo) { return ((BulletConfig) ammo).damageMult; }
        @Override public int min(Object ammo) { return ((BulletConfig) ammo).projectilesMin; }
        @Override public int max(Object ammo) { return ((BulletConfig) ammo).projectilesMax; }
        @Override public Object mainHand(Object shooter) { return ((LivingEntity) shooter).getMainHandItem(); }
        @Override public Object offHand(Object shooter) { return ((LivingEntity) shooter).getOffhandItem(); }
    }

    // ---------------------------------------------------------------- a shot

    /** the additions of a shot that has some, by the projectile (its hits' damage source has it as the direct entity) */
    private static final Map<Entity, float[]> EXTRA = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * A shot of {@code shooter}'s with {@code ammo} has been made with NTM's damage {@code damage}: its damage after the
     * balance; the projectile keeps the pair's additions.
     */
    public static float shot(Entity projectile, LivingEntity shooter, BulletConfig ammo, float damage) {
        if (shooter == null || ammo == null || !on()) return damage;
        Item gun = gunOf(shooter);
        if (gun == null) return damage;
        // the scheme's pair (balance.final), whatever the table has for it
        BalanceFinal.fired(projectile, shooter, gun, ammo);
        BalanceGunsRule.Shot s = safePair(gun, ammo);
        if (s.none()) return damage;
        if (s.neg() != 0F || s.pen() != 0F || s.floor() != 1F || s.tier() >= 0)
            EXTRA.put(projectile, new float[] {s.neg(), s.pen(), s.floor(), s.from(), s.tier()});
        if (!s.under().isEmpty()) UNDER.put(projectile, s);
        return damage * s.mult();
    }

    /** the pairs of the shots whose line has under= (by the projectile) */
    private static final Map<Entity, BalanceGunsRule.Shot> UNDER = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * the pair's under= (X904: the line's own numbers on every set) on a set of
     * {@code tier} as changes to the hit, {damage share, neg added, pen added} (the shot left with the line's mult,
     * neg and pen); null for none
     */
    public static float[] underOf(Entity direct, int tier) {
        if (direct == null) return null;
        BalanceGunsRule.Shot s = UNDER.get(direct);
        if (s == null) return null;
        return BalanceGunsRule.underShift(s, tier);
    }

    /**
     * a hit's additions to its threshold negation and piercing, its floor and the tier the floor counts from (the
     * projectile's, when it is a balanced shot)
     */
    public static float[] extra(Entity direct) {
        return direct == null ? null : EXTRA.get(direct);
    }

    /**
     * the table's floor of the hit's shot (X873: none) on a set of {@code tier} (1: none, or
     * a tier below the pair's), times balanceArmorTierFloor's share for the pair's tier on that set's (no less than the
     * table's least floor)
     */
    public static float floorOf(Entity direct, int tier) {
        return floorOf(extra(direct), tier);
    }

    /** the tooltip's mult of an ammo in a gun (1 with the setting off) */
    public static float tooltipMult(Item gun, BulletConfig ammo) {
        if (ammo == null || !on()) return 1F;
        return safePair(gun, ammo).mult();
    }

    /** pair(), and NTM's damage for a shot it cannot work out (a shot must never fail over the table) */
    private static BalanceGunsRule.Shot safePair(Item gun, BulletConfig ammo) {
        try {
            return pair(gun, ammo);
        } catch (RuntimeException e) {
            if (FAILED.add(gun)) LOGGER.warn("The balance's gun table: no numbers for {} with config {}", gun, ammo.id, e);
            return BalanceGunsRule.Shot.NONE;
        }
    }

    private static final java.util.Set<Item> FAILED = Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    // ---------------------------------------------------------------- the receivers

    /** what a changed receiver had: its pause, auto fire and base damage as NTM made it */
    private record Before(int delay, boolean auto, float dmg) {
    }

    private static final Map<Receiver, Before> BEFORE = new IdentityHashMap<>();
    /** a changed magazine's rounds as NTM made it (by the magazine: receivers may share one) */
    private static final Map<MagazineSingleTypeBase, Integer> MAGS = new IdentityHashMap<>();
    private static boolean applied;
    private static BalanceGunsRule.Table appliedTable;

    /** the receivers as the setting and the table have them (called every tick on both sides; cheap when nothing changed) */
    public static void sync() {
        synchronized (BalanceGunsBase.class) {
            boolean want = on();
            BalanceGunsRule.Table t = table();
            if (want == applied && (!want || t == appliedTable)) return;
            Map<ItemGunBaseNT, BalanceGunsRule.GunLine> guns = new java.util.LinkedHashMap<>();
            if (want) {
                for (Map.Entry<String, BalanceGunsRule.GunLine> e : t.guns().entrySet()) {
                    Item item = BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("hbm", "gun_" + e.getKey()));
                    if (!(item instanceof ItemGunBaseNT gun)) continue;
                    // a gun's stack needs the items' components: on the client's loading screen they are not bound yet
                    // (new ItemStack throws), so the lines wait for a later tick
                    if (!item.builtInRegistryHolder().areComponentsBound()) return;
                    guns.put(gun, e.getValue());
                }
            }
            restore();
            guns.forEach(BalanceGuns::change);
            applied = want;
            appliedTable = t;
        }
    }

    private static void change(ItemGunBaseNT gun, BalanceGunsRule.GunLine line) {
        if (line.delay() == null && line.auto() == null && line.mag() == null && line.dmg() == null) return;
        ItemStack stack = new ItemStack(gun);
        for (int c = 0; c < gun.getConfigCount(); c++) {
            GunConfig cfg = gun.getConfig(stack, c);
            for (Receiver rec : cfg.getReceivers(stack)) {
                ReceiverAccess a = (ReceiverAccess) rec;
                IMagazine<?> mag = a.gunfeel$magazine();
                BEFORE.putIfAbsent(rec, new Before(a.gunfeel$delay(), a.gunfeel$auto(), a.gunfeel$damage()));
                if (line.mag() != null && mag instanceof MagazineSingleTypeBase s) MAGS.putIfAbsent(s, s.capacity);
                if (line.delay() != null) a.gunfeel$setDelay(line.delay());
                if (line.auto() != null) a.gunfeel$setAuto(line.auto());
                if (line.dmg() != null) a.gunfeel$setDamage(line.dmg());
                if (line.mag() != null && mag instanceof MagazineSingleTypeBase s) s.capacity = line.mag();
            }
        }
    }

    private static void restore() {
        for (Map.Entry<Receiver, Before> e : BEFORE.entrySet()) {
            ReceiverAccess a = (ReceiverAccess) e.getKey();
            Before b = e.getValue();
            a.gunfeel$setDelay(b.delay());
            a.gunfeel$setAuto(b.auto());
            a.gunfeel$setDamage(b.dmg());
        }
        for (Map.Entry<MagazineSingleTypeBase, Integer> e : MAGS.entrySet()) e.getKey().capacity = e.getValue();
        BEFORE.clear();
        MAGS.clear();
    }
}
