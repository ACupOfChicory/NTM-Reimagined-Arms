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

import net.minecraft.util.Mth;

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
    void withTheGunUpSlowItWalksAsASoldierEachWay() {
        BotGaitOverride o = BotGaits.own(11);
        assertNotNull(o);
        BotGaitOverride.Aim up = o.aim();
        assertNotNull(up);
        // 100STYLE's Swat each way, the right the left mirrored; every mob the same
        assertEquals("walk_swat_fw", up.ahead().clip());
        assertEquals("strafe_left_swat_sr", up.left().clip());
        assertEquals("strafe_left_swat_sr_mirrored", up.right().clip());
        assertEquals("back_swat_br", up.back().clip());
        for (int n = 0; n < BotGaits.count(); n++) assertEquals(up, BotGaits.variant(n).aim());
        float[] at = new float[BotPoser.LEGS.length * 3], want = new float[at.length], none = new float[at.length], a = new float[3];
        BotGaitOverride.Way[] ways = { up.ahead(), up.right(), up.left(), up.back() };
        float[] headings = { 0F, Mth.HALF_PI, -Mth.HALF_PI, Mth.PI };
        for (int i = 0; i < ways.length; i++) {
            BotGaitOverride.Way w = ways[i];
            float heading = headings[i];
            BotClip clip = BotClips.get(w.clip());
            assertNotNull(clip, w.clip());
            assertEquals(0.45F, w.drop(), 1e-6F);
            // its clip alone: ahead the walk's slot, back the back's, aside the side's
            BotGaitOverride alone = i == 0 ? BotGaitOverride.all(w.clip(), w.speed(), w.drop(), true)
                    : i == 3 ? BotGaitOverride.none(true).withBack(w) : BotGaitOverride.none(true).withAside(up.left(), up.right());
            // slow (a creep, 0.09) with the gun up: its clip alone at its own pace, its feet standing
            assertTrue(BotPoser.gaitLegs(0.09F, 0F, heading, 3F, 1F, o, at, a));
            assertTrue(BotPoser.gaitLegs(0.09F, 0F, heading, 3F, 0F, alone, want, a));
            for (int k = 0; k < at.length; k++) assertEquals(want[k], at[k], 1e-3F, w.clip() + " " + k);
            assertEquals(BotPoser.CYCLE * 0.09F / (w.speed() * clip.length()), BotPoser.pace(0.7F, 0.09F, 0F, heading, 0F, 1F, o), 1e-5F, w.clip());
            // the gun half up: half and half
            assertTrue(BotPoser.gaitLegs(0.09F, 0F, heading, 3F, 0F, o, none, a));
            assertTrue(BotPoser.gaitLegs(0.09F, 0F, heading, 3F, 0.5F, o, at, a));
            for (int k = 0; k < at.length; k++) assertEquals((want[k] + none[k]) / 2F, at[k], 1e-3F, "half up " + w.clip() + " " + k);
            // a walk (0.19, AiSoldier.WALK: a walk shooting) as with the gun down, too fast for the clips
            assertTrue(BotPoser.gaitLegs(0.19F, 0F, heading, 3F, 1F, o, at, a));
            assertTrue(BotPoser.gaitLegs(0.19F, 0F, heading, 3F, 0F, o, none, a));
            for (int k = 0; k < at.length; k++) assertEquals(none[k], at[k], 1e-4F, "a walk " + w.clip() + " " + k);
            assertEquals(BotPoser.pace(0.7F, 0.19F, 0F, heading, 0F, 0F, o), BotPoser.pace(0.7F, 0.19F, 0F, heading, 0F, 1F, o), 1e-6F);
            // between, by the stroll's band
            float k = BotPoser.strolling(0.125F);
            assertTrue(BotPoser.gaitLegs(0.125F, 0F, heading, 3F, 1F, o, at, a));
            assertTrue(BotPoser.gaitLegs(0.125F, 0F, heading, 3F, 0F, alone, want, a));
            assertTrue(BotPoser.gaitLegs(0.125F, 0F, heading, 3F, 0F, i == 0 ? runOnly(o) : null, none, a));
            for (int j = 0; j < at.length; j++) assertEquals(k * want[j] + (1F - k) * none[j], at[j], 1e-3F, "in the band " + w.clip() + " " + j);
            // the foot down goes back from the way it goes: the clip each way the right one
            float with = footWithTheWay(o, heading);
            assertTrue(with < 0.1F, w.clip() + ": the foot down goes with the way " + with * 100F + "% of the ticks");
        }
        // the sides swapped, the foot down would go with the way
        BotGaitOverride swapped = o.withAim(new BotGaitOverride.Aim(up.ahead(), up.right(), up.left(), up.back()));
        for (float heading : new float[] { Mth.HALF_PI, -Mth.HALF_PI }) {
            float with = footWithTheWay(swapped, heading);
            assertTrue(with > 0.6F, "the sides swapped at " + heading + ": the foot down goes with the way " + with * 100F + "% of the ticks");
        }
        // a stand's gaits have none
        assertNull(BotGaitOverride.all("walk_swat_fw", 0.856F, 0.45F, true).aim());
    }

    @Test
    void withTheGunUpItsWalkIsPutInBareEachWayItsHipsDown() {
        // posed as the game does (a fight, the gun up, a creep): its legs the clip's each way as put in, no knees bent
        // or foot planted over them, the hips as far down going aside and back as ahead (the clips are made for it)
        BotGaitOverride o = BotGaits.own(11);
        assertNotNull(o);
        BotGaitOverride.BY_ENTITY = id -> o;
        try {
            float[] want = new float[BotPoser.LEGS.length * 3], a = new float[3];
            float hips = Float.NaN;
            for (float heading : new float[] { 0F, Mth.HALF_PI, -Mth.HALF_PI, Mth.PI }) {
                for (float walkPos : new float[] { 1F, 3F, 5.5F }) {
                    BotAnimator.Frame f = new BotAnimator.Frame(0F, 0F, new float[] { 0F, 1F, 0F, 0F }, null, BotAnimator.NO_ACTS,
                            BotAnimator.NO_HITS, 0F, 0, 0.09F, heading, -1F, 0.36F, walkPos, 1F, 0F);
                    BotPoser.Parts p = BotPoserTest.parts();
                    BotPoser.apply(p, f, 0F, 0.36F, walkPos);
                    assertTrue(BotPoser.gaitLegs(0.09F, f.gaitPace(), heading, walkPos, 1F, o, want, a));
                    net.minecraft.client.model.geom.ModelPart[] legs = { p.rightLeg(), p.rightShin(), p.leftLeg(), p.leftShin() };
                    for (int b = 0; b < legs.length; b++) {
                        float[] got = { legs[b].xRot, legs[b].yRot, legs[b].zRot };
                        for (int k = 0; k < 3; k++)
                            assertEquals(want[b * 3 + k], got[k] * Mth.RAD_TO_DEG, 0.05F, "heading " + heading + " at " + walkPos + " " + BotPoser.LEGS[b] + " " + k);
                    }
                    if (Float.isNaN(hips)) hips = p.rightLeg().y;
                    assertEquals(hips, p.rightLeg().y, 1e-4F, "the hips at heading " + heading);
                }
            }
            // down as far as the clips are made for, from where they stand at rest
            BotPoser.Parts rest = BotPoserTest.parts();
            assertEquals(0.45F, hips - rest.rightLeg().y, 0.05F, "the hips down");
        } finally {
            BotGaitOverride.BY_ENTITY = BotGaitOverride.OWN;
        }
    }

    static BotGaitOverride runOnly(BotGaitOverride o) {
        return new BotGaitOverride(o.walk(), o.jog(), o.sprint(), o.walkSpeed(), o.jogSpeed(), o.sprintSpeed(), o.drop(), true);
    }

    /**
     * Through a step slow (0.09) with the gun up at this heading, the share of the frames the foot down (the lower, the
     * same foot as the frame before, moving) goes with the way the mob goes (BotLegsTest.footWithTheWay)
     */
    static float footWithTheWay(BotGaitOverride o, float heading) {
        // the way it goes in the model: ahead is -z, its right -x
        float mx = -Mth.sin(heading), mz = -Mth.cos(heading);
        int n = 120, with = 0, counted = 0;
        org.joml.Vector3f was = null;
        boolean wasRight = false;
        float[] a = new float[3];
        for (int i = 0; i <= n; i++) {
            BotPoser.Parts p = BotPoserTest.parts();
            BotPoser.gait(p, 0.09F, 0F, heading, BotPoser.CYCLE * i / n, 1F, 1F, a, o);
            float r = BotLegsTest.lowest(p.rightLeg(), p.rightShin()), l = BotLegsTest.lowest(p.leftLeg(), p.leftShin());
            boolean right = r <= l;
            org.joml.Vector3f foot = right ? BotLegsReplayTest.footAt(p.rightLeg(), p.rightShin()) : BotLegsReplayTest.footAt(p.leftLeg(), p.leftShin());
            if (was != null && right == wasRight) {
                float vx = foot.x - was.x, vz = foot.z - was.z, v = (float) Math.hypot(vx, vz);
                if (v > 0.05F) {
                    counted++;
                    if ((vx * mx + vz * mz) / v > 0.5F) with++;
                }
            }
            was = foot;
            wasRight = right;
        }
        assertTrue(counted > n / 3, "heading " + heading + ": the foot down moved in " + counted + " of " + n + " frames");
        return with / (float) counted;
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
