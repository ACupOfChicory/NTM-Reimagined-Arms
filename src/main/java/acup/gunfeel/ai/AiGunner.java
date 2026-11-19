package acup.gunfeel.ai;

import org.jspecify.annotations.Nullable;

import com.hbm.handler.HbmKeybinds.EnumKeybind;
import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.Receiver;
import com.hbm.items.weapon.sedna.mags.IMagazine;
import com.hbm.items.weapon.sedna.mags.MagazineSingleTypeBase;

import acup.gunfeel.host.Body;
import acup.gunfeel.host.NtmGuns;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
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
public final class AiGunner extends AiGunnerBase {
    /** a shot meets its target this much later than its flight, on average (a bullet moves a tick at a time) */
    static final double LEAVES = 0.5;

    /** whether it holds fire with another AI mob in the line (off only for the stands' "before") */
    static boolean checkLine = true;

    private final Mob mob;
    /** where it aims, the object (AiGunnerBase keeps its numbers, set with it) */
    private @Nullable Vec3 aim;
    /**
     * this tick it fires blind, the gun held up over its cover with no look along it (its mind sets it each tick): its
     * bullets spread three times the gun's (MixinAiLeanShot)
     */
    public boolean blind;
    /** for the stands: shots let go blind, and the tick of the last */
    public int blindShots;
    public long lastBlind = Long.MIN_VALUE;
    /**
     * ticks it would have shot but another AI mob was in the line, since its last shot (the pauses of its bursts
     * between kept: a burst shorter than the soldier's patience, and it never stepped aside); ticks of that in all (the
     * stands)
     */
    public int blockedTicks, holds;
    /** ticks it held fire with a block that stops bullets just past its gun (its own cover), so too; such holds (the stands) */
    public int coverTicks, coverHolds;
    /** the counts above go back to none once it has not wanted to fire this long, ticks (a pause of a burst is shorter) */
    static final int HOLD_PAUSE = 40;
    /** the last tick it wanted to fire */
    private long wantedAt = Long.MIN_VALUE / 2;
    /** a block that stops bullets this near along the line of fire is its own cover, not the target's: no shot */
    static final double MUZZLE_CLEAR = 2.5;
    /** the line of fire starts this far under its eyes (NTM fires from the eyes, a gun's own offset off them) */
    static final double MUZZLE_DROP = 0.1;
    /** shots it let go (the stands) */
    public int shots;
    /** the stands: shots let go from a crouch it chose for its clear line, and shots with a block that stops bullets just past the gun (none) */
    public int shotsLow, coverShots;
    /** this tick it fires from a crouch, its line from there clear (AiSoldier sets it; the stands count its shots) */
    public boolean low;
    /** this tick's own cover just past the gun, as the pull was decided */
    private boolean covered;
    /** the stands: shots let go lying (with AiGunnerBase's crawlPulls and crawlHeldTicks) */
    public int shotsLying;
    private long reloadFrom = -1;
    /** a reload longer than this (ticks) was not seen through: it was not ticked meanwhile */
    static final int MAX_RELOAD = 200;
    // the burst: shots so far, the tick of the last, where its sideways wander is
    private int burstShots;
    private long lastShot = Long.MIN_VALUE;
    private float drift;
    // the barrel's heat: waiting for it to cool, and no pull before this tick (a pause cut into a burst)
    private boolean cooling;
    private long heatPauseUntil = Long.MIN_VALUE;
    // shots since its last tick (AiShots: once a tick, whatever the projectiles), and the tick of the last
    private int fresh;
    private long shotTick = Long.MIN_VALUE;

    AiGunner(Mob mob, AiAmmo ammo) {
        super(acup.gunfeel.EntityBody.of(mob), ammo);
        this.mob = mob;
    }

    /** the place it looks at, set by aim (null: none, or a target it follows) */
    @Nullable Vec3 aimPoint() {
        return tracking ? null : aim;
    }

