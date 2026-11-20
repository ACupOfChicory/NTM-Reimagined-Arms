package acup.gunfeel.host;

import org.jspecify.annotations.Nullable;

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

    /** {@link #settle} and {@link #fill} where there is no magazine that counts single rounds */
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

    /** {@link #fireTraits}'s places: refire on hold (1, else 0), delay after fire (ticks), a bullet loaded (1, else 0), its velocity, a laser (1, else 0); then whether the magazine counts single rounds of one type (1, else 0) */
    int T_REFIRE = 0, T_DELAY = 1, T_BULLET = 2, T_VELOCITY = 3, T_LASER = 4, T_SINGLE = 5, T_COUNT = 6;

    /**
     * What makes the gun an automatic for a mob's role (AiRoleBase): its receiver and magazine found once, then each of
     * these read once, in this order and none skipped (NTM's getters for them are pure reads; only the magazine's own
     * lookup runs the weapon mods' writes): into {@code out} ({@link #T_COUNT} long) the receiver's refire on hold and
     * delay after fire, whether the magazine's type is a bullet, its velocity and whether it is a laser (0 for no
     * bullet), whether the magazine counts single rounds of one type. Gives that magazine's capacity (0 for another
     * magazine, not read); {@link #NO_ROUNDS} with no magazine or where NTM threw on the way (a magazine that wants an
     * inventory), {@code out} then as far as it got. The hand must hold a gun; the hand is read before the catch.
     */
    int fireTraits(Body holder, int hand, float[] out);

    /**
     * The rounds its first magazine (receiver 0's) takes, by their {@link RoundTable} numbers in NTM's order (a round
     * twice as often as NTM lists it), into {@code out}: how many there are, -1 with no gun, no magazine, the GL-54's
     * (its magazines hold the grenades) or where NTM threw on the way to it; more than {@code out} holds are counted,
     * not written. The magazine is found once.
     */
    int rounds(Body holder, int hand, int[] out);

    /**
     * The ammo menu's {@link #rounds}, its own read (AMMO_CHOICE's; the kit's is MOB_AI's): the same rounds of the first
     * magazine into {@code out}, how many; -1 with no gun or no magazine, {@link #ROUNDS_GL54} for the GL-54's (its
     * magazines hold the grenades, the menu's entries for them are 26.2's). Not caught, as the menu never caught it.
     */
    int menuRounds(Body holder, int hand, int[] out);

    /** {@link #menuRounds}'s answer for the GL-54's magazine */
    int ROUNDS_GL54 = -2;

    /**
     * Rounds of this one ({@link RoundTable} number) the holder carries (its inventory and its ammo bags; 0 for a
     * holder with no inventory, a mob), {@link #INFINITE_ROUNDS} where an infinite bag holds it.
     */
    int reserve(Body holder, int round);

    /** {@link #reserve}'s answer for an infinite bag */
    int INFINITE_ROUNDS = -1;

    /**
     * The type of its first magazine ({@link RoundTable} number) as NTM gives it with the holder's inventory (a
     * player's; none for a mob): the round loaded, or for a magazine not locked the first the inventory holds; 0 for
     * none, no magazine or a type that is no bullet. The magazine is found once. Not caught.
     */
    int loadedRound(Body holder, int hand);

    /** {@link #choiceStats}'s places, read in this order: the gun's wear and durability, the receiver's damage and split */
    int C_WEAR = 0, C_DURABILITY = 1, C_BASE_DAMAGE = 2, C_SPLIT = 3, C_COUNT = 4;

    /**
     * The gun's numbers for the ammo menu's figures into {@code out} ({@link #C_COUNT} long), config 0 and receiver 0
     * found once; false with no gun, one with no config, or where NTM threw on the way (out then as far as it got).
     */
    boolean choiceStats(Body holder, int hand, float[] out);

    /** {@link #roundStats}'s places */
    int S_DAMAGE_MULT = 0, S_PROJECTILES_MIN = 1, S_PROJECTILES_MAX = 2, S_HEADSHOT_MULT = 3, S_THRESHOLD_NEGATION = 4,
            S_ARMOR_PIERCING = 5, S_COUNT = 6;

    /** The round's numbers ({@link RoundTable} number) for the ammo menu's figures into {@code out} ({@link #S_COUNT} long). */
    void roundStats(int round, float[] out);

    /** NTM's aiming (the gun's own state: down its sights) */
    boolean aiming(Body holder, int hand);

    /** The barrel's heat (the mod's, kept on NTM's gun): which of OverheatBase.Gun it is, its ordinal; -1 for none. */
    int heatGun(Body holder, int hand);

    /** The barrel's heat now (the game's tick now): Overheat.heat, 0 off or for no gun of OverheatBase.Gun. */
    float heat(Body holder, int hand, long now);

    /** {@link #hudBlocks}'s kinds of a block: the GL-54's, a magazine of single rounds of one type, a belt off an infinite bag, another */
    int HUD_GL54 = 0, HUD_SINGLE = 1, HUD_BELT_INFINITE = 2, HUD_OTHER = 3;

    /**
     * {@link #hudBlocks}'s places in a block: its ints (kind, rounds in it, its capacity, the rounds it may yet load or
     * {@link #INFINITE_ROUNDS}, the ammo lock 1 or 0), floats (the config's wear, its durability), objects (the icon, a
     * version's own; the GL-54's {@code acup.gunfeel.gl54.Gl54Hud.Model}) and its one text (NTM's own words for another)
     */
    int H_KIND = 0, H_AMOUNT = 1, H_CAPACITY = 2, H_RESERVE = 3, H_LOCKED = 4, H_INTS = 5;
    int H_WEAR = 0, H_DURABILITY = 1, H_FLOATS = 2;
    int H_ICON = 0, H_GL54 = 1, H_OBJECTS = 2;

    /**
     * The corner HUD's blocks, read once a frame: one per magazine, configs from the last down, each config's receivers
     * from the last down, a receiver with no magazine skipped (its magazine found once). Each is read in the HUD's own
     * order (wear, durability, the icon, its kind's numbers, the lock) into its places, block i at i x H_INTS, i x
     * H_FLOATS, i x H_OBJECTS and i of {@code texts}; what a kind has no number for is left as it was. Gives how many
     * blocks there are: more than {@code texts} holds are counted, not read past the magazine. Not caught.
     */
    int hudBlocks(Body holder, int hand, int[] ints, float[] floats, Object[] objects, String[] texts);

    // --- 2. writes, each one of NTM's, in the caller's order

    /**
     * NTM's key ({@link #PRIMARY}, {@link #RELOAD}) down or up on the gun in the hand, as a mob presses it (no player,
     * no inventory). The hand must hold a gun. Not caught.
     */
    void key(Body holder, int hand, int key, boolean down);

    /** {@link #loadRound}'s answers */
    int LOAD_UNLISTED = -1, LOAD_OTHER = 0, LOAD_BELT = 1, LOAD_SINGLE = 2;

    /**
     * Its first magazine loads this round ({@link RoundTable} number) from now on, the magazine found once: NTM's own
     * number of the round looked up, then a belt has it set as its type and is locked to it (the ammo lock, the mod's
     * value), a magazine of single rounds of one type has it set as its type. {@link #LOAD_BELT}, {@link #LOAD_SINGLE};
     * {@link #LOAD_UNLISTED} where NTM does not list the round, {@link #LOAD_OTHER} for another magazine or none,
     * nothing written then. The hand must hold a gun. Not caught.
     */
    int loadRound(Body holder, int hand, int round);

    /**
     * A magazine that counts single rounds of one type, found once: its rounds {@code now}, then {@code keep =
     * settle(now)}, set to {@code keep} only when it is not {@code now}. Gives {@code now}; {@link #NO_ROUNDS} with no
     * gun or no such magazine, {@code settle} not called. The way to the magazine is caught, the rest is not.
     */
    int settle(Body holder, int hand, java.util.function.IntUnaryOperator settle);

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
        public int fireTraits(Body holder, int hand, float[] out) {
            return NO_ROUNDS;
        }

        @Override
        public int rounds(Body holder, int hand, int[] out) {
            return -1;
        }

        @Override
        public int loadRound(Body holder, int hand, int round) {
            throw new IllegalStateException("no gun");
        }

        @Override
        public int menuRounds(Body holder, int hand, int[] out) {
            return -1;
        }

        @Override
        public int hudBlocks(Body holder, int hand, int[] ints, float[] floats, Object[] objects, String[] texts) {
            return 0;
        }

        @Override
        public int reserve(Body holder, int round) {
            return 0;
        }

        @Override
        public int loadedRound(Body holder, int hand) {
            return 0;
        }

        @Override
        public boolean choiceStats(Body holder, int hand, float[] out) {
            return false;
        }

        @Override
        public void roundStats(int round, float[] out) {
            throw new IllegalStateException("no round");
        }

        @Override
        public void key(Body holder, int hand, int key, boolean down) {
            throw new IllegalStateException("no gun");
        }

        @Override
        public boolean aiming(Body holder, int hand) {
            return false;
        }

        @Override
        public int heatGun(Body holder, int hand) {
            return -1;
        }

        @Override
        public float heat(Body holder, int hand, long now) {
            return 0F;
        }

        @Override
        public int settle(Body holder, int hand, java.util.function.IntUnaryOperator settle) {
            return NO_ROUNDS;
        }

        @Override
        public int fill(Body holder, int hand) {
            return NO_ROUNDS;
        }

        @Override
        public int reach(Body holder, int hand) {
            return 0;
        }

        @Override
        public int maxDelay(Body holder, int hand) {
            return 0;
        }

        @Override
        public @Nullable Shell grenadeShell(Body holder) {
            return null;
        }
    };

    /**
     * ticks the gun in that hand takes from a shot to the next one after a reload from empty (begin, a cycle per
     * round after the first of a one-at-a-time magazine, end, the empty gun's cocking), the longest of its configs;
     * 0: not an NTM gun
     */
    int reach(Body holder, int hand);

    /** Longest delay after fire across all configs and receivers; 0 with no NTM gun. Not caught. */
    int maxDelay(Body holder, int hand);

    /** 26.2: AiGrenadier.shell in the main hand, its velocity, gravity and expires. */
    @Nullable Shell grenadeShell(Body holder);
}
