package acup.gunfeel.ai;

import org.jspecify.annotations.Nullable;

import com.hbm.handler.HbmKeybinds.EnumKeybind;
import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.mags.IMagazine;
import com.hbm.items.weapon.sedna.mags.MagazineSingleTypeBase;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * An AI mob's hands on its NTM gun: it turns to where it is told to aim, or follows a target it sees, with a person's
 * eye and hand ({@link AiAim}, {@link AiSkill}: the server's aiSkill), holds and lets go of the trigger as NTM's keys
 * (the same way a player's gun is worked, so the gun's own timing, recoil and our bullets all apply), reloads when told
 * or empty, and keeps the gun's clock running (a mob's held item does not tick by itself). Rounds are counted against
 * {@link AiAmmo}. Server thread, from the mob's goal.
 */
public final class AiGunner {
    /** a shot meets its target this much later than its flight, on average (a bullet moves a tick at a time) */
    static final double LEAVES = 0.5;
    /** the cone kept clear of other AI mobs before a shot (the gun's spread and the aim's error), degrees */
    static final double CLEAR_CONE = 3.0;
    /** whether it holds fire with another AI mob in the line (off only for the stands' "before") */
    static boolean checkLine = true;

    private final Mob mob;
    public final AiAmmo ammo;
    private @Nullable Vec3 aim;
    private boolean wantFire, wantReload, held, reloadHeld;
    /** the eye and the hand; whether it follows a target (and which) or looks at a place */
    final AiAim sight = new AiAim();
    private boolean tracking;
    private int tracked = Integer.MIN_VALUE;
    /** for the stands: a perfect hand, at once exactly here, always on target */
    @Nullable
    Vec3 exact;
    /** shots let go (trigger pulls), for the stands */
    public int pulls;
    /** ticks in a row it would have shot but another AI mob was in the line; ticks of that in all (the stands) */
    public int blockedTicks, holds;

    AiGunner(Mob mob, AiAmmo ammo) {
        this.mob = mob;
        this.ammo = ammo;
    }

    /** a place to look at (not a target it follows), or null to stop aiming */
    public void aim(@Nullable Vec3 at) {
        if (tracking) {
            tracking = false;
            sight.retarget();
        }
        aim = at;
    }

    /**
     * A target in sight, its chest seen this tick: it aims where it expects the bullet to meet it, from what it saw
     * its skill's lag ago (AiAim).
     */
    public void track(int id, Vec3 chest, long tick) {
        AiSkill skill = AiSkill.current();
        if (!tracking || id != tracked) sight.retarget();
        tracking = true;
        tracked = id;
        sight.see(id, chest.x, chest.y, chest.z, tick, skill, mob.getX(), mob.getZ());
        double flight = LEAVES + chest.distanceTo(mob.getEyePosition()) / bulletSpeed();
        aim = sight.lead(tick, skill, flight) ? new Vec3(sight.spotX, sight.spotY, sight.spotZ) : chest;
    }

    public void fire(boolean on) {
        wantFire = on;
    }

    public void reload() {
        wantReload = true;
    }

    public @Nullable ItemGunBaseNT gun() {
        return mob.getMainHandItem().getItem() instanceof ItemGunBaseNT g ? g : null;
    }

