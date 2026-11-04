package acup.gunfeel;

import java.nio.FloatBuffer;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import com.hbm.extprop.HbmPlayerProps;
import com.hbm.handler.HbmKeybinds.EnumKeybind;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.render.anim.HbmAnimations;
import com.hbm.render.item.weapon.sedna.ItemRenderWeaponBase;

import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.item.Item;
import net.minecraft.util.MathHelper;

/**
 * The gun bobbing in step with walking (walkSway) and lowered to the side while sprinting (sprintPose), after TaCZ's
 * walk and run animations. Applied in view space before NTM places the gun, so the arms go with it; NTM's own walking
 * bob is left out for a gun that has walkSway on (MixinItemRenderWeaponBase).
 *
 * <p>The phase is vanilla's walk distance, the same that bobs the camera: one cycle is two steps, the gun dips at each
 * step together with the camera. Weights ramp in and out over time and are eased, so nothing jumps.
 */
// port: copied from 26.2 (ef36e84, notes/ports/port-1.7.10-copied.md). walk, sprint, ease, approach and the weights are
// unchanged; the turns are GL11's (26.2: PoseStack), the walk distance is distanceWalkedModified (26.2:
// getBackwardsInterpolatedWalkDistance, the same sum), moving is movementInput, crouching is isSneaking. NTM draws the
// hand from an identity modelview, so the GL modelview here is 26.2's pose stack. 1.7.10 has no vanilla hand bob under
// NTM's gun (setPerspectiveAndRender loads its own view), so there is no MixinHandBob. With Shader Fixer (vanilla's
// ItemRenderer draws the gun) WalkSprint is not applied.
public final class WalkSprint {
    /** One pose: degrees (pitch + = muzzle up, yaw + = muzzle left, roll + = top to the left) and blocks in view space. */
    public record Pose(float pitch, float yaw, float roll, float x, float y, float z) {
        static final Pose NONE = new Pose(0, 0, 0, 0, 0, 0);

        Pose plus(Pose o, float w) {
            return new Pose(pitch + o.pitch * w, yaw + o.yaw * w, roll + o.roll * w, x + o.x * w, y + o.y * w, z + o.z * w);
        }
    }

    // 26.2: Mth.TWO_PI
    private static final float TWO_PI = (float) (Math.PI * 2.0);
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
    private static final FloatBuffer GL_BUF = BufferUtils.createFloatBuffer(16);

    private WalkSprint() {
    }

    /** NTM's walking bob is left out on the gun drawn (Config selected for it): WalkSprint bobs it instead. */
    public static boolean bobOff() {
        return Config.enabled && Config.walkSway > 0F && !Arms.measuring && !GunFeelClient.currentAkimbo;
    }

    /** A new shot of the held gun (GunFeelClient.checkShot). */
    public static void onShot() {
        shot = true;
    }

    // 26.2: Mth.sin / Mth.cos, the same table as MathHelper's
    private static float sin(float a) {
        return MathHelper.sin(a);
    }

    /** Walking: u = cycle phase 0..1 (two steps), the bob of TaCZ's walk_forward, aiming = walk_aiming. */
    static Pose walk(float u, float aiming) {
        float s = sin(u * TWO_PI), c2 = MathHelper.cos(2F * u * TWO_PI);
        float a = Math.clamp(aiming, 0F, 1F);
        float k = 1F - 0.65F * a; // walk_aiming: yaw ±0.69 of ±1.95
        return new Pose(0F, -1.95F * s * k, (2.2F + sin((u - 0.1F) * TWO_PI)) * (1F - 0.9F * a),
                (0.11F + 0.04F * s) * PX * k, (-0.32F - 0.19F * c2) * PX * k, 0F);
    }

    /** Sprinting: u = cycle phase 0..1, the gun lowered and turned, swinging with the run (TaCZ's run). */
    static Pose sprint(float u) {
        float a = u * TWO_PI;
        float s1 = sin(a), c1 = MathHelper.cos(a), s2 = sin(2F * a);
        return new Pose(-35F + 6F * s2, 45F + 8F * c1, 31F + 6F * s2,
                -1.3F * PX + 1.9F * PX * s1, -5.4F * PX - 1.6F * PX * s2, 0F);
    }

    static float ease(float w) {
        return w * w * (3F - 2F * w);
    }

    static float approach(float w, boolean on, float dt, float in, float out) {
        return on ? Math.min(1F, w + dt / in) : Math.max(0F, w - dt / out);
    }

    // 26.2: Mth.lerp(partial, a, b)
    private static float lerp(float partial, float a, float b) {
        return a + partial * (b - a);
    }

