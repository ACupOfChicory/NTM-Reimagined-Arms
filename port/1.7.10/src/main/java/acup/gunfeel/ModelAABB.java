package acup.gunfeel;

// port: copy of 26.2 src/main/java/acup/gunfeel/ModelAABB.java at 0762086; changes marked "port:"
// port: 1.7.10's AxisAlignedBB: clip is calculateIntercept (a MovingObjectPosition with hitVec, sideHit -1), contains
// is isVecInside, inflate is expand. 26.2 hands this box out from Entity.getBoundingBox (MixinEntityModelBox); in
// 1.7.10 the box is the public field Entity.boundingBox, so a bullet's mixin swaps its read for ModelAABB.of(entity)
// where NTM tests a bullet against an entity (EntityThrowableNT.onUpdate, EntityBulletBeamBase.performHitscan).

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

// Preserve the broad-phase/movement box. Only point picking and ray intersection use the posed model. Keeping the
// association through inflate also covers vanilla ProjectileUtil and mods which enlarge a target box before clip,
// including NTM bullets and beams. Blocks, nonliving entities and swept movement boxes remain ordinary AABBs.
public final class ModelAABB extends AxisAlignedBB {
    private final Entity entity;

    public ModelAABB(Entity entity, AxisAlignedBB box) {
        super(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
        this.entity = entity;
    }

    // port: the entity's box, as a ModelAABB when the model can stand for it (a living entity or a part of one);
    // a plain copy otherwise. A new box each call: 1.7.10's boxes are mutable (offset, setBounds).
    public static AxisAlignedBB of(Entity entity) {
        AxisAlignedBB box = entity.boundingBox;
        boolean model = entity instanceof EntityLivingBase || ModelPartEntities.parent(entity) != null;
        return model ? new ModelAABB(entity, box) : box.copy();
    }

    private AxisAlignedBB plain() {
        return AxisAlignedBB.getBoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    @Override
    public AxisAlignedBB expand(double x, double y, double z) {
        return new ModelAABB(entity, super.expand(x, y, z));
    }

    @Override
    public MovingObjectPosition calculateIntercept(Vec3 from, Vec3 to) {
        Vec3 hit = ModelHits.clipMc(entity, from, to, plain());
        return hit == null ? null : new MovingObjectPosition(0, 0, 0, -1, hit);
    }

    @Override
    public boolean isVecInside(Vec3 point) {
        return ModelHits.clipMc(entity, point, point, plain()) != null;
    }

    public Entity entity() {
        return entity;
    }
}
