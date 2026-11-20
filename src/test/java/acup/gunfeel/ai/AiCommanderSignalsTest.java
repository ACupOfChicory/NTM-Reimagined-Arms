package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Core's staff signals keep the game's pose and voice numbers. */
class AiCommanderSignalsTest {
    @Test
    void sameSignalsAndDuration() {
        assertEquals(AiPose.SIGNAL_POINT, AiCommanderBase.SIGNAL_POINT);
        assertEquals(AiPose.SIGNAL_COME, AiCommanderBase.SIGNAL_COME);
        assertEquals(AiVoice.SIGN_TICKS, AiCommanderBase.SIGN_TICKS);
    }
}
