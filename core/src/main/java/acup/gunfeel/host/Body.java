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

    /** its eyes over its feet in a pose ({@link acup.gunfeel.ai.Stance#STAND} or CROUCH), as the game's size for it */
    float eyeHeight(byte stance);

    /** its height in a pose ({@link acup.gunfeel.ai.Stance#STAND} or CROUCH), as the game's size for it */
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

    /** its box (getBoundingBox) */
    void box(double[] out);

    float yRot();

    float yHeadRot();

    float xRot();

    boolean onGround();

    /** on a ladder, a vine (onClimbable); living */
    boolean climbing();

    /** crouching (its pose is the game's CROUCHING) */
    boolean crouching();

    /** its pose's name, for a line of text only */
    String poseName();

    boolean sprinting();

    /** lying down (the mod's own prone); living */
    boolean lying();

    boolean alive();

    boolean removed();

    /** left-handed; a mob only */
    boolean leftHanded();

    /** its walking speed (the movement speed attribute); living */
    double speed();

    /** its team's name, null when it has none */
    @Nullable String team();
}
