package acup.gunfeel;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.render.anim.HbmAnimations;
import com.hbm.render.item.weapon.sedna.ItemRenderWeaponBase;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * The gun bobbing in step with walking (walkSway) and lowered to the side while sprinting (sprintPose), after TaCZ's
 * walk and run animations. Applied in view space before NTM places the gun, so the arms go with it; NTM's own walking
 * bob is left out for a gun that has walkSway on (MixinItemRenderWeaponBase).
 *
 * <p>The phase is vanilla's walk distance, the same that bobs the camera: one cycle is two steps, the gun dips at each
 * step together with the camera. Weights ramp in and out over time and are eased, so nothing jumps.
 */
public final class WalkSprint {
    /** One pose: degrees (pitch + = muzzle up, yaw + = muzzle left, roll + = top to the left) and blocks in view space. */
    public record Pose(float pitch, float yaw, float roll, float x, float y, float z) {
        static final Pose NONE = new Pose(0, 0, 0, 0, 0, 0);

        Pose plus(Pose o, float w) {
            return new Pose(pitch + o.pitch * w, yaw + o.yaw * w, roll + o.roll * w, x + o.x * w, y + o.y * w, z + o.z * w);
        }
    }

    // TaCZ's walk_forward, in 1/16 block: yaw ±1.95°, roll 2.2 ± 1°, two dips of 0.38 px per cycle
    private static final float PX = 1F / 16F;
    // seconds to come in / go out
    static final float WALK_IN = 0.4F, WALK_OUT = 0.4F, SPRINT_IN = 0.3F, SPRINT_OUT = 0.65F, SPRINT_DROP = 0.1F;
    // after a shot the walking bob calms down for a moment (TaCZ: while the gun cycles)
    static final float SHOT_CALM = 0.3F;

    private static long last;
    private static float walk, sprint, calm;
    private static boolean shot;
    // view-space pivot of the sprint turn (right hand), from the last frame
    private static final Vector3f PIVOT = new Vector3f(0.35F, -0.45F, -0.7F);
    private static final Matrix4f APPLIED = new Matrix4f();
    private static boolean haveApplied;
    private static Item appliedItem;

    private WalkSprint() {
    }

    /** An NTM gun with walkSway is in the main hand: the vanilla bob of the hands is left out (MixinHandBob). */
    public static boolean handBobOff() {
        if (!Config.enabled || Config.walkSway <= 0F) return false;
        var mc = net.minecraft.client.Minecraft.getInstance();
        return mc.player != null && mc.player.getMainHandItem().getItem() instanceof ItemGunBaseNT;
    }

    /** A new shot of the held gun (GunFeel.checkShot). */
    public static void onShot() {
        shot = true;
    }

    /** Walking: u = cycle phase 0..1 (two steps), the bob of TaCZ's walk_forward, aiming = walk_aiming. */
    static Pose walk(float u, float aiming) {
        float s = Mth.sin(u * Mth.TWO_PI), c2 = Mth.cos(2F * u * Mth.TWO_PI);
        float a = Math.clamp(aiming, 0F, 1F);
        float k = 1F - 0.65F * a; // walk_aiming: yaw ±0.69 of ±1.95
        return new Pose(0F, -1.95F * s * k, (2.2F + Mth.sin((u - 0.1F) * Mth.TWO_PI)) * (1F - 0.9F * a),
                (0.11F + 0.04F * s) * PX * k, (-0.32F - 0.19F * c2) * PX * k, 0F);
    }

    /** Sprinting: u = cycle phase 0..1, the gun lowered and turned, swinging with the run (TaCZ's run). */
    static Pose sprint(float u) {
        float a = u * Mth.TWO_PI;
        float s1 = Mth.sin(a), c1 = Mth.cos(a), s2 = Mth.sin(2F * a);
        return new Pose(-35F + 6F * s2, 45F + 8F * c1, 31F + 6F * s2,
                -1.3F * PX + 1.9F * PX * s1, -5.4F * PX - 1.6F * PX * s2, 0F);
    }

    static float ease(float w) {
        return w * w * (3F - 2F * w);
    }

    static float approach(float w, boolean on, float dt, float in, float out) {
        return on ? Math.min(1F, w + dt / in) : Math.max(0F, w - dt / out);
    }

