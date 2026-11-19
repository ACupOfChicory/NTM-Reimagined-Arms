package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MobReviewStoreTest {
    @TempDir Path dir;
    @Test void persistsRussianNotesAndRestoresUnratedState() throws Exception {
        Path file=dir.resolve("reviews.tsv");
        var store=new MobReviewStore(file);
        store.set("hbm:entity_pigeon",new MobReviewStore.Decision("bad","Крыло\tне там\nпри повороте"));
        var loaded=new MobReviewStore(file);
        assertEquals(new MobReviewStore.Decision("bad","Крыло не там при повороте"),loaded.get("hbm:entity_pigeon"));
        assertTrue(loaded.reviewed("hbm:entity_pigeon"));
        loaded.set("hbm:entity_pigeon",null);
        assertFalse(new MobReviewStore(file).reviewed("hbm:entity_pigeon"));
    }
    @Test void brokenInputCannotSilentlyErasePreviousReviews() throws Exception {
        Path file=dir.resolve("reviews.tsv");
        Files.writeString(file,"minecraft:skeleton\tok\tнормально\nсломанная строка\n");
        assertThrows(java.io.IOException.class,() -> new MobReviewStore(file));
        assertTrue(Files.readString(file).contains("нормально"));
    }
    @Test void noteAloneDoesNotCountAsReviewed() throws Exception {
        var store=new MobReviewStore(dir.resolve("reviews.tsv"));
        store.set("minecraft:skeleton",new MobReviewStore.Decision("note","проверить ноги"));
        assertFalse(new MobReviewStore(dir.resolve("reviews.tsv")).reviewed("minecraft:skeleton"));
    }
}
