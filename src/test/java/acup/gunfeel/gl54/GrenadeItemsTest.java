package acup.gunfeel.gl54;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

// The grenade items' files (one per kind): the model with the author's grey body and nose mask, the tint of the kind, and the
// colours that are the HUD's (and notes/gl54/textures/tint.py's TYPES).
class GrenadeItemsTest {
    static final Path ASSETS = Path.of("src/main/resources/assets/ntmgunfeel");
    // = tint.py TYPES = the first mock-up's: key -> rgb
    static final Map<String, int[]> TYPES = Map.of("airburst", new int[] {79, 163, 247}, "ap", new int[] {155, 92, 246}, "he", new int[] {229, 72, 60},
            "inc", new int[] {255, 154, 46}, "smoke", new int[] {196, 198, 200}, "chlorine", new int[] {200, 224, 60}, "phosgene", new int[] {143, 214, 160},
            "mustard", new int[] {184, 134, 11}, "acid", new int[] {25, 201, 122});

    @Test
    void coloursAreTheHudsAndTheMockups() {
        assertEquals(TYPES.size(), GrenadeKind.values().length);
        for (GrenadeKind k : GrenadeKind.values()) {
            int[] c = TYPES.get(k.key);
            assertEquals(c[0] << 16 | c[1] << 8 | c[2], k.rgb, k.key);
        }
    }

    @Test
    void everyKindHasItsItemFileWithItsTint() throws IOException {
        for (GrenadeKind k : GrenadeKind.values()) {
            JsonObject root = JsonParser.parseString(Files.readString(ASSETS.resolve("items/gl54_grenade_" + k.key + ".json"))).getAsJsonObject();
            JsonObject model = root.getAsJsonObject("model");
            assertEquals("ntmgunfeel:item/gl54_grenade", model.get("model").getAsString());
            var tints = model.getAsJsonArray("tints");
            assertEquals(2, tints.size());
            assertEquals("ntmgunfeel:grenade_tint", tints.get(1).getAsJsonObject().get("type").getAsString());
            assertEquals(k.key, tints.get(1).getAsJsonObject().get("kind").getAsString());
        }
    }

    @Test
    void theModelIsTwoLayersBodyThenMask() throws IOException {
        JsonObject m = JsonParser.parseString(Files.readString(ASSETS.resolve("models/item/gl54_grenade.json"))).getAsJsonObject();
        assertEquals("ntmgunfeel:item/gl54_grenade_base", m.getAsJsonObject("textures").get("layer0").getAsString());
        assertEquals("ntmgunfeel:item/gl54_grenade_mask", m.getAsJsonObject("textures").get("layer1").getAsString());
    }

    @Test
    void theMaskIsGreyAndTheBodyHasNoColour() throws IOException {
        var mask = ImageIO.read(ASSETS.resolve("textures/item/gl54_grenade_mask.png").toFile());
        var base = ImageIO.read(ASSETS.resolve("textures/item/gl54_grenade_base.png").toFile());
        assertEquals(16, mask.getWidth());
        assertEquals(16, base.getHeight());
        int coloured = 0;
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
            int p = mask.getRGB(x, y);
            if ((p >>> 24) != 0) {
                coloured++;
                assertEquals((p >> 16) & 255, (p >> 8) & 255, "grey mask");
            }
        }
        assertTrue(coloured > 0);
    }
}
