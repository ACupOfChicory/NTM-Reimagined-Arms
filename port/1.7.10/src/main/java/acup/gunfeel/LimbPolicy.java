package acup.gunfeel;

// port: copy of 26.2 src/main/java/acup/gunfeel/LimbPolicy.java at 0762086; changes marked "port:"

import net.minecraft.entity.EntityLivingBase;

/** Размер взрослого типа: детёныш крупного моба не становится мелким видом. */
final class LimbPolicy {
    private LimbPolicy() {}
    // port: no type dimensions in 1.7.10; a child of an ageable mob or a zombie is set to half the adult's size
    // (EntityAgeable.setScaleForAge, EntityZombie.setChildSize), so the adult's is twice the child's
    static boolean eligible(EntityLivingBase entity) {
        float scale=entity.isChild()?2F:1F;return eligible(entity.width*scale,entity.height*scale);
    }
    static boolean eligible(float width,float height) { return Math.max(width,height)>=1.3F; }
}
