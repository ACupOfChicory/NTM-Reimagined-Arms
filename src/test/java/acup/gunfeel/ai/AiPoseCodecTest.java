package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

// The pose as it is sent to the clients: every field there and back, the fight (pose-contract §5) with them.
class AiPoseCodecTest {
    @Test
    void everyFieldThereAndBack() {
        for (byte alert : new byte[] { AiPose.CALM, AiPose.FIGHT }) {
            AiPose p = new AiPose(AiPose.CROUCH, (byte) -40, (byte) 20, 123456, AiPose.AIM, AiPose.RELOAD, 123400, (short) 37, alert);
            ByteBuf buf = Unpooled.buffer();
            AiPose.STREAM_CODEC.encode(buf, p);
            assertEquals(p, AiPose.STREAM_CODEC.decode(buf));
        }
        // the old way to make one: calm
        assertEquals(AiPose.CALM, new AiPose(AiPose.STAND, (byte) 0, (byte) 0, 0, AiPose.HOLD, AiPose.NONE, 0, (short) 0).alert());
        // the others keep it
        AiPose f = AiPose.IDLE.withAlert(AiPose.FIGHT);
        assertEquals(AiPose.FIGHT, f.withStance(AiPose.CROUCH).withArms(AiPose.AIM).withLean((byte) 50, 10).withAction(AiPose.THROW, 10, (short) 20).alert());
    }
}
