package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

// State per level by the version's level object: a WeakHashMap's operations, the level held weakly
class LevelMapTest {
    @Test
    void theMapsOwnOperations() {
        LevelMap<List<String>> m = new LevelMap<>();
        Object a = new Object(), b = new Object();
        assertTrue(m.isEmpty());
        List<String> la = m.computeIfAbsent(a, l -> new ArrayList<>());
        assertSame(la, m.computeIfAbsent(a, l -> new ArrayList<>()), "made once");
        assertSame(la, m.get(a));
        assertNull(m.get(b));
        assertNull(m.put(b, List.of("x")));
        assertEquals(List.of("x"), m.put(b, List.of("y")));
        assertSame(la, m.remove(a));
        assertNull(m.get(a));
        assertFalse(m.isEmpty());
        m.clear();
        assertTrue(m.isEmpty());
    }

    @Test
    void aLevelNobodyHoldsGoes() throws InterruptedException {
        LevelMap<String> m = new LevelMap<>();
        m.put(new Object(), "state of a level gone");
        for (int i = 0; i < 200 && !m.isEmpty(); i++) {
            System.gc();
            Thread.sleep(5);
        }
        assertTrue(m.isEmpty(), "the weak key let go");
    }
}
