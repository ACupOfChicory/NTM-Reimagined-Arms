package acup.gunfeel;

import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.ItemAttributeModifierEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * The balance's kit (stage 2, balance-by-progression.md 21.4): what a player carries on top of the armour may make
 * a fight about twice as long, not ten times.
 *
 * <ul>
 * <li>{@code balance.shield}: the player's shield. The shield mod (australium III) gives
 * {@code balance.shield.mod} (10) instead of NTM's 25, a shield flask {@code balance.shield.flask} (2) instead of 5,
 * all of it at most {@code balance.shield.cap} (30; NTM: 100 of flasks and the mod on top). It comes back
 * {@code balance.shield.delay} (100) ticks after the last hit, then {@code balance.shield.rate} (0.002)·t a tick, t the
 * ticks since (NTM: 60 and 0.005). NTM keeps its own count (5 a flask): with the key off its numbers come back.
 * <li>{@code balance.medx}: Med-X gives Resistance I for 60 s (NTM: III for 4 min); its recipe (balanceRecipes)
 * takes two plastic plates of the oil tier and two quartz instead of four quartz.
 * <li>{@code balance.superStimpak}: the super stimpak heals its 25 HP over 12 s, not at once (the Slowness stays).
 * <li>{@code balance.inserts} ({@code balance.inserts.bullets} 0.9): an armour insert takes at most a tenth off a
 * bullet (kevlar 0.9, SAPI 0.85, ESAPI 0.8, XSAPI 0.75, DU 0.85, ghiorsium 0.75 all become 0.9); its damage and blast
 * shares are NTM's.
 * <li>{@code balance.vanilla0}: the combat sets of the balance's table (BalanceArmor, those with a rating) have no
 * vanilla armour points and toughness, so vanilla's armour does not come on top of formula C (the mob's claws, a
 * skeleton's arrow). The joke sets (starmetal, the dineutronium ingot set) and the rest keep theirs.
 * </ul>
 */
public final class BalanceKit {
    private BalanceKit() {
    }

    /** NTM's numbers the balance's replace */
    public static final float NTM_MOD = 25F, NTM_FLASK = 5F, NTM_FLASK_LIMIT = 100F;

    /** the shield's numbers */
    public record Shield(float mod, float flask, float cap, int delay, float rate) {
        public static final Shield DEFAULT = new Shield(10F, 2F, 30F, 100, 0.002F);
    }

    private static boolean on(String key) {
        return acup.gunfeel.compat.Feature.BALANCE.on() && ReaimTimes.flag(key, true);
    }

    private static float number(String key, float def, float min, float max) {
        float v = ReaimTimes.number(key, def);
        return Float.isFinite(v) && v >= min && v <= max ? v : def;
    }

    public static boolean shieldOn() {
        return on("balance.shield");
    }

    public static Shield shield() {
        Shield d = Shield.DEFAULT;
        return new Shield(number("balance.shield.mod", d.mod(), 0F, 1000F), number("balance.shield.flask", d.flask(), 0F, 1000F),
                number("balance.shield.cap", d.cap(), 0F, 1000F), Math.round(number("balance.shield.delay", d.delay(), 0F, 72000F)),
                number("balance.shield.rate", d.rate(), 0F, 100F));
    }

    /**
     * The most shield: {@code stored} NTM's own count of flasks (5 a flask), {@code mod} what NTM's mod in the chestplate
     * gives (25, 0 without it).
     */
    public static float maxShield(float stored, float mod, Shield s) {
        float flasks = Math.max(0F, stored) / NTM_FLASK * s.flask();
        float fromMod = mod > 0F ? mod / NTM_MOD * s.mod() : 0F;
        return Math.min(s.cap(), flasks + fromMod);
    }

    /** NTM's count of flasks at which one more does nothing: the flasks alone reach the cap */
    public static float flaskLimit(Shield s) {
        if (!(s.flask() > 0F)) return 0F;
        return Math.min(NTM_FLASK_LIMIT, (float) Math.ceil(s.cap() / s.flask()) * NTM_FLASK);
    }

