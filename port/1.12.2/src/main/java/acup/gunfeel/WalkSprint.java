package acup.gunfeel;

import java.nio.FloatBuffer;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import com.hbm.capability.HbmCapability;
import com.hbm.handler.HbmKeybinds.EnumKeybind;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.render.anim.sedna.HbmAnimationsSedna;
import com.hbm.render.item.weapon.sedna.ItemRenderWeaponBase;

import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.item.Item;
import net.minecraft.util.EnumHandSide;
import net.minecraft.util.math.MathHelper;

/**
 * The gun bobbing in step with walking (walkSway) and lowered to the side while sprinting (sprintPose), after TaCZ's
 * walk and run animations. Applied in view space before NTM places the gun, so the arms go with it; NTM's own walking
 * bob is left out for a gun that has walkSway on (MixinItemRenderWeaponBase).
 *
 * <p>The phase is vanilla's walk distance, the same that bobs the camera: one cycle is two steps, the gun dips at each
 * step together with the camera. Weights ramp in and out over time and are eased, so nothing jumps.
 */
// port: copied from 26.2 (3ea930c) through the 1.7.10 port. walk, sprint, ease, approach, the weights and
// the scale are unchanged; the turns are GL11's (26.2: PoseStack), the walk distance is distanceWalkedModified (26.2:
// getBackwardsInterpolatedWalkDistance, the same sum), moving is movementInput, crouching is isSneaking, NTM's keys are
// HbmCapability's. CE draws the hand from an identity modelview (setPerspectiveAndRender), so the GL modelview here is 26.2's pose stack turned half round: GL's eye looks
// along -z with the screen's right +x, 26.2's pose along +z with the right -x (a trace has the gun at x 0.6, z -7.2
// here and at -0.6, 7.2 there). The turns and the shift are made between two half turns about y, in 26.2's frame,
// so its numbers mean the same here. CE cancels vanilla's hand with its bob, so there is no handBobOff. A first-person
// gun drawn straight through renderByItem (not from setupTransformsAndRender) gets no WalkSprint.
public final class WalkSprint {
    /**
     * One pose: degrees (pitch + = muzzle up, yaw + = muzzle left, roll + = top to the left), turned as TaCZ turns a
     * bone (roll, then yaw, then pitch, outermost first), and a shift in TaCZ's pixels at TaCZ's depth (x right, y up,
     * z back), scaled to the depth of our gun (see apply).
     */
    public record Pose(float pitch, float yaw, float roll, float x, float y, float z) {
        static final Pose NONE = new Pose(0, 0, 0, 0, 0, 0);

        Pose plus(Pose o, float w) {
            return new Pose(pitch + o.pitch * w, yaw + o.yaw * w, roll + o.roll * w, x + o.x * w, y + o.y * w, z + o.z * w);
        }
    }

    // 26.2: Mth.TWO_PI
    private static final float TWO_PI = (float) (Math.PI * 2.0);
    // the length of TaCZ's guns in their pixels, a pistol and a long gun: a shift in TaCZ's pixels is this share of the
    // gun's length, and ours moves by the same share of its own length
    static final float PISTOL_LENGTH = 13.5F, LONG_LENGTH = 48F;
    // NTM holds its guns lower on the screen than TaCZ: the same drop (and push) would take more of ours off the edge
    static final float DROP = 0.6F;
    // seconds to come in / go out
    static final float WALK_IN = 0.4F, WALK_OUT = 0.4F, SPRINT_IN = 0.3F, SPRINT_OUT = 0.65F, SPRINT_DROP = 0.1F;
    // after a shot the walking bob calms down for a moment (TaCZ: while the gun cycles)
    static final float SHOT_CALM = 0.3F;

    private static long last;
    private static float walk, sprint, calm;
    private static boolean shot;
    // the pivot of the turns in 26.2's frame (see above), from the last frame: the middle of the gun between its two
    // ends (NTM holds a gun with the hand below the screen's edge; TaCZ's root, the grip, is on the screen, and so is
    // the gun's middle)
    private static final Vector3f PIVOT = new Vector3f(0.35F, -0.45F, -0.7F);
    // the gun's length in the view space, from the last frame
    private static float length = 1F;
    // the modelview past our shift and turns, still in 26.2's frame (before the second half turn)
    private static final Matrix4f APPLIED = new Matrix4f();
    private static boolean haveApplied;
    private static Item appliedItem;
    // the gun PIVOT and length were measured on; another gun starts from rest and is measured first
    private static Item measuredItem;
    private static final FloatBuffer GL_BUF = BufferUtils.createFloatBuffer(16);

