package acup.gunfeel.ai.imitation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import acup.gunfeel.ai.CoverQuery;
import net.minecraft.core.BlockPos;

// A recorded view is one line of JSON that train.py reads: its numbers, the enemies, the cover with their rows; the
// header names the numbers as this build counts them.
class FightRecorderTest {
    static String num(double x) {
        StringBuilder b = new StringBuilder();
        FightRecorder.num(b, x);
        return b.toString();
    }

    @Test
    void threeDecimalsNoExponent() {
        assertEquals("0", num(0));
        assertEquals("1.5", num(1.5));
        assertEquals("-0.05", num(-0.05));
        assertEquals("0.001", num(0.0012));
        assertEquals("123456.789", num(123456.7891));
        assertEquals("0", num(Double.NaN));
        assertEquals("0", num(1e-7));
    }

    @Test
    void aViewIsALineOfJson() {
        FightView v = FightHistoryTest.view(120, 1.25, 2, true, false);
        v.loaded = 12;
        v.capacity = 30;
        CoverQuery.Spot s = new CoverQuery.Spot(BlockPos.asLong(3, 64, -5), 7.5, 2, true, BlockPos.asLong(4, 64, -5));
        double[] f = FightFeatures.of(v);
        String line = FightRecorder.line(v, f, List.of(s), FightLook.spotRows(List.of(s), v));
        assertEquals('\n', line.charAt(line.length() - 1));
        JsonObject o = JsonParser.parseString(line).getAsJsonObject();
        assertEquals(120, o.get("t").getAsLong());
        assertEquals(1.25, o.get("x").getAsDouble());
        assertEquals(2, o.get("ch").getAsInt());
        assertEquals(FightFeatures.SIZE, o.getAsJsonArray("f").size());
        assertEquals(1, o.getAsJsonArray("e").size());
        var c = o.getAsJsonArray("c").get(0).getAsJsonArray();
        assertEquals(3, c.get(0).getAsInt());
        assertEquals(-5, c.get(2).getAsInt());
        assertEquals(1, c.get(6).getAsInt(), "a place to shoot from");
        assertEquals(FightFeatures.SPOT_SIZE, c.get(7).getAsJsonArray().size());
        JsonObject h = JsonParser.parseString(FightRecorder.header("player", "Some One", 5)).getAsJsonObject();
        assertEquals("Some_One", h.get("name").getAsString());
        assertEquals(FightFeatures.NAMES.get(13), h.getAsJsonArray("features").get(13).getAsString());
    }
}
