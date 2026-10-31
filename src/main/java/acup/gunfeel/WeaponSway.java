// SPDX-License-Identifier: GPL-3.0-only
// Адаптировано из Superb Warfare, Mercurows и участники проекта.
// ClientEventHandler.handleWeaponTurn / gunRootMoveV2, dabb00774e8290bdeac804d237d1f5c50364c6f7.
// Изменения: Java/NeoForge 26.2 и интеграция NTM.
package acup.gunfeel;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.render.item.weapon.sedna.ItemRenderWeaponBase;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/** Отставание от поворота камеры по расчёту Superb Warfare, без второго фильтра. */
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
    public static void apply(PoseStack pose,LocalPlayer player) {
        float partial=ItemRenderWeaponBase.interp;
        float armPitch=Mth.lerp(partial,player.xBobO,player.xBob);
        float armYaw=Mth.lerp(partial,player.yBobO,player.yBob);
        float aiming=Mth.lerp(partial,ItemGunBaseNT.prevAimingProgress,ItemGunBaseNT.aimingProgress);
        var turn=fromAngles(player.getViewXRot(partial),player.getViewYRot(partial),armPitch,armYaw,aiming);
        if(TestLog.frames()) TestLog.c(-1,"sway","view",new org.joml.Vector3f(player.getViewXRot(partial),player.getViewYRot(partial),0F),
                "arm",new org.joml.Vector3f(armPitch,armYaw,0F),"aiming",aiming,"pitch",turn.pitch(),"yaw",turn.yaw(),"roll",turn.roll(),"x",turn.x());
        pose.translate(turn.x(),0,0);
        pose.mulPose(Axis.XP.rotationDegrees(turn.pitch()));
        pose.mulPose(Axis.YP.rotationDegrees(turn.yaw()));
        pose.mulPose(Axis.ZP.rotationDegrees(turn.roll()));
        ClientSmoke.weaponSway(turn,aiming);
    }
}
