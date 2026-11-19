package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

import net.minecraft.resources.Identifier;

// ResourceIds (core) against the game's Identifier.tryParse itself: the same id ("namespace:path") or none, on ids as
// the rules write them and on broken ones; every block's id is the bullet stand's (BulletCheck: the game's registry).
class ResourceIdsTest {
    private static String game(String s) {
        Identifier id = Identifier.tryParse(s);
        return id == null ? null : id.toString();
    }

    @Test
    void asTheGame() {
        String[] fixed = {"", ":", "::", "a:", ":a", "stone", "minecraft:stone", "hbm:machine_solar_boiler", "Stone", "minecraft:Stone",
                "..:a", ".:a", "...:a", "a/b:c", "a:b/c", "a:b:c", "a b", "a:b c", "minecraft:oak_fence[east=true]", "#minecraft:logs",
                "a.b-c_d:e.f-g_h/i", "é", "a:é", "\u0000", "a:\n", "0:0", "-:-", "_:_", "/:a", "a:/", "a:..", "..", "../a"};
        for (String s : fixed) assertEquals(game(s), ResourceIds.tryParse(s), s);
        String abc = "abz09_-./:A Z#~$%@é\u00a0[]=,";
        SplittableRandom r = new SplittableRandom(1120L);
        int valid = 0;
        for (int i = 0; i < 300_000; i++) {
            StringBuilder b = new StringBuilder();
            int n = r.nextInt(9);
            for (int k = 0; k < n; k++) b.append(r.nextInt(4) == 0 ? abc.charAt(r.nextInt(abc.length())) : abc.charAt(r.nextInt(8)));
            String s = b.toString();
            String want = game(s);
            assertEquals(want, ResourceIds.tryParse(s), s);
            if (want != null) valid++;
        }
        assertTrue(valid > 30_000, valid + " valid");
        // every char in the 16-bit range, alone and as a namespace and a path
        for (int c = 0; c < 0x10000; c++) {
            String ch = String.valueOf((char) c);
            for (String s : new String[] {ch, ch + ":a", "a:" + ch}) assertEquals(game(s), ResourceIds.tryParse(s), "char " + c);
        }
    }
}
