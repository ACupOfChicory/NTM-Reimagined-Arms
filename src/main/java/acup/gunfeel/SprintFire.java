package acup.gunfeel;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.hbm.extprop.HbmPlayerProps;
import com.hbm.handler.HbmKeybinds.EnumKeybind;
import com.hbm.items.weapon.sedna.GunConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.factory.Lego;

import acup.gunfeel.compat.Feature;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * sprintFireDelay (server, ntm-gunfeel-server.properties, global and "<gun>.sprintFireDelay"): no shot right after a
 * sprint, as TaCZ's sprint_time. While the player sprints the sprint time grows to the gun's sprintFireTime (seconds,
 * "<gun>.sprintFireTime"), and after it shrinks back at the same rate; the gun does not fire while it is above zero.
 *
 * <p>The client stops the sprint while a fire key is held or the gun aims (TaCZ does the same), otherwise a sprinting
 * player could never shoot. A click the delay holds back is kept while its key is held and fires once the time runs
 * out; a held burst waits in NTM's cooldown and goes on by itself (MixinSprintFire*).
 */
public final class SprintFire {
    public static final float DEFAULT_TIME = 0.15F;

    private static final class State {
        float time;
        int tick = Integer.MIN_VALUE;
        // a click held back: its gun, config index, receiver and key (GUN_PRIMARY / GUN_SECONDARY)
        Item gun;
        int index, receiver;
        EnumKeybind key;
    }

    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();
    // the key NTM is handling a press of right now (server thread), null outside a press
    private static EnumKeybind pressing;

    private SprintFire() {
    }

    static String id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    /** The delay is on for this gun (the server's file, or its copy on a connected client). */
    public static boolean on(Item gun) {
        if (!Feature.SPRINT_FIRE.on()) return false;
        String id = id(gun);
        return ReaimTimes.flag(id + ".sprintFireDelay", ReaimTimes.flag("sprintFireDelay", false));
    }

    public static float time(Item gun) {
        String id = id(gun);
        return Math.max(0F, ReaimTimes.number(id + ".sprintFireTime", ReaimTimes.number("sprintFireTime", DEFAULT_TIME)));
    }

    /** Sprint time after one tick: grows while sprinting, shrinks after, within 0..max (TaCZ's tickSprint). */
    static float step(float time, boolean sprinting, float max, float dt) {
        float t = sprinting ? Math.min(max, time + dt) : time - dt;
        // whole ticks in float leave a crumb (7e-9) that would hold the shot one tick longer
        return t < 1e-4F ? 0F : t;
    }

    // ---- server

    /** Once a tick for the gun in the main hand, before NTM's state machine (ItemGunBaseNT.inventoryTick). */
    public static void tick(ItemStack stack, LivingEntity entity) {
        if (!(entity instanceof Player player) || entity.level().isClientSide()) return;
        Item gun = stack.getItem();
        State s = STATES.computeIfAbsent(player.getUUID(), k -> new State());
        int now = player.tickCount;
        if (s.tick == now) return;
        // a tick without a gun in hand (or another player object after respawn): TaCZ counts no sprint time then
        if (now != s.tick + 1) s.time = 0F;
        s.tick = now;
        if (!on(gun)) {
            s.time = 0F;
            s.key = null;
            return;
        }
        float before = s.time;
        s.time = step(s.time, player.isSprinting() && !player.isCrouching(), time(gun), 0.05F);
        if (TestLog.server && before != s.time) TestLog.s(-1, "sprintFire", "time", s.time, "sprinting", player.isSprinting());
    }

    /** After NTM's state machine: a held-back click fires once the sprint time is out, if its key is still held. */
    public static void retry(ItemStack stack, LivingEntity entity) {
        if (!(entity instanceof Player player) || entity.level().isClientSide()) return;
        State s = STATES.get(player.getUUID());
        if (s == null || s.key == null) return;
        if (s.gun != stack.getItem() || !(stack.getItem() instanceof ItemGunBaseNT gun) || !held(stack, s.index, s.key)) {
            s.key = null;
            return;
        }
        if (s.time > 0F || ItemGunBaseNT.getState(stack, s.index) != ItemGunBaseNT.GunState.IDLE) return;
        int index = s.index, receiver = s.receiver;
        s.key = null;
        GunConfig config = gun.getConfig(stack, index);
        if (TestLog.server) TestLog.s(-1, "sprintFire", "retry", s.time);
        Lego.clickReceiver(stack, new ItemGunBaseNT.LambdaContext(config, player, player.getInventory(), index), receiver);
    }

    private static boolean held(ItemStack stack, int index, EnumKeybind key) {
        return key == EnumKeybind.GUN_SECONDARY ? ItemGunBaseNT.getSecondary(stack, index) : ItemGunBaseNT.getPrimary(stack, index);
    }

    public static void pressing(EnumKeybind key) {
        pressing = key;
    }

    static boolean blocked(LivingEntity entity, ItemStack stack) {
        if (!(entity instanceof Player player) || entity.level().isClientSide() || !on(stack.getItem())) return false;
        State s = STATES.get(player.getUUID());
        return s != null && s.time > 0F;
    }

    /** Lego.clickReceiver on an idle gun: false = no shot now (the click is kept while its key is held). */
    public static boolean allowClick(ItemStack stack, ItemGunBaseNT.LambdaContext ctx, int receiver) {
        // only a click from a key press: NTM calls clickReceiver from nothing else, but a mod might
        if (pressing != EnumKeybind.GUN_PRIMARY && pressing != EnumKeybind.GUN_SECONDARY) return true;
        LivingEntity entity = ctx.entity();
        if (!blocked(entity, stack)) return true;
        State s = STATES.get(entity.getUUID());
        s.gun = stack.getItem();
        s.index = ctx.configIndex();
        s.receiver = receiver;
        s.key = pressing;
        if (TestLog.server) TestLog.s(-1, "sprintFire", "held", s.time);
        return false;
    }

    /** NTM's held burst (GunStateDecider.deciderAutoRefire after a cooldown): false = wait a tick in the cooldown. */
    public static boolean allowRefire(ItemStack stack, ItemGunBaseNT.LambdaContext ctx) {
        return !blocked(ctx.entity(), stack);
    }

    // ---- client

    /** The local player may not sprint: an NTM gun with the delay, a fire key held or aiming (MixinSprintStop). */
    public static boolean stopsSprint(Player player) {
        // the server's rule, not the player's settings: kept with the mod's own effects switched off too
        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof ItemGunBaseNT) || !on(stack.getItem())) return false;
        if (ItemGunBaseNT.getIsAiming(stack)) return true;
        HbmPlayerProps props = HbmPlayerProps.getData(player);
        return props.getKeyPressed(EnumKeybind.GUN_PRIMARY) || props.getKeyPressed(EnumKeybind.GUN_SECONDARY);
    }
}
