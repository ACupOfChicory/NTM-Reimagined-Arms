package acup.gunfeel.gas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

// The gases' state saved with the entity (the attachment's codec) and read back.
class GasStateCodecTest {
    @Test
    void theStateIsSavedAndReadBack() {
        GasState s = new GasState();
        s.dose[GasState.CHLORINE] = 1.5F;
        s.dose[GasState.PHOSGENE] = 4F;
        s.k = 0.3F;
        s.latent = 120;
        s.smelled = true;
        var tag = GasStateCodec.CODEC.codec().encodeStart(com.mojang.serialization.JsonOps.INSTANCE, s).getOrThrow();
        GasState back = GasStateCodec.CODEC.codec().parse(com.mojang.serialization.JsonOps.INSTANCE, tag).getOrThrow();
        assertEquals(1.5F, back.dose[GasState.CHLORINE]);
        assertEquals(4F, back.dose[GasState.PHOSGENE]);
        assertEquals(0.3F, back.k);
        assertEquals(120, back.latent);
        assertTrue(back.smelled);
        assertTrue(back.persists());
        assertFalse(new GasState().persists());
    }
}