    /** a place to look at (not a target it follows), or null to stop aiming */
    public void aim(@Nullable Vec3 at) {
        if (tracking) {
            tracking = false;
            sight.retarget();
        }
        setAim(at);
        halfHeight = AiAim.HALF_HEIGHT;
    }

    private void setAim(@Nullable Vec3 at) {
        aim = at;
        if (at == null) aimAt(false, 0, 0, 0);
        else aimAt(true, at.x, at.y, at.z);
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
        setAim((sight.lead(tick, skill, flight) ? new Vec3(sight.spotX, sight.spotY, sight.spotZ) : chest).add(0, up, 0));
        this.halfHeight = halfHeight;
    }

    public void fire(boolean on) {
        wantFire = on;
    }

    /**
     * A lob (AiGrenadier): turned exactly along the barrel to this point, with no hand's error and no kick, till the
     * tick until; null lets go of it.
     */
    void lob(@Nullable Vec3 along, long until) {
        if (along == null) lob(false, 0, 0, 0, until);
        else lob(true, along.x, along.y, along.z, until);
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
        letGoReload();
        if (state == ItemGunBaseNT.GunState.RELOADING) wantReload = false;
        long time = level.getGameTime();
        // the holds counted on through the pauses of its bursts: a shot, its aim taken away (idle, ordered) or no fire
        // wanted a while ends them (read before this tick's want: a count from before a sleep starts over)
        boolean holdsOver = aim == null || time - wantedAt > HOLD_PAUSE;
        if (wantFire) wantedAt = time;
        if (state == ItemGunBaseNT.GunState.RELOADING && reloadFrom < 0) reloadFrom = time;
        else if (reloadFrom >= 0 && state == ItemGunBaseNT.GunState.IDLE) {
            // ready again (NTM's reload ends in DRAWING, its end animation); not one it was not ticked through
            if (time - reloadFrom <= MAX_RELOAD) lastReload = (int) (time - reloadFrom);
            reloadFrom = -1;
        }
        // lying, it fires only still: a pull while it crawls would be thrown away (ProneFire), so it waits until it has
        // stopped (its legs keep still meanwhile, steady)
        // stuck at a step, turned to its path a moment (AiMoveControl): its body does not face the aim, no shot
        boolean turned = mob.getMoveControl() instanceof AiMoveControl legs && legs.turned();
        // swimming it holds fire: its hands are for the water (AiWater)
        boolean shoot = wantFire && aim != null && aligned && loaded != 0 && !runs && !turned && !acup.gunfeel.ProneFire.blocks(mob, stack)
                && !AiWater.swims(mob);
        // a grenadier's shells go only as lobs worked out to burst clear of its own (AiGrenadier), never straight
        if (shoot && !hasExact && AiGrenadier.lobsOnly(mob, stack)) shoot = false;
        // a hot barrel (acup.gunfeel.heat.Overheat): shorter bursts with longer pauses, and near the top it waits for it to cool
        if (shoot && heatHolds(stack, time)) shoot = false;
        // the line to the aim, the line it looks along now (a burst's kick, a turn not done) and the one its body faces
        // (the bullet's), each in a cone as wide as the gun strays: an automatic's wider
        boolean friend = false;
        if (shoot && checkLine) {
            Vec3 eye = AiLean.eye(mob);
            double cone = AiBots.wideLine ? clearCone() : CLEAR_CONE;
            double far = Math.max(2, aim.distanceTo(eye));
            // NTM sends the bullet along the body's turn (getYRot), not the head's
            double yaw = Math.toRadians(mob.getYRot()), pitch = Math.toRadians(mob.getXRot());
            Vec3 body = new Vec3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
            // the bullet leaves NTM's gun at its muzzle, off the eye to the side and down, and flies along the body's
            // line from there (AiBots.leadLine)
            Vec3 from = AiBots.leadLine ? muzzle(eye, stack) : eye;
            friend = AiBots.friendInLine(level, mob, eye, aim, cone)
                    || AiBots.wideLine && AiBots.friendInLine(level, mob, from, from.add(mob.getViewVector(1F).scale(far)), cone)
                    || AiBots.wideLine && AiBots.friendInLine(level, mob, from, from.add(body.scale(far)), cone);
        }
        if (AiBots.friendTrace && shoot) checked = new Checked(level.getGameTime(), AiLean.eye(mob), AiBots.leadLine ? muzzle(AiLean.eye(mob), stack) : AiLean.eye(mob),
                aim, mob.getViewVector(1F), mob.getYRot(), mob.getXRot(), AiBots.wideLine ? clearCone() : CLEAR_CONE, friend, checkLine);
        if (friend) {
            // one of its own between it and the target: it holds fire
            shoot = false;
            holds++;
            if (AiSoldier.counting && suppresses()) AiSoldier.suppressHolds++;
        }
        blockedTicks = held(blockedTicks, friend, shoot, holdsOver);
        covered = shoot && ownCover(level);
        if (covered) {
            // its own cover in front of the gun (it sees over it, the bullet would not go over): it holds fire
            shoot = false;
            coverHolds++;
        }
        coverTicks = held(coverTicks, covered, shoot, holdsOver);
        trigger(shoot, state == ItemGunBaseNT.GunState.IDLE, loaded);

        int gone = settleMagazine();
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
        if (n > 0 && low && mob.getPose() == net.minecraft.world.entity.Pose.CROUCHING) shotsLow += n;
        if (n > 0 && covered) coverShots += n;
        if (n > 0 && !hasExact) recoil(n, level.getGameTime(), stack);
    }

