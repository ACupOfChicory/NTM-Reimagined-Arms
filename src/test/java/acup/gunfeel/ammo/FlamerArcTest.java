package acup.gunfeel.ammo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.hbm.items.weapon.sedna.BulletConfig;

class FlamerArcTest {
    // NTM's numbers (XFactoryFlamer): the hand flamer's diesel, Topaz's and the Fritz's straight beams
    static final double HAND_SPEED = 1, HAND_GRAVITY = 0.02, EYE = 1.5;
    static final int HAND_LIFE = 100;
    // the Fritz: NTM's barrel (getBarrelLength), its detector's range with the grace it keeps a target in (× 1.1)
    static final double BARREL = 2.25, REACH = 48 * 1.1;

    @Test
    void fallsAsTheHandFlamer() {
        assertEquals(FlamerArc.FALL, FlamerArc.SPEED * FlamerArc.GRAVITY, 1e-12);
        assertEquals(HAND_SPEED * HAND_GRAVITY, FlamerArc.FALL, 1e-12);
    }

    @Test
    void topazReachesFurtherThanTheHandFlamer() {
        for (double deg : new double[] {0, 15, 30, 45}) {
            double hand = FlamerArc.range(HAND_SPEED, HAND_GRAVITY, HAND_LIFE, deg, EYE);
            double topaz = FlamerArc.range(FlamerArc.SPEED, FlamerArc.GRAVITY, FlamerArc.TOPAZ_LIFE, deg, EYE);
            assertTrue(topaz > hand * 1.25, deg + "°: Topaz " + topaz + ", hand " + hand);
            // and it lands, no beam ending in the air as NTM's
            assertTrue(Double.isFinite(topaz), deg + "°");
        }
        // NTM's beam: 60 blocks flat and still 2 blocks up when its life ends
        assertTrue(Double.isNaN(FlamerArc.range(1, 0, 60, 0, EYE)));
    }

    @Test
    void topazLandsAtEveryPitch() {
        for (int deg = -60; deg <= 89; deg++)
            assertTrue(Double.isFinite(FlamerArc.range(FlamerArc.SPEED, FlamerArc.GRAVITY, FlamerArc.TOPAZ_LIFE, deg, EYE)), deg + "°");
    }

    @Test
    void fritzHitsAlongItsArc() {
        for (double x = 3.5; x <= REACH; x += 0.5)
            for (double y = -12; y <= 20; y += 1) {
                if (Math.toDegrees(Math.atan2(y, x)) > 45) continue; // NTM's Fritz sees no higher (getTurretElevation)
                double pitch = FlamerArc.pitch(x, y, FlamerArc.SPEED, FlamerArc.FALL, BARREL);
                double at = flight(pitch, x, FlamerArc.FRITZ_LIFE);
                assertTrue(Math.abs(at - y) < 0.05, "x " + x + " y " + y + ": " + at);
                // the low arc, not a lob: a lower barrel would put the flame lower
                assertTrue(flight(pitch - Math.toRadians(0.5), x, FlamerArc.FRITZ_LIFE) < y, "x " + x + " y " + y + ": " + Math.toDegrees(pitch));
            }
    }

    @Test
    void fritzStraightAimWouldFallShort() {
        // NTM's aim straight at a target 40 blocks off at its height: the flame lands blocks below it
        double at = flight(0, 40, FlamerArc.FRITZ_LIFE);
        assertTrue(at < -6, "" + at);
    }

    @Test
    void outOfReachAimsHighest() {
        double pitch = FlamerArc.pitch(40, 60, FlamerArc.SPEED, FlamerArc.FALL, BARREL);
        double top = flight(pitch, 40, 400);
        for (double d = -10; d <= 10; d += 1)
            assertTrue(flight(pitch + Math.toRadians(d), 40, 400) <= top + 1e-6, d + "°");
        assertTrue(top < 60);
    }

    @Test
    void closeInIsStraight() {
        assertEquals(Math.atan2(1, 2), FlamerArc.pitch(2, 1, FlamerArc.SPEED, FlamerArc.FALL, BARREL), 1e-12);
    }

    @Test
    void setsOnlyNtmsStraightBeam() {
        BulletConfig topaz = new BulletConfig().setVel(1F).setGrav(0).setLife(60);
        BulletConfig changed = new BulletConfig().setVel(1F).setGrav(0.01).setLife(60);
        assertTrue(FlamerGravity.beam(topaz, 60));
        assertFalse(FlamerGravity.beam(changed, 60));
        assertFalse(FlamerGravity.beam(topaz, 100));
        FlamerGravity.arc(topaz, FlamerArc.TOPAZ_LIFE);
        assertEquals(FlamerArc.SPEED, topaz.velocity);
        assertEquals(FlamerArc.GRAVITY, topaz.gravity);
        assertEquals(FlamerArc.TOPAZ_LIFE, topaz.expires);
        assertFalse(FlamerGravity.beam(topaz, 60));
    }

    /** the Fritz's flame as NTM flies it from the barrel's tip: its height over the pivot x ahead of it (NaN: life ends first) */
    static double flight(double pitch, double x, int life) {
        double hx = Math.cos(pitch), hy = Math.sin(pitch), px = BARREL * hx, py = BARREL * hy;
        for (int t = 0; t < life; t++) {
            double nx = px + hx * FlamerArc.SPEED, ny = py + hy * FlamerArc.SPEED;
            if (nx >= x) return py + (ny - py) * (x - px) / (nx - px);
            px = nx;
            py = ny;
            hy -= FlamerArc.GRAVITY;
        }
        return Double.NaN;
    }

    /** the table for the notes: ranges before and after */
    public static void main(String[] a) {
        String[][] rows = {{"hand 1/0.02/100"}, {"Topaz NTM 1/0/60"}, {"Topaz now"}, {"Fritz NTM 1/0/100"}, {"Fritz now"}};
        double[][] p = {{1, 0.02, 100}, {1, 0, 60}, {FlamerArc.SPEED, FlamerArc.GRAVITY, FlamerArc.TOPAZ_LIFE}, {1, 0, 100},
                {FlamerArc.SPEED, FlamerArc.GRAVITY, FlamerArc.FRITZ_LIFE}};
        for (int i = 0; i < rows.length; i++) {
            StringBuilder s = new StringBuilder(String.format("%-20s", rows[i][0]));
            for (double deg : new double[] {0, 15, 45}) s.append(String.format("%10.1f", FlamerArc.range(p[i][0], p[i][1], (int) p[i][2], deg, EYE)));
            System.out.println(s);
        }
    }
}