    /** Called with Config selected for the gun drawn, at the start of NTM's first-person transform. */
    public static void apply(PoseStack pose, LocalPlayer player, Item item) {
        long now = System.nanoTime();
        float dt = last == 0 ? 0F : Math.clamp((now - last) / 1e9F, 0F, 0.1F);
        last = now;
        float partial = ItemRenderWeaponBase.interp;
        float aiming = Mth.lerp(partial, ItemGunBaseNT.prevAimingProgress, ItemGunBaseNT.aimingProgress);
        boolean busy = HbmAnimations.getRelevantAnim(0) != null;
        boolean ground = player.onGround();
        boolean moving = player.input != null && player.input.getMoveVector().lengthSquared() > 1e-4F;
        boolean running = player.isSprinting() && !player.isMovingSlowly();
        if (shot) {
            shot = false;
            calm = SHOT_CALM;
        } else {
            calm = Math.max(0F, calm - dt);
        }
        boolean walkOn = Config.walkSway > 0F && moving && ground && calm <= 0F;
        boolean sprintOn = Config.sprintPose > 0F && running && !busy && aiming < 0.05F && calm <= 0F;
        walk = approach(walk, walkOn, dt, WALK_IN, WALK_OUT);
        // a shot, aiming or an NTM animation take the gun out of the sprint pose quickly
        boolean drop = calm > 0F || aiming >= 0.05F || busy;
        sprint = approach(sprint, sprintOn, dt, SPRINT_IN, drop ? SPRINT_DROP : SPRINT_OUT);
        if (walk <= 0F && sprint <= 0F) {
            haveApplied = false;
            return;
        }
        float d = player.avatarState().getBackwardsInterpolatedWalkDistance(partial);
        float u = d / 2F - Mth.floor(d / 2F);
        float ws = ease(walk) * Config.walkSway * (1F - ease(sprint)), ss = ease(sprint) * Config.sprintPose;
        Pose w = Pose.NONE.plus(walk(u, aiming), ws), s = Pose.NONE.plus(sprint(u), ss);
        if (TestLog.frames()) TestLog.c(-1, "walkSprint", "walk", walk, "sprint", sprint, "u", u, "aiming", aiming,
                "busy", busy, "calm", calm, "walkTurn", new Vector3f(w.pitch(), w.yaw(), w.roll()),
                "walkPos", new Vector3f(w.x(), w.y(), w.z()), "sprintTurn", new Vector3f(s.pitch(), s.yaw(), s.roll()),
                "sprintPos", new Vector3f(s.x(), s.y(), s.z()), "pivot", PIVOT);
        pose.translate(w.x() + s.x(), w.y() + s.y(), w.z() + s.z());
        // walking turns around the eye (TaCZ's root), sprinting around the right hand
        turn(pose, w, 0F, 0F, 0F);
        turn(pose, s, PIVOT.x, PIVOT.y, PIVOT.z);
        APPLIED.set(pose.last().pose());
        haveApplied = true;
        appliedItem = item;
    }

    private static void turn(PoseStack pose, Pose p, float px, float py, float pz) {
        if (p.pitch() == 0F && p.yaw() == 0F && p.roll() == 0F) return;
        pose.translate(px, py, pz);
        pose.mulPose(Axis.YP.rotationDegrees(p.yaw()));
        pose.mulPose(Axis.XP.rotationDegrees(p.pitch()));
        pose.mulPose(Axis.ZP.rotationDegrees(p.roll()));
        pose.translate(-px, -py, -pz);
    }

    /** After NTM placed the gun: the right hand's place in the view space before our turn, for the next frame. */
    public static void gunPlaced(Item item) {
        if (!haveApplied || appliedItem != item) return;
        Vector3f hand = new Vector3f();
        if (!Arms.rightGripRendered(item, hand)) return;
        // APPLIED = view·T; the hand in view space is view⁻¹·hand, and before T it is T⁻¹ of that
        Matrix4f inv = new Matrix4f(APPLIED).invert();
        inv.transformPosition(hand);
        if (Float.isFinite(hand.x) && hand.lengthSquared() < 9F) PIVOT.set(hand);
    }
}
