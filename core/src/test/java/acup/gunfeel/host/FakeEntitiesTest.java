package acup.gunfeel.host;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.ai.Stance;

// The entities' contract (EntityViewContract) on a level of plain fakes: a mob at 1.5 64 2.5, a projectile, a player
// watching, a second mob and one removed. Each version's check runs the same promises on its own entities.
class FakeEntitiesTest extends EntityViewContract {
    @org.junit.jupiter.api.Test
    void playersInOrderIncludingSpectators() {
        Body first = level.byId(3);
        Fake second = level.add(new Fake(7, EntityView.LIVING, true, true, false, 0, 64, 0));
        Body[] out = new Body[3];
        org.junit.jupiter.api.Assertions.assertEquals(-2, level.players(new Body[1]));
        org.junit.jupiter.api.Assertions.assertEquals(-2, level.players(new Body[0]));
        org.junit.jupiter.api.Assertions.assertEquals(2, level.players(out));
        org.junit.jupiter.api.Assertions.assertSame(first, out[0]);
        org.junit.jupiter.api.Assertions.assertSame(second, out[1]);
        org.junit.jupiter.api.Assertions.assertTrue(first.spectator());
        org.junit.jupiter.api.Assertions.assertFalse(second.spectator());
        org.junit.jupiter.api.Assertions.assertFalse(second.creative());
        org.junit.jupiter.api.Assertions.assertEquals(20, second.health());
        org.junit.jupiter.api.Assertions.assertEquals(20, second.maxHealth());
        Body projectile = level.byId(2);
        org.junit.jupiter.api.Assertions.assertThrows(ClassCastException.class, projectile::health);
        org.junit.jupiter.api.Assertions.assertThrows(ClassCastException.class, projectile::maxHealth);
        org.junit.jupiter.api.Assertions.assertFalse(projectile.spectator());
        org.junit.jupiter.api.Assertions.assertFalse(projectile.creative());
        level.remove(second);
        level.remove((Fake) first);
        org.junit.jupiter.api.Assertions.assertEquals(0, level.players(new Body[0]));
    }

    static final class Fake implements Body {
        final java.util.UUID uuid = java.util.UUID.randomUUID();
        final int id;
        final boolean living, player, spectator;
        final int kind;
        double x, y, z;
        boolean removed;
        byte stance = Stance.STAND;
        float yRot, yHeadRot, yBodyRot, xRot;

