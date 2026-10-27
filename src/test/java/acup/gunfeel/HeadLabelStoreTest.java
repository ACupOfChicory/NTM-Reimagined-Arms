package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HeadLabelStoreTest {
    @TempDir Path root;
    @Test void multipleHeadsAndNoHeadSurviveRestart() throws Exception {
        var store=new HeadLabelStore(root);
        var sample=store.snapshot("minecraft:wither",Map.of("geometry","три головы"));
        store.save("minecraft:wither",sample,"done",Map.of("1:-1",1,"2:-1",2,"3:7",3));
        var loaded=new HeadLabelStore(root);
        assertEquals(3,loaded.get("minecraft:wither").labels().size());
        assertTrue(loaded.done("minecraft:wither"));
        var none=loaded.snapshot("hbm:test",Map.of());
        loaded.save("hbm:test",none,"none",Map.of());
        assertEquals(2,new HeadLabelStore(root).completed());
    }
    @Test void incompleteSelectionDoesNotOverwriteConfirmedExample() throws Exception {
        var store=new HeadLabelStore(root);
        var first=store.snapshot("minecraft:skeleton",Map.of("pose",1));
        store.save("minecraft:skeleton",first,"done",Map.of("0:-1",1));
        assertThrows(IllegalArgumentException.class,()->store.save("minecraft:skeleton",first,"done",Map.of()));
        assertTrue(new HeadLabelStore(root).done("minecraft:skeleton"));
        var second=store.snapshot("minecraft:skeleton",Map.of("pose",2));
        store.save("minecraft:skeleton",second,"draft",Map.of("1:-1",1));
        assertFalse(new HeadLabelStore(root).done("minecraft:skeleton"));
        assertTrue(Files.readString(root.resolve("samples").resolve(first).resolve("annotation.json")).contains("done"));
    }
}
