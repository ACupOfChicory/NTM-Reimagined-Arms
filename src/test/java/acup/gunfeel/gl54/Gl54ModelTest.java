package acup.gunfeel.gl54;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hbm.render.loader.GroupObject;

// The files tools/gl54_export.py made from the .blend, read the way NTM reads them (ObjMesh, AnimationLoader's format).
class Gl54ModelTest {
    static final String DIR = "/assets/ntmgunfeel/models/weapons/";

    static JsonObject json(String path) throws IOException {
        try (InputStream in = Gl54ModelTest.class.getResourceAsStream(path)) {
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    static GroupObject[] mesh() throws IOException {
        try (InputStream in = Gl54ModelTest.class.getResourceAsStream(DIR + "gl54.hbm_mesh")) {
            return com.hbm.render.loader.ObjMesh.read(in, "gl54");
        }
    }

    static GroupObject part(String name) throws IOException {
        for (GroupObject g : mesh()) if (g.name.equals(name)) return g;
        throw new AssertionError(name);
    }

    @Test
    void partsAreTheModelsObjectsAndEveryPartIsInTheItem() throws IOException {
        List<String> names = new ArrayList<>();
        for (GroupObject g : mesh()) names.add(g.name);
        assertEquals(Set.of("Body", "TitaniumBarrel", "Magazine", "Trigger", "FrontIronSight", "RearIronSight", "StockButtpad",
                "LaserRangefinder", "BoltCarrier", "ChargingHandle"), Set.copyOf(names));
        JsonArray parts = json("/assets/ntmgunfeel/items/gun_gl54.json").getAsJsonObject("model").getAsJsonArray("parts");
        List<String> listed = new ArrayList<>();
        parts.forEach(e -> listed.add(e.getAsString()));
        assertEquals(Set.copyOf(names), Set.copyOf(listed));
    }

    @Test
    void axesAreNtmsMuzzleOnPlusZUpOnPlusY() throws IOException {
        // the .blend has the barrel along +X, 0.7 m, up on +Z; NTM's models point along +Z, up on +Y, 12 units a metre
        float[] b = part("TitaniumBarrel").bounds();
        assertTrue(b[5] > 3.5F && b[5] < 4.5F, "the muzzle is ahead on +Z: " + b[5]);
        assertTrue(b[2] < 0F, "the barrel reaches back over the middle");
        float[] stock = part("StockButtpad").bounds();
        assertTrue(stock[5] < -3.5F, "the butt is behind, on -Z: " + stock[5]);
        float[] mag = part("Magazine").bounds();
        assertTrue(mag[1] < part("Body").bounds()[4] && mag[4] < b[1] + 1F, "the magazine hangs below the barrel");
        float[] sight = part("FrontIronSight").bounds();
        assertTrue(sight[4] > b[4], "the sights are above the barrel on +Y");
        // the model is centred on the middle of the gun, its barrel's axis about 1.4 units above the origin
        assertEquals(1.37F, (b[1] + b[4]) / 2F, 0.05F);
    }

    @Test
    void fireAnimationIsHalfASecondAndTheBoltGoesBack() throws IOException {
        JsonObject j = json(DIR + "animations/gl54.json");
        JsonObject fire = j.getAsJsonObject("anim").getAsJsonObject("Fire");
        assertEquals(Set.of("BoltCarrier", "ChargingHandle"), fire.keySet());
        JsonArray z = fire.getAsJsonObject("BoltCarrier").getAsJsonObject("location").getAsJsonArray("z");
        // a keyframe is [value, ms since the one before, interpolation, easing]
        int total = 0;
        double lowest = 0, first = z.get(0).getAsJsonArray().get(0).getAsDouble();
        for (var e : z) {
            JsonArray k = e.getAsJsonArray();
            total += k.get(1).getAsInt();
            lowest = Math.min(lowest, k.get(0).getAsDouble());
            assertEquals("LINEAR", k.get(2).getAsString());
        }
        assertEquals(500, total, "0.5 s");
        // 6 cm back is 0.72 units on -Z
        assertEquals(0.72, first - lowest, 1e-3);
        // the part sits at its offset when it rests
        JsonArray offset = j.getAsJsonObject("offset").getAsJsonArray("BoltCarrier");
        assertEquals(offset.get(2).getAsDouble(), first, 1e-3);
        // x (left) and y (up) do not move
        for (String axis : new String[] {"x", "y"}) {
            JsonArray a = fire.getAsJsonObject("BoltCarrier").getAsJsonObject("location").getAsJsonArray(axis);
            for (var e : a) assertEquals(a.get(0).getAsJsonArray().get(0).getAsDouble(), e.getAsJsonArray().get(0).getAsDouble(), 1e-9);
        }
    }

    @Test
    void everyChannelOfAnimatedPartsHasAllThreeAxes() throws IOException {
        JsonObject fire = json(DIR + "animations/gl54.json").getAsJsonObject("anim").getAsJsonObject("Fire");
        for (String part : fire.keySet()) {
            JsonObject loc = fire.getAsJsonObject(part).getAsJsonObject("location");
            assertEquals(Set.of("x", "y", "z"), loc.keySet(), part + ": a missing axis would be read as 0 and throw the part off its offset");
        }
    }

    @Test
    void theCaseComesOutToTheRightAtTickTwo() throws IOException {
        Gl54Casing c = Gl54Casing.get();
        assertTrue(c.present());
        assertEquals(2, c.tick(), "frame 7 of 60 per second is 0.1 s");
        double[] v = c.launch();
        assertTrue(v[2] < 0, "thrown to the right (the .blend's -Y): " + v[2]);
        assertTrue(v[1] > 0, "up a little");
        assertTrue(Math.abs(v[2]) > Math.abs(v[0]));
        // the speed is one constant, Congo Lake's; the direction is the animation's
        assertEquals(Math.hypot(0.12, 0.18), Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]), 1e-9);
        assertEquals(c.velSide() / c.velHeight(), v[2] / v[1], 1e-6);
    }