    /**
     * From a crouch where it stands, its line of fire to the aim is clear of every block that stops bullets: it may
     * fire down low (over cover lower than its gun, through a gap, in the open), not into its cover.
     */
    boolean clearCrouched(ServerLevel level) {
        if (aim == null) return false;
        Vec3 from = mob.position().add(0, AiPose.CROUCH_SIZE.eyeHeight() - MUZZLE_DROP, 0);
        return VoxelRay.blocked(AiBots.voxels(level), from.x, from.y, from.z, aim.x, aim.y, aim.z, Voxel.PROTECT_MASK) < 0;
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

    /**
     * Where its gun's bullet starts: the eye and the receiver's projectile offset (scoped when it aims), turned with
     * the body as EntityBulletBaseMK4 turns it (NTM's offset is forward, up, aside; its Vec3 the other way round)
     */
    Vec3 muzzle(Vec3 eye, ItemStack stack) {
        ItemGunBaseNT gun = gun();
        if (gun == null) return eye;
        acup.gunfeel.Vec3d o = offset(ItemGunBaseNT.getIsAiming(stack));
        if (o != offsetFrom) {
            offsetFrom = o;
            offsetMc = o == null ? null : new Vec3(o.x(), o.y(), o.z());
        }
        if (offsetMc == null) return eye;
        return eye.add(offsetMc.xRot((float) Math.toRadians(-mob.getXRot())).yRot((float) Math.toRadians(-mob.getYRot())));
    }

    // the base's offset as the game's Vec3, made again only when that one is read anew
    private acup.gunfeel.@Nullable Vec3d offsetFrom;
    private @Nullable Vec3 offsetMc;

    // the stands: it fires to keep a target's head down (AiSoldier.suppress)
    private boolean suppresses() {
        AiBot b = AiBots.get(mob);
        return b != null && b.brain instanceof AiSoldier so && so.why == AiThoughts.Why.SUPPRESS;
    }

    /** the stands (AiBots.friendTrace): the last line check that let a shot go, as it was then */
    record Checked(long tick, Vec3 eye, Vec3 from, Vec3 aim, Vec3 look, float yaw, float pitch, double cone, boolean friend, boolean on) {
    }

    /** the stands: a shot as NTM let it go, the shooter's pose then and where its own stood */
    record Snap(long tick, Vec3 eye, Vec3 look, float yaw, float pitch, @Nullable Checked checked, java.util.Map<Mob, Vec3> own) {
    }

    private @Nullable Checked checked;
    final java.util.ArrayDeque<Snap> snaps = new java.util.ArrayDeque<>();

    private void snap(ServerLevel level, long tick) {
        java.util.Map<Mob, Vec3> own = new java.util.HashMap<>();
        for (Mob m : AiBots.ownNear(level, mob, 24)) own.put(m, m.getBoundingBox().getCenter());
        snaps.addLast(new Snap(tick, AiLean.eye(mob), mob.getViewVector(1F), mob.getYRot(), mob.getXRot(), checked, own));
        while (snaps.size() > 30) snaps.removeFirst();
    }

    /** the tick NTM last let a shot of its go (AiShots; Long.MIN_VALUE: none yet) */
    long shotTick() {
        return shotTick;
    }

    /** NTM let a shot of its go (AiShots), counted once a tick */
    void shot(long tick) {
        if (AiBots.friendTrace && mob.level() instanceof ServerLevel level) snap(level, tick);
        if (tick == shotTick) return;
        shotTick = tick;
        if (AiSoldier.counting && suppresses()) AiSoldier.suppressShots++;
        fresh++;
    }

    /**
     * Each shot's kick on the view, from NTM's recoil for the gun and the part of it that stays, as on a player's camera
     * (AiRecoil), aiming or not, lying or not; AiAim keeps it.
     */
    private void recoil(int shots, long tick, ItemStack stack) {
        float strength = AiRecoil.strength();
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        AiRecoil.Kick k = strength <= 0F ? null : AiRecoil.of(id);
        // the burst is counted with no kick too: the barrel's heat cuts it by its shots (heatHolds)
        boolean first = lastShot == Long.MIN_VALUE || tick - lastShot > BURST_GAP;
        lastShot = tick;
        if (first) burstShots = 0;
        if (k == null) {
            burstShots += shots;
            return;
        }
        AiSkill skill = AiSkill.current();
        boolean aiming = ItemGunBaseNT.getIsAiming(stack), lying = acup.gunfeel.compat.Feature.PRONE.on() && acup.gunfeel.Prone.lying(mob);
        for (int i = 0; i < shots; i++) {
            burstShots++;
            drift = AiRecoil.drift(drift, first && i == 0, (float) mob.getRandom().nextGaussian());
            float[] d = AiRecoil.degrees(k, id, strength, burstShots, drift, aiming, lying, mob.getRandom()::nextGaussian);
            float[] moved = sight.kick(d[0], d[1], d[2], d[3], k.rebound(), skill);
            face(mob.getYHeadRot() + moved[0], Math.max(-90F, Math.min(90F, mob.getXRot() + moved[1])), skill);
        }
    }

    /**
     * The barrel's heat holds its fire (Overheat): from AI_WAIT until it is down to AI_RESUME; warmer than AI_SHORT, a burst
     * is cut at the gun's aiBurst shots and the pause after it is longer than a burst's gap (BURST_GAP), the hotter the
     * longer: the next burst starts its climb over.
     */
    private boolean heatHolds(ItemStack stack, long time) {
        acup.gunfeel.heat.Overheat.Gun g = acup.gunfeel.heat.Overheat.gun(stack);
        if (g == null) return false;
        float h = acup.gunfeel.heat.Overheat.heat(stack, time);
        if (h >= acup.gunfeel.heat.Overheat.AI_WAIT) cooling = true;
        else if (h <= acup.gunfeel.heat.Overheat.AI_RESUME) cooling = false;
        if (cooling || time < heatPauseUntil) return true;
        if (h > acup.gunfeel.heat.Overheat.AI_SHORT && burstShots >= g.aiBurst && lastShot != Long.MIN_VALUE && time - lastShot <= BURST_GAP) {
            heatPauseUntil = time + heatPause(h);
            return true;
        }
        return false;
    }

    @Override
    void leanEye(double[] out) {
        Vec3 e = AiLean.eye(mob);
        out[0] = e.x;
        out[1] = e.y;
        out[2] = e.z;
    }

    @Override
    float @Nullable [] rubberDrift() {
        return acup.gunfeel.compat.Feature.RUBBER_SLUG.on() ? acup.gunfeel.ammo.RubberServer.drift(mob) : null;
    }

    @Override
    boolean proneBlocks() {
        return acup.gunfeel.ProneFire.blocks(mob, mob.getMainHandItem());
    }

    /**
     * 26.2's NTM guns for core (host.NtmGuns): the stack in the body's hand now, read as this class read it (config 0,
     * receiver 0). Here, in AiGunner, so that its calls into NTM stay the AI's (Feature.MOB_AI, ntmgunfeel-ntm-api.txt)
     * and not the core's, whose NTM missing a member turns the whole mod off.
     */
    public static final class Guns implements NtmGuns {
        // core's numbers by NTM's states' names (NtmGuns.IDLE...), -1 for one core has none for; made the first time
        // a state is asked for, not when Wiring makes the port
        private static final class States {
            static final int[] OF = states();
        }

        private static int[] states() {
            ItemGunBaseNT.GunState[] all = ItemGunBaseNT.GunState.values();
            int[] out = new int[all.length];
            for (int i = 0; i < all.length; i++) {
                out[i] = switch (all[i].name()) {
                    case "IDLE" -> IDLE;
                    case "RELOADING" -> RELOADING;
                    case "COOLDOWN" -> COOLDOWN;
                    case "JAMMED" -> JAMMED;
                    case "DRAWING" -> DRAWING;
                    default -> -1;
                };
            }
            return out;
        }

        private static ItemStack stack(Body holder, int hand) {
            if (!(((acup.gunfeel.EntityBody) holder).entity() instanceof LivingEntity l)) return ItemStack.EMPTY;
            return hand == Body.MAIN_HAND ? l.getMainHandItem() : l.getOffhandItem();
        }

        private static Receiver receiver(ItemStack stack) {
            return ((ItemGunBaseNT) stack.getItem()).getConfig(stack, 0).getReceivers(stack)[0];
        }

        // AiGunner.magazine as it was: null with no gun, and where NTM threw on the way
        @SuppressWarnings("rawtypes")
        private static IMagazine magazine(ItemStack stack) {
            if (!(stack.getItem() instanceof ItemGunBaseNT gun)) return null;
            try {
                return gun.getConfig(stack, 0).getReceivers(stack)[0].getMagazine(stack);
            } catch (RuntimeException e) {
                return null;
            }
        }

        @Override
        public boolean isGun(Body holder, int hand) {
            return stack(holder, hand).getItem() instanceof ItemGunBaseNT;
        }

        @Override
        @SuppressWarnings("rawtypes")
        public int loaded(Body holder, int hand) {
            ItemStack stack = stack(holder, hand);
            IMagazine mag = magazine(stack);
            return mag instanceof MagazineSingleTypeBase ? mag.getAmount(stack, null) : -1;
        }

        @Override
        @SuppressWarnings({"rawtypes", "unchecked"})
        public int settle(Body holder, int hand, java.util.function.IntUnaryOperator settle) {
            ItemStack stack = stack(holder, hand);
            IMagazine mag = magazine(stack);
            if (!(mag instanceof MagazineSingleTypeBase)) return NO_ROUNDS;
            int now = mag.getAmount(stack, null);
            int keep = settle.applyAsInt(now);
            if (keep != now) mag.setAmount(stack, keep);
            return now;
        }

        @Override
        @SuppressWarnings("rawtypes")
        public int capacity(Body holder, int hand) {
            ItemStack stack = stack(holder, hand);
            IMagazine mag = magazine(stack);
            return mag == null ? 0 : mag.getCapacity(stack);
        }

        @Override
        public int state(Body holder, int hand) {
            ItemStack stack = stack(holder, hand);
            if (!(stack.getItem() instanceof ItemGunBaseNT)) return -1;
            ItemGunBaseNT.GunState s = ItemGunBaseNT.getState(stack, 0);
            return s == null ? -1 : States.OF[s.ordinal()];
        }

        @Override
        @SuppressWarnings("rawtypes")
        public boolean bullet(Body holder, int hand, float[] out) {
            ItemStack stack = stack(holder, hand);
            IMagazine mag = magazine(stack);
            if (mag == null || !(mag.getType(stack, null) instanceof BulletConfig b)) return false;
            out[B_VELOCITY] = b.velocity;
            out[B_SPREAD] = b.spread;
            out[B_PROJECTILES_MAX] = b.projectilesMax;
            out[B_DAMAGE_MULT] = b.damageMult;
            out[B_DUPLEX] = b.projectilesMax > 1 && acup.gunfeel.compat.Feature.DUPLEX.on() && acup.gunfeel.ammo.Duplex.is(b) ? 1F : 0F;
            return true;
        }

        @Override
        public void receiver(Body holder, int hand, int mask, float[] out) {
            ItemStack stack = stack(holder, hand);
            Receiver r = receiver(stack);
            if ((mask & 1 << R_INNATE_SPREAD) != 0) out[R_INNATE_SPREAD] = r.getInnateSpread(stack);
            if ((mask & 1 << R_AMMO_SPREAD) != 0) out[R_AMMO_SPREAD] = r.getAmmoSpread(stack);
            if ((mask & 1 << R_HIPFIRE_SPREAD) != 0) out[R_HIPFIRE_SPREAD] = r.getHipfireSpread(stack);
            if ((mask & 1 << R_BASE_DAMAGE) != 0) out[R_BASE_DAMAGE] = r.getBaseDamage(stack);
            if ((mask & 1 << R_DELAY_AFTER_FIRE) != 0) out[R_DELAY_AFTER_FIRE] = r.getDelayAfterFire(stack);
            if ((mask & 1 << R_RELOAD_BEGIN) != 0) out[R_RELOAD_BEGIN] = r.getReloadBeginDuration(stack);
            if ((mask & 1 << R_RELOAD_END) != 0) out[R_RELOAD_END] = r.getReloadEndDuration(stack);
            if ((mask & 1 << R_COCK_ON_EMPTY_PRE) != 0) out[R_COCK_ON_EMPTY_PRE] = r.getReloadCockOnEmptyPre(stack);
            if ((mask & 1 << R_COCK_ON_EMPTY_POST) != 0) out[R_COCK_ON_EMPTY_POST] = r.getReloadCockOnEmptyPost(stack);
        }

        @Override
        public boolean muzzle(Body holder, int hand, boolean scoped, double[] out) {
            ItemStack stack = stack(holder, hand);
            Receiver r = receiver(stack);
            Vec3 off = scoped ? r.getProjectileOffsetScoped(stack) : r.getProjectileOffset(stack);
            if (off == null) return false;
            out[0] = off.x;
            out[1] = off.y;
            out[2] = off.z;
            return true;
        }

        @Override
        public void key(Body holder, int hand, int key, boolean down) {
            ItemStack stack = stack(holder, hand);
            EnumKeybind k = switch (key) {
                case PRIMARY -> EnumKeybind.GUN_PRIMARY;
                case RELOAD -> EnumKeybind.RELOAD;
                default -> throw new IllegalArgumentException("key " + key);
            };
            ((ItemGunBaseNT) stack.getItem()).handleKeybind((LivingEntity) ((acup.gunfeel.EntityBody) holder).entity(), null, stack, k, down);
        }

        @Override
        @SuppressWarnings({"rawtypes", "unchecked"})
        public int fill(Body holder, int hand) {
            ItemStack stack = stack(holder, hand);
            IMagazine mag = magazine(stack);
            if (mag == null) return NO_ROUNDS;
            mag.reloadAction(stack, null);
            return mag instanceof MagazineSingleTypeBase ? mag.getAmount(stack, null) : -1;
        }
    }
}
