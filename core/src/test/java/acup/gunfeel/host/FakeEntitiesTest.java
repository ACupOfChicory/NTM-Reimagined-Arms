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
    static final class Fake implements Body {
        final int id;
        final boolean living, player, spectator;
        final int kind;
        double x, y, z;
        boolean removed;
        byte stance = Stance.STAND;

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
        @Override public void box(double[] out) { out[0] = x - 0.3; out[1] = y; out[2] = z - 0.3; out[3] = x + 0.3; out[4] = y + bbHeight(); out[5] = z + 0.3; }
        @Override public float yRot() { return 0; }
        @Override public float yHeadRot() { return 0; }
        @Override public float xRot() { return 0; }
        @Override public boolean onGround() { return true; }
        @Override public boolean inWater() { return false; }
        @Override public boolean passenger() { return false; }
        @Override public acup.gunfeel.GameRandom random() {
            java.util.Random r = new java.util.Random(id);
            return new acup.gunfeel.GameRandom() {
                @Override public double nextDouble() { return r.nextDouble(); }
                @Override public boolean nextBoolean() { return r.nextBoolean(); }
                @Override public float nextFloat() { return r.nextFloat(); }
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
        @Override public boolean alive() { return !removed; }
        @Override public boolean removed() { return removed; }
        @Override public boolean leftHanded() { livingOnly(); return false; }
        @Override public int held(int hand) { livingOnly(); return 0; }
        @Override public double speed() { livingOnly(); return 0.25; }
        @Override public @Nullable String team() { return null; }
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

    /** the fakes' control: a walk is the cells given, done once stopped; the point wanted kept; never stuck */
    static final class FakeControl implements BodyControl {
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
}
