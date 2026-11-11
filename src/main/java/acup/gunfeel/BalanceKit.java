package acup.gunfeel;

import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
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
 * all of it at most {@code balance.shield.cap} (16: the mod and three flasks; NTM: 100 of flasks and the mod on top).
 * The shield takes a hit's raw damage before the armour, and what it takes counts to the damage cap first: with Med-X
 * and a super stimpak this makes a fight about twice as long (notes/balance/work/scripts/kit_shield.py, the stand). It comes back
 * {@code balance.shield.delay} (100) ticks after the last hit, then {@code balance.shield.rate} (0.002)·t a tick, t the
 * ticks since (NTM: 60 and 0.005). NTM keeps its own count (5 a flask): with the key off its numbers come back.
 * <li>{@code balance.medx}: Med-X gives Resistance I for 60 s (NTM: III for 4 min); its recipe (balanceRecipes)
 * takes two plastic plates of the oil tier and two quartz instead of four quartz.
 * <li>{@code balance.superStimpak}: the super stimpak heals its 25 HP over 12 s, not at once (the Slowness stays);
 * another one on the same player starts it again, the more of the two left, not on top (balance.healStack).
 * <li>{@code balance.inserts} ({@code balance.inserts.bullets} 0.9): an armour insert takes at most a tenth off a
 * bullet (kevlar 0.9, SAPI 0.85, ESAPI 0.8, XSAPI 0.75, DU 0.85, ghiorsium 0.75 all become 0.9); its damage and blast
 * shares are NTM's.
 * <li>{@code balance.vanilla0}: the combat sets of the balance's table (BalanceArmor, those with a rating) have no
 * vanilla armour points and toughness, so vanilla's armour does not come on top of formula C (the mob's claws, a
 * skeleton's arrow). The joke set (the dineutronium ingot set; starmetal with balanceS.starmetal 0) and the rest keep
 * theirs. NTM's FBI agent
 * wears Security: it gets {@code balance.fbiArmor} (10) vanilla armour of its own instead, when it joins the world, so
 * it holds as before (the owner, 2026-11-10: a player's .44 takes 10-11 clicks, not 5).
 * </ul>
 */
public final class BalanceKit {
    private BalanceKit() {
    }

    /** NTM's numbers the balance's replace */
    public static final float NTM_MOD = 25F, NTM_FLASK = 5F, NTM_FLASK_LIMIT = 100F;

    /** the shield's numbers */
    public record Shield(float mod, float flask, float cap, int delay, float rate) {
        public static final Shield DEFAULT = new Shield(10F, 2F, 16F, 100, 0.002F);
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

    /**
     * heal {@code amount} over {@code STIMPAK_TICKS}: balance.healStack off (the default), the more of it and what is
     * left of an earlier one; on (X884), on top of it
     */
    public static synchronized void healOverTime(LivingEntity entity, float amount) {
        addHeal(HEALS.computeIfAbsent(entity, e -> new float[2]), amount, ReaimTimes.flag("balance.healStack", false));
    }

    /** one more heal of {@code amount} on {@code h} (left, ticks): added, or the more of the two ({@code stack} off) */
    public static void addHeal(float[] h, float amount, boolean stack) {
        // without stacking a weaker heal changes nothing (nor stretches what is left over a fresh 12 s)
        if (!stack && amount <= h[0]) return;
        h[0] = stack ? h[0] + amount : amount;
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

    /** the heal still to come is called off (the stand between its fights) */
    static synchronized void clearHeal(LivingEntity entity) {
        HEALS.remove(entity);
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

    static final Identifier FBI = Identifier.fromNamespaceAndPath("hbm", "entity_fbi");
    static final Identifier FBI_ARMOR = Identifier.fromNamespaceAndPath("ntmgunfeel", "balance_fbi_armor");

    /** the FBI agent's own vanilla armour on top of NTM's while the combat sets have none (0: none) */
    public static float fbiArmor() {
        return vanilla0On() ? number("balance.fbiArmor", 10F, 0F, 30F) : 0F;
    }

    private static void fbiJoins(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide() || !(e.getEntity() instanceof LivingEntity agent) || !FBI.equals(BuiltInRegistries.ENTITY_TYPE.getKey(agent.getType())))
            return;
        var armor = agent.getAttribute(Attributes.ARMOR);
        if (armor == null) return;
        armor.removeModifier(FBI_ARMOR);
        float add = fbiArmor();
        if (add > 0F) armor.addTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(FBI_ARMOR, add,
                net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE));
    }

    /**
     * A hit the formula does not take (melee, arrows, fire and the rest: NTM's numbers) on a combat set that
     * balance.vanilla0 left with no vanilla armour: the armour and toughness of the set's pieces worn, for vanilla's
     * armour to take them as before the balance (the FBI agent has its share back already: balance.fbiArmor). {0, 0}
     * for a hit of the formula.
     */
    public static float[] vanillaBack(LivingEntity target, net.minecraft.world.damagesource.DamageSource source) {
        if (!vanilla0On() || target.level().isClientSide() || BalanceArmor.category(source) != null) return NO_ARMOR;
        double armor = 0, tough = 0;
        for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack stack = target.getItemBySlot(slot);
            if (stack.isEmpty() || !combatPiece(stack)) continue;
            var mods = stack.getPrototype().get(net.minecraft.core.component.DataComponents.ATTRIBUTE_MODIFIERS);
            if (mods == null) continue;
            for (var m : mods.modifiers()) {
                // as vanilla puts it on: for the slot it is worn in
                if (m.modifier().operation() != AttributeModifier.Operation.ADD_VALUE || !m.slot().test(slot)) continue;
                if (m.attribute().is(Attributes.ARMOR)) armor += m.modifier().amount();
                else if (m.attribute().is(Attributes.ARMOR_TOUGHNESS)) tough += m.modifier().amount();
            }
        }
        if (FBI.equals(BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()))) armor = Math.max(0, armor - fbiArmor());
        return armor <= 0 && tough <= 0 ? NO_ARMOR : new float[] {(float) armor, (float) tough};
    }

    private static final float[] NO_ARMOR = {0F, 0F};

    private static void attributes(ItemAttributeModifierEvent e) {
        ItemStack stack = e.getItemStack();
        if (stack.isEmpty() || !combatPiece(stack) || !vanilla0On()) return;
        e.removeAllModifiersFor(Attributes.ARMOR);
        e.removeAllModifiersFor(Attributes.ARMOR_TOUGHNESS);
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(ItemAttributeModifierEvent.class, BalanceKit::attributes);
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent.class, BalanceKit::fbiJoins);
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
