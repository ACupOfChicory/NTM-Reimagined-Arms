// SPDX-License-Identifier: GPL-3.0-only
// Copied from TaCZ (Timeless and Classics Zero), MCModderAnchor/TACZ and contributors, GPL-3.0.
// https://github.com/MCModderAnchor/TACZ, revision 19f14c8, src/main/java/com/tacz/guns/client/event/TickAnimationEvent.java:
// the choice of the state in tickAnimation(ClientTickEvent). Changes: the package and class; the state is returned
// instead of being triggered on TaCZ's animation state machine (INPUT_RUN, INPUT_WALK, INPUT_IDLE).
// port 1.7.10: EntityPlayerSP; input is movementInput, isMovingSlowly() is isSneaking() (26.2: crouching or crawling,
// 1.7.10 has no crawling), the move vector's length is that of (moveStrafe, moveForward).
package acup.gunfeel.tacz;

import net.minecraft.client.entity.EntityPlayerSP;

/** Run, walk or idle by the player's state and movement keys, as TaCZ picks its gun animation: not by the speed. */
public enum MoveState {
    RUN, WALK, IDLE;

    public static MoveState of(EntityPlayerSP player) {
        // 群组服切世界导致的特殊 BUG 处理，正常情况不会遇到此问题
        if (player.movementInput == null) {
            return IDLE;
        }
        if (!player.isSneaking() && player.isSprinting()) {
            // 如果玩家正在移动，播放移动动画，否则播放 idle 动画
            return RUN;
        } else if (!player.isSneaking() && Math.sqrt(player.movementInput.moveStrafe * player.movementInput.moveStrafe
                + player.movementInput.moveForward * player.movementInput.moveForward) > 0.01) {
            return WALK;
        } else {
            return IDLE;
        }
    }
}
