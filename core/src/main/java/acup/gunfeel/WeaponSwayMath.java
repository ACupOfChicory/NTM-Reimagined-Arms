// SPDX-License-Identifier: GPL-3.0-only
// Адаптировано из Superb Warfare, Mercurows и участники проекта.
// ClientEventHandler.handleWeaponTurn / gunRootMoveV2, dabb00774e8290bdeac804d237d1f5c50364c6f7.
// Изменения: Java/NeoForge 26.2 и интеграция NTM.
package acup.gunfeel;

/** Отставание от поворота камеры по расчёту Superb Warfare, без второго фильтра: сам расчёт (WeaponSway его применяет). */
public final class WeaponSwayMath {
    public record Turn(float pitch,float yaw,float roll,float x) {}
    private WeaponSwayMath() {}
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
}
