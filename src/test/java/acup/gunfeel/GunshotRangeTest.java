package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

// How far a gunshot carries by its gun's fire sound, and the volume NTM plays it at for that.
class GunshotRangeTest {
    @Test
    void theLouderTheGunTheFurther() {
        assertEquals(16F, GunshotRange.of("weapon.fire.silenced"));
        assertTrue(GunshotRange.of("weapon.fire.laser") >= 24F, "energy weapons loud (AiBots.LOUD)");
        assertTrue(GunshotRange.of("weapon.fire.pistol") < GunshotRange.of("weapon.fire.shotgun"));
        assertTrue(GunshotRange.of("weapon.fire.shotgun") < GunshotRange.of("weapon.fire.rifle"));
        assertTrue(GunshotRange.of("weapon.fire.rifle") < GunshotRange.of("weapon.fire.rifle_heavy"));
        assertTrue(GunshotRange.of("weapon.fire.rifle_heavy") < GunshotRange.of("weapon.fire.amat"));
        // not a shot: as NTM plays it
        assertEquals(16F, GunshotRange.of("weapon.fire.smack"));
        assertEquals(16F, GunshotRange.of("weapon.fire.flame_loop"));
        assertEquals(16F, GunshotRange.of("weapon.fire.loudest_noise_on_earth"));
    }

    @Test
    void theVolumeIsTheRangeOverSixteenAndTheSettingScalesThePartOverSixteen() {
        net.minecraft.sounds.SoundEvent rifle = net.minecraft.sounds.SoundEvent.createVariableRangeEvent(
                net.minecraft.resources.Identifier.fromNamespaceAndPath("hbm", "weapon.fire.rifle"));
        assertEquals(5F, GunshotRange.volume(rifle, 1F, 1F), 1e-6);
        assertEquals(1F, GunshotRange.volume(rifle, 1F, 0F), 1e-6);
        assertEquals(3F, GunshotRange.volume(rifle, 1F, 0.5F), 1e-6);
        // a quieter shot of NTM's stays as much quieter
        assertEquals(2.5F, GunshotRange.volume(rifle, 0.5F, 1F), 1e-6);
        // NTM's own over-loud joke shot: as it is
        assertEquals(100F, GunshotRange.volume(rifle, 100F, 1F), 1e-6);
        // and SoundEvent.getRange: 16 a unit of volume over 1
        assertEquals(80F, rifle.getRange(GunshotRange.volume(rifle, 1F, 1F)), 1e-4);
    }
}
