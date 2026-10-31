package acup.gunfeel;

import java.util.List;

import com.hbm.entity.mob.EntityMaskMan;
import com.hbm.entity.mob.EntityRADBeast;
import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.entity.projectile.EntityBulletBeamBase;
import com.hbm.items.weapon.sedna.BulletConfig;

import acup.gunfeel.phys.Vec3;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.EntityIronGolem;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.WorldServer;

// port: 26.2's BulletCheck.modelHits, maskmanModelHits, surfaceModelHits and retainedModelHits (e284339) on the 1.7.10
// server: a pose put straight into ModelHits (as a client's would be) and real NTM bullets and beams against it. Not
// here, as 1.7.10 does not have them: the vanilla box of the entity (the port hands ModelAABB only to NTM's bullet and
// beam, not to Entity.boundingBox), so no vanilla arrow or melee pick; the movement-dimension check (the box is the
// vanilla one by construction); posture (1.7.10 has no Pose: sneaking only for players).
final class BulletCheckModels {
    interface Check {
        void check(String name, Object want, Object got);
    }

    private static WorldServer level;
    private static Check out;

    private BulletCheckModels() {}

    static void run(WorldServer world, Check check) {
        level = world;
        out = check;
        try {
            modelHits();
            maskman();
            surface();
            retained(new EntityIronGolem(level));
            retained(new EntityRADBeast(level));
        } finally {
            ModelHits.clear(level);
        }
    }

    private static void check(String name, Object want, Object got) {
        out.check(name, want, got);
    }

    private static <T extends EntityLivingBase> T place(T e, double x, double y, double z) {
        e.setPosition(x, y, z);
        e.rotationYaw = e.renderYawOffset = e.prevRenderYawOffset = 0F;
        level.spawnEntityInWorld(e);
        return e;
    }

    private static Vec3 pos(net.minecraft.entity.Entity e) {
        return new Vec3(e.posX, e.posY - e.yOffset, e.posZ);
    }

    private static net.minecraft.util.Vec3 mc(Vec3 v) {
        return net.minecraft.util.Vec3.createVectorHelper(v.x(), v.y(), v.z());
    }

    private static boolean clips(EntityLivingBase e, Vec3 from, Vec3 to) {
        return ModelAABB.of(e).calculateIntercept(mc(from), mc(to)) != null;
    }

    private static boolean clipsInflated(EntityLivingBase e, double r, Vec3 from, Vec3 to) {
        return ModelAABB.of(e).expand(r, r, r).calculateIntercept(mc(from), mc(to)) != null;
    }

    // a .50 DU round with 10 damage from `start` along +z for one tick: whether the target lost health
    private static boolean bullet(EntityLivingBase target, Vec3 start) {
        BulletConfig du = com.hbm.items.weapon.sedna.factory.XFactory50.bmg50_du;
        EntityBulletBaseMK4 b = new EntityBulletBaseMK4(level, du, 1F, 0F, 0F, 0F);
        b.setPosition(start.x(), start.y(), start.z());
        b.setThrowableHeading(0, 0, 1, du.velocity, 0F);
        b.damage = 10;
        level.spawnEntityInWorld(b);
        target.hurtResistantTime = 0;
        target.setHealth(target.getMaxHealth());
        float before = target.getHealth();
        b.onUpdate();
        b.setDead();
        return target.getHealth() < before;
    }

    private static final BulletConfig BEAM = new BulletConfig().setBeam().setDamage(10).setOnBeamImpact(BulletConfig.LAMBDA_STANDARD_BEAM_HIT);

    // an NTM beam with 10 damage from `start` along +z, 6 blocks: whether the target lost health
    private static boolean beam(EntityLivingBase target, Vec3 start) {
        EntityBulletBeamBase beam = new EntityBulletBeamBase(level, BEAM, 10F);
        beam.setPosition(start.x(), start.y(), start.z());
        beam.setRotationsFromVector(net.minecraft.util.Vec3.createVectorHelper(0, 0, 1));
        target.hurtResistantTime = 0;
        target.setHealth(target.getMaxHealth());
        float before = target.getHealth();
        beam.performHitscanExternal(6);
        beam.setDead();
        return target.getHealth() < before;
    }

    private static Vec3 turned(Vec3 offset, Vec3 origin, double degrees) {
        return offset.yRot((float) Math.toRadians(degrees)).add(origin);
    }

