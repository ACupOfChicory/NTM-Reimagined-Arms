// SPDX-License-Identifier: GPL-3.0-only
// Адаптировано из Superb Warfare, Mercurows и участники проекта.
// ClientEventHandler.handleWeaponTurn / gunRootMoveV2, dabb00774e8290bdeac804d237d1f5c50364c6f7.
// Изменения: Java/NeoForge 26.2 и интеграция NTM; порт на Minecraft 1.7.10 Forge (GL11 вместо PoseStack).
package acup.gunfeel;

import org.lwjgl.opengl.GL11;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.render.item.weapon.sedna.ItemRenderWeaponBase;

import net.minecraft.client.entity.EntityPlayerSP;

/** Отставание от поворота камеры по расчёту Superb Warfare, без второго фильтра. */
// port: copied from 26.2 (4a6ec20, notes/ports/port-1.7.10-copied.md): Turn, fromAngles and sample unchanged,
// apply draws with GL11 and reads EntityPlayerSP.renderArmPitch/Yaw (26.2: LocalPlayer.xBob/yBob).
public final class WeaponSway {
    public record Turn(float pitch,float yaw,float roll,float x) {}
    private WeaponSway() {}
    static Turn fromAngles(float pitch,float yaw,float armPitch,float armYaw,float aiming) {
        // Это непрерывные углы LocalPlayer, а не два независимых направления.
        // wrapDegrees менял +220° отставания на -140° и перебрасывал пушку.
        return sample(pitch-armPitch,yaw-armYaw,aiming);
    }
    static Turn sample(float pitchDifference,float yawDifference,float aiming) {
        float zoom=Math.clamp(aiming,0F,1F);
        float pitch=Math.clamp(.05F*pitchDifference,-20F,20F)*(1-.05F*zoom);
        float yaw=Math.clamp(.025F*yawDifference,-20F,20F)*(1-.05F*zoom);
        float roll=Math.clamp(.05F*yawDifference,-20F,20F)*(1-.5F*zoom);
        float x=.5F*yaw*(1-.5F*zoom)/16F;
        return new Turn(pitch,yaw,roll,x);
    }
    // 26.2: Mth.lerp(partial, a, b) = a + partial * (b - a)
    private static float lerp(float partial,float a,float b) {
        return a+partial*(b-a);
    }
    public static Turn turn(EntityPlayerSP player) {
        float partial=ItemRenderWeaponBase.interp;
        float armPitch=lerp(partial,player.prevRenderArmPitch,player.renderArmPitch);
        float armYaw=lerp(partial,player.prevRenderArmYaw,player.renderArmYaw);
        float aiming=lerp(partial,ItemGunBaseNT.prevAimingProgress,ItemGunBaseNT.aimingProgress);
        return fromAngles(lerp(partial,player.prevRotationPitch,player.rotationPitch),lerp(partial,player.prevRotationYaw,player.rotationYaw),armPitch,armYaw,aiming);
    }
    public static void apply(EntityPlayerSP player) {
        Turn turn=turn(player);
        GL11.glTranslatef(turn.x(),0,0);
        GL11.glRotatef(turn.pitch(),1,0,0);
        GL11.glRotatef(turn.yaw(),0,1,0);
        GL11.glRotatef(turn.roll(),0,0,1);
    }
}
