package acup.gunfeel.host;

/**
 * NTM's guns (sedna's ItemGunBaseNT) in a living body's hand ({@link Body#MAIN_HAND}, {@link Body#OFF_HAND}), read
 * live: each call looks at the stack in that hand now, the port keeps nothing. The gun's config 0 and its receiver 0,
 * as the AI reads them. A version without NTM's guns, and core's own tests, get {@link #NONE}: no gun in any hand.
 * NTM's exceptions go up to the caller, but where a method says it catches (the way to the magazine, as the AI's
 * gunner has always caught it). The numbers come into the caller's own array at fixed places; an int of NTM's (ticks,
 * a count) is put in a float as it is, exact below 2^24.
 */
public interface NtmGuns {
    /** NTM's GunState by its name, as core numbers it; -1 is no gun, or a state of a version's own */
    int IDLE = 0, RELOADING = 1, COOLDOWN = 2, JAMMED = 3, DRAWING = 4;

    /** receiver 0's numbers, their places in {@link #receiver}'s out and their bits in its mask */
    int R_INNATE_SPREAD = 0, R_AMMO_SPREAD = 1, R_HIPFIRE_SPREAD = 2, R_BASE_DAMAGE = 3, R_DELAY_AFTER_FIRE = 4,
            R_RELOAD_BEGIN = 5, R_RELOAD_END = 6, R_COCK_ON_EMPTY_PRE = 7, R_COCK_ON_EMPTY_POST = 8, R_COUNT = 9;

    /** the loaded bullet's numbers, their places in {@link #bullet}'s out; DUPLEX 1 is the mod's Duplex round */
    int B_VELOCITY = 0, B_SPREAD = 1, B_PROJECTILES_MAX = 2, B_DAMAGE_MULT = 3, B_DUPLEX = 4, B_COUNT = 5;

    /** {@link #rounds} and {@link #fill} where there is no magazine that counts single rounds */
    int NO_ROUNDS = Integer.MIN_VALUE;

    /** NTM's keys a mob presses on its gun ({@link #key}): GUN_PRIMARY, RELOAD */
    int PRIMARY = 0, RELOAD = 1;

    /** the hand holds one of NTM's guns */
    boolean isGun(Body holder, int hand);

    /**
     * Rounds in its magazine; -1 with no gun, no magazine or one that does not count single rounds of one type. The
     * way to the magazine is caught (none then), the count is not.
     */
    int loaded(Body holder, int hand);

    /**
     * Rounds in a magazine that counts single rounds of one type, as it holds them; {@link #NO_ROUNDS} with no gun or no
     * such magazine. The way to the magazine is caught, the count is not.
     */
    int rounds(Body holder, int hand);

    /** Its magazine's capacity; 0 with no gun or no magazine. The way to the magazine is caught, the capacity is not. */
    int capacity(Body holder, int hand);

    /** Its state ({@link #IDLE}...), -1 with no gun or a state core has no number for. Not caught. */
    int state(Body holder, int hand);

    /**
     * The loaded round's numbers into {@code out} ({@link #B_COUNT} long); false with no gun, no magazine or a type
     * that is no bullet. DUPLEX is worked out only for more than one projectile (else 0). The way to the magazine is
     * caught (false then), the magazine's type is not.
     */
    boolean bullet(Body holder, int hand, float[] out);

    /**
     * The receiver's numbers asked for by {@code mask} (bits 1 << R_...) into their places in {@code out}
     * ({@link #R_COUNT} long), read in the order of their places; the rest of out is left as it was. The hand must hold
     * a gun ({@link #isGun}). Not caught.
     */
    void receiver(Body holder, int hand, int mask, float[] out);

    /**
     * The receiver's projectile offset (the scoped one when {@code scoped}) as NTM gives it, x, y, z into {@code out};
     * false when it has none. The hand must hold a gun. Not caught.
     */
    boolean muzzle(Body holder, int hand, boolean scoped, double[] out);

    // --- 2. writes, each one of NTM's, in the caller's order

    /**
     * NTM's key ({@link #PRIMARY}, {@link #RELOAD}) down or up on the gun in the hand, as a mob presses it (no player,
     * no inventory). The hand must hold a gun. Not caught.
     */
    void key(Body holder, int hand, int key, boolean down);

    /** Its magazine's rounds set to {@code n}; nothing with no magazine. The way to the magazine is caught, the setting is not. */
    void setLoaded(Body holder, int hand, int n);

    /**
     * Its magazine filled from nothing (NTM's reload with no inventory), then its rounds as {@link #loaded} gives them;
     * {@link #NO_ROUNDS} with no gun or no magazine, nothing done. The way to the magazine is caught, the rest is not.
     */
    int fill(Body holder, int hand);

    /** No guns. */
    NtmGuns NONE = new NtmGuns() {
        @Override
        public boolean isGun(Body holder, int hand) {
            return false;
        }

        @Override
        public int loaded(Body holder, int hand) {
            return -1;
        }

        @Override
        public int rounds(Body holder, int hand) {
            return NO_ROUNDS;
        }

        @Override
        public int capacity(Body holder, int hand) {
            return 0;
        }

        @Override
        public int state(Body holder, int hand) {
            return -1;
        }

        @Override
        public boolean bullet(Body holder, int hand, float[] out) {
            return false;
        }

        @Override
        public void receiver(Body holder, int hand, int mask, float[] out) {
            throw new IllegalStateException("no gun");
        }

        @Override
        public boolean muzzle(Body holder, int hand, boolean scoped, double[] out) {
            throw new IllegalStateException("no gun");
        }

        @Override
        public void key(Body holder, int hand, int key, boolean down) {
            throw new IllegalStateException("no gun");
        }

        @Override
        public void setLoaded(Body holder, int hand, int n) {
        }

        @Override
        public int fill(Body holder, int hand) {
            return NO_ROUNDS;
        }
    };
}
