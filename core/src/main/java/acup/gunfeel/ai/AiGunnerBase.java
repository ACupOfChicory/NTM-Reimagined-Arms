package acup.gunfeel.ai;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.CoreServices;
import acup.gunfeel.ReaimTimes;
import acup.gunfeel.heat.OverheatBase;
import acup.gunfeel.host.Body;
import acup.gunfeel.host.NtmGuns;
import acup.gunfeel.Vec3d;

/**
 * What an AI mob's gunner knows of the gun in its main hand, read through NTM's guns' port (host.NtmGuns): its rounds,
 * its magazine, its reload, its bullet's speed, the distance it is best at, the cone kept clear for it, the muzzle's
 * offset. AiGunner in 26.2: the aim, the trigger, the keys and the recoil.
 */
public abstract class AiGunnerBase {
    static final NtmGuns GUNS = CoreServices.load(acup.gunfeel.host.NtmGuns.class, NtmGuns.NONE);

    /** the cone kept clear of other AI mobs before a shot (the gun's spread and the aim's error), degrees */
    static final double CLEAR_CONE = 3.0;
    /** the same for an automatic (AUTO_DELAY ticks between shots at most): its burst's kick strays further (the stands: 4 to 9 degrees) */
    static final double AUTO_CONE = 10.0;
    static final int AUTO_DELAY = 3;
    // the gun the cone was worked out for, by its number (Body.held); -1 none yet
    private int coneOf = -1;
    private double cone = CLEAR_CONE;

    private static final acup.gunfeel.host.BodyControl CONTROL = CoreServices.load(acup.gunfeel.host.BodyControl.class,
            acup.gunfeel.host.BodyControl.MISSING);

    final Body body;
    public final AiAmmo ammo;
    /** ammo.settle, made once (no object a tick) for NtmGuns.settle */
    private final java.util.function.IntUnaryOperator settle;
    /** the ticks its last reload took, from the start to ready again (0 before its first) */
    public int lastReload;
    // fire and reload wanted (its mind), the trigger and the reload key down (its hand)
    boolean wantFire, wantReload, held, reloadHeld;
    /** shots let go (trigger pulls), for the stands */
    public int pulls;
    /**
     * the stands: trigger pulls the server's no-shot-while-crawling rule threw away (ProneFire), ticks a held trigger
     * was down crawling (its burst cut)
     */
    public int crawlPulls, crawlHeldTicks;
    // where it aims (the version keeps its own object of it, set with these): the place, none without
    boolean hasAim;
    double aimX, aimY, aimZ;
    /** how far up and down from the aim the part it aims at reaches (the trigger's gate), blocks */
    double halfHeight = AiAim.HALF_HEIGHT;
    /** it runs this tick (AiMover.runs): its body faces its path, it does not shoot */
    boolean runs;
    /** the eye and the hand; whether it follows a target (and which) or looks at a place */
    final AiAim sight = new AiAim();
    boolean tracking;
    int tracked = Integer.MIN_VALUE;
    // for the stands and a grenadier's lob: a perfect hand, at once exactly here, always on target; let go after exactUntil
    boolean hasExact;
    double exactX, exactY, exactZ;
    long exactUntil = Long.MAX_VALUE;
    // the burst: shots so far, the tick of the last, where its sideways wander is
    private int burstShots;
    private long lastShot = Long.MIN_VALUE;
    private float drift;
    // the barrel's heat: waiting for it to cool, and no pull before this tick (a pause cut into a burst)
    private boolean cooling;
    private long heatPauseUntil = Long.MIN_VALUE;
    private static final acup.gunfeel.host.ItemTable ITEMS = CoreServices.load(acup.gunfeel.host.ItemTable.class,
            acup.gunfeel.host.ItemTable.MISSING);
    /** OverheatBase.Gun by ordinal (NtmGuns.heatGun), once: values() makes an array each call */
    private static final OverheatBase.Gun[] HEAT_GUNS = OverheatBase.Gun.values();
    /** its body's random numbers (the game's own generator), and their gaussian for its hand, made once */
    private final acup.gunfeel.GameRandom random;
    private final java.util.function.DoubleSupplier gauss;
    // the eye it turns from (leaned), the upright eye its look control turns the head from, the way it looks
    private final double[] eye = new double[3], upright = new double[3], dir = new double[3];