    private static void modelHits() {
        EntityIronGolem g = place(new EntityIronGolem(level), 20.5, 170, 20.5);
        Vec3 origin = pos(g);
        List<ModelBox> boxes = List.of(
                new ModelBox("left_leg", -.4, 0, -.1, .2, 0, 0, 0, 1, 0, 0, 0, .2),
                new ModelBox("right_leg", .2, 0, -.1, .2, 0, 0, 0, 1, 0, 0, 0, .2),
                new ModelBox("body", -.4, 1, -.2, .8, 0, 0, 0, 1, 0, 0, 0, .4),
                new ModelBox("wing", 2, 1.5, -.1, .5, 0, 0, 0, .3, 0, 0, 0, .2));
        ModelHits.put(level, g, 0, boxes);
        check("model: debug shows all collision parts", boxes.size(), ModelHits.outlines(g).size());
        Vec3 gap = origin.add(0, .5, -3), gapEnd = gap.add(0, 0, 6);
        Vec3 leg = origin.add(.3, .5, -3), legEnd = leg.add(0, 0, 6);
        check("model: the bullet's box of the entity is model-aware", true, ModelAABB.of(g) instanceof ModelAABB);
        check("model: gap stays a miss after NTM inflation", false, clipsInflated(g, .3, gap, gapEnd));
        check("model: leg hit after vanilla scalar inflation", true, clipsInflated(g, .3, leg, legEnd));
        check("model: a point between legs is outside", false, ModelAABB.of(g).isVecInside(mc(origin.add(0, .5, 0))));
        check("model: a point inside leg is inside", true, ModelAABB.of(g).isVecInside(mc(origin.add(.3, .5, 0))));
        g.renderYawOffset = g.prevRenderYawOffset = g.rotationYaw = 45;
        check("model: body turn rotates a limb with the entity", true,
                clips(g, turned(leg.subtract(origin), origin, -45), turned(legEnd.subtract(origin), origin, -45)));
        check("model: body turn preserves gap", false,
                clips(g, turned(gap.subtract(origin), origin, -45), turned(gapEnd.subtract(origin), origin, -45)));
        g.renderYawOffset = g.prevRenderYawOffset = g.rotationYaw = 0;
        Vec3 wing = origin.add(2.2, 1.6, -3);
        check("model: NTM's candidates find a part outside the vanilla box", true, ModelHits.candidates(level, null,
                new java.util.ArrayList<>(), AxisAlignedBB.getBoundingBox(wing.x() - .1, wing.y() - .1, wing.z(), wing.x() + .1, wing.y() + .1, wing.z() + 6),
                e -> e == g).contains(g));
        for (int i = 0; i < 2; i++)
            check("model: NTM bullet " + (i == 0 ? "misses gap" : "hits leg"), i != 0, bullet(g, i == 0 ? gap : leg));
        for (int i = 0; i < 2; i++)
            check("model: NTM beam " + (i == 0 ? "misses gap" : "hits leg"), i != 0, beam(g, i == 0 ? gap : leg));
        check("model: NTM bullet hits a wing outside the vanilla box", true, bullet(g, wing));
        g.setPosition(g.posX + 30, g.posY, g.posZ);
        check("model: teleport invalidates old pose", true, clips(g, gap.add(30, 0, 0), gapEnd.add(30, 0, 0)));
        ModelHits.clear(level);
        check("model: no fresh pose keeps vanilla debug box", true, ModelHits.outlines(g).isEmpty());
        check("model: missing pose uses original hitbox", true, clips(g, gap.add(30, 0, 0), gapEnd.add(30, 0, 0)));
        g.setDead();
    }

    private static void maskman() {
        EntityMaskMan m = place(new EntityMaskMan(level), 35.5, 170, 35.5);
        Vec3 origin = pos(m);
        // a narrow body and an arm beyond the vanilla box (Mask Man is 2 wide)
        ModelHits.put(level, m, 0, List.of(
                new ModelBox("body", -.4, 1, -.2, .8, 0, 0, 0, 3, 0, 0, 0, .4),
                new ModelBox("arm", 1.1, 2, -.1, .3, 0, 0, 0, 1, 0, 0, 0, .2)));
        Vec3 empty = origin.add(.7, 2.5, -3);
        check("maskman: no invisible 0.3 expansion beside body", false, clipsInflated(m, .3, empty, empty.add(0, 0, 6)));
        Vec3 arm = origin.add(1.25, 2.5, -3);
        check("maskman: arm beyond vanilla width is hit", true, clips(m, arm, arm.add(0, 0, 6)));
        check("maskman: NTM bullet hits the protruding arm", true, bullet(m, arm));
        check("maskman: debug uses model parts", 2, ModelHits.outlines(m).size());
        m.setDead();
    }