    @Test
    void casingOffsetFromTheEyeIsTheGunOriginPlusThePortInTheModel() {
        Gl54Casing c = new Gl54Casing(true, 2, -0.275, 0.064, -0.036, -0.1, -0.05, 0.0, -0.432, 1.368, -2.1, 0, 1.368, -1.668, 0, 0, 0, 0F, 0F);
        double[] at = c.eyeOffset(new double[] {0.5, -0.2, -0.3}, new double[] {0.1, 0.2, 0.3});
        assertEquals(0.5 - 0.21, at[0], 1e-9);
        assertEquals(-0.2 + 0.2736, at[1], 1e-9);
        assertEquals(-0.3 - 0.1296, at[2], 1e-9);
    }

    @Test
    void thePortOfTheFileIsBehindTheMiddleOnTheRightOfTheAxis() {
        Gl54Casing c = Gl54Casing.get();
        assertTrue(c.portZ() < -1.5, "the ejection window is behind the middle: " + c.portZ());
        assertTrue(c.portX() < 0, "to the right of the barrel's axis: " + c.portX());
        assertEquals(1.37, c.portY(), 0.05);
        assertEquals(1.37, c.restY(), 0.05);
    }

    @Test
    void casingFileWithoutACaseIsNone() {
        assertTrue(!Gl54Casing.parse(new java.io.StringReader("{\"present\": false}")).present());
    }

    @Test
    void textureIsAPaletteOfEightByEightCells() throws IOException {
        try (InputStream in = Gl54ModelTest.class.getResourceAsStream("/assets/ntmgunfeel/textures/models/weapons/gl54.png")) {
            byte[] png = in.readAllBytes();
            // IHDR: width and height, 8 cells of 8 pixels
            int w = ((png[16] & 255) << 24) | ((png[17] & 255) << 16) | ((png[18] & 255) << 8) | (png[19] & 255);
            int h = ((png[20] & 255) << 24) | ((png[21] & 255) << 16) | ((png[22] & 255) << 8) | (png[23] & 255);
            assertEquals(64, w);
            assertEquals(64, h);
        }
    }

    @Test
    void everyCornerSitsOnItsMaterialsCell() throws IOException {
        // all uv of the model are the middle of a cell of the 8 x 8 palette
        for (GroupObject g : mesh()) {
            float[] q = g.quads(false);
            for (int v = 0; v + 8 <= q.length; v += 8) {
                float u = q[v + 3] * 8F, w = q[v + 4] * 8F;
                assertEquals(0.5F, u - (float) Math.floor(u), 1e-3, g.name);
                assertEquals(0.5F, w - (float) Math.floor(w), 1e-3, g.name);
            }
        }
    }
}