        Fake(int id, int kind, boolean living, boolean player, boolean spectator, double x, double y, double z) {
            this.id = id;
            this.kind = kind;
            this.living = living;
            this.player = player;
            this.spectator = spectator;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        private void livingOnly() {
            if (!living) throw new ClassCastException("not living");
        }

        @Override public int id() { return id; }
        @Override public int typeNumber() { return player ? 3 : kind + 10; }
        @Override public java.util.UUID uuid() { return uuid; }
        @Override public boolean living() { return living; }
        @Override public boolean player() { return player; }
        @Override public double x() { return x; }
        @Override public double y() { return y; }
        @Override public double z() { return z; }
        @Override public int blockX() { return (int) Math.floor(x); }
        @Override public int blockY() { return (int) Math.floor(y); }
        @Override public int blockZ() { return (int) Math.floor(z); }
        @Override public float bbHeight() { return height(Stance.STAND); }
        @Override public float eyeHeight() { return eyeHeight(Stance.STAND); }
        @Override public float eyeHeight(byte stance) { return stance == Stance.CROUCH ? 1.27F : stance == Stance.PRONE ? 0.4F : 1.62F; }
        @Override public float height(byte stance) { return stance == Stance.CROUCH ? 1.5F : stance == Stance.PRONE ? 0.6F : 1.8F; }
        @Override public void eye(double[] out) { out[0] = x; out[1] = y + eyeHeight(); out[2] = z; }
        @Override public void look(double[] out) { out[0] = 0; out[1] = 0; out[2] = 1; }
        @Override public void view(float partial, double[] out) { look(out); }
        @Override public void motion(double[] out) { out[0] = 0; out[1] = -0.08; out[2] = 0; }
        @Override public void lastSpeed(double[] out) { out[0] = 0; out[1] = 0; out[2] = 0; }
        @Override public void knownSpeed(double[] out) { out[0] = 0; out[1] = 0; out[2] = 0; }
        @Override public void box(double[] out) { out[0] = x - 0.3; out[1] = y; out[2] = z - 0.3; out[3] = x + 0.3; out[4] = y + bbHeight(); out[5] = z + 0.3; }
        @Override public float yRot() { return yRot; }
        @Override public float yHeadRot() { return yHeadRot; }
        @Override public float xRot() { return xRot; }
        @Override public boolean onGround() { return true; }
        @Override public boolean inWater() { return false; }
        @Override public boolean passenger() { return false; }
        @Override public acup.gunfeel.GameRandom random() {
            java.util.Random r = new java.util.Random(id);
            return new acup.gunfeel.GameRandom() {
                @Override public int nextInt(int bound) { return r.nextInt(bound); }
                @Override public double nextDouble() { return r.nextDouble(); }
                @Override public boolean nextBoolean() { return r.nextBoolean(); }
                @Override public float nextFloat() { return r.nextFloat(); }
                @Override public double nextGaussian() { return r.nextGaussian(); }
            };
        }
        @Override public boolean climbing() { livingOnly(); return false; }
        @Override public boolean crouching() { return stance == Stance.CROUCH; }
        @Override public byte stance() { return stance; }
        @Override public boolean sleeping() { livingOnly(); return false; }
        @Override public void typeBox(double[] out) { out[0] = x - 0.3; out[1] = y; out[2] = z - 0.3; out[3] = x + 0.3; out[4] = y + height(Stance.STAND); out[5] = z + 0.3; }
        @Override public String poseName() { return "standing"; }
        @Override public boolean sprinting() { return false; }
        @Override public boolean lying() { livingOnly(); return false; }
        @Override public boolean crawling() { return stance == Stance.PRONE; }
        @Override public boolean fallFlying() { livingOnly(); return false; }
        @Override public boolean flying() { return false; }
        @Override public boolean alive() { return !removed; }
        @Override public boolean removed() { return removed; }
        @Override public Body lastHurtBy() { livingOnly(); return player ? null : this; }
        @Override public Body killCredit() { livingOnly(); return player ? null : this; }
        @Override public float health() { livingOnly(); return 20; }
        @Override public float maxHealth() { livingOnly(); return 20; }
        @Override public boolean spectator() { return player && spectator; }
        @Override public boolean creative() { return false; }
        @Override public boolean leftHanded() { livingOnly(); return false; }
        @Override public int held(int hand) { livingOnly(); return 0; }
        @Override public int worn(int slot) { livingOnly(); return 0; }
        @Override public double speed() { livingOnly(); return 0.25; }
        @Override public int effectAmplifier(int which) {
            livingOnly();
            if (which != EFFECT_REGENERATION && which != EFFECT_RESISTANCE) throw new IllegalArgumentException("no effect " + which);
            return -1;
        }
        @Override public @Nullable String team() { return null; }
        @Override public boolean tagged(String tag) { return false; }
        @Override public Object level() { return this; }
    }

    static final class FakeLevel implements EntityView {
        final Map<Integer, Fake> byId = new LinkedHashMap<>();

        Fake add(Fake f) {
            byId.put(f.id, f);
            return f;
        }

        void remove(Fake f) {
            f.removed = true;
            byId.remove(f.id);
        }

        @Override
        public @Nullable Body byId(int id) {
            return byId.get(id);
        }

        @Override
        public int players(Body[] out) {
            List<Fake> found = byId.values().stream().filter(f -> f.player).toList();
            if (found.size() > out.length) return -found.size();
            for (int i = 0; i < found.size(); i++) out[i] = found.get(i);
            return found.size();
        }

