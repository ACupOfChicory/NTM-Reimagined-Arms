package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import acup.gunfeel.host.Body;

/** The effect numbers' switches, without starting the game's registries in a unit test. */
class EffectKindsTest {
    @Test
    void effectsAreTheGamesOnBothPorts() throws Exception {
        assertEquals(0, Body.EFFECT_REGENERATION);
        assertEquals(1, Body.EFFECT_RESISTANCE);
        String body = Files.readString(Path.of("src/main/java/acup/gunfeel/EntityBody.java"));
        String read = body.substring(body.indexOf("public int effectAmplifier("), body.indexOf("@Override public java.util.UUID"));
        assertTrue(read.contains("((LivingEntity) e).getEffect(switch (which)"));
        assertTrue(read.contains("case EFFECT_REGENERATION -> net.minecraft.world.effect.MobEffects.REGENERATION;"));
        assertTrue(read.contains("case EFFECT_RESISTANCE -> net.minecraft.world.effect.MobEffects.RESISTANCE;"));
        assertTrue(read.contains("default -> throw new IllegalArgumentException("));
        assertTrue(read.contains("return effect == null ? -1 : effect.getAmplifier();"));
        String control = Files.readString(Path.of("src/main/java/acup/gunfeel/EntityBodyControl.java"));
        assertTrue(control.contains("((LivingEntity) EntityBody.entity(b)).removeEffect(switch (which)"));
        for (String effect : new String[] {"REGENERATION", "RESISTANCE"})
            assertTrue(control.contains("case Body.EFFECT_" + effect + " -> net.minecraft.world.effect.MobEffects." + effect + ";"));
        assertTrue(control.contains("default -> throw new IllegalArgumentException("));
    }
}
