package acup.gunfeel.ai.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;

// Each AI mob's own gait ahead from recordings of people (BotGaits): one of each slot's variants by its entity id
class BotGaitsTest {
    static final String QUICK = "run_cmu16_56";

    @Test
    void everyVariantIsReadItsClipsLoopTheLegsAtTheirOwnPace() {
        // each of the 10 jogs with each of the 3 sprints and each of the 2 strolls
        assertEquals(60, BotGaits.count());
        Set<String> walks = new HashSet<>(), jogs = new HashSet<>(), sprints = new HashSet<>(), strolls = new HashSet<>();
        float[] a = new float[3];
        for (int n = 0; n < BotGaits.count(); n++) {
            BotGaitOverride o = BotGaits.variant(n);
            assertNotNull(o);
            assertNotNull(o.stroll());
            for (String c : new String[] { o.walk(), o.jog(), o.sprint(), o.stroll().clip() }) {
                BotClip clip = BotClips.get(c);
                assertNotNull(clip, c);
                assertEquals(BotClip.Mode.LOOP, clip.mode(), c);
                for (String bone : BotPoser.LEGS) assertTrue(clip.rotation(bone, clip.length() * 0.3F, a), c + " " + bone);
            }
            assertTrue(o.walkSpeed() > 2F && o.jogSpeed() > 2F && o.sprintSpeed() > 2F, "paces " + o);
            // a walk's: under a metre a second
            assertTrue(o.stroll().speed() > 0.5F && o.stroll().speed() < 1F && o.stroll().clip().startsWith("walk_"), "the stroll " + o);
            assertEquals(0.45F, o.drop(), 1e-6F);
            assertEquals(0.45F, o.stroll().drop(), 1e-6F);
            assertTrue(o.bare());
            assertNull(o.back());
            assertNull(o.left());
            assertNull(o.right());
            assertNull(o.crouch());
            // the quick steps on the run only; the walk the jog's own where it can be
            assertNotEquals(QUICK, o.walk());
            if (!QUICK.equals(o.jog())) assertEquals(o.jog(), o.walk());
            assertTrue(o.jog().startsWith("run_") && o.sprint().startsWith("sprint_"), o.toString());
            walks.add(o.walk());
            jogs.add(o.jog());
            sprints.add(o.sprint());
            strolls.add(o.stroll().clip());
        }
        assertEquals(9, walks.size());
        assertEquals(10, jogs.size());
        assertEquals(3, sprints.size());
        assertEquals(2, strolls.size());
    }

    @Test
    void aMobKeepsItsOwnAndTheyAreSpread() {
        Map<BotGaitOverride, Integer> seen = new HashMap<>();
        int same = 0, n = 100 * BotGaits.count();
        for (int id = 0; id < n; id++) {
            BotGaitOverride o = BotGaits.own(id);
            assertNotNull(o);
            assertSame(o, BotGaits.own(id));
            assertSame(o, BotGaitOverride.of(id), "the mob's own by default");
            seen.merge(o, 1, Integer::sum);
            if (o == BotGaits.own(id + 1)) same++;
        }
        assertEquals(BotGaits.count(), seen.size(), "every variant played");
        // 100 each on average
        for (int k : seen.values()) assertTrue(k > 60 && k < 140, "a variant " + k + " times of " + n);
        // mobs made one after another (ids in a row) mostly go each its own way
        assertTrue(same < n / 10, same + " of " + n + " like the one before");
    }

    @Test
    void theGaitsNamesAreNoneOfTheModsOtherClips() throws IOException {
        // BotClips reads the gaits' file last: a name of the mod's own would be put over
        Set<String> other = new HashSet<>(names(BotClips.PATH));
        other.addAll(names(BotClips.PEOPLE));
        assertFalse(other.isEmpty());
        for (String n : names(BotGaits.PATH)) assertFalse(other.contains(n), n);
    }

    static Set<String> names(String path) throws IOException {
        try (InputStream in = BotGaits.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("animations").keySet();
        }
    }

