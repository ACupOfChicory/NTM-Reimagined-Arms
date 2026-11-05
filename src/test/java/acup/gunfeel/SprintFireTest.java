package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import org.junit.jupiter.api.Test;

class SprintFireTest {
    // the sprint time as TaCZ's: up to the gun's time while sprinting, back down at the same rate after
    @Test
    void timeGrowsToTheGunsAndRunsBack() {
        float t = 0F;
        for (int i = 0; i < 10; i++) t = SprintFire.step(t, true, 0.15F, 0.05F);
        assertEquals(0.15F, t, 1e-6F);
        int ticks = 0;
        while (t > 0F) {
            t = SprintFire.step(t, false, 0.15F, 0.05F);
            ticks++;
        }
        assertEquals(3, ticks);
        assertEquals(0F, SprintFire.step(0F, false, 0.15F, 0.05F));
        // 0.15 runs out in exactly three ticks, with no crumb left by float steps
        assertEquals(0F, SprintFire.step(SprintFire.step(SprintFire.step(0.15F, false, 0.15F, 0.05F), false, 0.15F, 0.05F), false, 0.15F, 0.05F));
        // a short sprint gives a short delay
        assertEquals(0.05F, SprintFire.step(0F, true, 0.15F, 0.05F), 1e-6F);
    }

    // the mod ships the delay switched on for every gun: a server turns it off with sprintFireDelay = false
    @Test
    void onByDefault() throws Exception {
        Properties p = new Properties();
        try (var in = SprintFire.class.getResourceAsStream("/ntmgunfeel-server.properties")) {
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        assertEquals("true", p.getProperty("sprintFireDelay"));
        for (String key : p.stringPropertyNames())
            if (key.endsWith(".sprintFireDelay")) throw new AssertionError("a gun has its own delay switch in the mod: " + key);
        assertEquals(0.08F, Float.parseFloat(p.getProperty("hbm:gun_light_revolver.sprintFireTime")));
        assertEquals(0.19F, Float.parseFloat(p.getProperty("hbm:gun_m2.sprintFireTime")));
    }
}
