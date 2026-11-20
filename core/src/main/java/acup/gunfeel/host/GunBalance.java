package acup.gunfeel.host;

import java.util.List;
import java.util.Map;

/** Live gun-table reads; all tokens are the version's original objects, never wrappers. */
public interface GunBalance {
    /** Item identifier, possibly null (26.2: ITEM.getKey). */
    Object itemId(Object item);
    /** Stack item's identifier, registry read first (26.2: ITEM, stack.getItem, getKey). */
    Object stackId(Object stack);
    /** Identifier namespace (26.2: Identifier.getNamespace). */
    String namespace(Object id);
    /** Identifier path (26.2: Identifier.getPath). */
    String path(Object id);
    /** Whether the item is a gun (26.2: instanceof ItemGunBaseNT). */
    boolean isGun(Object item);
    /** A fresh stack, only when requested (26.2: new ItemStack). */
    Object freshStack(Object item);
    /** Visit configs, receivers and accepted ammo live (26.2: getConfigCount/getConfig/getReceivers/getMagazine, AmmoLock.accepted). */
    void walk(Object gun, Object stack, Walk visit, List<Object> out, Map<Object, Float> click);

    /** Reusable core continuation; the version keeps receiver types local and reads base/split only when needed. */
    interface Walk {
        /** Record the first occurrence (26.2: before getBaseDamage, false skips this ammo). */
        boolean begin(Object ammo, List<Object> out, Map<Object, Float> click);
        /** Core's damage (26.2: after getBaseDamage, before getSplitProjectiles). */
        float damage(Object ammo, float base, GunBalance reads);
        /** Finish the calculation and write the click map (26.2: after getSplitProjectiles). */
        void finish(Object ammo, float damage, float split, Map<Object, Float> click);
    }
    /** Ammo item, possibly null (26.2: BulletConfig.ammoItem). */
    Object ammoItem(Object ammo);
    /** Ammo stack; may throw (26.2: BulletConfig.ammoStack). */
    Object ammoStack(Object ammo);
    /** Numeric config id (26.2: BulletConfig.id). */
    int ammoId(Object ammo);
    /** Stack emptiness (26.2: ItemStack.isEmpty). */
    boolean empty(Object stack);
    /** Stack item (26.2: ItemStack.getItem). */
    Object item(Object stack);
    /** Ammo damage multiplier (26.2: BulletConfig.damageMult). */
    float damageMult(Object ammo);
    /** Minimum projectile count (26.2: BulletConfig.projectilesMin). */
    int min(Object ammo);
    /** Maximum projectile count (26.2: BulletConfig.projectilesMax). */
    int max(Object ammo);
    /** Main-hand stack (26.2: LivingEntity.getMainHandItem). */
    Object mainHand(Object shooter);
    /** Off-hand stack (26.2: LivingEntity.getOffhandItem). */
    Object offHand(Object shooter);

    /** Final scheme shot callback (26.2: BalanceFinal.fired). */
    void fired(Object projectile, Object shooter, Object gun, Object ammo);
    /** Failed pair warning (26.2: LOGGER.warn with BulletConfig.id). */
    void warnPair(Object gun, Object ammo, RuntimeException error);
    /** Named gun lookup (26.2: ITEM.getValue of hbm:gun_name). */
    Object gun(String name);
    /** Bound item components (26.2: builtInRegistryHolder().areComponentsBound). */
    boolean bound(Object item);
    /** Gun config count (26.2: ItemGunBaseNT.getConfigCount). */
    int configCount(Object gun);
    /** Config receiver tokens (26.2: ItemGunBaseNT.getConfig, GunConfig.getReceivers). */
    Object[] receivers(Object gun, Object stack, int index);
    /** Receiver magazine field (26.2: ReceiverAccess.gunfeel$magazine). */
    Object magazine(Object receiver);
    /** Mutable single-type magazine (26.2: instanceof MagazineSingleTypeBase). */
    boolean singleMagazine(Object magazine);
    /** Receiver delay field (26.2: ReceiverAccess.gunfeel$delay). */
    int delay(Object receiver);
    /** Receiver auto field (26.2: ReceiverAccess.gunfeel$auto). */
    boolean auto(Object receiver);
    /** Receiver damage field (26.2: ReceiverAccess.gunfeel$damage). */
    float damage(Object receiver);
    /** Magazine capacity field (26.2: MagazineSingleTypeBase.capacity). */
    int capacity(Object magazine);
    /** Write receiver delay (26.2: ReceiverAccess.gunfeel$setDelay). */
    void delay(Object receiver, int value);
    /** Write receiver auto (26.2: ReceiverAccess.gunfeel$setAuto). */
    void auto(Object receiver, boolean value);
    /** Write receiver damage (26.2: ReceiverAccess.gunfeel$setDamage). */
    void damage(Object receiver, float value);
    /** Write magazine capacity (26.2: MagazineSingleTypeBase.capacity). */
    void capacity(Object magazine, int value);

    GunBalance MISSING = new GunBalance() {
        private UnsupportedOperationException missing() { return new UnsupportedOperationException("no GunBalance here"); }
        @Override public Object itemId(Object item) { throw missing(); }
        @Override public Object stackId(Object stack) { throw missing(); }
        @Override public String namespace(Object id) { throw missing(); }
        @Override public String path(Object id) { throw missing(); }
        @Override public boolean isGun(Object item) { throw missing(); }
        @Override public Object freshStack(Object item) { throw missing(); }
        @Override public void walk(Object gun, Object stack, Walk visit, List<Object> out, Map<Object, Float> click) { throw missing(); }
        @Override public Object ammoItem(Object ammo) { throw missing(); }
        @Override public Object ammoStack(Object ammo) { throw missing(); }
        @Override public int ammoId(Object ammo) { throw missing(); }
        @Override public boolean empty(Object stack) { throw missing(); }
        @Override public Object item(Object stack) { throw missing(); }
        @Override public float damageMult(Object ammo) { throw missing(); }
        @Override public int min(Object ammo) { throw missing(); }
        @Override public int max(Object ammo) { throw missing(); }
        @Override public Object mainHand(Object shooter) { throw missing(); }
        @Override public Object offHand(Object shooter) { throw missing(); }
        @Override public void fired(Object projectile, Object shooter, Object gun, Object ammo) { throw missing(); }
        @Override public void warnPair(Object gun, Object ammo, RuntimeException error) { throw missing(); }
        @Override public Object gun(String name) { throw missing(); }
        @Override public boolean bound(Object item) { throw missing(); }
        @Override public int configCount(Object gun) { throw missing(); }
        @Override public Object[] receivers(Object gun, Object stack, int index) { throw missing(); }
        @Override public Object magazine(Object receiver) { throw missing(); }
        @Override public boolean singleMagazine(Object magazine) { throw missing(); }
        @Override public int delay(Object receiver) { throw missing(); }
        @Override public boolean auto(Object receiver) { throw missing(); }
        @Override public float damage(Object receiver) { throw missing(); }
        @Override public int capacity(Object magazine) { throw missing(); }
        @Override public void delay(Object receiver, int value) { throw missing(); }
        @Override public void auto(Object receiver, boolean value) { throw missing(); }
        @Override public void damage(Object receiver, float value) { throw missing(); }
        @Override public void capacity(Object magazine, int value) { throw missing(); }
    };
}
