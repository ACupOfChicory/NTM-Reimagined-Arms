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
 * where it was); near in the game's order, players watching left out, nothing cut when out is short; the players in
 * the game's order, those watching in, nothing cut; a triple written into out[0..2] only; the living-only methods
 * throwing on another, the players' only false on another. A version's test extends this with a level of
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

    /** the level's players' numbers in the game's order (the game's players(), those watching too) */
    protected abstract int[] players();

    /** advance the entities by one tick */
    protected abstract void tick();

    /** the living entity's last attacker timestamp, read directly */
    protected abstract int lastHurtTime(int id);

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
        assertTrue(a.effectAmplifier(Body.EFFECT_REGENERATION) >= -1);
        assertTrue(a.effectAmplifier(Body.EFFECT_RESISTANCE) >= -1);
        assertTrue(a.alive());
        assertFalse(a.removed());
    }

    @Test
    void uuidIsTheSameObjectEachCall() {
        for (Body b : new Body[] {view().byId(mob()), view().byId(projectile())}) {
            assertNotNull(b.uuid());
            assertSame(b.uuid(), b.uuid());
        }
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

    // the turn BodyControl sets is the one the Body reads; a mob standing has not turned to a path; it is put back
    @Test
    void turnSetIsTurnRead() {
        Body a = view().byId(mob());
        float y0 = a.yRot(), h0 = a.yHeadRot(), x0 = a.xRot();
        control().yRot(a, 21F);
        control().yHeadRot(a, -33F);
        control().xRot(a, 17F);
        control().yBodyRot(a, 21F);
        assertEquals(21F, a.yRot());
        assertEquals(-33F, a.yHeadRot());
        assertEquals(17F, a.xRot());
        assertFalse(control().turned(a));
        control().face(a, 21F);
        control().lookAt(a, a.x(), a.y() + 1, a.z() + 5, 10F, 10F);
        control().yRot(a, y0);
        control().yHeadRot(a, h0);
        control().xRot(a, x0);
    }

    @Test
    void ageAdvancesOnePerTick() {
        Body[] bodies = {view().byId(mob()), view().byId(projectile()), view().byId(spectator())};
        int[] before = new int[bodies.length];
        for (int i = 0; i < bodies.length; i++) before[i] = bodies[i].age();
        tick();
        for (int i = 0; i < bodies.length; i++) assertEquals(before[i] + 1, bodies[i].age());
    }

    @Test
    void lastHurtTimeOfTheLiving() {
        for (int id : new int[] {mob(), spectator()}) {
            Body b = view().byId(id);
            int timestamp = lastHurtTime(id);
            assertEquals(timestamp, b.lastHurtTime());
            tick();
            assertEquals(timestamp, b.lastHurtTime());
        }
    }

    @Test
    void attributionKeepsBodyIdentity() {
        Body b = view().byId(mob());
        assertSame(b.lastHurtBy(), b.lastHurtBy());
        assertSame(b.killCredit(), b.killCredit());
        Body by = b.lastHurtBy();
        if (by != null) assertSame(by, view().byId(by.id()));
        by = b.killCredit();
        if (by != null) assertSame(by, view().byId(by.id()));
    }

    @Test
    void snapSetsPlaceAndTurn() {
        for (Body b : new Body[] {view().byId(mob()), view().byId(projectile())}) {
            double x = b.x(), y = b.y(), z = b.z();
            float yaw = b.yRot(), pitch = b.xRot();
            control().snap(b, x + 3, y + 2, z - 1, 35F, 17F);
            assertEquals(x + 3, b.x());
            assertEquals(y + 2, b.y());
            assertEquals(z - 1, b.z());
            assertEquals(35F, b.yRot());
            assertEquals(17F, b.xRot());
            control().snap(b, x, y, z, yaw, pitch);
        }
    }

    // place: the Body reads the new place and its box there at once; put back after
    @Test
    void placeSetIsPlaceRead() {
        for (Body a : new Body[] {view().byId(mob()), view().byId(projectile())}) {
            double x0 = a.x(), y0 = a.y(), z0 = a.z();
            double[] before = new double[6], after = new double[6];
            a.box(before);
            control().place(a, x0 + 0.75, y0 + 0.5, z0 - 0.25);
            a.box(after);
            assertEquals(x0 + 0.75, a.x());
            assertEquals(y0 + 0.5, a.y());
            assertEquals(z0 - 0.25, a.z());
            assertEquals(before[0] + 0.75, after[0], 1e-9);
            assertEquals(before[1] + 0.5, after[1], 1e-9);
            assertEquals(before[2] - 0.25, after[2], 1e-9);
            assertEquals(before[3] - before[0], after[3] - after[0], 1e-9);
            control().place(a, x0, y0, z0);
            assertEquals(x0, a.x());
        }
    }

    // a walk along three cells from the mob's: under way, at its first node; stopped, done; a mob standing, never stuck
    @Test
    void aWalkAndItsStop() {
        Body a = view().byId(mob());
        int x = a.blockX(), y = a.blockY(), z = a.blockZ();
        long[] cells = {acup.gunfeel.PosLong.block(x, y, z), acup.gunfeel.PosLong.block(x + 1, y, z), acup.gunfeel.PosLong.block(x + 2, y, z)};
        assertTrue(control().walk(a, cells, true, 1.0));
        assertFalse(control().done(a));
        assertEquals(0, control().pathNext(a));
        control().speed(a, 0.5);
        assertFalse(control().done(a));
        control().stop(a);
        assertTrue(control().done(a));
        assertEquals(0, control().pathNext(a));
        assertFalse(control().stuck(a));
        control().want(a, x + 0.5, y, z + 0.5, 1.0);
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
        assertThrows(RuntimeException.class, () -> p.effectAmplifier(Body.EFFECT_REGENERATION));
        assertThrows(RuntimeException.class, () -> p.effectAmplifier(Body.EFFECT_RESISTANCE));
        assertThrows(RuntimeException.class, p::lying);
        assertThrows(RuntimeException.class, () -> p.held(Body.MAIN_HAND));
        assertThrows(RuntimeException.class, () -> p.worn(Body.CHEST));
        assertThrows(RuntimeException.class, p::sleeping);
        assertThrows(RuntimeException.class, p::fallFlying);
        assertThrows(RuntimeException.class, p::health);
        assertThrows(RuntimeException.class, p::maxHealth);
        assertThrows(RuntimeException.class, p::lastHurtBy);
        assertThrows(RuntimeException.class, p::lastHurtTime);
        assertThrows(RuntimeException.class, p::killCredit);
        // a player's only: false on another
        assertFalse(p.spectator());
        assertFalse(p.creative());
        // what any entity answers: no projectile crawls or flies as a player
        assertFalse(p.crawling());
        assertFalse(p.flying());
        double[] v = {9, 9, 9};
        p.knownSpeed(v);
        assertFalse(Double.isNaN(v[0] + v[1] + v[2]));
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
    void healthOfTheLiving() {
        Body a = view().byId(mob());
        assertTrue(a.maxHealth() > 0);
        assertTrue(a.health() > 0 && a.health() <= a.maxHealth());
        assertFalse(a.spectator());
        assertFalse(a.creative());
        Body s = view().byId(spectator());
        assertTrue(s.spectator());
        assertFalse(s.creative(), "watching is not creative");
    }

    @Test
    void playersInTheGamesOrderWatchingIn() {
        int[] want = players();
        Body[] out = new Body[want.length + 2];
        assertEquals(want.length, view().players(out));
        boolean watching = false;
        for (int i = 0; i < want.length; i++) {
            assertEquals(want[i], out[i].id());
            assertSame(view().byId(want[i]), out[i]);
            assertTrue(out[i].player());
            watching |= out[i].id() == spectator();
        }
        assertTrue(watching, "the one watching is among them");
        assertEquals(-want.length, view().players(new Body[want.length - 1]));
        assertEquals(want.length, view().players(new Body[want.length]));
    }

    @Test
    void nearNothingCut() {
        int want = livingInBox().length;
        Body[] out = new Body[Math.max(0, want - 1)];
        assertEquals(-want, near(EntityView.LIVING, out));
        assertEquals(want, near(EntityView.LIVING, new Body[want]));
    }
}
