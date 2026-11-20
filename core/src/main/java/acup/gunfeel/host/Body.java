package acup.gunfeel.host;

import org.jspecify.annotations.Nullable;

/**
 * One entity of the version's, read live: each call asks the entity itself now, as its own getter does, no copy (the
 * AI writes to its mob within a tick, and what it reads after must be what it wrote). One Body for an entity for its
 * whole life, made the first time it is asked for: holding a Body is holding the entity, a removed one too, and two
 * Bodies are the same entity only if they are the same object.
 *
 * <p>Its {@link #id} is the game's number for the entity, in this process and its level only: never into packets,
 * files or traces. A triple (a place, a direction) is written into out[0..2], a box into out[0..5] (min x, y, z, max x,
 * y, z), so nothing is made for the asker. The methods marked living are for a living entity only ({@link #living});
 * on another they throw, as the cast does now.
 */
public interface Body {
    /** the game's number for it (in this process and level only) */
    int id();

    /** the number of its type (EntityTypeNumbers); the same number on every call (26.2: EntityTypeNumbers.number(getType())) */
    int typeNumber();
    /** its age in ticks (tickCount) */
    int age();

    /** whether it is a living one (the game's LivingEntity) */
    boolean living();

    /** whether it is a player */
    boolean player();

    double x();

    double y();

    double z();

    /** the block it is in (the game's blockPosition) */
    int blockX();

    int blockY();

    int blockZ();

    /** its box's height (getBbHeight) */
    float bbHeight();

    /** its eyes over its feet in its pose now */
    float eyeHeight();

    /** its eyes over its feet in a stance ({@link acup.gunfeel.ai.Stance}), as the game's size for its pose */
    float eyeHeight(byte stance);

    /** its height in a stance ({@link acup.gunfeel.ai.Stance}), as the game's size for its pose */
    float height(byte stance);

    /** its eyes' place */
    void eye(double[] out);

    /** the way it looks (the game's getLookAngle) */
    void look(double[] out);

    /** the way it looks between the last tick and this (getViewVector) */
    void view(float partial, double[] out);

    /** its motion (getDeltaMovement) */
    void motion(double[] out);

    /** the step it made over its last tick, as the server counts it (lastKnownSpeed) */
    void lastSpeed(double[] out);

    /**
     * its known speed as the game gives it to the living spread (getKnownSpeed: a player riding and steering it
     * gives the player's), not {@link #lastSpeed}
     */
    void knownSpeed(double[] out);

    /** its box (getBoundingBox) */
    void box(double[] out);

    float yRot();

    float yHeadRot();

    float xRot();

    boolean onGround();

    /** in water (the game's isInWater) */
    boolean inWater();

    /** riding something (isPassenger) */
    boolean passenger();

    /** its own random numbers (getRandom): the calls go to the entity's generator, in its sequence */
    acup.gunfeel.GameRandom random();

    /** on a ladder, a vine (onClimbable); living */
    boolean climbing();

    /** crouching (its pose is the game's CROUCHING) */
    boolean crouching();

    /** its pose's name, for a line of text only */
    String poseName();

    boolean sprinting();

    /** lying down (the mod's own prone); living */
    boolean lying();

    /** in the swimming pose out of water (isVisuallyCrawling: under a trapdoor, the mod's prone), not {@link #lying} */
    boolean crawling();

    /** gliding on elytra (isFallFlying); living */
    boolean fallFlying();

    /** a player flying as in creative (its abilities' flying); false for any other */
    boolean flying();

    /**
     * Its stance by the game's pose: standing {@link acup.gunfeel.ai.Stance#STAND}, crouching CROUCH, the swimming
     * pose (the one an AI mob lies down in, AiProne) PRONE; any other pose -1. After {@link BodyControl#stance}(this,
     * s) it is s.
     */
    byte stance();

    /** asleep in a bed (isSleeping); living */
    boolean sleeping();

    /**
     * The box its type has at its feet, whatever its pose (getType().getDimensions().makeBoundingBox(position())): the
     * room it takes standing; into out as {@link #box}.
     */
    void typeBox(double[] out);

    boolean alive();

    boolean removed();

    /** its health (getHealth); living */
    float health();

    /** Last attacker (getLastHurtByMob); living only, null if none. The attacker's one Body, also on repeated calls. */
    @Nullable Body lastHurtBy();

    /** its last attacker's timestamp (getLastHurtByMobTimestamp); living only */
    int lastHurtTime();

    /** Credited killer (getKillCredit); living only, null if none. The entity's one Body, also on repeated calls. */
    @Nullable Body killCredit();

    /** its maximum health (getMaxHealth); living */
    float maxHealth();

    /** a player watching only (isSpectator); false for any other */
    boolean spectator();

    /** a player in creative (isCreative); false for any other */
    boolean creative();

    /** left-handed; a mob only */
    boolean leftHanded();

    /** the hands of {@link #held} */
    int MAIN_HAND = 0, OFF_HAND = 1;

    /**
     * The item in a hand by the mod's own number (one an Item, the same while the game runs; not the registry's, which
     * a server renumbers), 0 an empty hand; the living only
     */
    int held(int hand);

    /** the armour slots of {@link #worn}, head to feet */
    int HEAD = 0, CHEST = 1, LEGS = 2, FEET = 3;

    /**
     * The item worn in an armour slot ({@link #HEAD} .. {@link #FEET}) by the mod's own number, as {@link #held}'s; 0
     * an empty slot (a stack of none is empty); the living only
     */
    int worn(int slot);

    /** its walking speed (the movement speed attribute); living */
    double speed();

    int EFFECT_REGENERATION = 0, EFFECT_RESISTANCE = 1;

    /** the effect's amplifier, -1 without it; living */
    int effectAmplifier(int which);

    /** its id that outlives a respawn (the game's getUUID): the same object each call */
    java.util.UUID uuid();

    /** its team's name, null when it has none */
    @Nullable String team();

    /** it has this tag (/tag: the game's entity tags) */
    boolean tagged(String tag);

    /**
     * The level it is in, the version's object: to look it up in what core keeps per level (LevelMap), never to be
     * read. One level is one object (what holds it compares by ==).
     */
    Object level();
}
