package acup.gunfeel;

import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

// Stub until the model hitbox stand is ported (notes/ports/de-1.21.1-progress.md): the root's MobSmoke watches one
// mob's boxes for it. Here no stand runs, so it hears nothing.
public final class MobSmoke {
    private MobSmoke() {
    }

    static boolean enabled() {
        return false;
    }

    static boolean review() {
        return false;
    }

    static void start() {
    }

    static void frame() {
    }

    static void captured(LivingEntity e, float anchor, List<ModelBox> boxes, String source) {
    }

    static void accepted(LivingEntity e, float anchor, List<ModelBox> boxes) {
    }

    public static void debug(Entity e, boolean model) {
    }

    static void tracer(int bullet) {
    }
}
