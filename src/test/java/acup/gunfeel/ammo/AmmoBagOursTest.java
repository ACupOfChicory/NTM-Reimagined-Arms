package acup.gunfeel.ammo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

class AmmoBagOursTest {
    // the bag's tag holds every round of the mod's (an item model ammo_*), each optional (a feature off leaves it out)
    @Test
    void tagHoldsEveryRound() throws IOException {
        var tag = JsonParser.parseString(Files.readString(Path.of("src/main/resources/data/ntmgunfeel/tags/item/ammo_bag.json"))).getAsJsonObject();
        TreeSet<String> inTag = new TreeSet<>();
        for (JsonElement e : tag.getAsJsonArray("values")) {
            assertFalse(e.getAsJsonObject().get("required").getAsBoolean(), e.toString());
            inTag.add(e.getAsJsonObject().get("id").getAsString());
        }
        TreeSet<String> rounds = new TreeSet<>();
        try (Stream<Path> s = Files.list(Path.of("src/main/resources/assets/ntmgunfeel/items"))) {
            s.map(p -> p.getFileName().toString()).filter(n -> n.startsWith("ammo_") && n.endsWith(".json"))
                    .forEach(n -> rounds.add("ntmgunfeel:" + n.substring(0, n.length() - 5)));
        }
        assertEquals(rounds, inTag);
        assertEquals(5, inTag.size());
    }
}
