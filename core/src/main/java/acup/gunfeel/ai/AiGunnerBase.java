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

    final Body body;
    public final AiAmmo ammo;
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
    /** a gap between shots, ticks, after which a new burst starts (its climb grows again from the first shot) */
    static final int BURST_GAP = 10;

    // the port's numbers, this gunner's own
    private final float[] rec = new float[NtmGuns.R_COUNT], bul = new float[NtmGuns.B_COUNT];
    private final double[] off = new double[3];

    AiGunnerBase(Body body, AiAmmo ammo) {
        this.body = body;
        this.ammo = ammo;
    }

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
        int now = GUNS.rounds(body, Body.MAIN_HAND);
        if (now != NtmGuns.NO_ROUNDS) {
            int before = ammo.lastLoaded;
            int keep = ammo.settle(now);
            if (keep != now) GUNS.setLoaded(body, Body.MAIN_HAND, keep);
            if (before > 0 && now < before) gone = before - Math.max(0, now);
        }
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
