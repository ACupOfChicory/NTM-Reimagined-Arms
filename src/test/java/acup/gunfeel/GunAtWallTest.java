package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class GunAtWallTest {
    // no wall on the way: free; at the muzzle: just touching; where the wall is once the eyes are NEAR it: all in
    @Test
    void intoTheWall() {
        float df = 0.9F;
        assertEquals(0F, GunAtWall.into(1F, 0F, df));
        assertEquals(0F, GunAtWall.into(1.5F, 0F, df));
        assertEquals(1F, GunAtWall.into(GunAtWall.NEAR / df, 0F, df), 1e-6F);
        assertEquals(1F, GunAtWall.into(0.05F, 0F, df));
        float half = (1F + GunAtWall.NEAR / df) / 2F;
        assertEquals(0.5F, GunAtWall.into(half, 0F, df), 1e-6F);
        // along the gun from a point already forward of the eyes
        float db = 0.1F, t = (GunAtWall.NEAR - db) / (df - db);
        assertEquals(1F, GunAtWall.into(t, db, df), 1e-6F);
        assertTrue(GunAtWall.into(0.9F, db, df) > 0F && GunAtWall.into(0.9F, db, df) < 1F);
    }

    // a short gun at the same wall goes in less than a long one
    @Test
    void shortGunLess() {
        float wall = 0.5F;
        float pistol = 0.6F, rifle = 1.0F;
        assertTrue(GunAtWall.into(wall / pistol, 0F, pistol) < GunAtWall.into(wall / rifle, 0F, rifle));
    }

    // a muzzle nearer than NEAR in front never counts past all in
    @Test
    void muzzleNearTheEyes() {
        assertEquals(1F, GunAtWall.into(0F, 0F, 0.2F));
        // a ray going back toward the eyes: no "all in" share, the wall's share alone
        assertEquals(0.5F, GunAtWall.into(0.5F, 0.5F, 0.4F), 1e-6F);
    }

    // the hands' field of view against the world's: the same, nothing moves; a narrower world (zoomed) draws it in
    @Test
    void fovScale() {
        assertEquals(1F, GunAtWall.fovScale(70F, 70F), 1e-6F);
        assertTrue(GunAtWall.fovScale(30F, 70F) < 1F);
        assertTrue(GunAtWall.fovScale(100F, 70F) > 1F);
        assertEquals(1F, GunAtWall.fovScale(70F, 0F));
    }

    // the G3: its model 25.07 long, 0.0703125 in the third person, 9.4 in the hands
    @Test
    void blocksPerUnitByTheThirdPerson() {
        assertEquals(25.07F * 0.0703125F / 9.4F, GunAtWall.blocksPerUnit(25.07F, 0.0703125F, 9.4F), 1e-6F);
        assertTrue(Float.isNaN(GunAtWall.blocksPerUnit(25F, Float.NaN, 9.4F)));
        assertTrue(Float.isNaN(GunAtWall.blocksPerUnit(25F, 0.07F, 0F)));
        assertTrue(Float.isNaN(GunAtWall.blocksPerUnit(0F, 0.07F, 9.4F)));
    }

    // the scale as NTM's item model gives it (gun_g3_base.json), none without the third person
    @Test
    void scaleFromTheItemModel() {
        var display = JsonParser.parseString("""
                {"thirdperson_righthand": {"rotation": [-179.494, -1.1252687, 179.74399], "scale": [0.0703125, 0.0703125, 0.0703125],
                 "translation": [-0.7841931, 2.2830474, -3.4715495]}}""").getAsJsonObject();
        assertEquals(0.0703125F, GunAtWall.scale(display));
        assertTrue(Float.isNaN(GunAtWall.scale(JsonParser.parseString("{\"gui\": {\"scale\": [1, 1, 1]}}").getAsJsonObject())));
        assertTrue(Float.isNaN(GunAtWall.scale(JsonParser.parseString("{\"thirdperson_righthand\": {\"scale\": [0, 0, 0]}}").getAsJsonObject())));
        assertTrue(Float.isNaN(GunAtWall.scale(null)));
    }

    // the muzzle turned up just enough to stay before the wall; none with no wall; all of MAX_PITCH when that is not enough
    @Test
    void pitchKeepsTheMuzzleOut() {
        // a gun 10 units long along -z, its middle 3 units ahead, 0.1 blocks a unit: the muzzle 0.8 blocks ahead
        Vector3f pivot = new Vector3f(0F, 0F, -3F), muzzle = new Vector3f(0F, 0F, -8F);
        float c = 0.1F, rest = 0.8F;
        assertEquals(0F, GunAtWall.pitch(Float.POSITIVE_INFINITY, muzzle, pivot, 0F, c, rest));
        assertEquals(0F, GunAtWall.pitch(2F, muzzle, pivot, 0F, c, rest));
        float limit = 0.6F;
        float deg = GunAtWall.pitch(limit, muzzle, pivot, 0F, c, rest);
        // the muzzle at deg: 3 + 5 cos(deg) units ahead
        float depth = (3F + 5F * (float) Math.cos(Math.toRadians(deg))) * c;
        assertEquals(limit - GunAtWall.MARGIN, depth, 1e-3F);
        assertTrue(deg > 0F && deg < GunAtWall.MAX_PITCH);
        // drawn back, it turns less
        assertTrue(GunAtWall.pitch(limit, muzzle, pivot, 1F, c, rest) < deg);
        assertEquals(GunAtWall.MAX_PITCH, GunAtWall.pitch(0.1F, muzzle, pivot, 0F, c, rest));
        // no limit or no scale: no turn
        assertEquals(0F, GunAtWall.pitch(Float.NaN, muzzle, pivot, 0F, c, rest));
        assertEquals(0F, GunAtWall.pitch(limit, muzzle, pivot, 0F, Float.NaN, rest));
    }

    // a muzzle above or below the turn's middle (a gun held sloping): the turn found still puts it MARGIN before the wall
    @Test
    void pitchWithTheMuzzleOffTheAxis() {
        Vector3f pivot = new Vector3f(0F, 0F, -3F);
        // below the middle the muzzle first swings forward as it turns: 0.6 would take more than MAX_PITCH there
        float c = 0.1F, limit = 0.65F;
        for (float y : new float[] {-1.5F, 1.5F}) {
            Vector3f muzzle = new Vector3f(0F, y, -8F);
            float rest = 0.8F;
            float deg = GunAtWall.pitch(limit, muzzle, pivot, 0F, c, rest);
            double a = Math.toRadians(deg);
            float z = -3F + (float) (y * Math.sin(a) + -5F * Math.cos(a));
            float depth = rest + (-8F - z) * c;
            assertTrue(deg > 0F && deg < GunAtWall.MAX_PITCH, "y " + y + ": " + deg);
            assertEquals(limit - GunAtWall.MARGIN, depth, 1e-3F, "y " + y);
        }
        // the muzzle above the middle comes back sooner than one below it
        assertTrue(GunAtWall.pitch(limit, new Vector3f(0F, 1.5F, -8F), pivot, 0F, c, 0.8F)
                < GunAtWall.pitch(limit, new Vector3f(0F, -1.5F, -8F), pivot, 0F, c, 0.8F));
    }

    // drawn back by the weight, no more than a share of the middle's distance; the muzzle lowered by low
    @Test
    void poseAndBack() {
        assertEquals(0F, GunAtWall.back(0F, 9F, 3F));
        assertEquals(GunAtWall.BACK, GunAtWall.back(1F, 9F, 30F), 1e-6F);
        assertEquals(GunAtWall.BACK / 2F, GunAtWall.back(0.5F, 9F, 30F), 1e-6F);
        float length = 30.4F, middle = 8F;
        assertEquals(GunAtWall.BACK_OF_MIDDLE * middle, GunAtWall.back(1F, length, middle) * length, 1e-4F);
        var p = GunAtWall.pose(40F, 0F, 0.1F);
        assertEquals(40F, p.pitch());
        assertEquals(0.1F, p.z());
        assertEquals(0F, GunAtWall.pose(40F, 1F, 0.1F).pitch());
    }

    // an NTM animation takes the gun out of the wall's pose, a shot's or a barrel's spin does not
    @Test
    void animationsGiveWay() {
        for (var type : com.hbm.render.anim.AnimationEnums.GunAnimation.values()) {
            boolean shot = switch (type.name()) {
                case "CYCLE", "CYCLE_EMPTY", "CYCLE_DRY", "ALT_CYCLE", "SPINUP", "SPINDOWN" -> true;
                default -> false;
            };
            assertEquals(!shot, GunAtWall.givesWay(type), type.name());
        }
        assertTrue(GunAtWall.givesWay(null));
    }

    // the walk's and the sprint's share: all of it with no wall, none pressed against it, less the closer
    @Test
    void wallTakesTheirPlace() {
        assertEquals(1F, GunAtWall.others(0F));
        assertEquals(0F, GunAtWall.others(1F));
        assertEquals(0.5F, GunAtWall.others(0.5F), 1e-6F);
        float last = 1F;
        for (float w = 0.1F; w <= 1F; w += 0.1F) {
            float o = GunAtWall.others(w);
            assertTrue(o <= last);
            last = o;
        }
        assertEquals(1F, GunAtWall.others(-1F));
        assertEquals(0F, GunAtWall.others(2F));
    }
}
