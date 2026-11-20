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
    };
}
