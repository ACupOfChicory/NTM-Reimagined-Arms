package acup.gunfeel.smoke.client;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import acup.gunfeel.host.render.RecordingRenderPort;

class SmokeAtlasSlotsTest {
    @AfterEach void close() { SmokeAtlasSlots.close(); }

    @Test void slotsPersistAndFreedSlotsGoToNewCloudsInOrder() {
        var p = new RecordingRenderPort();
        SmokeAtlasSlots.init(p,(x,y,z)->.5F);
        var a=SmokeMarchTest.slot(1).cloud(); var b=SmokeMarchTest.slot(2).cloud(); var c=SmokeMarchTest.slot(3).cloud();
        SmokeAtlasSlots.update(List.of(a,b));
        assertEquals(0,SmokeAtlasSlots.drawn().get(0).index());
        assertEquals(1,SmokeAtlasSlots.drawn().get(1).index());
        SmokeAtlasSlots.update(List.of(b,c));
        assertEquals(1,SmokeAtlasSlots.drawn().get(0).index());
        assertEquals(0,SmokeAtlasSlots.drawn().get(1).index());
        SmokeAtlasSlots.none();
        assertTrue(SmokeAtlasSlots.drawn().isEmpty());
        SmokeAtlasSlots.update(List.of(c));
        assertEquals(0,SmokeAtlasSlots.drawn().get(0).index());
    }

    @Test void unchangedAgeWritesNothingAndNewSlotWritesAllRows() {
        var p = new RecordingRenderPort();
        SmokeAtlasSlots.init(p,(x,y,z)->.5F);
        var c=SmokeMarchTest.slot(1).cloud();
        SmokeAtlasSlots.update(List.of(c));
        assertTrue(p.log.getLast().contains(" 0 0 "+SmokeAtlasSlots.WIDTH+" "+SmokeAtlasSlots.TILE_H+" "));
        p.log.clear();
        SmokeAtlasSlots.update(List.of(c));
        assertTrue(p.log.isEmpty());
        var s=SmokeAtlasSlots.drawn().getFirst();
        for (int tick=0;tick<100;tick++) {
            int previousLo=s.boxLo,previousHi=s.boxHi;
            c.grid.step();
            SmokeAtlasSlots.update(List.of(c));
            int[] box=c.grid.box();
            assertEquals(Math.min(box[2],previousLo),s.rowLo);
            assertEquals(Math.max(Math.min(SmokeAtlasSlots.TILE_H-1,box[5]+2),previousHi),s.rowHi);
            assertEquals(Math.min(SmokeAtlasSlots.TILE_H-1,box[5]+2),s.boxHi);
        }
        assertFalse(p.log.isEmpty());
    }

    @Test void lostTextureStartsSlotsAgain() {
        var p=new RecordingRenderPort();
        SmokeAtlasSlots.init(p,(x,y,z)->.5F);
        var c=SmokeMarchTest.slot(1).cloud();
        SmokeAtlasSlots.update(List.of(c));
        int old=SmokeAtlasSlots.texture();
        p.lose=old;
        SmokeAtlasSlots.update(List.of(c));
        assertNotEquals(old,SmokeAtlasSlots.texture());
        assertEquals(0,SmokeAtlasSlots.drawn().getFirst().index());
    }
}