    private static void surface() {
        ModelMesh mesh = new ModelMesh(new float[] {
                0, 0, 0, 0, 1, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1,
                0, 0, 0, 0, 0, 1, 0, 1, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1});
        new ModelMeshPayload(mesh.id(), mesh.triangles()).receive();
        check("mesh: server registers geometry blueprint", true, ModelMesh.get(mesh.id()) != null);
        EntityIronGolem target = place(new EntityIronGolem(level), 50.5, 175, 50.5);
        Vec3 origin = pos(target);
        ModelHits.put(level, target, 0, List.of(new ModelBox("mesh", 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1).withMesh(mesh.id())));
        check("mesh: debug draws six surface edges", 12, ModelHits.outlines(target).get(0).length);
        for (int i = 0; i < 2; i++) {
            double xy = i == 0 ? .8 : .1;
            Vec3 start = origin.add(xy, xy, -3);
            check("mesh: NTM bullet " + (i == 0 ? "misses empty corner" : "hits real surface"), i != 0, bullet(target, start));
            check("mesh: NTM beam " + (i == 0 ? "misses empty corner" : "hits real surface"), i != 0, beam(target, start));
        }
        target.setDead();
    }

    private static void retained(EntityLivingBase target) {
        place(target, 65.5, 180, 65.5);
        String name = "retained " + net.minecraft.entity.EntityList.getEntityString(target);
        List<ModelBox> boxes = List.of(
                new ModelBox("left", -.3, 0, -.1, .2, 0, 0, 0, 1, 0, 0, 0, .2),
                new ModelBox("right", .1, 0, -.1, .2, 0, 0, 0, 1, 0, 0, 0, .2),
                new ModelBox("wing", 2, .5, -.1, .2, 0, 0, 0, .2, 0, 0, 0, .2));
        EntityIronGolem other = place(new EntityIronGolem(level), 70.5, 180, 70.5);
        for (int age : new int[] {6, 18}) {
            ModelHits.putAt(level, target, 0, boxes, level.getTotalWorldTime() - age);
            ModelHits.put(level, other, 0, boxes);
            check(name + ": render gap " + age + " ticks keeps outlines", 3, ModelHits.outlines(target).size());
            Vec3 start = pos(target).add(0, .5, -3);
            check(name + ": render gap " + age + " ticks keeps model miss", false, clips(target, start, start.add(0, 0, 6)));
        }
        for (int i = 0; i < 25; i++) {
            target.setPosition(target.posX + .25, target.posY, target.posZ);
            ModelHits.tick(level);
        }
        check(name + ": normal movement beyond four blocks keeps model", 3, ModelHits.outlines(target).size());
        Vec3 wing = pos(target).add(2.1, .6, -3);
        check(name + ": stale pose still supplies protruding candidates", true, ModelHits.candidates(level, null,
                new java.util.ArrayList<>(), AxisAlignedBB.getBoundingBox(wing.x() - .01, wing.y() - .01, wing.z(), wing.x() + .01, wing.y() + .01, wing.z() + 6),
                e -> e == target).contains(target));
        target.renderYawOffset = target.prevRenderYawOffset = target.rotationYaw = 135;
        check(name + ": large body turn keeps outlines", 3, ModelHits.outlines(target).size());
        Vec3 from = turned(new Vec3(0, .5, -3), pos(target), -135), to = turned(new Vec3(0, .5, 3), pos(target), -135);
        check(name + ": large body turn preserves real gap", false, clips(target, from, to));
        target.setPosition(target.posX + 30, target.posY, target.posZ);
        ModelHits.tick(level);
        check(name + ": teleport removes retained model", true, ModelHits.outlines(target).isEmpty());
        target.setDead();
        other.setDead();
        ModelHits.tick(level);
    }
}
