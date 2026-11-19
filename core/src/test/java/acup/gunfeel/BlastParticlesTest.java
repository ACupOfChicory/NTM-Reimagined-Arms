package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

// NTM's blast puffs at no more than MAX of its blocks, spread evenly over all of them.
class BlastParticlesTest {
    @Test
    void capEvenly() {
        List<Integer> few = List.of(1, 2, 3);
        assertSame(few, BlastParticles.cap(few));
        List<Integer> many = new ArrayList<>();
        for (int i = 0; i < 26_000; i++) many.add(i);
        List<Integer> capped = BlastParticles.cap(many);
        assertEquals(BlastParticles.MAX, capped.size());
        assertEquals(0, capped.get(0));
        assertEquals((int) (1499L * 26_000 / BlastParticles.MAX), capped.get(1499));
    }
}
