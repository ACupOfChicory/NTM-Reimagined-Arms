package acup.gunfeel.ai;

/** How an AI mob holds its body: standing, crouching, lying (AiPose's stance, the bytes the clients are sent). */
public final class Stance {
    private Stance() {
    }

    public static final byte STAND = 0, CROUCH = 1, PRONE = 2;

    /** a crouching AI mob's size, a crouching player's (AiPose.CROUCH_SIZE: Avatar.CROUCHING_DIMENSIONS) */
    public static final float CROUCH_WIDTH = 0.6F, CROUCH_HEIGHT = 1.5F, CROUCH_EYE = 1.27F;

    /** a lying AI mob's size, a crawling player's (AiProne.SIZE) */
    public static final float PRONE_WIDTH = 0.6F, PRONE_HEIGHT = 0.6F, PRONE_EYE = 0.4F;
}
