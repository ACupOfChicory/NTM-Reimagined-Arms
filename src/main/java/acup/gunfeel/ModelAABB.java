package acup.gunfeel;

import java.util.Optional;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

// Preserve the broad-phase/movement box. Only point picking and ray intersection use the posed model. Keeping the
// association through inflate also covers vanilla ProjectileUtil and mods which enlarge a target box before clip,
// including NTM bullets and beams. Blocks, nonliving entities and swept movement boxes remain ordinary AABBs.
public final class ModelAABB extends AABB {
    private final LivingEntity entity;

    public ModelAABB(LivingEntity entity, AABB box) {
        super(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
        this.entity = entity;
    }

    private AABB plain() {
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    @Override
    public AABB inflate(double x, double y, double z) {
        return new ModelAABB(entity, super.inflate(x, y, z));
    }

    @Override
    public Optional<Vec3> clip(Vec3 from, Vec3 to) {
        return ModelHits.clip(entity, from, to, plain());
    }

    @Override
    public boolean contains(Vec3 point) {
        return contains(point.x, point.y, point.z);
    }

    @Override
    public boolean contains(double x, double y, double z) {
        Vec3 point = new Vec3(x, y, z);
        return ModelHits.clip(entity, point, point, plain()).isPresent();
    }
}
