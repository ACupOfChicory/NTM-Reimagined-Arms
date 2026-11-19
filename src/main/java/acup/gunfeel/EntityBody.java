package acup.gunfeel;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.ai.Stance;
import acup.gunfeel.host.Body;
import acup.gunfeel.mixin.mc.EntityAccess;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;

/** 26.2's Body for core: each call the entity's own getter now (no copy); the living-only ones cast, as the AI does. */
public final class EntityBody implements Body {
    private final Entity e;

    /** the one for the entity: {@link #of}, kept on it (MixinEntityBody) */
    public EntityBody(Entity e) {
        this.e = e;
    }

    /** the entity's one Body */
    public static Body of(Entity e) {
        return ((BodyOwner) e).gunfeel$body();
    }

    /** the entity behind it (26.2's code on its way to core) */
    public Entity entity() {
        return e;
    }

    private static Pose pose(byte stance) {
        return switch (stance) {
            case Stance.STAND -> Pose.STANDING;
            case Stance.CROUCH -> Pose.CROUCHING;
            default -> throw new IllegalArgumentException("stance " + stance);
        };
    }

    private static void put(Vec3 v, double[] out) {
        out[0] = v.x;
        out[1] = v.y;
        out[2] = v.z;
    }

    @Override public int id() { return e.getId(); }
    @Override public boolean living() { return e instanceof LivingEntity; }
    @Override public boolean player() { return e instanceof Player; }
    @Override public double x() { return e.getX(); }
    @Override public double y() { return e.getY(); }
    @Override public double z() { return e.getZ(); }
    @Override public int blockX() { return e.getBlockX(); }
    @Override public int blockY() { return e.getBlockY(); }
    @Override public int blockZ() { return e.getBlockZ(); }
    @Override public float bbHeight() { return e.getBbHeight(); }
    @Override public float eyeHeight() { return e.getEyeHeight(); }
    @Override public float eyeHeight(byte stance) { return e.getDimensions(pose(stance)).eyeHeight(); }
    @Override public float height(byte stance) { return e.getDimensions(pose(stance)).height(); }
    @Override public void eye(double[] out) { put(e.getEyePosition(), out); }
    @Override public void look(double[] out) { put(e.getLookAngle(), out); }
    @Override public void view(float partial, double[] out) { put(e.getViewVector(partial), out); }
    @Override public void motion(double[] out) { put(e.getDeltaMovement(), out); }
    @Override public void lastSpeed(double[] out) { put(((EntityAccess) e).gunfeel$lastKnownSpeed(), out); }

    @Override
    public void box(double[] out) {
        AABB b = e.getBoundingBox();
        out[0] = b.minX;
        out[1] = b.minY;
        out[2] = b.minZ;
        out[3] = b.maxX;
        out[4] = b.maxY;
        out[5] = b.maxZ;
    }

    @Override public float yRot() { return e.getYRot(); }
    @Override public float yHeadRot() { return e.getYHeadRot(); }
    @Override public float xRot() { return e.getXRot(); }
    @Override public boolean onGround() { return e.onGround(); }
    @Override public boolean climbing() { return ((LivingEntity) e).onClimbable(); }
    @Override public boolean crouching() { return e.getPose() == Pose.CROUCHING; }
    @Override public String poseName() { return String.valueOf(e.getPose()); }
    @Override public boolean sprinting() { return e.isSprinting(); }
    @Override public boolean lying() { return Prone.lying((LivingEntity) e); }
    @Override public boolean alive() { return e.isAlive(); }
    @Override public boolean removed() { return e.isRemoved(); }
    @Override public boolean leftHanded() { return ((Mob) e).isLeftHanded(); }
    @Override
    public int held(int hand) {
        LivingEntity l = (LivingEntity) e;
        return ItemNumbers.number((hand == MAIN_HAND ? l.getMainHandItem() : l.getOffhandItem()).getItem());
    }
    @Override public double speed() { return ((LivingEntity) e).getAttributeValue(Attributes.MOVEMENT_SPEED); }

    @Override
    public @Nullable String team() {
        PlayerTeam t = e.getTeam();
        return t == null ? null : t.getName();
    }
}
