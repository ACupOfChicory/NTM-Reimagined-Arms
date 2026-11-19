package acup.gunfeel.host;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import acup.gunfeel.ai.Stance;

/**
 * What every version's entities must say (EntityView, Body): one Body an entity, the same object each time it is
 * asked for, its number its own; a removed one gone from the number but its Body still read as it was left (removed,
 * where it was); near in the game's order, players watching left out, nothing cut when out is short; a triple written
 * into out[0..2] only; the living-only methods throwing on another. A version's test extends this with a level of
 * its own: a living mob standing at {@link #mobAt}, a projectile, a player watching (a spectator) and a removed mob
 * whose Body was taken before it went, all in {@link #box}.
 */
public abstract class EntityViewContract {
    protected abstract EntityView view();

    /** the version's BodyControl */
    protected abstract BodyControl control();

    /** a living mob's number, standing (not crouching) on the ground */
    protected abstract int mob();

    /** where that mob stands (its feet) */
    protected abstract double[] mobAt();

    /** a projectile's number (not living) */
    protected abstract int projectile();

    /** a player watching (a spectator)'s number, in the box */
    protected abstract int spectator();

    /** the Body of a mob taken while it was there, the mob removed since */
    protected abstract Body removed();

    /** where the removed mob was */
    protected abstract double[] removedAt();

    /** a box round them all: x0, y0, z0, x1, y1, z1 */
    protected abstract double[] box();

    /** the living ones' numbers in the box in the game's order (the game's getEntitiesOfClass with no spectators) */
    protected abstract int[] livingInBox();

    private int near(int kind, Body[] out) {
        double[] b = box();
        return view().near(kind, b[0], b[1], b[2], b[3], b[4], b[5], out);
    }

    @Test
    void oneBodyAnEntity() {
        Body a = view().byId(mob());
        assertNotNull(a);
        assertSame(a, view().byId(mob()));
        assertEquals(mob(), a.id());
        assertTrue(a.living());
        assertTrue(a.alive());
        assertFalse(a.removed());
    }

    // the stance BodyControl sets is the one the Body reads; the mob is left standing
    @Test
    void stanceSetIsStanceRead() {
        Body a = view().byId(mob());
        for (byte s : new byte[] {Stance.PRONE, Stance.STAND, Stance.CROUCH, Stance.PRONE, Stance.STAND}) {
            control().stance(a, s);
            assertEquals(s, a.stance());
            assertEquals(s == Stance.CROUCH, a.crouching());
        }
    }

    @Test
    void noneByAnotherNumber() {
        assertNull(view().byId(Integer.MIN_VALUE));
    }

    @Test
    void placeAndPose() {
        Body a = view().byId(mob());
        double[] at = mobAt();
        assertEquals(at[0], a.x());
        assertEquals(at[1], a.y());
        assertEquals(at[2], a.z());
        assertEquals((int) Math.floor(at[0]), a.blockX());
        assertEquals((int) Math.floor(at[1]), a.blockY());
        assertEquals((int) Math.floor(at[2]), a.blockZ());
        assertFalse(a.crouching());
        assertFalse(a.inWater());
        assertFalse(a.passenger());
        assertFalse(a.sleeping());
        assertEquals(Stance.STAND, a.stance());
        double[] box = new double[6], type = new double[6];
        a.box(box);
        a.typeBox(type);
        assertArrayEquals(box, type, "standing, its type's box is its box");
        float f = a.random().nextFloat();
        assertTrue(f >= 0 && f < 1);
        assertTrue(a.eyeHeight(Stance.CROUCH) < a.eyeHeight(Stance.STAND));
        assertTrue(a.height(Stance.CROUCH) < a.height(Stance.STAND));
        assertEquals(a.eyeHeight(Stance.STAND), a.eyeHeight());
        assertEquals(a.height(Stance.STAND), a.bbHeight());
        double[] eye = new double[3];
        a.eye(eye);
        assertEquals(a.y() + a.eyeHeight(), eye[1], 1e-9);
    }

    @Test
    void triplesIntoTheirOwnRoom() {
        Body a = view().byId(mob());
        double[] out = {9, 9, 9, 7, 7, 7};
        a.look(out);
        assertEquals(7, out[3]);
        a.view(1F, out);
        a.motion(out);
        a.lastSpeed(out);
        a.eye(out);
        assertArrayEquals(new double[] {7, 7, 7}, new double[] {out[3], out[4], out[5]});
        double[] box = new double[6];
        a.box(box);
        assertEquals(a.bbHeight(), box[4] - box[1], 1e-6);
    }

    @Test
    void removedGoneByNumberItsBodyAsLeft() {
        Body r = removed();
        assertTrue(r.removed());
        assertNull(view().byId(r.id()));
        double[] at = removedAt();
        assertEquals(at[0], r.x());
        assertEquals(at[1], r.y());
        assertEquals(at[2], r.z());
    }

    @Test
    void notLivingThrowsWhereOnlyTheLivingAnswer() {
        Body p = view().byId(projectile());
        assertNotNull(p);
        assertFalse(p.living());
        assertThrows(RuntimeException.class, p::climbing);
        assertThrows(RuntimeException.class, p::speed);
        assertThrows(RuntimeException.class, p::lying);
        assertThrows(RuntimeException.class, p::sleeping);
    }

    @Test
    void nearInTheGamesOrderNoSpectators() {
        int[] want = livingInBox();
        Body[] out = new Body[want.length + 2];
        int n = near(EntityView.LIVING, out);
        assertEquals(want.length, n);
        for (int i = 0; i < n; i++) {
            assertEquals(want[i], out[i].id());
            assertSame(view().byId(want[i]), out[i]);
            assertTrue(out[i].id() != spectator());
        }
        assertEquals(1, countOf(EntityView.PROJECTILE));
    }

    private int countOf(int kind) {
        Body[] out = new Body[16];
        return near(kind, out);
    }

    @Test
    void nearNothingCut() {
        int want = livingInBox().length;
        Body[] out = new Body[Math.max(0, want - 1)];
        assertEquals(-want, near(EntityView.LIVING, out));
        assertEquals(want, near(EntityView.LIVING, new Body[want]));
    }
}
