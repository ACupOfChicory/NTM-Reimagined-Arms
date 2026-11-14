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

    /** what the game reads by default: the server's file shipped in the jar and the balance's keys fixed in the mod (balance = arms) */
    static Properties shipped() throws IOException {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(Path.of("src/main/resources/ntmgunfeel-server.properties"))) {
            p.load(r);
        }
        p.putAll(BalanceKeys.ARMS);
        return p;
    }

    static float get(Properties p, String gun, String key, float def) {
        String v = p.getProperty(gun + "." + key, p.getProperty(key));
        return v == null ? def : Float.parseFloat(v.trim());
    }

    static String deg(float spread) {
        return String.format(Locale.ROOT, "%.2f", Math.toDegrees(Math.atan(spread)));
    }

    // in power armour (powerArmorSpread, as Spread works it) the pace that moving adds spread by, the air part and the
    // bloom count k times; NTM's own spread and the stances stay
    static float armored(float ntm, float mult0, float spreadMove, float spreadSpeed, float pace, float air, float bloom, float k) {
        float live = pace * k;
        return SpreadModel.total(ntm, mult0 * SpreadModel.moveMult(spreadMove, spreadMove > 1F ? live : pace), spreadSpeed, live, air * k,
                bloom * k, 1F);
    }

    /** Fable №3: the choke; the shipped keys of the shotguns (knee 0.015, over 0.3): aimed, the SPAS-12's from the hip, none for an akimbo */
    @Test
    void choke() throws IOException {
        assertEquals(0.021F, SpreadModel.choke(0.035F, 0.015F, 0.3F), E);
        assertEquals(0.018F, SpreadModel.choke(0.025F, 0.015F, 0.3F), E);
        assertEquals(0.015F, SpreadModel.choke(0.015F, 0.015F, 0.3F), E);
        assertEquals(0.01F, SpreadModel.choke(0.01F, 0.015F, 0.3F), E);
        // a knee of 0: NTM's spread as it is
        assertEquals(0.035F, SpreadModel.choke(0.035F, 0F, 0.3F), E);
        Properties p = shipped();
        assertEquals(0.015F, get(p, "hbm:gun_spas12", "spreadHipKnee", 0F));
        assertEquals(0.3F, get(p, "hbm:gun_spas12", "spreadHipOver", 1F));
        assertEquals(0F, get(p, "hbm:gun_spas12", "spreadAimKnee", 0F));
        for (String g : new String[] {"maresleg", "maresleg_akimbo", "autoshotgun", "g3", "minigun"}) assertEquals(0F, get(p, "hbm:gun_" + g, "spreadHipKnee", 0F), g);
        for (String g : new String[] {"maresleg", "maresleg_broken", "autoshotgun", "autoshotgun_sexy", "autoshotgun_heretic", "double_barrel",
                "double_barrel_sacred_dragon"}) {
            assertEquals(0.015F, get(p, "hbm:gun_" + g, "spreadAimKnee", 0F), g);
            assertEquals(0.3F, get(p, "hbm:gun_" + g, "spreadAimOver", 1F), g);
        }
        for (String g : new String[] {"maresleg_akimbo", "autoshotgun_shredder", "g3", "minigun", "uzi_akimbo"})
            assertEquals(0F, get(p, "hbm:gun_" + g, "spreadAimKnee", 0F), g);
    }

    @Test
    void powerArmor() throws IOException {
        float k = get(shipped(), "hbm:gun_g3", "powerArmorSpread", 1F);
        assertEquals(0.5F, k);
        // standing it is NTM's spread as it is; walking the walking multiplier grows half as much and the speed part is
        // half; the air part and the bloom are halved
        assertEquals(0.025F, armored(0.025F, 1F, 1.5F, 0.008F, 0F, 0F, 0F, k), E);
        assertEquals(0.025F * 1.25F + 0.004F, armored(0.025F, 1F, 1.5F, 0.008F, 1F, 0F, 0F, k), E);
        assertEquals(0.025F + 0.015F, armored(0.025F, 1F, 1.5F, 0.008F, 0F, 0.03F, 0F, k), E);
        assertEquals(0.01F + 0.006F, armored(0.01F, 1F, 1.5F, 0.008F, 0F, 0F, 0.012F, k), E);
        // a gun with spreadMove under 1 is no less steady moving in armour, and never worse than without it
        assertEquals(0.025F * 0.8F + 0.004F, armored(0.025F, 1F, 0.8F, 0.008F, 1F, 0F, 0F, k), E);
        assertTrue(armored(0.025F, 1F, 0.8F, 0.008F, 1F, 0F, 0F, k) <= armored(0.025F, 1F, 0.8F, 0.008F, 1F, 0F, 0F, 1F));
        // 1 is the spread without the armour
        assertEquals(SpreadModel.total(0.025F, 1.5F, 0.008F, 1F, 0.03F, 0.01F, 1F), armored(0.025F, 1F, 1.5F, 0.008F, 1F, 0.03F, 0.01F, 1F), E);
    }

    // degrees per axis, standing and walking, hip and aimed, sprinting from the hip, a jump, a held-down burst; the
    // gun's own spread without its ammo's (shotgun pellets add theirs); then the same moving, in the air and in a burst
    // in power armour
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
            float air = get(p, gun, "spreadAir", 0F), k = get(p, gun, "powerArmorSpread", 1F);
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
                    + "  burst every " + delay + " " + deg(SpreadModel.total(innate, stand * aim, speed, 0F, 0F, burst, 1F))
                    + "  | armour walk " + deg(armored(hip, 1F, move, speed, walk, 0F, 0F, k))
                    + "/" + deg(armored(innate, aim, move, speed, walk, 0F, 0F, k))
                    + "  sprint " + deg(armored(hip, 1F, move, speed, run, 0F, 0F, k))
                    + "  jump " + deg(armored(hip, stand, move, speed, 0F, air, 0F, k))
                    + "  burst " + deg(armored(innate, stand * aim, move, speed, 0F, 0F, burst, k)));
        }
        assertTrue(out.size() > 60, "gun parts: " + out.size());
        PinnedTest.pin("spread", out);
    }
}