    private WalkSprint() {
    }

    /**
     * WalkSprint bobs the gun drawn, so NTM's own walking bob is left out (MixinItemRenderWeaponBase). Not for an akimbo
     * pair (as last drawn) or a left main hand: NTM draws those through the other hand's pass, which WalkSprint leaves
     * alone, so they keep NTM's bob.
     */
    public static boolean bobOff() {
        if (!Config.enabled || Config.walkSway <= 0F || Arms.measuring || GunFeelClient.currentAkimbo) return false;
        return net.minecraft.client.Minecraft.getMinecraft().gameSettings.mainHand != EnumHandSide.LEFT;
    }

    /** A new shot of the held gun (GunFeelClient.checkShot). */
    public static void onShot() {
        shot = true;
    }

    /** A pistol or a revolver (kickPreset): TaCZ holds them closer, turns them less while running, tilts more walking. */
    static boolean pistol(String kickPreset) {
        return "pistol".equals(kickPreset) || "revolver".equals(kickPreset);
    }

    // 26.2: Mth.sin / Mth.cos, the same table as MathHelper's
    private static float sin(float a) {
        return MathHelper.sin(a);
    }

    private static float cos(float a) {
        return MathHelper.cos(a);
    }

    /**
     * Walking: u = cycle phase 0..1 (two steps), aiming 0..1. After TaCZ's walk_forward: the muzzle swings ±2° left and
     * right once a cycle, the gun leans to the left (a pistol 9°, a long gun 2°) and dips at each step. TaCZ also pulls
     * the gun a little closer; ours is long and its back end near the eye already, so it is left where it is.
     */
    static Pose walk(float u, float aiming, boolean pistol) {
        float a = u * TWO_PI;
        float s = sin(a), c = cos(a), s2 = sin(2F * a), c2 = cos(2F * a);
        float aim = Math.clamp(aiming, 0F, 1F);
        float k = 1F - 0.65F * aim; // walk_aiming: the swing a third
        float lean = (pistol ? 8.9F + 1.9F * s - 2.5F * c : 2.2F + 0.7F * s - 0.7F * c) * (1F - 0.9F * aim);
        float dip = pistol ? -0.65F + 0.04F * s2 - 0.15F * c2 : -0.3F + 0.06F * s2 - 0.23F * c2;
        return new Pose(0F, (2F * s - 0.2F * c) * k, lean, (-0.11F - 0.04F * s) * k, dip * k, 0F);
    }

    /**
     * Sprinting: u = cycle phase 0..1. After TaCZ's run: the muzzle raised 35° and turned to the left (a pistol 23°, a
     * long gun 45°), the gun leaned over and lowered, swinging with the run.
     */
    static Pose sprint(float u, boolean pistol) {
        float a = u * TWO_PI;
        float s1 = sin(a), c1 = cos(a), s2 = sin(2F * a), c2 = cos(2F * a);
        float pitch = 35F - 4.7F * s1 + 0.7F * c1 - 3.6F * s2;
        float swing = -1.1F * s1 - 8.6F * c1 - 0.6F * c2;
        float lean = -4.5F * s1 + 0.8F * c1 - 3.2F * s2 + 0.4F * c2;
        if (pistol) return new Pose(pitch, 23.3F + swing, 21.6F + lean, -0.33F - 0.65F * s1 + 1.6F * c1, -2.66F - 0.7F * s2, -0.32F);
        return new Pose(pitch, 45.5F + swing, 30.9F + lean, 1.05F - 0.73F * s1 + 1.9F * c1 - 0.36F * s2, -5.3F - 0.3F * c1 - 1.34F * s2, -0.64F);
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

    private static Matrix4f modelview(Matrix4f out) {
        GL_BUF.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, GL_BUF);
        return out.set(GL_BUF);
    }

