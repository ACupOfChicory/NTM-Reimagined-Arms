package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

// The walk of the blocks on a line without the game: the blocks in order, the stop, the miss (the game's own walk:
// BlockWalkVanillaTest on 26.2).
class BlockWalkTest {
    private static List<String> walk(double fx, double fy, double fz, double tx, double ty, double tz, int stopAt) {
        List<String> seen = new ArrayList<>();
        String r = BlockWalk.walk(fx, fy, fz, tx, ty, tz, seen, (s, x, y, z) -> {
            s.add(x + "," + y + "," + z);
            return s.size() == stopAt ? "stop" : null;
        }, s -> "miss");
        seen.add(r);
        return seen;
    }

    @Test
    void alongX() {
        assertEquals(List.of("0,0,0", "1,0,0", "2,0,0", "miss"), walk(0.5, 0.5, 0.5, 2.5, 0.5, 0.5, Integer.MAX_VALUE));
        assertEquals(List.of("0,0,0", "-1,0,0", "miss"), walk(0.5, 0.5, 0.5, -0.5, 0.5, 0.5, Integer.MAX_VALUE));
    }

    @Test
    void diagonalAndStop() {
        // through the edge at (1, 1): the tie goes to y, then x
        assertEquals(List.of("0,0,0", "0,1,0", "1,1,0", "miss"), walk(0.5, 0.5, 0.5, 1.5, 1.5, 0.5, Integer.MAX_VALUE));
        assertEquals(List.of("0,0,0", "0,1,0", "stop"), walk(0.5, 0.5, 0.5, 1.5, 1.5, 0.5, 2));
    }

    @Test
    void endsPushedOut() {
        // an end on a face reaches into the block beyond it: 1e-7 of the line outwards
        assertEquals(List.of("0,0,0", "1,0,0", "miss"), walk(0.5, 0.5, 0.5, 1.0, 0.5, 0.5, Integer.MAX_VALUE));
        assertEquals(List.of("0,0,0", "miss"), walk(0.5, 0.5, 0.5, 0.99, 0.5, 0.5, Integer.MAX_VALUE));
        assertEquals(List.of("miss"), walk(0.5, 0.5, 0.5, 0.5, 0.5, 0.5, Integer.MAX_VALUE));
    }
}
