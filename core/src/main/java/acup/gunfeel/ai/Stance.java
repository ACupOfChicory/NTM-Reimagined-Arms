package acup.gunfeel.ai;

/** How an AI mob holds its body: standing, crouching, lying (AiPose's stance, the bytes the clients are sent). */
public final class Stance {
    private Stance() {
    }

    public static final byte STAND = 0, CROUCH = 1, PRONE = 2;
}
