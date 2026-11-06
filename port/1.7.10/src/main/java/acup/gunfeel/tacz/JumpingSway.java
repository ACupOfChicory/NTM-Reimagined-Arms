// SPDX-License-Identifier: GPL-3.0-only
// Copied from TaCZ (Timeless and Classics Zero), MCModderAnchor/TACZ and contributors, GPL-3.0.
// https://github.com/MCModderAnchor/TACZ, revision 19f14c8, src/main/java/com/tacz/guns/client/event/FirstPersonRenderGunEvent.java:
// the jumping sway (its fields and applyJumpingSway). Changes: the package and class; the method returns the sway
// instead of moving TaCZ's model root (see the note at its end).
// port 1.7.10: Minecraft.getMinecraft(), EntityPlayerSP, lastTickPosY and posY for yOld and getY(), onGround the field.
package acup.gunfeel.tacz;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;


/** The gun lags down when the player jumps and dips when they land (TaCZ's applyJumpingSway). */
public final class JumpingSway {
    // 用于跳跃延滞动画的平滑
    private static final SecondOrderDynamics JUMPING_DYNAMICS = new SecondOrderDynamics(0.28f, 1f, 0.65f, 0);
    private static final float JUMPING_Y_SWAY = -2f;
    private static final float JUMPING_SWAY_TIME = 0.3f;
    private static final float LANDING_SWAY_TIME = 0.15f;
    private static float jumpingSwayProgress = 0;
    private static boolean lastOnGround = false;
    private static long jumpingTimeStamp = -1;

    private JumpingSway() {
    }

    public static float applyJumpingSway(float partialTicks) {
        if (jumpingTimeStamp == -1) {
            jumpingTimeStamp = GunTime.nowMillis();
        }
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        if (player != null) {
            // port: Mth.lerp(partialTicks, yOld, getY()) is lastTickPosY + partialTicks * (posY - lastTickPosY)
            double posY = Minecraft.getMinecraft().thePlayer.lastTickPosY + partialTicks * (Minecraft.getMinecraft().thePlayer.posY - Minecraft.getMinecraft().thePlayer.lastTickPosY);
            float velocityY = (float) (posY - Minecraft.getMinecraft().thePlayer.lastTickPosY) / partialTicks;
            if (player.onGround) {
                if (!lastOnGround) {
                    jumpingSwayProgress = velocityY / -0.1f;
                    if (jumpingSwayProgress > 1) {
                        jumpingSwayProgress = 1;
                    }
                    lastOnGround = true;
                } else {
                    jumpingSwayProgress -= (GunTime.nowMillis() - jumpingTimeStamp) / (LANDING_SWAY_TIME * 1000);
                    if (jumpingSwayProgress < 0) {
                        jumpingSwayProgress = 0;
                    }
                }
            } else {
                if (lastOnGround) {
                    // 0.42 是玩家自然起跳的速度
                    jumpingSwayProgress = velocityY / 0.42f;
                    if (jumpingSwayProgress > 1) {
                        jumpingSwayProgress = 1;
                    }
                    lastOnGround = false;
                } else {
                    jumpingSwayProgress -= (GunTime.nowMillis() - jumpingTimeStamp) / (JUMPING_SWAY_TIME * 1000);
                    if (jumpingSwayProgress < 0) {
                        jumpingSwayProgress = 0;
                    }
                }
            }
        }
        jumpingTimeStamp = GunTime.nowMillis();
        float ySway = JUMPING_DYNAMICS.update(JUMPING_Y_SWAY * jumpingSwayProgress);
        // port: TaCZ adds -ySway / 16 to its upside-down bedrock root bone's offsetY; here the sway is returned, in
        // TaCZ's model pixels, up positive, for WalkSprint to move the gun by
        return ySway;
    }
}