    /** a gap between shots, ticks, after which a new burst starts (its climb grows again from the first shot) */
    static final int BURST_GAP = 10;

    // the port's numbers, this gunner's own
    private final float[] rec = new float[NtmGuns.R_COUNT], bul = new float[NtmGuns.B_COUNT];
    private final double[] off = new double[3];

    AiGunnerBase(Body body, AiAmmo ammo) {
        this.body = body;
        this.ammo = ammo;
        this.settle = ammo::settle;
        this.random = body.random();
        this.gauss = random::nextGaussian;
    }

    /** where it aims: the place (has false: none) */
    void aimAt(boolean has, double x, double y, double z) {
        hasAim = has;
        aimX = x;
        aimY = y;
        aimZ = z;
    }

    /**
     * A lob (AiGrenadier): turned exactly along the barrel to this point, with no hand's error and no kick, till the
     * tick until; has false lets go of it.
     */
    void lob(boolean has, double x, double y, double z, long until) {
        hasExact = has;
        exactX = x;
        exactY = y;
        exactZ = z;
        exactUntil = has ? until : Long.MAX_VALUE;
    }

    // the head, body and look control to this view; the look control turns the head after the goals, so it is given a
    // place along the view and the hand's error and the kick stay
    void face(float y, float p, AiSkill skill) {
        CONTROL.yHeadRot(body, y);
        CONTROL.xRot(body, p);
        // NTM fires a mob's bullet the way its body faces: the body faces the aim, but while running (the body
        // follows the path then, the head turns within its neck)
        if (!runs && !CONTROL.turned(body)) {
            CONTROL.yRot(body, y);
            CONTROL.yBodyRot(body, y);
            CONTROL.face(body, y);
        }
        // from the upright eye: the look control turns the head from the mob's own place, not a leaned eye's
        body.eye(upright);
        double far;
        if (!hasAim) far = 16;
        else {
            // Vec3.distanceTo, the aim's minus the eye's
            double dx = aimX - upright[0], dy = aimY - upright[1], dz = aimZ - upright[2];
            far = Math.max(1, Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
        acup.gunfeel.GameMath.direction(p, y, dir);
        CONTROL.lookAt(body, upright[0] + dir[0] * far, upright[1] + dir[1] * far, upright[2] + dir[2] * far, skill.turn(), skill.turn());
    }

    /**
     * Turns the mob towards the aim with its hand (AiAim.turn); whether the spot it expects the target at is on the
     * barrel now (the trigger may be down).
     */
    boolean turn(long tick) {
        if (hasExact && tick > exactUntil) lob(false, 0, 0, 0, tick);
        if (!hasAim) return false;
        leanEye(eye);
        AiSkill skill = AiSkill.current();
        float y, p;
        boolean on;
        if (hasExact) {
            y = AiAim.yawTo(eye[0], eye[2], exactX, exactZ);
            p = AiAim.pitchTo(eye[0], eye[1], eye[2], exactX, exactY, exactZ);
            on = true;
        } else {
            double mx = tracking ? sight.moveX : 0, my = tracking ? sight.moveY : 0, mz = tracking ? sight.moveZ : 0;
            sight.turn(tick, skill, eye[0], eye[1], eye[2], body.yHeadRot(), body.xRot(), aimX, aimY, aimZ, mx, my, mz, gauss);
            y = sight.yaw;
            p = sight.pitch;
            // on target as far as it knows: a kick it has not noticed yet left out
            on = AiAim.onTarget(skill, eye[0], eye[1], eye[2], sight.seenYaw, sight.seenPitch, aimX, aimY, aimZ, halfHeight);
        }
        // a rubber slug's confusion swings the aim off (it fires all the same, as it believes it is on)
        float[] off = rubberDrift();
        if (off != null) {
            y += off[0];
            p = Math.clamp(p + off[1], -90F, 90F);
        }
        face(y, p, skill);
        return on;
    }

    /**
     * Each shot's kick on the view, from NTM's recoil for the gun and the part of it that stays, as on a player's camera
     * (AiRecoil), aiming or not, lying or not; AiAim keeps it.
     */
    void recoil(int shots, long tick) {
        float strength = AiRecoil.strength();
        String id = ITEMS.name(body.held(Body.MAIN_HAND));
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
        boolean aiming = GUNS.aiming(body, Body.MAIN_HAND), lying = recoilLying();
        for (int i = 0; i < shots; i++) {
            burstShots++;
            drift = AiRecoil.drift(drift, first && i == 0, (float) random.nextGaussian());
            float[] d = AiRecoil.degrees(k, id, strength, burstShots, drift, aiming, lying, gauss);
            float[] moved = sight.kick(d[0], d[1], d[2], d[3], k.rebound(), skill);
            face(body.yHeadRot() + moved[0], Math.max(-90F, Math.min(90F, body.xRot() + moved[1])), skill);
        }
    }

    /**
     * The barrel's heat holds its fire (Overheat): from AI_WAIT until it is down to AI_RESUME; warmer than AI_SHORT, a burst
     * is cut at the gun's aiBurst shots and the pause after it is longer than a burst's gap (BURST_GAP), the hotter the
     * longer: the next burst starts its climb over.
     */
    boolean heatHolds(long time) {
        int gun = GUNS.heatGun(body, Body.MAIN_HAND);
        if (gun < 0) return false;
        OverheatBase.Gun g = HEAT_GUNS[gun];
        float h = GUNS.heat(body, Body.MAIN_HAND, time);
        if (h >= OverheatBase.AI_WAIT) cooling = true;
        else if (h <= OverheatBase.AI_RESUME) cooling = false;
        if (cooling || time < heatPauseUntil) return true;
        if (h > OverheatBase.AI_SHORT && burstShots >= g.aiBurst && lastShot != Long.MIN_VALUE && time - lastShot <= BURST_GAP) {
            heatPauseUntil = time + heatPause(h);
            return true;
        }
        return false;
    }

    /** it lies for its recoil: the prone feature on and it lying (Feature.PRONE, Prone.lying) */
    abstract boolean recoilLying();

    /** the eye it aims from, leaned as it leans (AiLean.eye) */
    abstract void leanEye(double[] out);

    /** a rubber slug's confusion now, {yaw, pitch} degrees, read only with the rubber slug on (null: off) */
    abstract float @Nullable [] rubberDrift();

    /** the cone kept clear for the gun it holds */
    double clearCone() {
        int item = body.held(Body.MAIN_HAND);
        if (item == coneOf) return cone;
        coneOf = item;
        cone = CLEAR_CONE;
        boolean gun = GUNS.isGun(body, Body.MAIN_HAND);
        try {
            if (gun && delay() <= AUTO_DELAY) cone = AUTO_CONE;
        } catch (RuntimeException e) {
            cone = CLEAR_CONE;
        }
        return cone;
    }

    private float delay() {
        GUNS.receiver(body, Body.MAIN_HAND, 1 << NtmGuns.R_DELAY_AFTER_FIRE, rec);
        return rec[NtmGuns.R_DELAY_AFTER_FIRE];
    }

    /** rounds in the gun, -1 without a gun whose rounds are counted */
    public int loaded() {
        return GUNS.loaded(body, Body.MAIN_HAND);
    }

    /** how fast its gun's bullets fly, blocks a tick: NTM's speed, and faster by bulletSpeed as MixinBulletServer makes it */
    double bulletSpeed() {
        try {
            if (GUNS.bullet(body, Body.MAIN_HAND, bul) && bul[NtmGuns.B_VELOCITY] > 0F) {
                float v = bul[NtmGuns.B_VELOCITY];
                return v >= 5F ? v * ReaimTimes.number("bulletSpeed", 1.6F) : v;
            }
        } catch (RuntimeException e) {
            // a magazine that wants an inventory: NTM's usual speed
        }
        return 16;
    }

    // the gun the lead's speed was worked out for (Body.held), -1 none yet
    private int leadOf = -1;
    private long leadAt = Long.MIN_VALUE;
    private double leadSpeed = 16;

    /** bulletSpeed for the line kept clear of its own (AiBots.leadLine), worked out again when its gun changes or a second on */
    double leadSpeed(long tick) {
        int item = body.held(Body.MAIN_HAND);
        if (item != leadOf || tick - leadAt > LEAD_AGAIN || tick < leadAt) {
            leadOf = item;
            leadAt = tick;
            leadSpeed = Math.max(1, bulletSpeed());
        }
        return leadSpeed;
    }

    /** ticks a gun's bullet speed is kept for the line's lead (a server setting may change it) */
    private static final int LEAD_AGAIN = 20;

    /** the distance it would rather fight at with no gun to go by, blocks */
    static final double FIGHT_AT = 20;

    /**
     * The distance its gun is best at, blocks: where its shot strays about half a block (a body's half width) from
     * NTM's spread unaimed (the gun's, the round's, the hip's), 8 to 40; a shotgun's pellets 12 at most; a heavy single
     * shot that fires slowly (a marksman's rifle) 32 at least.
     */
    public double fightAt() {
        try {
            if (!GUNS.bullet(body, Body.MAIN_HAND, bul)) return FIGHT_AT;
            GUNS.receiver(body, Body.MAIN_HAND, 1 << NtmGuns.R_INNATE_SPREAD | 1 << NtmGuns.R_AMMO_SPREAD | 1 << NtmGuns.R_HIPFIRE_SPREAD, rec);
            double spread = rec[NtmGuns.R_INNATE_SPREAD] + bul[NtmGuns.B_SPREAD] * rec[NtmGuns.R_AMMO_SPREAD] + rec[NtmGuns.R_HIPFIRE_SPREAD];
            double at = spread <= 1e-4 ? 40 : Math.max(8, Math.min(40, 0.5 / spread));
            // buckshot, not a Duplex round's two bullets (a rifle's)
            if (bul[NtmGuns.B_PROJECTILES_MAX] > 1 && bul[NtmGuns.B_DUPLEX] == 0F) return Math.min(at, 12);
            GUNS.receiver(body, Body.MAIN_HAND, 1 << NtmGuns.R_BASE_DAMAGE, rec);
            if (rec[NtmGuns.R_BASE_DAMAGE] * bul[NtmGuns.B_DAMAGE_MULT] >= 25 && delay() >= 20) return Math.max(at, 32);
            return at;
        } catch (RuntimeException e) {
            return FIGHT_AT;
        }
    }

    public int capacity() {
        return GUNS.capacity(body, Body.MAIN_HAND);
    }

    /**
     * How long a reload of its gun takes, ticks: as long as its last one took, else NTM's start and end of a reload
     * (and the cocking an empty gun adds); a gun that loads round by round takes longer, and its first reload shows it.
     */
    public int reloadTicks() {
        if (lastReload > 0) return lastReload;
        if (!GUNS.isGun(body, Body.MAIN_HAND)) return 0;
        try {
            GUNS.receiver(body, Body.MAIN_HAND, 1 << NtmGuns.R_RELOAD_BEGIN | 1 << NtmGuns.R_RELOAD_END, rec);
            int t = (int) rec[NtmGuns.R_RELOAD_BEGIN] + (int) rec[NtmGuns.R_RELOAD_END];
            if (loaded() > 0) return t;
            GUNS.receiver(body, Body.MAIN_HAND, 1 << NtmGuns.R_COCK_ON_EMPTY_PRE | 1 << NtmGuns.R_COCK_ON_EMPTY_POST, rec);
            return t + (int) rec[NtmGuns.R_COCK_ON_EMPTY_PRE] + (int) rec[NtmGuns.R_COCK_ON_EMPTY_POST];
        } catch (RuntimeException e) {
            return 0;
        }
    }

    public boolean reloading() {
        return GUNS.isGun(body, Body.MAIN_HAND) && GUNS.state(body, Body.MAIN_HAND) == NtmGuns.RELOADING;
    }

    /** fills the gun from nothing, not from the reserve (a mob that comes in loaded) */
    public void loadFree() {
        int n = GUNS.fill(body, Body.MAIN_HAND);
        if (n == NtmGuns.NO_ROUNDS) return;
        ammo.lastLoaded = n;
    }

    /** let go of every key: the mob stops shooting at once */
    public void release() {
        wantFire = false;
        if (!GUNS.isGun(body, Body.MAIN_HAND)) return;
        if (held) GUNS.key(body, Body.MAIN_HAND, NtmGuns.PRIMARY, false);
        if (reloadHeld) GUNS.key(body, Body.MAIN_HAND, NtmGuns.RELOAD, false);
        held = reloadHeld = false;
    }

    /** its tick (AiGunner.tick), first: the reload key down since the last tick let go */
    void letGoReload() {
        if (reloadHeld) {
            GUNS.key(body, Body.MAIN_HAND, NtmGuns.RELOAD, false);
            reloadHeld = false;
        }
    }

    /**
     * its tick, once it knows whether to shoot: the trigger pulled, let go, or let go to be pulled again (a gun that
     * fires once a pull, ready again), then the reload key down for an idle gun empty or told to (with rounds to load
     * and room for them)
     */
    void trigger(boolean shoot, boolean idle, int loaded) {
        if (shoot) {
            if (!held) {
                if (proneBlocks()) crawlPulls++;
                GUNS.key(body, Body.MAIN_HAND, NtmGuns.PRIMARY, true);
                held = true;
                pulls++;
            } else if (idle) {
                // a gun that fires once a pull is ready again: let go, pull again next tick
                GUNS.key(body, Body.MAIN_HAND, NtmGuns.PRIMARY, false);
                held = false;
            }
        } else if (held) {
            GUNS.key(body, Body.MAIN_HAND, NtmGuns.PRIMARY, false);
            held = false;
        }
        if (held && proneBlocks()) crawlHeldTicks++;
        if (!held && idle && (wantReload || loaded == 0) && ammo.any()
                && loaded >= 0 && loaded < capacity()) {
            GUNS.key(body, Body.MAIN_HAND, NtmGuns.RELOAD, true);
            reloadHeld = true;
        }
    }

    /**
     * its tick, last: the magazine's rounds kept as its ammo has them (AiAmmo.settle), and the rounds gone since the last
     * tick (0 when it is no magazine of single rounds)
     */
    int settleMagazine() {
        int gone = 0;
        int before = ammo.lastLoaded;
        int now = GUNS.settle(body, Body.MAIN_HAND, settle);
        if (now != NtmGuns.NO_ROUNDS && before > 0 && now < before) gone = before - Math.max(0, now);
        return gone;
    }

    /** a pull now would be thrown away: it lies and crawls (ProneFire.blocks on its mob and the stack in its hand) */
    abstract boolean proneBlocks();

    /** nothing in the gun and nothing to reload with */
    public boolean dry() {
        return loaded() == 0 && !ammo.any();
    }

    /**
     * A count of ticks it held fire for one reason, after this tick: one more while it holds (the first, the holds
     * over before); none once it shot, or the holds are over (its aim taken away, no fire wanted for HOLD_PAUSE); else
     * as it was (a pause of its burst keeps it).
     */
    static int held(int count, boolean holds, boolean shot, boolean over) {
        return holds ? (over ? 1 : count + 1) : shot || over ? 0 : count;
    }

    // the gun and aim the offset was read for (the receiver's, as EntityBulletBaseMK4 takes it: aside, up, forward)
    private int offsetOf = -1;
    private boolean offsetAiming;
    private @Nullable Vec3d offset;

    /**
     * The receiver's projectile offset (scoped when it aims) as EntityBulletBaseMK4 takes it, aside, up, forward (NTM's
     * Vec3 the other way round); null with none. Read again when the gun or the aim changes; the hand must hold a gun.
     */
    @Nullable Vec3d offset(boolean aiming) {
        int item = body.held(Body.MAIN_HAND);
        if (item != offsetOf || aiming != offsetAiming) {
            offsetOf = item;
            offsetAiming = aiming;
            offset = null;
            try {
                if (GUNS.muzzle(body, Body.MAIN_HAND, aiming, off)) offset = new Vec3d(off[2], off[1], off[0]);
            } catch (RuntimeException e) {
                offset = null;
            }
        }
        return offset;
    }

    /** the pause after a burst cut by the heat, ticks: just over a burst's gap when warm, three seconds near the top */
    static int heatPause(float h) {
        return BURST_GAP + 5 + Math.round(45 * OverheatBase.ramp(h, OverheatBase.AI_SHORT, OverheatBase.AI_WAIT));
    }
}
