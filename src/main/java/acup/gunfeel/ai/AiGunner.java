package acup.gunfeel.ai;

import org.jspecify.annotations.Nullable;

import com.hbm.handler.HbmKeybinds.EnumKeybind;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.mags.IMagazine;
import com.hbm.items.weapon.sedna.mags.MagazineSingleTypeBase;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * An AI mob's hands on its NTM gun: it turns to where it is told to aim (so fast a tick, a little off), holds and lets
 * go of the trigger as NTM's keys (the same way a player's gun is worked, so the gun's own timing, recoil and our
 * bullets all apply), reloads when told or empty, and keeps the gun's clock running (a mob's held item does not tick
 * by itself). Rounds are counted against {@link AiAmmo}. Server thread, from the mob's goal.
 */
public final class AiGunner {
    /** the most it turns a tick, degrees */
    static final float TURN = 30F;
    /** close enough to the aim to pull the trigger, degrees */
    static final float ALIGNED = 4F;

    private final Mob mob;
    public final AiAmmo ammo;
    private @Nullable Vec3 aim;
    private boolean wantFire, wantReload, held, reloadHeld;
    private float errYaw, errPitch;
    /** shots let go (trigger pulls), for the stands */
    public int pulls;

    AiGunner(Mob mob, AiAmmo ammo) {
        this.mob = mob;
        this.ammo = ammo;
    }

    /** where to aim, or null to stop aiming */
    public void aim(@Nullable Vec3 at) {
        aim = at;
    }

    public void fire(boolean on) {
        wantFire = on;
    }

    public void reload() {
        wantReload = true;
    }

    /** a new aim error (a new burst), sigma in degrees */
    public void jitter(float sigma) {
        var rng = mob.getRandom();
        errYaw = (float) rng.nextGaussian() * sigma;
        errPitch = (float) rng.nextGaussian() * sigma * 0.6F;
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
        boolean aligned = turn();

        ItemGunBaseNT.GunState state = ItemGunBaseNT.getState(stack, 0);
        int loaded = loaded();
        if (reloadHeld) {
            key(gun, stack, EnumKeybind.RELOAD, false);
            reloadHeld = false;
        }
        if (state == ItemGunBaseNT.GunState.RELOADING) wantReload = false;
        boolean shoot = wantFire && aim != null && aligned && loaded != 0;
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

    /** turns the mob towards the aim (plus its error), at most TURN a tick; whether it is there */
    private boolean turn() {
        if (aim == null) return false;
        Vec3 eye = mob.getEyePosition();
        double dx = aim.x - eye.x, dy = aim.y - eye.y, dz = aim.z - eye.z;
        double flat = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90F + errYaw;
        float pitch = (float) -(Mth.atan2(dy, flat) * Mth.RAD_TO_DEG) + errPitch;
        float dyaw = Mth.wrapDegrees(yaw - mob.getYHeadRot()), dpitch = pitch - mob.getXRot();
        float y = mob.getYHeadRot() + Mth.clamp(dyaw, -TURN, TURN);
        float p = Mth.clamp(mob.getXRot() + Mth.clamp(dpitch, -TURN, TURN), -90F, 90F);
        mob.setYHeadRot(y);
        mob.setYRot(y);
        mob.yBodyRot = y;
        mob.setXRot(p);
        mob.getLookControl().setLookAt(aim.x, aim.y, aim.z, TURN, TURN);
        return Math.abs(Mth.wrapDegrees(yaw - y)) <= ALIGNED && Math.abs(pitch - p) <= ALIGNED;
    }

    private void key(ItemGunBaseNT gun, ItemStack stack, EnumKeybind key, boolean down) {
        gun.handleKeybind(mob, null, stack, key, down);
    }
}