    @Test
    void slowerThanItsRunItWalks() {
        BotGaitOverride o = BotGaits.own(11);
        assertNotNull(o);
        float[] at = new float[BotPoser.LEGS.length * 3], want = new float[at.length], a = new float[3];
        BotGaitOverride walkOnly = BotGaitOverride.all(o.stroll().clip(), o.stroll().speed(), o.drop(), true),
                runOnly = new BotGaitOverride(o.walk(), o.jog(), o.sprint(), o.walkSpeed(), o.jogSpeed(), o.sprintSpeed(), o.drop(), true);
        // a creep (0.09 a tick): the stroll's legs at the stroll's pace
        assertTrue(BotPoser.gaitLegs(0.09F, 0F, 0F, 3F, o, at, a));
        assertTrue(BotPoser.gaitLegs(0.09F, 0F, 0F, 3F, walkOnly, want, a));
        for (int i = 0; i < at.length; i++) assertEquals(want[i], at[i], 1e-4F, "a creep's legs " + i);
        BotClip s = BotClips.get(o.stroll().clip());
        assertEquals(BotPoser.CYCLE * 0.09F / (o.stroll().speed() * s.length()), BotPoser.pace(0.7F, 0.09F, 0F, 0F, o), 1e-5F);
        // a walk (0.19): the run's
        assertTrue(BotPoser.gaitLegs(0.19F, 0F, 0F, 3F, o, at, a));
        assertTrue(BotPoser.gaitLegs(0.19F, 0F, 0F, 3F, runOnly, want, a));
        for (int i = 0; i < at.length; i++) assertEquals(want[i], at[i], 1e-4F, "a walk's legs " + i);
        assertEquals(BotPoser.pace(0.7F, 0.19F, 0F, 0F, runOnly), BotPoser.pace(0.7F, 0.19F, 0F, 0F, o), 1e-6F);
        // between, the two by the stroll's share (the walk's whole: no other way in), every mob's
        for (int n = 0; n < BotGaits.count(); n += 7) {
            BotGaitOverride v = BotGaits.variant(n);
            BotGaitOverride w = BotGaitOverride.all(v.stroll().clip(), v.stroll().speed(), v.drop(), true),
                    r = new BotGaitOverride(v.walk(), v.jog(), v.sprint(), v.walkSpeed(), v.jogSpeed(), v.sprintSpeed(), v.drop(), true);
            float[] ofW = new float[at.length], ofR = new float[at.length];
            for (float speed : new float[] { 0.11F, 0.125F, 0.14F }) {
                float k = BotPoser.strolling(speed);
                assertTrue(k > 0.05F && k < 0.95F, "inside the band at " + speed);
                assertTrue(BotPoser.gaitLegs(speed, 0F, 0F, 3F, v, at, a));
                assertTrue(BotPoser.gaitLegs(speed, 0F, 0F, 3F, w, ofW, a));
                assertTrue(BotPoser.gaitLegs(speed, 0F, 0F, 3F, r, ofR, a));
                for (int i = 0; i < at.length; i++)
                    assertEquals(k * ofW[i] + (1F - k) * ofR[i], at[i], 1e-3F, v.stroll().clip() + " over " + v.walk() + " at " + speed + " " + i);
            }
        }
        // and its share goes evenly from the one to the other
        float last = 1F;
        for (float v = BotPoser.STROLL_FROM; v <= BotPoser.STROLL_FULL; v += 0.005F) {
            float k = BotPoser.strolling(v);
            assertTrue(k <= last + 1e-6F && k >= 0F && k <= 1F, "the stroll's share " + k + " at " + v);
            last = k;
        }
        assertEquals(1F, BotPoser.strolling(BotPoser.STROLL_FROM), 1e-6F);
        assertEquals(0F, BotPoser.strolling(BotPoser.STROLL_FULL), 1e-6F);
    }

    @Test
    void oldKeepsTheModsOwnClips() {
        String was = System.getProperty("ntmgunfeel.botMoves");
        System.setProperty("ntmgunfeel.botMoves", "old");
        try {
            assertNull(BotGaits.own(5));
            assertNull(BotGaitOverride.of(5));
        } finally {
            if (was == null) System.clearProperty("ntmgunfeel.botMoves");
            else System.setProperty("ntmgunfeel.botMoves", was);
        }
    }

    @Test
    void aMobsLegsAreItsOwnClipsAtTheirPace() {
        BotGaitOverride o = BotGaits.own(11);
        assertNotNull(o);
        BotClip walk = BotClips.get(o.walk());
        // at a walk (0.19 blocks a tick): a cycle of its clip each walkSpeed * its length blocks, its feet standing
        assertEquals(BotPoser.CYCLE * 0.19F / (o.walkSpeed() * walk.length()), BotPoser.pace(0.7F, 0.19F, 0F, 0F, o), 1e-5F);
        // the legs its clip's, not the mod's own walk
        float[] put = new float[BotPoser.LEGS.length * 3], own = new float[put.length], a = new float[3];
        assertTrue(BotPoser.gaitLegs(0.19F, 0F, 0F, 3F, o, put, a));
        assertTrue(BotPoser.gaitLegs(0.19F, 0F, 0F, 3F, null, own, a));
        float off = 0F;
        for (int i = 0; i < put.length; i++) off = Math.max(off, Math.abs(put[i] - own[i]));
        assertTrue(off > 1F, "the legs " + off + " degrees from the mod's own walk");
    }
}
