package acup.gunfeel;

import java.util.List;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

// port/continuum: a stand-in for the 26.2 mob self-test (src/main/java MobSmoke: NTM NEXT's bullets and beams, the 26.2
// mobs): it does not run on this node, so it is never enabled.
final class MobSmoke {
    private MobSmoke() {
    }

    static void gpuGeometry(LivingEntity entity, boolean matches) {
    }

    static void gpuPose(LivingEntity entity, boolean matches) {
    }

    static void tracer(int bullet) {
    }

    static boolean enabled() {
        return false;
    }

    static void debugProbe(boolean ok, String text) {
    }

    static void contourShot() {
    }

    static boolean review() {
        return false;
    }

    static List<String> cases() {
        return List.of();
    }

    static int index() {
        return -1;
    }

    static boolean busy() {
        return false;
    }

    static boolean reviewReady() {
        return false;
    }

    static String current() {
        return "";
    }

    static boolean hasPose() {
        return false;
    }

    static Entity target() {
        return null;
    }

    static String status() {
        return "";
    }

    static String evidence() {
        return "";
    }

    // the port's own mob check takes these (MobPortSmoke)
    static void captured(LivingEntity e, float anchor, List<ModelBox> boxes, String source) {
        MobPortSmoke.captured(e, boxes, source);
    }

    static void accepted(LivingEntity e, float anchor, List<ModelBox> boxes) {
        MobPortSmoke.accepted(e, boxes);
    }

    public static void debug(Entity e, boolean model) {
    }

    static void start() {
    }

    static void frame() {
    }

    static void select(int selected) {
    }

    static void camera() {
    }

    static void rotate(float delta) {
    }
}
