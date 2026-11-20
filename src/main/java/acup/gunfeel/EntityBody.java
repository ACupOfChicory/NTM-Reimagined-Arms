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

    /** the entity behind a Body, or null for none (26.2's code on its way to core) */
    public static @Nullable Entity entity(@Nullable Body b) {
        return b == null ? null : ((EntityBody) b).e;
    }

    /** the game's pose for a stance: the swimming pose for PRONE (the one an AI mob lies down in, AiProne) */
    static Pose pose(byte stance) {
        return switch (stance) {
            case Stance.STAND -> Pose.STANDING;
            case Stance.CROUCH -> Pose.CROUCHING;
            case Stance.PRONE -> Pose.SWIMMING;
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
    @Override public void knownSpeed(double[] out) { put(e.getKnownSpeed(), out); }

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
    @Override public boolean inWater() { return e.isInWater(); }
    @Override public boolean passenger() { return e.isPassenger(); }
    @Override public GameRandom random() { return GameRandoms.of(e.getRandom()); }
    @Override public boolean climbing() { return ((LivingEntity) e).onClimbable(); }
    @Override public boolean crouching() { return e.getPose() == Pose.CROUCHING; }
    @Override public String poseName() { return String.valueOf(e.getPose()); }
    @Override public boolean sprinting() { return e.isSprinting(); }
    @Override public boolean lying() { return Prone.lying((LivingEntity) e); }
    @Override public boolean crawling() { return e.isVisuallyCrawling(); }
    @Override public boolean fallFlying() { return ((LivingEntity) e).isFallFlying(); }
    @Override public boolean flying() { return e instanceof net.minecraft.world.entity.player.Player p && p.getAbilities().flying; }
    @Override public boolean sleeping() { return ((LivingEntity) e).isSleeping(); }

    @Override
    public byte stance() {
        Pose p = e.getPose();
        return p == Pose.STANDING ? Stance.STAND : p == Pose.CROUCHING ? Stance.CROUCH : p == Pose.SWIMMING ? Stance.PRONE : (byte) -1;
    }

    @Override
    public void typeBox(double[] out) {
        AABB b = e.getType().getDimensions().makeBoundingBox(e.position());
        out[0] = b.minX;
        out[1] = b.minY;
        out[2] = b.minZ;
        out[3] = b.maxX;
        out[4] = b.maxY;
        out[5] = b.maxZ;
    }
    @Override public boolean alive() { return e.isAlive(); }
    @Override public boolean removed() { return e.isRemoved(); }
    @Override public float health() { return ((LivingEntity) e).getHealth(); }
    @Override public @Nullable Body lastHurtBy() {
        LivingEntity by = ((LivingEntity) e).getLastHurtByMob();
        return by == null ? null : of(by);
    }
    @Override public @Nullable Body killCredit() {
        LivingEntity by = ((LivingEntity) e).getKillCredit();
        return by == null ? null : of(by);
    }
    @Override public float maxHealth() { return ((LivingEntity) e).getMaxHealth(); }
    @Override public boolean spectator() { return e instanceof Player p && p.isSpectator(); }
    @Override public boolean creative() { return e instanceof Player p && p.isCreative(); }
    @Override public boolean leftHanded() { return ((Mob) e).isLeftHanded(); }
    @Override
    public int held(int hand) {
        LivingEntity l = (LivingEntity) e;
        return ItemNumbers.number((hand == MAIN_HAND ? l.getMainHandItem() : l.getOffhandItem()).getItem());
    }
    private static final net.minecraft.world.entity.EquipmentSlot[] WORN = {net.minecraft.world.entity.EquipmentSlot.HEAD,
            net.minecraft.world.entity.EquipmentSlot.CHEST, net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET};
    @Override
    public int worn(int slot) {
        // getItem of an empty stack (EMPTY, air, a count of 0) is air's: 0
        return ItemNumbers.number(((LivingEntity) e).getItemBySlot(WORN[slot]).getItem());
    }
    @Override public double speed() { return ((LivingEntity) e).getAttributeValue(Attributes.MOVEMENT_SPEED); }
    @Override public java.util.UUID uuid() { return e.getUUID(); }

    @Override
    public @Nullable String team() {
        PlayerTeam t = e.getTeam();
        return t == null ? null : t.getName();
    }

    @Override public boolean tagged(String tag) { return e.entityTags().contains(tag); }
    @Override public Object level() { return e.level(); }
}
