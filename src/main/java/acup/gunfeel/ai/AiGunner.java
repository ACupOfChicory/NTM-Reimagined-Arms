package acup.gunfeel.ai;

import org.jspecify.annotations.Nullable;

import com.hbm.handler.HbmKeybinds.EnumKeybind;
import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.mags.IMagazine;
import com.hbm.items.weapon.sedna.mags.MagazineSingleTypeBase;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * An AI mob's hands on its NTM gun: it turns to where it is told to aim, or follows a target it sees, with a person's
 * eye and hand ({@link AiAim}, {@link AiSkill}: the server's aiSkill), holds and lets go of the trigger as NTM's keys
 * (the same way a player's gun is worked, so the gun's own timing, recoil and our bullets all apply) and reloads when
 * told or empty. The gun's clock is the game's: it ticks a mob's held item before the mob's goals, as a player's
 * inventory. Rounds are counted against {@link AiAmmo}. Server thread, from the mob's goal.
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
    /** how far up and down from the aim the part it aims at reaches (the trigger's gate), blocks */
    private double halfHeight = AiAim.HALF_HEIGHT;
    private boolean wantFire, wantReload, held, reloadHeld;
    /** it runs this tick (AiMover.runs): its body faces its path, it does not shoot */
    boolean runs;
    /** the eye and the hand; whether it follows a target (and which) or looks at a place */
    final AiAim sight = new AiAim();
    private boolean tracking;
    private int tracked = Integer.MIN_VALUE;
    /** for the stands: a perfect hand, at once exactly here, always on target */
    @Nullable
    Vec3 exact;
    /** shots let go (trigger pulls), for the stands */
    public int pulls;
    /**
     * this tick it fires blind, the gun held up over its cover with no look along it (its mind sets it each tick): its
     * bullets spread three times the gun's (MixinAiLeanShot)
     */
    public boolean blind;
    /** for the stands: shots let go blind, and the tick of the last */
    public int blindShots;
    public long lastBlind = Long.MIN_VALUE;
    /** ticks in a row it would have shot but another AI mob was in the line; ticks of that in all (the stands) */
    public int blockedTicks, holds;
    /** ticks it held fire with a block that stops bullets just past its gun (its own cover), and such holds (the stands) */
    public int coverTicks, coverHolds;
    /** a block that stops bullets this near along the line of fire is its own cover, not the target's: no shot */
    static final double MUZZLE_CLEAR = 2.5;
    /** the line of fire starts this far under its eyes (NTM fires from the eyes, a gun's own offset off them) */
    static final double MUZZLE_DROP = 0.1;
    /** shots it let go, and shots that kicked its view (the stands) */
    public int shots, kicks;
    /**
     * the stands: trigger pulls the server's no-shot-while-crawling rule threw away (ProneFire), ticks a held trigger
     * was down crawling (its burst cut), and shots let go lying
     */
    public int crawlPulls, crawlHeldTicks, shotsLying;
    /** the ticks its last reload took, from the start to ready again (0 before its first) */
    public int lastReload;
    private long reloadFrom = -1;
    /** a reload longer than this (ticks) was not seen through: it was not ticked meanwhile */
    static final int MAX_RELOAD = 200;
    /** a gap between shots, ticks, after which a new burst starts (its climb grows again from the first shot) */
    static final int BURST_GAP = 10;
    // the burst: shots so far, the tick of the last, where its sideways wander is
    private int burstShots;
    private long lastShot = Long.MIN_VALUE;
    private float drift;
    // shots since its last tick (AiShots: once a tick, whatever the projectiles), and the tick of the last
    private int fresh;
    private long shotTick = Long.MIN_VALUE;

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
        halfHeight = AiAim.HALF_HEIGHT;
    }

    /**
     * A target in sight, its chest seen this tick: it aims where it expects the bullet to meet it, from what it saw
     * its skill's lag ago (AiAim).
     */
    public void track(int id, Vec3 chest, long tick) {
        track(id, chest, 0, AiAim.HALF_HEIGHT, tick);
    }

    /**
     * The same, aiming up blocks above the chest (minus: below) at a part of the body halfHeight blocks up and down
     * (its head over a wall, its legs under a beam). The eye follows the chest still; a new part is a new spot, which
     * the hand turns to at its next correction, a little off as ever.
     */
    public void track(int id, Vec3 chest, double up, double halfHeight, long tick) {
        AiSkill skill = AiSkill.current();
        if (!tracking || id != tracked) sight.retarget();
        tracking = true;
        tracked = id;
        sight.see(id, chest.x, chest.y, chest.z, tick, skill, mob.getX(), mob.getZ());
        double flight = LEAVES + chest.distanceTo(AiLean.eye(mob)) / bulletSpeed();
        aim = (sight.lead(tick, skill, flight) ? new Vec3(sight.spotX, sight.spotY, sight.spotZ) : chest).add(0, up, 0);
        this.halfHeight = halfHeight;
    }

    public void fire(boolean on) {
        wantFire = on;
    }

    /** whether its mind wants the trigger down now (the debug view) */
    public boolean wantsFire() {
        return wantFire;
    }

    /** where it fires: its aim while its mind wants the trigger down, else null (FireLines) */
    @Nullable Vec3 firingAt() {
        return wantFire ? aim : null;
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

    /** the distance it would rather fight at with no gun to go by, blocks */
    static final double FIGHT_AT = 20;

    /**
     * The distance its gun is best at, blocks: where its shot strays about half a block (a body's half width) from
     * NTM's spread unaimed (the gun's, the round's, the hip's), 8 to 40; a shotgun's pellets 12 at most; a heavy single
     * shot that fires slowly (a marksman's rifle) 32 at least.
     */
    @SuppressWarnings("rawtypes")
    public double fightAt() {
        ItemStack stack = mob.getMainHandItem();
        ItemGunBaseNT gun = gun();
        IMagazine mag = gun == null ? null : magazine(gun, stack);
        try {
            if (mag == null || !(mag.getType(stack, null) instanceof BulletConfig b)) return FIGHT_AT;
            com.hbm.items.weapon.sedna.Receiver rec = gun.getConfig(stack, 0).getReceivers(stack)[0];
            double spread = rec.getInnateSpread(stack) + b.spread * rec.getAmmoSpread(stack) + rec.getHipfireSpread(stack);
            double at = spread <= 1e-4 ? 40 : Math.max(8, Math.min(40, 0.5 / spread));
            if (b.projectilesMax > 1) return Math.min(at, 12);
            if (rec.getBaseDamage(stack) * b.damageMult >= 25 && rec.getDelayAfterFire(stack) >= 20) return Math.max(at, 32);
            return at;
        } catch (RuntimeException e) {
            return FIGHT_AT;
        }
    }

    @SuppressWarnings("rawtypes")
    public int capacity() {
        ItemStack stack = mob.getMainHandItem();
        ItemGunBaseNT gun = gun();
        IMagazine mag = gun == null ? null : magazine(gun, stack);
        return mag == null ? 0 : mag.getCapacity(stack);
    }

    /**
     * How long a reload of its gun takes, ticks: as long as its last one took, else NTM's start and end of a reload
     * (and the cocking an empty gun adds); a gun that loads round by round takes longer, and its first reload shows it.
     */
    public int reloadTicks() {
        if (lastReload > 0) return lastReload;
        ItemStack stack = mob.getMainHandItem();
        ItemGunBaseNT gun = gun();
        if (gun == null) return 0;
        try {
            com.hbm.items.weapon.sedna.Receiver rec = gun.getConfig(stack, 0).getReceivers(stack)[0];
            int t = rec.getReloadBeginDuration(stack) + rec.getReloadEndDuration(stack);
            return loaded() <= 0 ? t + rec.getReloadCockOnEmptyPre(stack) + rec.getReloadCockOnEmptyPost(stack) : t;
        } catch (RuntimeException e) {
            return 0;
        }
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
        if (gun == null) {
            fresh = 0;
            return;
        }
        ItemStack stack = mob.getMainHandItem();
        boolean aligned = turn(level.getGameTime());

        ItemGunBaseNT.GunState state = ItemGunBaseNT.getState(stack, 0);
        int loaded = loaded();
        if (reloadHeld) {
            key(gun, stack, EnumKeybind.RELOAD, false);
            reloadHeld = false;
        }
        if (state == ItemGunBaseNT.GunState.RELOADING) wantReload = false;
        long time = level.getGameTime();
        if (state == ItemGunBaseNT.GunState.RELOADING && reloadFrom < 0) reloadFrom = time;
        else if (reloadFrom >= 0 && state == ItemGunBaseNT.GunState.IDLE) {
            // ready again (NTM's reload ends in DRAWING, its end animation); not one it was not ticked through
            if (time - reloadFrom <= MAX_RELOAD) lastReload = (int) (time - reloadFrom);
            reloadFrom = -1;
        }
        // lying, it fires only still: a pull while it crawls would be thrown away (ProneFire), so it waits until it has
        // stopped (its legs keep still meanwhile, steady)
        boolean shoot = wantFire && aim != null && aligned && loaded != 0 && !runs && !acup.gunfeel.ProneFire.blocks(mob, stack);
        if (shoot && checkLine && AiBots.friendInLine(level, mob, AiLean.eye(mob), aim, CLEAR_CONE)) {
            // one of its own between it and the target: it holds fire
            shoot = false;
            blockedTicks++;
            holds++;
        } else if (shoot || !wantFire) blockedTicks = 0;
        if (shoot && ownCover(level)) {
            // its own cover in front of the gun (it sees over it, the bullet would not go over): it holds fire
            shoot = false;
            coverTicks++;
            coverHolds++;
        } else if (shoot || !wantFire) coverTicks = 0;
        if (shoot) {
            if (!held) {
                if (acup.gunfeel.ProneFire.blocks(mob, stack)) crawlPulls++;
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
        if (held && acup.gunfeel.ProneFire.blocks(mob, stack)) crawlHeldTicks++;
        if (!held && state == ItemGunBaseNT.GunState.IDLE && (wantReload || loaded == 0) && ammo.any()
                && loaded >= 0 && loaded < capacity()) {
            key(gun, stack, EnumKeybind.RELOAD, true);
            reloadHeld = true;
        }

        IMagazine mag = magazine(gun, stack);
        int gone = 0;
        if (mag instanceof MagazineSingleTypeBase) {
            int now = mag.getAmount(stack, null), before = ammo.lastLoaded;
            int keep = ammo.settle(now);
            if (keep != now) mag.setAmount(stack, keep);
            if (before > 0 && now < before) gone = before - Math.max(0, now);
        }
        // shots since its last tick, as the gun ticked or as the trigger went down (those NTM worked out; else rounds
        // gone, as one: a gun fires at most once a tick, and a magazine changed by other means is no burst), each
        // kicking the view
        int n = fresh > 0 ? fresh : Math.min(gone, 1);
        fresh = 0;
        shots += n;
        if (n > 0 && blind) {
            blindShots += n;
            lastBlind = level.getGameTime();
        }
        if (n > 0 && acup.gunfeel.Prone.lying(mob)) shotsLying += n;
        if (n > 0 && exact == null) recoil(n, level.getGameTime(), stack);
    }

    /** a block that stops bullets within MUZZLE_CLEAR of the gun along the line to the aim */
    private boolean ownCover(ServerLevel level) {
        Vec3 from = AiLean.eye(mob).add(0, -MUZZLE_DROP, 0);
        double len = from.distanceTo(aim);
        if (len < 1e-3) return false;
        double t = VoxelRay.blocked(AiBots.voxels(level), from.x, from.y, from.z, aim.x, aim.y, aim.z, Voxel.PROTECT_MASK);
        return t >= 0 && t * len < MUZZLE_CLEAR;
    }

    /**
     * It wants to fire lying, where the server allows no shot while crawling (ProneFire): its legs keep still this tick
     * (AiMover.hold), so it crawls only between bursts.
     */
    boolean steady() {
        if (!wantFire || aim == null || gun() == null || !acup.gunfeel.Prone.lying(mob) || loaded() == 0) return false;
        return acup.gunfeel.ProneFire.on(mob.getMainHandItem());
    }

    /** NTM let a shot of its go (AiShots), counted once a tick */
    void shot(long tick) {
        if (tick == shotTick) return;
        shotTick = tick;
        fresh++;
    }

    /**
     * Each shot's kick on the view, from NTM's recoil for the gun and the part of it that stays, as on a player's camera
     * (AiRecoil), aiming or not, lying or not; AiAim keeps it.
     */
    private void recoil(int shots, long tick, ItemStack stack) {
        float strength = AiRecoil.strength();
        if (strength <= 0F) return;
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        AiRecoil.Kick k = AiRecoil.of(id);
        if (k == null) return;
        AiSkill skill = AiSkill.current();
        boolean aiming = ItemGunBaseNT.getIsAiming(stack), lying = acup.gunfeel.compat.Feature.PRONE.on() && acup.gunfeel.Prone.lying(mob);
        boolean first = lastShot == Long.MIN_VALUE || tick - lastShot > BURST_GAP;
        lastShot = tick;
        if (first) burstShots = 0;
        for (int i = 0; i < shots; i++) {
            burstShots++;
            drift = AiRecoil.drift(drift, first && i == 0, (float) mob.getRandom().nextGaussian());
            float[] d = AiRecoil.degrees(k, id, strength, burstShots, drift, aiming, lying, mob.getRandom()::nextGaussian);
            float[] moved = sight.kick(d[0], d[1], d[2], d[3], k.rebound(), skill);
            face(mob.getYHeadRot() + moved[0], Math.max(-90F, Math.min(90F, mob.getXRot() + moved[1])), skill);
            kicks++;
        }
    }

    // the head, body and look control to this view; the look control turns the head after the goals, so it is given a
    // place along the view and the hand's error and the kick stay
    private void face(float y, float p, AiSkill skill) {
        mob.setYHeadRot(y);
        mob.setXRot(p);
        // NTM fires a mob's bullet the way its body faces: the body faces the aim, but while running (the body
        // follows the path then, the head turns within its neck)
        if (!runs) {
            mob.setYRot(y);
            mob.yBodyRot = y;
            if (mob.getMoveControl() instanceof AiMoveControl legs) legs.face(y);
        }
        // from the upright eye: the look control turns the head from the mob's own place, not a leaned eye's
        Vec3 eye = mob.getEyePosition();
        double far = aim == null ? 16 : Math.max(1, eye.distanceTo(aim));
        Vec3 look = eye.add(Vec3.directionFromRotation(p, y).scale(far));
        mob.getLookControl().setLookAt(look.x, look.y, look.z, skill.turn(), skill.turn());
    }

    /**
     * Turns the mob towards the aim with its hand (AiAim.turn); whether the spot it expects the target at is on the
     * barrel now (the trigger may be down).
     */
    private boolean turn(long tick) {
        if (aim == null) return false;
        Vec3 eye = AiLean.eye(mob);
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
            // on target as far as it knows: a kick it has not noticed yet left out
            on = AiAim.onTarget(skill, eye.x, eye.y, eye.z, sight.seenYaw, sight.seenPitch, aim.x, aim.y, aim.z, halfHeight);
        }
        face(y, p, skill);
        return on;
    }

    private void key(ItemGunBaseNT gun, ItemStack stack, EnumKeybind key, boolean down) {
        gun.handleKeybind(mob, null, stack, key, down);
    }
}