        @Override
        public int near(int kind, double x0, double y0, double z0, double x1, double y1, double z1, Body[] out) {
            List<Fake> found = new ArrayList<>();
            double[] b = new double[6];
            for (Fake f : byId.values()) {
                boolean of = kind == EntityView.LIVING ? f.living : kind == EntityView.MOB ? f.living && !f.player : f.kind == EntityView.PROJECTILE;
                if (!of || f.spectator) continue;
                f.box(b);
                if (b[3] < x0 || b[0] > x1 || b[4] < y0 || b[1] > y1 || b[5] < z0 || b[2] > z1) continue;
                found.add(f);
            }
            if (found.size() > out.length) return -found.size();
            for (int i = 0; i < found.size(); i++) out[i] = found.get(i);
            return found.size();
        }
    }

    private final FakeLevel level = new FakeLevel();
    private final Fake removed;

    FakeEntitiesTest() {
        level.add(new Fake(1, EntityView.MOB, true, false, false, 1.5, 64, 2.5));
        level.add(new Fake(2, EntityView.PROJECTILE, false, false, false, 3.5, 65, 2.5));
        level.add(new Fake(3, EntityView.LIVING, true, true, true, 2.5, 64, 4.5));
        level.add(new Fake(4, EntityView.MOB, true, false, false, 4.5, 64, 1.5));
        removed = level.add(new Fake(5, EntityView.MOB, true, false, false, 0.5, 64, 0.5));
        level.remove(removed);
    }

    @Override
    protected EntityView view() {
        return level;
    }

    @Override
    protected int typeNumber(int entity) {
        return entity == 3 ? 3 : entity == 2 ? EntityView.PROJECTILE + 10 : EntityView.MOB + 10;
    }

    /** the fakes' control: a walk is the cells given, done once stopped; the point wanted kept; never stuck */
    static final class FakeControl implements BodyControl {
        @Override public void removeEffect(Body b, int which) { ((Fake) b).livingOnly(); }
        @Override public void tellBar(Body b, String key, int arg) { }
        long @Nullable [] walking;
        double modifier, wantX, wantY, wantZ;

        @Override public void stance(Body b, byte s) { ((Fake) b).stance = s; }
        @Override public void speed(Body b, double m) { modifier = m; }
        @Override public void stop(Body b) { walking = null; }
        @Override public boolean done(Body b) { return walking == null; }

        @Override
        public boolean walk(Body b, long[] cells, boolean reached, double m) {
            walking = cells.clone();
            modifier = m;
            return true;
        }

        @Override public int pathNext(Body b) { return 0; }

        @Override
        public void want(Body b, double x, double y, double z, double m) {
            wantX = x;
            wantY = y;
            wantZ = z;
            modifier = m;
        }

        @Override public boolean stuck(Body b) { return false; }
        @Override public void yRot(Body b, float v) { ((Fake) b).yRot = v; }
        @Override public void yHeadRot(Body b, float v) { ((Fake) b).yHeadRot = v; }
        @Override public void yBodyRot(Body b, float v) { ((Fake) b).yBodyRot = v; }
        @Override public void xRot(Body b, float v) { ((Fake) b).xRot = v; }
        @Override public void face(Body b, float yaw) { }
        @Override public boolean turned(Body b) { return false; }
        @Override public void lookAt(Body b, double x, double y, double z, float yMax, float xMax) { }

        @Override
        public void snap(Body b, double x, double y, double z, float yaw, float pitch) {
            place(b, x, y, z);
            yRot(b, yaw);
            xRot(b, pitch);
        }

        @Override
        public void place(Body b, double x, double y, double z) {
            Fake f = (Fake) b;
            f.x = x;
            f.y = y;
            f.z = z;
        }
    }

    private final FakeControl control = new FakeControl();

    @Override
    protected BodyControl control() {
        return control;
    }

    @Override
    protected int mob() {
        return 1;
    }

    @Override
    protected double[] mobAt() {
        return new double[] {1.5, 64, 2.5};
    }

    @Override
    protected int projectile() {
        return 2;
    }

    @Override
    protected int spectator() {
        return 3;
    }

    @Override
    protected Body removed() {
        return removed;
    }

    @Override
    protected double[] removedAt() {
        return new double[] {0.5, 64, 0.5};
    }

    @Override
    protected double[] box() {
        return new double[] {-8, 56, -8, 8, 72, 8};
    }

    @Override
    protected int[] livingInBox() {
        return new int[] {1, 4};
    }

    @Override
    protected int[] players() {
        return new int[] {3};
    }
}
