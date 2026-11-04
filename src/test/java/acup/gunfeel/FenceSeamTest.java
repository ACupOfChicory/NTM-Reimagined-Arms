package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

// Where two fences, walls, panes or bars join, each one's arm ends on the block's side with a face the game leaves
// undrawn against the neighbour (its cull face). Tracers.meshHole puts no hole on such a face (one there hangs in the
// air beside the rail: the joint check, runCheckClient -PfenceSeam). Here: every face of the arms' models that lies
// on the block's side carries that side as its cull face, so the game's culling is what hides it.
class FenceSeamTest {
    private static JsonObject model(String name) throws Exception {
        try (var in = FenceSeamTest.class.getResourceAsStream("/assets/minecraft/models/block/" + name + ".json")) {
            assertNotNull(in, name);
            JsonObject m = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            // the elements come from the parent when the model has none of its own
            if (!m.has("elements") && m.has("parent")) return model(m.get("parent").getAsString().replace("minecraft:", "").replace("block/", ""));
            return m;
        }
    }

    @Test
    void armEndsOnTheSideAreCulled() throws Exception {
        for (String name : List.of("oak_fence_side", "spruce_fence_side", "cobblestone_wall_side", "glass_pane_side", "iron_bars_side")) {
            int ends = 0;
            for (var el : model(name).getAsJsonArray("elements")) {
                JsonObject e = el.getAsJsonObject();
                double fromZ = e.getAsJsonArray("from").get(2).getAsDouble();
                JsonObject faces = e.getAsJsonObject("faces");
                // the side models point north: an arm reaching the block's north side ends there
                if (fromZ != 0 || !faces.has("north")) continue;
                ends++;
                JsonObject north = faces.getAsJsonObject("north");
                assertTrue(north.has("cullface") && north.get("cullface").getAsString().equals("north"), name + ": the arm's end has no cull face");
            }
            assertTrue(ends > 0, name + ": no arm reaching the side");
        }
    }

    // a hole's polygon with no area, or a sliver a hair wide (a cut on float UVs off a texel's edge), is dropped:
    // it draws nothing and lies off the pixels it was cut to (seen on NTM's chain-link fence)
    private static List<double[]> rect(double x0, double y0, double x1, double y1) {
        return List.of(new double[] {x0, y0}, new double[] {x1, y0}, new double[] {x1, y1}, new double[] {x0, y1});
    }

    @Test
    void noHoleOnAPolygonWithNoArea() {
        assertTrue(Tracers.some(rect(0, 0, 1 / 16.0, 1 / 16.0)), "a texel");
        assertTrue(Tracers.some(rect(0, 0, 1 / 64.0, 1)), "a quarter-texel strip");
        assertFalse(Tracers.some(rect(0.1875, 0, 0.1875, 0.0625)), "a line, two corners twice");
        assertFalse(Tracers.some(rect(-0.1251, 0.3125, -0.125, 0.375)), "a sliver 1/10000 block wide");
        assertFalse(Tracers.some(List.of(new double[] {0, 0}, new double[] {1, 1})), "two points");
    }
}
