package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

import org.junit.jupiter.api.Test;

// The living spread (SpreadModel): speed, air and bloom on hand-worked cases, then what the shipped settings do to every
// NTM gun (src/test/gundata), in degrees, pinned so a change to the numbers shows up in review.
class SpreadModelTest {
    static final float E = 1.0E-5F;

    @Test
    void pace() {
        assertEquals(0F, SpreadModel.pace(0F));
        assertEquals(0F, SpreadModel.pace(0.01F)); // turning on the spot, drifting
        assertEquals(1F, SpreadModel.pace(SpreadModel.WALK), E);
        assertEquals(1.301F, SpreadModel.pace(SpreadModel.SPRINT), 0.001F);
        // knockback or a launch pad is not faster running
        assertEquals(SpreadModel.pace(SpreadModel.SPRINT * 1.25F), SpreadModel.pace(3F), E);
    }

    @Test
    void speed() {
        assertEquals(1F, SpreadModel.moveMult(1.5F, 0F), E);
        assertEquals(1.25F, SpreadModel.moveMult(1.5F, 0.5F), E);
        assertEquals(1.5F, SpreadModel.moveMult(1.5F, 1F), E);
        assertEquals(0.008F, SpreadModel.speedAdd(0.008F, 1F), E);
        assertEquals(0F, SpreadModel.speedAdd(-1F, 1F), E);
        // a gun with no spread of its own still loses accuracy moving and in the air
        assertEquals(0.008F, SpreadModel.total(0F, 1.5F, 0.008F, 1F, 0F, 0F, 1F), E);
        assertEquals(0.038F, SpreadModel.total(0F, 1.5F, 0.008F, 1F, 0.03F, 0F, 1F), E);
        assertEquals(0.025F * 1.5F * 0.75F + 0.008F * 0.3F + 0.01F * 0.75F,
                SpreadModel.total(0.025F, 1.5F * 0.75F, 0.008F, 0.3F, 0F, 0.01F, 0.75F), E);
        assertEquals(0F, SpreadModel.total(0.025F, -1F, 0F, 0F, -1F, 0F, 1F), E);
    }

    @Test
    void bloom() {
        assertEquals(0.01F, SpreadModel.decay(0.01F, 0, 0.25F), E);
        assertEquals(0.005F, SpreadModel.decay(0.01F, 5, 0.25F), E); // a quarter second is 5 ticks
        assertEquals(0.0025F, SpreadModel.decay(0.01F, 10, 0.25F), E);
        assertEquals(0F, SpreadModel.decay(0.01F, 1, 0F), E);
        assertEquals(0.03F, SpreadModel.afterShot(0.029F, 0.004F, 0.03F), E);
        assertEquals(0.005F, SpreadModel.afterShot(0.001F, 0.004F, 0.03F), E);
        // a burst settles where a shot's bloom equals what decays between shots: b = (b + s) * 0.5^(d / 5)
        float k = (float) Math.pow(0.5, 2 / 5.0);
        assertEquals(k * 0.004F / (1F - k), SpreadModel.steadyBloom(2, 0.004F, 1F, 0.25F), E);
        // faster fire blooms more, the cap holds, and taps half a second apart barely bloom
        assertTrue(SpreadModel.steadyBloom(1, 0.004F, 1F, 0.25F) > SpreadModel.steadyBloom(3, 0.004F, 1F, 0.25F));
        assertTrue(SpreadModel.steadyBloom(1, 0.01F, 0.03F, 0.25F) <= 0.03F);
        assertTrue(SpreadModel.steadyBloom(10, 0.004F, 0.03F, 0.25F) < 0.0015F);
    }

    static Properties shipped() throws IOException {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(Path.of("src/main/resources/ntmgunfeel-server.properties"))) {
            p.load(r);
        }
        return p;
    }

    static float get(Properties p, String gun, String key, float def) {
        String v = p.getProperty(gun + "." + key, p.getProperty(key));
        return v == null ? def : Float.parseFloat(v.trim());
    }

    static String deg(float spread) {
        return String.format(Locale.ROOT, "%.2f", Math.toDegrees(Math.atan(spread)));
    }

    // degrees per axis, standing and walking, hip and aimed, sprinting from the hip, a jump, a held-down burst; the
    // gun's own spread without its ammo's (shotgun pellets add theirs)
    @Test
    void everyGunPinned() throws IOException {
        Properties p = shipped();
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (AmmoStatsTest.Row r : AmmoStatsTest.rows()) {
            String gun = r.s("gun");
            if (!seen.add(gun + " " + r.s("config") + "/" + r.s("receiver"))) continue;
            float innate = r.n("spreadInnate"), hip = innate + r.n("spreadHipfire");
            float stand = get(p, gun, "spreadStand", 1F), aim = get(p, gun, "spreadAim", 1F);
            float move = get(p, gun, "spreadMove", 1F), speed = get(p, gun, "spreadSpeed", 0F);
            float air = get(p, gun, "spreadAir", 0F);
            float walk = SpreadModel.pace(SpreadModel.WALK), run = SpreadModel.pace(SpreadModel.SPRINT);
            int delay = Math.max(1, r.i("delayAfterFire"));
            float burst = SpreadModel.steadyBloom(delay, get(p, gun, "spreadBloom", 0F), get(p, gun, "spreadBloomMax", 0F),
                    get(p, gun, "spreadBloomHalfLife", 0.25F));
            out.add(gun + " " + r.s("config") + "/" + r.s("receiver")
                    + "  hip " + deg(SpreadModel.total(hip, stand, speed, 0F, 0F, 0F, 1F))
                    + "  aim " + deg(SpreadModel.total(innate, stand * aim, speed, 0F, 0F, 0F, 1F))
                    + "  walk " + deg(SpreadModel.total(hip, SpreadModel.moveMult(move, walk), speed, walk, 0F, 0F, 1F))
                    + "/" + deg(SpreadModel.total(innate, SpreadModel.moveMult(move, walk) * aim, speed, walk, 0F, 0F, 1F))
                    + "  sprint " + deg(SpreadModel.total(hip, SpreadModel.moveMult(move, run), speed, run, 0F, 0F, 1F))
                    + "  jump " + deg(SpreadModel.total(hip, stand, speed, 0F, air, 0F, 1F))
                    + "  burst every " + delay + " " + deg(SpreadModel.total(innate, stand * aim, speed, 0F, 0F, burst, 1F)));
        }
        assertTrue(out.size() > 60, "gun parts: " + out.size());
        PinnedTest.pin("spread", out);
    }
}