    @SuppressWarnings("rawtypes")
    static @Nullable IMagazine magazine(ItemGunBaseNT gun, ItemStack stack) {
        try {
            return gun.getConfig(stack, 0).getReceivers(stack)[0].getMagazine(stack);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** rounds in the gun, -1 without a gun whose rounds are counted */
    @SuppressWarnings("rawtypes")
    public int loaded() {
        ItemStack stack = mob.getMainHandItem();
        ItemGunBaseNT gun = gun();
        IMagazine mag = gun == null ? null : magazine(gun, stack);
        return mag instanceof MagazineSingleTypeBase ? mag.getAmount(stack, null) : -1;
    }

    /** how fast its gun's bullets fly, blocks a tick: NTM's speed, and faster by bulletSpeed as MixinBulletServer makes it */
    @SuppressWarnings("rawtypes")
    double bulletSpeed() {
        ItemStack stack = mob.getMainHandItem();
        ItemGunBaseNT gun = gun();
        IMagazine mag = gun == null ? null : magazine(gun, stack);
        try {
            if (mag != null && mag.getType(stack, null) instanceof BulletConfig b && b.velocity > 0F)
                return b.velocity >= 5F ? b.velocity * acup.gunfeel.ReaimTimes.number("bulletSpeed", 1.6F) : b.velocity;
        } catch (RuntimeException e) {
            // a magazine that wants an inventory: NTM's usual speed
        }
        return 16;
    }

    @SuppressWarnings("rawtypes")
    public int capacity() {
        ItemStack stack = mob.getMainHandItem();
        ItemGunBaseNT gun = gun();
        IMagazine mag = gun == null ? null : magazine(gun, stack);
        return mag == null ? 0 : mag.getCapacity(stack);
    }

    public boolean reloading() {
        return gun() != null && ItemGunBaseNT.getState(mob.getMainHandItem(), 0) == ItemGunBaseNT.GunState.RELOADING;
    }

    /** nothing in the gun and nothing to reload with */
    public boolean dry() {
        return loaded() == 0 && !ammo.any();
    }

    /** fills the gun from nothing, not from the reserve (a mob that comes in loaded) */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void loadFree() {
        ItemStack stack = mob.getMainHandItem();
        ItemGunBaseNT gun = gun();
        IMagazine mag = gun == null ? null : magazine(gun, stack);
        if (mag == null) return;
        mag.reloadAction(stack, null);
        ammo.lastLoaded = mag instanceof MagazineSingleTypeBase ? mag.getAmount(stack, null) : -1;
    }

    /** let go of every key: the mob stops shooting at once */
    public void release() {
        wantFire = false;
        ItemGunBaseNT gun = gun();
        if (gun == null) return;
        ItemStack stack = mob.getMainHandItem();
        if (held) key(gun, stack, EnumKeybind.GUN_PRIMARY, false);
        if (reloadHeld) key(gun, stack, EnumKeybind.RELOAD, false);
        held = reloadHeld = false;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    void tick(ServerLevel level) {
        ItemGunBaseNT gun = gun();
        if (gun == null) return;
        ItemStack stack = mob.getMainHandItem();
        boolean aligned = turn(level.getGameTime());

        ItemGunBaseNT.GunState state = ItemGunBaseNT.getState(stack, 0);
        int loaded = loaded();
        if (reloadHeld) {
            key(gun, stack, EnumKeybind.RELOAD, false);
            reloadHeld = false;
        }
        if (state == ItemGunBaseNT.GunState.RELOADING) wantReload = false;
        boolean shoot = wantFire && aim != null && aligned && loaded != 0;
        if (shoot && checkLine && AiBots.friendInLine(level, mob, mob.getEyePosition(), aim, CLEAR_CONE)) {
            // one of its own between it and the target: it holds fire
            shoot = false;
            blockedTicks++;
            holds++;
        } else if (shoot || !wantFire) blockedTicks = 0;
        if (shoot) {
            if (!held) {
                key(gun, stack, EnumKeybind.GUN_PRIMARY, true);
                held = true;
                pulls++;
            } else if (state == ItemGunBaseNT.GunState.IDLE) {
                // a gun that fires once a pull is ready again: let go, pull again next tick
                key(gun, stack, EnumKeybind.GUN_PRIMARY, false);
                held = false;
            }
        } else if (held) {
            key(gun, stack, EnumKeybind.GUN_PRIMARY, false);
            held = false;
        }
        if (!held && state == ItemGunBaseNT.GunState.IDLE && (wantReload || loaded == 0) && ammo.any()
                && loaded >= 0 && loaded < capacity()) {
            key(gun, stack, EnumKeybind.RELOAD, true);
            reloadHeld = true;
        }

        gun.inventoryTick(stack, level, mob, EquipmentSlot.MAINHAND);

        IMagazine mag = magazine(gun, stack);
        if (mag instanceof MagazineSingleTypeBase) {
            int now = mag.getAmount(stack, null);
            int keep = ammo.settle(now);
            if (keep != now) mag.setAmount(stack, keep);
        }
    }

    /**
     * Turns the mob towards the aim with its hand (AiAim.turn); whether the spot it expects the target at is on the
     * barrel now (the trigger may be down).
     */
    private boolean turn(long tick) {
        if (aim == null) return false;
        Vec3 eye = mob.getEyePosition();
        AiSkill skill = AiSkill.current();
        float y, p;
        boolean on;
        if (exact != null) {
            y = AiAim.yawTo(eye.x, eye.z, exact.x, exact.z);
            p = AiAim.pitchTo(eye.x, eye.y, eye.z, exact.x, exact.y, exact.z);
            on = true;
        } else {
            double mx = tracking ? sight.moveX : 0, my = tracking ? sight.moveY : 0, mz = tracking ? sight.moveZ : 0;
            sight.turn(tick, skill, eye.x, eye.y, eye.z, mob.getYHeadRot(), mob.getXRot(), aim.x, aim.y, aim.z, mx, my, mz,
                    mob.getRandom()::nextGaussian);
            y = sight.yaw;
            p = sight.pitch;
            on = AiAim.onTarget(skill, eye.x, eye.y, eye.z, y, p, aim.x, aim.y, aim.z);
        }
        mob.setYHeadRot(y);
        mob.setYRot(y);
        mob.yBodyRot = y;
        mob.setXRot(p);
        // the look control turns the head after the goals: to the place it looks at now, so the hand's error stays
        Vec3 look = eye.add(Vec3.directionFromRotation(p, y).scale(Math.max(1, eye.distanceTo(aim))));
        mob.getLookControl().setLookAt(look.x, look.y, look.z, skill.turn(), skill.turn());
        return on;
    }

    private void key(ItemGunBaseNT gun, ItemStack stack, EnumKeybind key, boolean down) {
        gun.handleKeybind(mob, null, stack, key, down);
    }
}