    public static boolean medxOn() {
        return on("balance.medx");
    }

    /** Med-X's effect: Resistance I for 60 s */
    public static MobEffectInstance medx() {
        return new MobEffectInstance(MobEffects.RESISTANCE, 1200, 0);
    }

    public static boolean superStimpakOn() {
        return on("balance.superStimpak");
    }

    /** the super stimpak's heal: its HP over this many ticks */
    public static final int STIMPAK_TICKS = 240;

    /** what is left to heal of each, and the ticks left */
    private static final Map<LivingEntity, float[]> HEALS = new WeakHashMap<>();

    /** heal {@code amount} over {@code STIMPAK_TICKS}, on top of what is left of an earlier one */
    public static synchronized void healOverTime(LivingEntity entity, float amount) {
        float[] h = HEALS.computeIfAbsent(entity, e -> new float[2]);
        h[0] += amount;
        h[1] = STIMPAK_TICKS;
    }

    /** one tick of the heal: what to heal now, and what is left after it */
    public static float healStep(float[] h) {
        if (!(h[0] > 0F) || !(h[1] > 0F)) return 0F;
        float now = h[0] / h[1];
        h[0] -= now;
        h[1] -= 1F;
        return now;
    }

    static synchronized void tickHeal(LivingEntity entity) {
        float[] h = HEALS.get(entity);
        if (h == null) return;
        if (entity.isDeadOrDying() || entity.isRemoved()) {
            HEALS.remove(entity);
            return;
        }
        float now = healStep(h);
        if (now > 0F) entity.heal(now);
        if (!(h[0] > 1e-4F) || !(h[1] > 0F)) HEALS.remove(entity);
    }

    static synchronized float healLeft(LivingEntity entity) {
        float[] h = HEALS.get(entity);
        return h == null ? 0F : h[0];
    }

    public static boolean insertsOn() {
        return on("balance.inserts");
    }

    /** an insert's share of a bullet's damage it leaves: NTM's, but not under {@code balance.inserts.bullets} */
    public static float insertBullets(float ntm, float floor) {
        return Math.max(ntm, floor);
    }

    public static float insertBulletsNow(float ntm) {
        if (!insertsOn()) return ntm;
        return insertBullets(ntm, number("balance.inserts.bullets", 0.9F, 0F, 1F));
    }

    public static boolean vanilla0On() {
        return on("balance.vanilla0") && BalanceArmor.on();
    }

    /** the piece is of a combat set of the balance's table (one with a rating) */
    static boolean combatPiece(ItemStack stack) {
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null || !id.getNamespace().equals("hbm")) return false;
        for (int i = 0; i < 4; i++) {
            String set = BalanceArmor.setOf(stack, i);
            if (set != null && BalanceArmor.rating(set) > 0F) return true;
        }
        return false;
    }

    private static void attributes(ItemAttributeModifierEvent e) {
        ItemStack stack = e.getItemStack();
        if (stack.isEmpty() || !combatPiece(stack) || !vanilla0On()) return;
        e.removeAllModifiersFor(Attributes.ARMOR);
        e.removeAllModifiersFor(Attributes.ARMOR_TOUGHNESS);
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(ItemAttributeModifierEvent.class, BalanceKit::attributes);
        NeoForge.EVENT_BUS.addListener(EntityTickEvent.Post.class, e -> {
            if (e.getEntity() instanceof LivingEntity living && !living.level().isClientSide() && !HEALS.isEmpty()) tickHeal(living);
        });
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.server.ServerStoppedEvent.class, e -> {
            synchronized (BalanceKit.class) {
                HEALS.clear();
            }
            if (acup.gunfeel.compat.Feature.BALANCE.on()) EuphImmunity.clear();
        });
    }
}
