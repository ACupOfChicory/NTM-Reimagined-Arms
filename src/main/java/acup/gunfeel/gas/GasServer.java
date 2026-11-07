package acup.gunfeel.gas;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.hbm.extprop.HbmLivingProps;
import com.hbm.handler.ArmorUtil;
import com.hbm.handler.EquipmentProtections;
import com.hbm.hazard.HazardClass;
import com.hbm.lib.ModDamageTypes;
import com.hbm.util.ArmorRegistry;
import com.hbm.util.DamageResistanceHandler;

import acup.gunfeel.ReaimTimes;
import acup.gunfeel.smoke.SmokeClouds;
import acup.gunfeel.smoke.SmokeGrid;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The gases' work on the living, on the server, once a level tick: the concentration of each gas at a head, the
 * protection the armour gives against it, one step of {@link GasModel}, and what comes of it (damage, slowing, the
 * mask's wear, the black lung, the effects, the packets to a player). Alongside it the floor mustard leaves.
 *
 * <p>Only what is in a gas or still carries a dose is looked at: the {@code AFFLICTED} set of a level.
 */
public final class GasServer {
    static final Identifier SLOW_ID = Identifier.fromNamespaceAndPath("ntmgunfeel", "gas_slow");
    private static final Map<Level, Set<LivingEntity>> AFFLICTED = new ConcurrentHashMap<>();
    private static final Map<Level, GasFloor> FLOORS = new ConcurrentHashMap<>();
    private static final Map<UUID, int[]> SENT = new ConcurrentHashMap<>();
    /** Whole units of filter wear that are put on a mask at once (a write every tick would resend the helmet). */
    private static final float WEAR_LUMP = 25F;

    // the last tick's cost, for the stand: ns spent, entities looked at
    public static volatile long lastNanos, totalNanos, ticks;

    private GasServer() {
    }

    public static GasFloor floor(Level level) {
        return FLOORS.computeIfAbsent(level, l -> new GasFloor());
    }

    public static void forget(Level level) {
        AFFLICTED.remove(level);
        FLOORS.remove(level);
    }

    public static void forget(UUID player) {
        SENT.remove(player);
    }

    /** A thing that came into the world with a dose in it (loaded) is looked at again. */
    public static void joined(LivingEntity e) {
        if (e.level() instanceof ServerLevel level && e.getExistingDataOrNull(GasAttach.STATE.get()) != null) afflicted(level).add(e);
    }

    private static Set<LivingEntity> afflicted(Level level) {
        return AFFLICTED.computeIfAbsent(level, l -> Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>()));
    }

    public static boolean has(LivingEntity e) {
        return e.getExistingDataOrNull(GasAttach.STATE.get()) != null;
    }

    public static GasState state(LivingEntity e) {
        return e.getData(GasAttach.STATE.get());
    }

    /** Once a level tick. */
    public static void tick(ServerLevel level) {
        Set<LivingEntity> held = AFFLICTED.get(level);
        SmokeClouds all = SmokeClouds.get(level);
        boolean clouds = all != null && !all.isEmpty();
        if (!clouds && (held == null || held.isEmpty())) return;
        long t0 = System.nanoTime();
        Map<LivingEntity, float[]> in = new IdentityHashMap<>();
        if (clouds) {
            long time = level.getGameTime();
            for (SmokeClouds.Cloud c : all.clouds()) {
                GasKind gas = GasKind.of(c.kind());
                if (gas == null || !c.grid.occupied()) continue;
                if (gas == GasKind.MUSTARD && time % GasRules.FLOOR_EVERY == 0) markFloor(level, c);
                int[] b = c.grid.box();
                AABB box = new AABB(c.baseX() + b[0], c.baseY() + b[1], c.baseZ() + b[2], c.baseX() + b[3] + 1, c.baseY() + b[4] + 1, c.baseZ() + b[5] + 1);
                for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, e -> e.isAlive() && !e.isSpectator())) {
                    BlockPos head = BlockPos.containing(e.getEyePosition());
                    float v = Math.min(1F, c.cell(head.getX(), head.getY(), head.getZ()));
                    if (v <= 0F) continue;
                    float[] conc = in.computeIfAbsent(e, k -> new float[GasState.N]);
                    conc[gas.ordinal()] = Math.max(conc[gas.ordinal()], v);
                }
            }
            if (time % GasRules.FLOOR_EVERY == 0) floor(level).dry(GasRules.FLOOR_EVERY);
        }
        Set<LivingEntity> set = afflicted(level);
        List<LivingEntity> todo = new ArrayList<>(set);
        for (LivingEntity e : in.keySet()) if (!set.contains(e)) todo.add(e);
        float[] none = new float[GasState.N];
        for (LivingEntity e : todo) {
            if (e.isRemoved() || !e.isAlive()) {
                set.remove(e);
                continue;
            }
            if (e.isSpectator()) continue;
            float[] c = in.getOrDefault(e, none);
            GasState s = state(e);
            set.add(e);
            step(level, e, s, c);
            if (!s.active()) {
                clear(e);
                set.remove(e);
            }
        }
        long spent = System.nanoTime() - t0;
        lastNanos = spent;
        totalNanos += spent;
        ticks++;
    }

    private static void clear(LivingEntity e) {
        AttributeInstance speed = e.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) speed.removeModifier(SLOW_ID);
        e.removeData(GasAttach.STATE.get());
        if (e instanceof ServerPlayer p) sendState(p, new GasState(), true);
    }

    // one living thing, one tick
    private static void step(ServerLevel level, LivingEntity e, GasState s, float[] c) {
        float[] p = new float[GasState.N];
        // the mustard on the floor counts even where the air is clean
        float floorG = e.onGround() ? floor(level).at(e.blockPosition().asLong()) : 0F;
        protection(e, s, c, p, floorG > 0F);
        GasModel.tick(s, c, p, floorG, bareSlots(e), e.isInWaterOrRain(), level.getRandom()::nextInt);
        wearFilters(e, s);
        // slow: an attribute modifier, the same for the player and the mob
        AttributeInstance speed = e.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) {
            if (s.slow > 0F) speed.addOrUpdateTransientModifier(new AttributeModifier(SLOW_ID, -s.slow, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            else if (speed.getModifier(SLOW_ID) != null) speed.removeModifier(SLOW_ID);
        }
        if (s.noSprint && e.isSprinting()) e.setSprinting(false);
        if (s.nausea) refresh(e, MobEffects.NAUSEA);
        if (s.darkness) refresh(e, MobEffects.DARKNESS);
        if (s.blackLung > 0) HbmLivingProps.incrementBlackLung(e, s.blackLung);
        if (s.coughNow && e instanceof ServerPlayer sp) {
            PacketDistributor.sendToPlayer(sp, new GasPayloads.Cough(GasRules.COUGH_TICKS, ReaimTimes.flag(GasRules.COUGH_AIM_KEY, true)));
            level.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.PLAYER_HURT, SoundSource.PLAYERS, 0.4F, 0.6F + level.getRandom().nextFloat() * 0.2F);
        }
        if (s.smellNow && e instanceof ServerPlayer sp) sp.sendSystemMessage(Component.translatable("gas.ntmgunfeel.phosgene_smell"), true);
        float hp = GasModel.takeDamage(s);
        if (hp > 0F) hurt(level, e, hp, damageType(level, s));
        if (e instanceof ServerPlayer sp) sendState(sp, s, false);
    }

    private static Holder<DamageType> damageType(ServerLevel level, GasState s) {
        var types = level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE);
        return types.get(ModDamageTypes.CLOUD).map(h -> (Holder<DamageType>) h).orElseGet(() -> types.getOrThrow(net.minecraft.world.damagesource.DamageTypes.MAGIC));
    }

    /** Hurts without the damage cooldown getting in the way, and without starting one that would shield it from bullets. */
    private static void hurt(ServerLevel level, LivingEntity e, float hp, Holder<DamageType> type) {
        int cooldown = e.invulnerableTime;
        e.invulnerableTime = 0;
        e.hurtServer(level, new DamageSource(type), hp);
        e.invulnerableTime = cooldown;
    }

    private static void refresh(LivingEntity e, Holder<net.minecraft.world.effect.MobEffect> effect) {
        MobEffectInstance now = e.getEffect(effect);
        if (now == null || now.getDuration() < 60) e.addEffect(new MobEffectInstance(effect, 100, 0, false, false, false));
    }

    // ---- protection

    // the protection against each gas that is about, and the wear it puts on the filter (kept in the state until a lump)
    private static void protection(LivingEntity e, GasState s, float[] c, float[] p, boolean floor) {
        ItemStack head = e.getItemBySlot(EquipmentSlot.HEAD);
        for (GasKind g : GasKind.values()) {
            int i = g.ordinal();
            boolean about = c[i] > 0F || floor && g == GasKind.MUSTARD;
            if (!about || head.isEmpty() && g != GasKind.MUSTARD) continue;
            if (g == GasKind.MUSTARD) {
                boolean helmet = !head.isEmpty() && ArmorRegistry.hasProtection(e, EquipmentSlot.HEAD, HazardClass.GAS_BLISTERING);
                boolean suit = ArmorUtil.checkForHazmat(e);
                p[i] = GasModel.mustardProtection(helmet, suit, helmet ? filterLeft(head) : 1F);
                if (helmet && c[i] > 0F) s.wear[i] += GasModel.filterWear(g, c[i]);
            } else if (ArmorRegistry.hasProtection(e, EquipmentSlot.HEAD, g.protection)) {
                p[i] = GasModel.coefficient(filterLeft(head));
                s.wear[i] += GasModel.filterWear(g, c[i]);
            }
        }
    }

    /** The share of the mask's filter left; 1 for a helmet that has none to wear. */
    public static float filterLeft(ItemStack head) {
        if (!EquipmentProtections.isMask(head)) return 1F;
        ItemStack filter = ArmorUtil.getGasMaskFilter(head);
        if (filter.isEmpty()) return 1F;
        int max = filter.getMaxDamage();
        return max <= 0 ? 1F : (float) (max - filter.getDamageValue()) / max;
    }

    private static void wearFilters(LivingEntity e, GasState s) {
        float sum = 0F;
        for (float w : s.wear) sum += w;
        if (sum < WEAR_LUMP) return;
        int n = (int) sum;
        for (int i = 0; i < s.wear.length; i++) s.wear[i] = 0F;
        ArmorUtil.damageGasMaskFilter(e, n);
        s.worn += n;
    }

    /** The armour slots that leave the skin bare to acid (head, chest, legs, feet). */
    public static int bareSlots(LivingEntity e) {
        boolean[] present = new boolean[4], power = new boolean[4];
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        for (int i = 0; i < 4; i++) {
            ItemStack a = e.getItemBySlot(slots[i]);
            present[i] = !a.isEmpty();
            power[i] = present[i] && DamageResistanceHandler.getSetResistanceContaining(a.getItem()) != null;
        }
        return GasModel.bareSlots(present, power);
    }

    // ---- the floor under a mustard cloud

    private static void markFloor(ServerLevel level, SmokeClouds.Cloud c) {
        GasFloor floor = floor(level);
        int[] b = c.grid.box();
        SmokeGrid g = c.grid;
        for (int y = Math.max(1, b[1]); y <= b[4]; y++) for (int z = b[2]; z <= b[5]; z++) for (int x = b[0]; x <= b[3]; x++) {
            if (g.density(x, y, z) < GasRules.FLOOR_MARK_C || g.closed(x, y, z) || !g.closed(x, y - 1, z)) continue;
            floor.mark(BlockPos.asLong(c.baseX() + x, c.baseY() + y, c.baseZ() + z), GasRules.MUSTARD_FLOOR_G);
        }
    }

    // ---- the player's side

    /** The HUD's numbers go to the player when they changed (every five ticks at most), and once more when they are gone. */
    static void sendState(ServerPlayer p, GasState s, boolean gone) {
        int[] before = SENT.get(p.getUUID());
        if (gone) {
            if (before != null) {
                SENT.remove(p.getUUID());
                PacketDistributor.sendToPlayer(p, new GasPayloads.State(new float[GasState.N], 0F, false));
            }
            return;
        }
        int[] now = {Math.round(s.dose[GasState.CHLORINE] * 10F), Math.round(s.dose[GasState.MUSTARD] * 10F), Math.round(s.dose[GasState.ACID] * 10F),
                Math.round(s.k * 100F), s.noSprint ? 1 : 0};
        if (before != null && (java.util.Arrays.equals(before, now) || p.tickCount % 5 != 0)) return;
        SENT.put(p.getUUID(), now);
        PacketDistributor.sendToPlayer(p, new GasPayloads.State(s.dose.clone(), s.k, s.noSprint));
    }

    /** Whether natural healing is shut for this one (the mustard's dose is at its first level). */
    public static boolean noRegen(LivingEntity e) {
        GasState s = e.getExistingDataOrNull(GasAttach.STATE.get());
        return s != null && s.dose[GasState.MUSTARD] >= GasRules.MUSTARD_L1;
    }

    /** Vanilla milk drunk: the phosgene dose by half. */
    public static void milk(LivingEntity e) {
        GasState s = e.getExistingDataOrNull(GasAttach.STATE.get());
        if (s != null) GasModel.milk(s);
    }

    /** The acid's corrosion on this one, 0 to 0.75 (0 on a client: the armour is worked out on the server). */
    public static float corrosion(LivingEntity e) {
        GasState s = e.getExistingDataOrNull(GasAttach.STATE.get());
        return s == null ? 0F : s.k;
    }
}
