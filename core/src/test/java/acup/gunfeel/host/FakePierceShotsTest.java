package acup.gunfeel.host;

import acup.gunfeel.Materials;
import acup.gunfeel.Vec3d;

class FakePierceShotsTest extends PierceShotsContract {
    private record Hit(Object point) {}

    private final PierceShots shots = new PierceShots() {
        @Override public Object location(Object hit) { return ((Hit) hit).point; }
        @Override public double distance(Object point, Object from) { return ((Vec3d) point).distanceTo((Vec3d) from); }
        @Override public Object level(Object bullet) { throw new AssertionError(); }
        @Override public boolean removed(Object bullet) { throw new AssertionError(); }
        @Override public float water(Object bullet, Object in) { throw new AssertionError(); }
        @Override public float damage(Object bullet) { throw new AssertionError(); }
        @Override public void damage(Object bullet, float value) { throw new AssertionError(); }
        @Override public float pierced(Object level, Object in, Object out, float damage, Object bullet, Materials.Penetration penetration) { throw new AssertionError(); }
        @Override public void stopAt(Object bullet, Object in) { throw new AssertionError(); }
        @Override public int id(Object bullet) { throw new AssertionError(); }
        @Override public void logLater(int id, int blocks, Object bullet) { throw new AssertionError(); }
        @Override public void logBlock(Object bullet, Object level, Object in, float left) { throw new AssertionError(); }
    };

    @Override protected PierceShots shots() { return shots; }
    @Override protected Object point(double x, double y, double z) { return new Vec3d(x, y, z); }
    @Override protected Object hit(Object point) { return new Hit(point); }
}
