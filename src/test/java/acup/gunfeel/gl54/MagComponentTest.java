package acup.gunfeel.gl54;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.mojang.serialization.JsonOps;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;

// The data components of the magazines (MagContents on a magazine, GunMag on the gun): the saved form and the network form
// come back as they went in, and the keys stay strings.
class MagComponentTest {
    static <T> T json(com.mojang.serialization.Codec<T> codec, T v) {
        var e = codec.encodeStart(JsonOps.INSTANCE, v).getOrThrow();
        return codec.parse(JsonOps.INSTANCE, e).getOrThrow();
    }

    @Test
    void magazineSavedForm() {
        MagContents c = new MagContents(GrenadeKind.SAMPLE_MIX);
        assertEquals(c, json(MagContents.CODEC, c));
        assertEquals(MagContents.EMPTY, json(MagContents.CODEC, MagContents.EMPTY));
        var e = MagContents.CODEC.encodeStart(JsonOps.INSTANCE, MagContents.filled("he", 8)).getOrThrow();
        assertTrue(e.isJsonArray());
        assertEquals("he", e.getAsJsonArray().get(0).getAsString());
    }

    @Test
    void magazineHoldsAtMostEight() {
        var e = MagContents.CODEC.encodeStart(JsonOps.INSTANCE, MagContents.filled("he", 8)).getOrThrow();
        e.getAsJsonArray().add("he");
        assertTrue(MagContents.CODEC.parse(JsonOps.INSTANCE, e).result().isEmpty());
    }

    @Test
    void magazineNetworkForm() {
        MagContents c = new MagContents(List.of("he", "airburst", "airburst", "someday_new"));
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        MagContents.STREAM_CODEC.encode(buf, c);
        assertEquals(c, MagContents.STREAM_CODEC.decode(buf));
        assertEquals("mixed", c.kind());
    }

    @Test
    void gunSavedAndNetworkForm() {
        GunMag g = new GunMag(List.of("he", "inc", "smoke"), "airburst", "mixed", true, true);
        assertEquals(g, json(GunMag.CODEC, g));
        assertEquals(GunMag.NONE, json(GunMag.CODEC, GunMag.NONE));
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        GunMag.STREAM_CODEC.encode(buf, g);
        assertEquals(g, GunMag.STREAM_CODEC.decode(buf));
    }

    @Test
    void anOldGunWithoutTheFieldsReadsAsEmpty() {
        var e = new com.google.gson.JsonObject();
        assertEquals(GunMag.NONE, GunMag.CODEC.parse(JsonOps.INSTANCE, e).getOrThrow());
    }

    @Test
    void theKeysAreStable() {
        // saved magazines carry these strings for years: a rename would empty them
        assertEquals(List.of("airburst", "ap", "he", "inc", "smoke", "chlorine", "phosgene", "mustard", "acid"),
                java.util.Arrays.stream(GrenadeKind.values()).map(k -> k.key).toList());
        assertEquals("mixed", GrenadeKind.MIXED);
    }

    @Test
    void commandWordsMakeMagazines() {
        assertEquals(List.of("he", "he", "airburst"), MagCommand.parse("he,HE, AB"));
        assertEquals(MagContents.filled("smoke", 8).rounds(), MagCommand.parse("smoke"));
        assertEquals(List.of(), MagCommand.parse("empty"));
        assertEquals(null, MagCommand.parse("he,nonsense"));
        assertEquals(null, MagCommand.parse("he,he,he,he,he,he,he,he,he"));
    }
}