    /** Called with Config selected for the gun drawn, where NTM's first-person transform begins (after WeaponSway). */
    public static void apply(EntityPlayerSP player, Item item) {
        long now = System.nanoTime();
        float dt = last == 0 ? 0F : Math.clamp((now - last) / 1e9F, 0F, 0.1F);
        last = now;
        float partial = ItemRenderWeaponBase.interp;
        float aiming = lerp(partial, ItemGunBaseNT.prevAimingProgress, ItemGunBaseNT.aimingProgress);
        boolean busy = HbmAnimations.getRelevantAnim(0) != null;
        boolean ground = player.onGround;
        boolean moving = player.movementInput != null
                && player.movementInput.moveForward * player.movementInput.moveForward + player.movementInput.moveStrafe * player.movementInput.moveStrafe > 1e-4F;
        boolean running = player.isSprinting() && !player.isSneaking();
        // a fire key held brings the gun up at once, as a shot does (a sprint may go on: NTM fires while sprinting)
        boolean firing = firing(player);
        if (shot) {
            shot = false;
            calm = SHOT_CALM;
        } else {
            calm = Math.max(0F, calm - dt);
        }
        boolean walkOn = Config.walkSway > 0F && moving && ground && calm <= 0F;
        boolean sprintOn = Config.sprintPose > 0F && running && !busy && aiming < 0.05F && calm <= 0F && !firing;
        walk = approach(walk, walkOn, dt, WALK_IN, WALK_OUT);
        // a shot, aiming or an NTM animation take the gun out of the sprint pose quickly
        boolean drop = calm > 0F || aiming >= 0.05F || busy || firing;
        sprint = approach(sprint, sprintOn, dt, SPRINT_IN, drop ? SPRINT_DROP : SPRINT_OUT);
        if (walk <= 0F && sprint <= 0F) {
            haveApplied = false;
            return;
        }
        // 26.2: getBackwardsInterpolatedWalkDistance(partial) = -(walkDist + (walkDist - walkDistO) * partial)
        float d = -(player.distanceWalkedModified + (player.distanceWalkedModified - player.prevDistanceWalkedModified) * partial);
        float u = d / 2F - MathHelper.floor_float(d / 2F);
        float ws = ease(walk) * Config.walkSway * (1F - ease(sprint)), ss = ease(sprint) * Config.sprintPose;
        Pose w = Pose.NONE.plus(walk(u, aiming), ws), s = Pose.NONE.plus(sprint(u), ss);
        // the trace's vectors as 26.2 writes its Vector3f: x,y,z
        if (TestLog.frames()) TestLog.c(-1, "walkSprint", "walk", walk, "sprint", sprint, "u", u, "aiming", aiming,
                "busy", busy, "calm", calm, "firing", firing, "sprinting", running, "walkTurn", new float[] {w.pitch(), w.yaw(), w.roll()},
                "walkPos", new float[] {w.x(), w.y(), w.z()}, "sprintTurn", new float[] {s.pitch(), s.yaw(), s.roll()},
                "sprintPos", new float[] {s.x(), s.y(), s.z()}, "pivot", new float[] {PIVOT.x, PIVOT.y, PIVOT.z});
        GL11.glTranslatef(w.x() + s.x(), w.y() + s.y(), w.z() + s.z());
        // walking turns around the eye (TaCZ's root), sprinting around the right hand
        turn(w, 0F, 0F, 0F);
        turn(s, PIVOT.x, PIVOT.y, PIVOT.z);
        GL_BUF.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, GL_BUF);
        APPLIED.set(GL_BUF);
        haveApplied = true;
        appliedItem = item;
    }

    private static boolean firing(EntityPlayerSP player) {
        HbmPlayerProps props = HbmPlayerProps.getData(player);
        return props.getKeyPressed(EnumKeybind.GUN_PRIMARY) || props.getKeyPressed(EnumKeybind.GUN_SECONDARY);
    }

    // 26.2: mulPose(Axis.YP / XP / ZP .rotationDegrees)
    private static void turn(Pose p, float px, float py, float pz) {
        if (p.pitch() == 0F && p.yaw() == 0F && p.roll() == 0F) return;
        GL11.glTranslatef(px, py, pz);
        GL11.glRotatef(p.yaw(), 0F, 1F, 0F);
        GL11.glRotatef(p.pitch(), 1F, 0F, 0F);
        GL11.glRotatef(p.roll(), 0F, 0F, 1F);
        GL11.glTranslatef(-px, -py, -pz);
    }

    /** After NTM placed the gun: the right hand's place in the view space before our turn, for the next frame. */
    public static void gunPlaced(Item item) {
        Vector3f hand = new Vector3f();
        if (TestLog.frames() && Arms.rightGripRendered(item, hand)) {
            Vector3f back = new Vector3f(), front = new Vector3f();
            float[] h = {hand.x, hand.y, hand.z};
            if (Arms.gunEndsRendered(item, back, front))
                TestLog.c(-1, "walkSprintHand", "hand", h, "back", new float[] {back.x, back.y, back.z}, "front", new float[] {front.x, front.y, front.z});
            else TestLog.c(-1, "walkSprintHand", "hand", h);
        }
        if (!haveApplied || appliedItem != item) return;
        if (!Arms.rightGripRendered(item, hand)) return;
        // APPLIED = view·T; the hand in view space is view⁻¹·hand, and before T it is T⁻¹ of that
        Matrix4f inv = new Matrix4f(APPLIED).invert();
        inv.transformPosition(hand);
        if (Float.isFinite(hand.x) && hand.lengthSquared() < 9F) PIVOT.set(hand);
    }
}
