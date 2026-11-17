package acup.gunfeel.ai;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.JumpControl;

/** An AI mob's jump, one its legs may take back this tick (AiMoveControl: the game's jump out of a ladder's cell) */
final class AiJumpControl extends JumpControl {
    AiJumpControl(Mob mob) {
        super(mob);
    }

    /** the jump asked for this tick not made */
    void cancel() {
        jump = false;
    }
}
