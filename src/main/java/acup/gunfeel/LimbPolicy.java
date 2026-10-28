package acup.gunfeel;

import net.minecraft.world.entity.EntityType;

/** Размер взрослого типа: детёныш крупного моба не становится мелким видом. */
final class LimbPolicy {
    private LimbPolicy() {}
    static boolean eligible(EntityType<?> type) {
        var size=type.getDimensions();return eligible(size.width(),size.height());
    }
    static boolean eligible(float width,float height) { return Math.max(width,height)>=1.3F; }
}
