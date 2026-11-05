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

import acup.gunfeel.tacz.JumpingSway;
import acup.gunfeel.tacz.MoveState;

/**
 * The gun bobbing in step with walking (walkSway) and lowered to the side while sprinting (sprintPose), after TaCZ's
 * walk and run animations. Applied in view space before NTM places the gun, so the arms go with it; NTM's own walking
 * bob is left out for a gun that has walkSway on (MixinItemRenderWeaponBase).
 *
 * <p>Run, walk or idle is TaCZ's choice, by the sprint and the movement keys, once a tick (tacz.MoveState), not by the
 * speed. The phase is vanilla's walk distance, the same that bobs the camera, but only the part walked on the ground:
 * in the air (flying, gliding, falling) it stands still, and so does the gun. One cycle is two steps, the gun dips at
 * each step together with the camera. A jump and a landing move the gun by TaCZ's jumping sway (tacz.JumpingSway,
 * with walkSway). Weights ramp in and out over time and are eased, so nothing jumps.
 */
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
    // TaCZ's state of the player, from the last client tick
    private static MoveState state = MoveState.IDLE;
    // the walk distance walked on the ground (the phase), and vanilla's walk distance at the last frame
    private static float groundDist, lastDist = Float.NaN;
    // the player on the ground at the last frame (a step counts between two frames on the ground), and the player the
    // distances belong to (a new world or a respawn starts over)
    private static boolean lastGround;
    private static Object lastPlayer;
    private static boolean shot;
    // view-space pivot of the turns, from the last frame: the middle of the gun between its two ends (NTM holds a gun
    // with the hand below the screen's edge; TaCZ's root, the grip, is on the screen, and so is the gun's middle)
    private static final Vector3f PIVOT = new Vector3f(0.35F, -0.45F, -0.7F);
    // the gun's length in the view space, from the last frame
    private static float length = 1F;
    private static final Matrix4f APPLIED = new Matrix4f();
    private static boolean haveApplied;
    private static Item appliedItem;
    // the gun PIVOT and length were measured on; another gun starts from rest and is measured first
    private static Item measuredItem;

    private WalkSprint() {
    }

    /**
     * WalkSprint bobs the gun drawn, so NTM's own walking bob is left out (MixinItemRenderWeaponBase). Not for an akimbo
     * pair (as last drawn) or a left main hand: NTM draws those through the other hand's pass, which WalkSprint leaves
     * alone, so they keep NTM's bob.
     */
    public static boolean bobOff() {
        if (!Config.enabled || Config.walkSway <= 0F || Arms.measuring || GunFeel.currentAkimbo) return false;
        var mc = net.minecraft.client.Minecraft.getInstance();
        return mc.options.mainHand().get() != net.minecraft.world.entity.HumanoidArm.LEFT;
    }

    /** An NTM gun with walkSway is in the main hand: the vanilla bob of the hands is left out too (MixinHandBob). */
    public static boolean handBobOff() {
        if (!bobOff()) return false;
        var mc = net.minecraft.client.Minecraft.getInstance();
        return mc.player != null && mc.player.getMainHandItem().getItem() instanceof ItemGunBaseNT;
    }

    /** Once a client tick: TaCZ's run / walk / idle for the player (its TickAnimationEvent does it with a gun held). */
    public static void tick() {
        var player = net.minecraft.client.Minecraft.getInstance().player;
        state = player == null ? MoveState.IDLE : MoveState.of(player);
        if (player != lastPlayer) {
            lastPlayer = player;
            groundDist = 0F;
            lastDist = Float.NaN;
            lastGround = false;
        }
    }

    /** A new shot of the held gun (GunFeel.checkShot). */
    public static void onShot() {
        shot = true;
    }

    /** A pistol or a revolver (kickPreset): TaCZ holds them closer, turns them less while running, tilts more walking. */
    static boolean pistol(String kickPreset) {
        return "pistol".equals(kickPreset) || "revolver".equals(kickPreset);
    }

    /**
     * Walking: u = cycle phase 0..1 (two steps), aiming 0..1. After TaCZ's walk_forward: the muzzle swings ±2° left and
     * right once a cycle, the gun leans to the left (a pistol 9°, a long gun 2°) and dips at each step. TaCZ also pulls
     * the gun a little closer; ours is long and its back end near the eye already, so it is left where it is.
     */
    static Pose walk(float u, float aiming, boolean pistol) {
        float a = u * Mth.TWO_PI;
        float s = Mth.sin(a), c = Mth.cos(a), s2 = Mth.sin(2F * a), c2 = Mth.cos(2F * a);
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
        float a = u * Mth.TWO_PI;
        float s1 = Mth.sin(a), c1 = Mth.cos(a), s2 = Mth.sin(2F * a), c2 = Mth.cos(2F * a);
        float pitch = 35F - 4.7F * s1 + 0.7F * c1 - 3.6F * s2;
        float swing = -1.1F * s1 - 8.6F * c1 - 0.6F * c2;
        float lean = -4.5F * s1 + 0.8F * c1 - 3.2F * s2 + 0.4F * c2;
        if (pistol) return new Pose(pitch, 23.3F + swing, 21.6F + lean, -0.33F - 0.65F * s1 + 1.6F * c1, -2.66F - 0.7F * s2, -0.32F);
        return new Pose(pitch, 45.5F + swing, 30.9F + lean, 1.05F - 0.73F * s1 + 1.9F * c1 - 0.36F * s2, -5.3F - 0.3F * c1 - 1.34F * s2, -0.64F);
    }

    /**
     * The walk distance on the ground after a frame: vanilla's walk distance went from last to now, and only on the
     * ground it counts (in the air the phase, and the gun, stand still). A jump of 2 or more in one frame (a new world,
     * a respawn) is not a step.
     */
    static float groundStep(float acc, boolean ground, float last, float now) {
        if (!ground || !Float.isFinite(last) || !Float.isFinite(now) || Math.abs(now - last) >= 2F) return acc;
        return acc + now - last;
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
        boolean moving = state == MoveState.WALK;
        boolean running = state == MoveState.RUN;
        // the phase goes on only with the steps on the ground
        float dist = player.avatarState().getBackwardsInterpolatedWalkDistance(partial);
        groundDist = groundStep(groundDist, ground && lastGround, lastDist, dist);
        lastDist = dist;
        lastGround = ground;
        // every frame while walkSway is on, as TaCZ calls it: its timing counts from the last call (off, its filter's
        // threads are not even started). TaCZ's speed is the frame's height change over partialTicks, which is the
        // tick's whole change for any partialTicks above 0; at 0 it would be 0 / 0
        float jump = Config.walkSway > 0F ? JumpingSway.applyJumpingSway(Math.max(partial, 1e-3F)) : 0F;
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
        float js = Config.walkSway > 0F && measuredItem == item && Float.isFinite(jump) ? jump * Config.walkSway : 0F;
        if (walk <= 0F && sprint <= 0F && js == 0F) {
            APPLIED.set(pose.last().pose());
            return;
        }
        float d = groundDist;
        float u = d / 2F - Mth.floor(d / 2F);
        float ws = ease(walk) * Config.walkSway * (1F - ease(sprint)), ss = ease(sprint) * Config.sprintPose;
        boolean pistol = pistol(Config.kickPreset);
        Pose w = Pose.NONE.plus(walk(u, aiming, pistol), ws), s = Pose.NONE.plus(sprint(u, pistol), ss);
        // TaCZ's pixels -> our blocks by the guns' lengths
        float scale = length / (pistol ? PISTOL_LENGTH : LONG_LENGTH);
        if (TestLog.frames()) TestLog.c(-1, "walkSprint", "walk", walk, "sprint", sprint, "u", u, "aiming", aiming,
                "busy", busy, "calm", calm, "firing", firing, "sprinting", running, "pistol", pistol, "scale", scale, "walkTurn", new Vector3f(w.pitch(), w.yaw(), w.roll()),
                "walkPos", new Vector3f(w.x(), w.y(), w.z()), "sprintTurn", new Vector3f(s.pitch(), s.yaw(), s.roll()),
                "sprintPos", new Vector3f(s.x(), s.y(), s.z()), "pivot", PIVOT, "length", length, "state", state.name(),
                "ground", ground, "dist", d, "jump", js);
        pose.translate((w.x() + s.x()) * scale, ((w.y() + s.y()) * DROP + js) * scale, (w.z() + s.z()) * scale * DROP);
        // both turn around the gun's middle, as TaCZ turns its root bone around the grip
        turn(pose, s, PIVOT.x, PIVOT.y, PIVOT.z);
        turn(pose, w, PIVOT.x, PIVOT.y, PIVOT.z);
        APPLIED.set(pose.last().pose());
    }

    private static boolean firing(LocalPlayer player) {
        var props = com.hbm.extprop.HbmPlayerProps.getData(player);
        return props.getKeyPressed(com.hbm.handler.HbmKeybinds.EnumKeybind.GUN_PRIMARY)
                || props.getKeyPressed(com.hbm.handler.HbmKeybinds.EnumKeybind.GUN_SECONDARY);
    }

    private static void turn(PoseStack pose, Pose p, float px, float py, float pz) {
        if (p.pitch() == 0F && p.yaw() == 0F && p.roll() == 0F) return;
        pose.translate(px, py, pz);
        pose.mulPose(Axis.ZP.rotationDegrees(p.roll()));
        pose.mulPose(Axis.YP.rotationDegrees(p.yaw()));
        pose.mulPose(Axis.XP.rotationDegrees(p.pitch()));
        pose.translate(-px, -py, -pz);
    }

    /** After NTM placed the gun: the gun's middle in the view space before our turn, for the next frame. */
    public static void gunPlaced(Item item) {
        Vector3f hand = new Vector3f();
        if (TestLog.frames() && Arms.rightGripRendered(item, hand)) {
            Vector3f back = new Vector3f(), front = new Vector3f();
            if (Arms.gunEndsRendered(item, back, front)) TestLog.c(-1, "walkSprintHand", "hand", hand, "back", back, "front", front);
            else TestLog.c(-1, "walkSprintHand", "hand", hand);
        }
        if (!haveApplied || appliedItem != item) return;
        // once per apply: a second pass of the same gun (the other hand) is not measured against this one's matrix
        haveApplied = false;
        Vector3f back = new Vector3f(), front = new Vector3f();
        if (!Arms.gunEndsRendered(item, back, front)) return;
        // APPLIED = view·T; a point in view space is view⁻¹·p, and before T it is T⁻¹ of that
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
