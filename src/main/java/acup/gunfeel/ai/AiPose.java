package acup.gunfeel.ai;

import net.minecraft.world.entity.LivingEntity;

/**
 * STAND-IN until c7's AiPose (notes/ai/pose-contract.md) is merged: the same record and constants, never set, so every
 * mob reads {@link #IDLE} and keeps its vanilla pose. Removed when the real class lands.
 */
public record AiPose(byte stance, byte lean, byte prevLean, int leanTick, byte arms, byte action, int actionTick,
        short actionTicks) {
    public static final byte STAND = 0, CROUCH = 1, PRONE = 2;
    public static final byte HOLD = 0, AIM = 1, BLIND = 2, NONE = 3;
    public static final byte ACT_NONE = 0, RELOAD = 1, THROW = 2, SIGNAL_POINT = 3, SIGNAL_HALT = 4, SIGNAL_COME = 5,
            SHOT = 6;
    public static final AiPose IDLE = new AiPose(STAND, (byte) 0, (byte) 0, 0, HOLD, ACT_NONE, 0, (short) 0);

    public static AiPose of(LivingEntity entity) {
        return IDLE;
    }
}