    /** Called with Config selected for the gun drawn, where NTM's first-person transform begins (after WeaponSway). */
    public static void apply(EntityPlayerSP player, Item item) {
        long now = System.nanoTime();
        float dt = last == 0 ? 0F : Math.clamp((now - last) / 1e9F, 0F, 0.1F);
        last = now;
        float partial = ItemRenderWeaponBase.interp;
        float aiming = lerp(partial, ItemGunBaseNT.prevAimingProgress, ItemGunBaseNT.aimingProgress);
        boolean busy = HbmAnimationsSedna.getRelevantAnim(0) != null;
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
        // a gun not yet measured would turn around the last gun's middle: it stays at rest until gunPlaced measures it,
        // then comes in as usual
        if (measuredItem != item) walk = sprint = 0F;
        // the gun is measured every frame, moving or not (gunPlaced)
        appliedItem = item;
        haveApplied = true;
        if (walk <= 0F && sprint <= 0F) {
            modelview(APPLIED).rotateY((float) Math.PI);
            return;
        }
        // 26.2: getBackwardsInterpolatedWalkDistance(partial) = -(walkDist + (walkDist - walkDistO) * partial)
        float d = -(player.distanceWalkedModified + (player.distanceWalkedModified - player.prevDistanceWalkedModified) * partial);
        float u = d / 2F - MathHelper.floor(d / 2F);
        float ws = ease(walk) * Config.walkSway * (1F - ease(sprint)), ss = ease(sprint) * Config.sprintPose;
        boolean pistol = pistol(Config.kickPreset);
        Pose w = Pose.NONE.plus(walk(u, aiming, pistol), ws), s = Pose.NONE.plus(sprint(u, pistol), ss);
        // TaCZ's pixels -> our blocks by the guns' lengths
        float scale = length / (pistol ? PISTOL_LENGTH : LONG_LENGTH);
        // the trace's vectors as 26.2 writes its Vector3f: x,y,z
        if (TestLog.frames()) TestLog.c(-1, "walkSprint", "walk", walk, "sprint", sprint, "u", u, "aiming", aiming,
                "busy", busy, "calm", calm, "firing", firing, "sprinting", running, "pistol", pistol, "scale", scale,
                "walkTurn", new float[] {w.pitch(), w.yaw(), w.roll()}, "walkPos", new float[] {w.x(), w.y(), w.z()},
                "sprintTurn", new float[] {s.pitch(), s.yaw(), s.roll()}, "sprintPos", new float[] {s.x(), s.y(), s.z()},
                "pivot", new float[] {PIVOT.x, PIVOT.y, PIVOT.z}, "length", length);
        // into 26.2's frame
        GL11.glRotatef(180F, 0F, 1F, 0F);
        GL11.glTranslatef((w.x() + s.x()) * scale, (w.y() + s.y()) * scale * DROP, (w.z() + s.z()) * scale * DROP);
        // both turn around the gun's middle, as TaCZ turns its root bone around the grip
        turn(s, PIVOT.x, PIVOT.y, PIVOT.z);
        turn(w, PIVOT.x, PIVOT.y, PIVOT.z);
        modelview(APPLIED);
        // and back
        GL11.glRotatef(180F, 0F, 1F, 0F);
    }

    private static boolean firing(EntityPlayerSP player) {
        HbmCapability.IHBMData props = HbmCapability.getData(player);
        return props.getKeyPressed(EnumKeybind.GUN_PRIMARY) || props.getKeyPressed(EnumKeybind.GUN_SECONDARY);
    }

    // 26.2: mulPose(Axis.ZP / YP / XP .rotationDegrees)
    private static void turn(Pose p, float px, float py, float pz) {
        if (p.pitch() == 0F && p.yaw() == 0F && p.roll() == 0F) return;
        GL11.glTranslatef(px, py, pz);
        GL11.glRotatef(p.roll(), 0F, 0F, 1F);
        GL11.glRotatef(p.yaw(), 0F, 1F, 0F);
        GL11.glRotatef(p.pitch(), 1F, 0F, 0F);
        GL11.glTranslatef(-px, -py, -pz);
    }

    /** After NTM placed the gun: the gun's middle in 26.2's frame before our turn, for the next frame. */
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
        // once per apply: a second pass of the same gun (the other hand) is not measured against this one's matrix
        haveApplied = false;
        Vector3f back = new Vector3f(), front = new Vector3f();
        if (!Arms.gunEndsRendered(item, back, front)) return;
        // APPLIED = view·H·T (H the half turn); a point in view space is view⁻¹·p, before T in 26.2's frame (H·T)⁻¹ of that
        Matrix4f inv = new Matrix4f(APPLIED).invert();
        inv.transformPosition(back);
        inv.transformPosition(front);
        float len = back.distance(front);
        Vector3f mid = back.add(front).mul(0.5F);
        if (!Float.isFinite(len) || len < 1e-3F || len > 10F || mid.lengthSquared() > 25F) return;
        PIVOT.set(mid);
        length = len;
        measuredItem = item;
    }
}
