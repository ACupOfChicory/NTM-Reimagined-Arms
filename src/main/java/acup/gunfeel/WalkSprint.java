package acup.gunfeel;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.render.anim.HbmAnimations;
import com.hbm.render.item.weapon.sedna.ItemRenderWeaponBase;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import acup.gunfeel.tacz.JumpingSway;
import acup.gunfeel.tacz.MoveState;

/**
 * The gun bobbing in step with walking (walkSway) and lowered to the side while sprinting (sprintPose), by the
 * reference measurements (notes/measure.md is local). Applied in view space before NTM places the gun, so the arms go
 * with it; NTM's own walking bob is left out for a gun that has walkSway on (MixinItemRenderWeaponBase).
 *
 * <p>Run, walk or idle is picked by the sprint and the movement keys, once a tick (tacz.MoveState), not by the speed.
 * The phase is vanilla's walk distance, but only the part walked on the ground: in the air (flying, gliding, falling)
 * it stands still, and so does the gun. One cycle is two steps, counted from where the walk or the run began, as the
 * measurements count it; the gun dips at each step. A jump and a landing move the gun by the jumping sway
 * (tacz.JumpingSway, with walkSway). Weights ramp in and out over time and are eased, so nothing jumps.
 *
 * <p>Each measured signal is a mean and two harmonics over the cycle (Wave). The turn is measured as a rotation vector
 * in view space; it is turned into our three angles through its matrix (fromVector), so the big turns of the run come
 * out as measured. The shift is in the gun's length L. One curve per class: a pistol or a revolver, or a long gun (the
 * measured turns are the same within a class; the shifts in L differ a little, the middle of the two guns is taken).
 */
public final class WalkSprint {
    /**
     * One pose: degrees (pitch + = muzzle up, yaw + = muzzle left, roll + = top to the left), turned around the gun's
     * middle as roll, then yaw, then pitch (outermost first), and a shift in the gun's length (x right, y up, z back,
     * toward the eye).
     */
    public record Pose(float pitch, float yaw, float roll, float x, float y, float z) {
    }

    /** One measured signal over the cycle u: mean + a1·cos(2π(u − u1)) + a2·cos(4π(u − u2)). */
    record Wave(float mean, float a1, float u1, float a2, float u2) {
        static Wave of(float mean) {
            return new Wave(mean, 0F, 0F, 0F, 0F);
        }

        float at(float u) {
            return mean + a1 * (float) Math.cos(2.0 * Math.PI * (u - u1)) + a2 * (float) Math.cos(4.0 * Math.PI * (u - u2));
        }
    }

    /** A measured move against the rest: the rotation vector rx, ry, rz (degrees, view space) and the shift (in L). */
    record Move(Wave rx, Wave ry, Wave rz, Wave tx, Wave ty, Wave tz) {
        static Move still(float rx, float ry, float rz, float tx, float ty, float tz) {
            return new Move(Wave.of(rx), Wave.of(ry), Wave.of(rz), Wave.of(tx), Wave.of(ty), Wave.of(tz));
        }
    }

    // Walking against the rest at the hip (the middle of the two guns of each class). A pistol leans to the left and
    // swings with the steps, a long gun leans less; both swing the muzzle ±2° once a cycle and dip at each step.
    static final Move WALK_PISTOL = new Move(
            new Wave(0.01F, 0.15F, 0.23F, 0.02F, 0.42F),
            new Wave(0.12F, 1.98F, 0.74F, 0.15F, 0.42F),
            new Wave(8.93F, 3.14F, 0.605F, 0.18F, 0.175F),
            new Wave(-0.0229F, 0.0127F, 0.20F, 0.0006F, 0.235F),
            new Wave(-0.0534F, 0.0016F, 0.155F, 0.0126F, 0.275F),
            new Wave(0.0311F, 0.0001F, 0.75F, 0.00005F, 0.25F));
    static final Move WALK_LONG = new Move(
            new Wave(0.025F, 0.04F, 0.23F, 0.01F, 0.44F),
            new Wave(0.12F, 2.00F, 0.74F, 0.15F, 0.42F),
            new Wave(2.265F, 0.98F, 0.635F, 0.05F, 0.13F),
            new Wave(-0.0053F, 0.0077F, 0.23F, 0.0005F, 0.19F),
            new Wave(-0.0064F, 0.0003F, 0.185F, 0.0053F, 0.28F),
            new Wave(0.0163F, 0F, 0F, 0.0001F, 0.205F));
    // Walking while aimed, against the aimed rest: almost still
    static final Move WALK_AIMED_PISTOL = new Move(
            Wave.of(0.08F),
            new Wave(-0.03F, 0.13F, 0.76F, 0F, 0F),
            new Wave(-0.05F, 0.04F, 0.065F, 0.01F, 0.305F),
            new Wave(-0.0003F, 0.0014F, 0.76F, 0F, 0F),
            new Wave(0.0028F, 0F, 0F, 0.0020F, 0.28F),
            new Wave(0.0067F, 0.0001F, 0.29F, 0.00065F, 0.295F));
    static final Move WALK_AIMED_LONG = new Move(
            Wave.of(0.065F),
            new Wave(-0.005F, 0.13F, 0.76F, 0F, 0F),
            new Wave(-0.04F, 0.09F, 0.07F, 0.02F, 0.31F),
            new Wave(-0.0001F, 0.0003F, 0.79F, 0F, 0F),
            new Wave(0.0004F, 0F, 0F, 0.0003F, 0.285F),
            new Wave(0.0010F, 0F, 0F, 0.0001F, 0.30F));
    // Sprinting: the gun raised, turned to the left and leaned over, swinging once a cycle (yaw) and twice (pitch,
    // roll, the dip)
    static final Move SPRINT_PISTOL = new Move(
            new Wave(29.785F, 3.99F, 0.715F, 2.87F, 0.18F),
            new Wave(28.80F, 8.055F, 0.005F, 1.295F, 0.215F),
            new Wave(13.54F, 4.40F, 0.675F, 2.32F, 0.17F),
            new Wave(-0.1261F, 0.1523F, 0.61F, 0.0038F, 0.44F),
            new Wave(-0.1651F, 0.0027F, 0.08F, 0.0596F, 0.195F),
            new Wave(0.0576F, 0.0139F, 0.865F, 0.0091F, 0.185F));
    static final Move SPRINT_LONG = new Move(
            new Wave(20.305F, 3.69F, 0.67F, 2.12F, 0.19F),
            new Wave(51.37F, 7.67F, 0.02F, 1.16F, 0.23F),
            new Wave(14.505F, 3.92F, 0.65F, 1.54F, 0.18F),
            new Wave(-0.1128F, 0.0587F, 0.61F, 0.0069F, 0.16F),
            new Wave(-0.1045F, 0.0038F, 0.945F, 0.0346F, 0.20F),
            new Wave(0.0744F, 0.0145F, 0.965F, 0.0057F, 0.205F));
    // Flying with the sprint held (creative flight, gliding): the sprint pose without the swing, a little higher
    static final Move FLY_PISTOL = Move.still(30.8F, 27.7F, 15.95F, -0.059F, -0.0365F, 0.0575F);
    static final Move FLY_LONG = Move.still(18.5F, 55.1F, 12.3F, -0.1035F, -0.059F, 0.0795F);

    // NTM holds its guns lower on the screen than the measured ones: the full drop (and push) would take more of ours
    // off the edge. Not for a pistol in pistolHold: that puts it where the measured pistols are, as deep in L
    static final float DROP = 0.6F;
    // the jump's sway (tacz.JumpingSway, -2 at most, through its filter: about -1.21 at a take-off and -0.95 at a
    // landing) -> the gun's length: the measured dip of the gun's middle, take-off -0.106 L and landing -0.085 L for a
    // pistol, -0.032 L and -0.025 L for a long gun
    static final float JUMP_PISTOL = 0.088F, JUMP_LONG = 0.026F;
    // seconds to come in / go out, by the measured 10 / 50 / 90 % times (the state changes on the next tick)
    static final float WALK_IN = 0.18F, WALK_OUT = 0.25F, SPRINT_IN = 0.45F, SPRINT_OUT = 0.25F;
    // a shot, aiming or an NTM animation take the gun out of the sprint pose quicker
    static final float SPRINT_DROP = 0.1F;
    // the run comes in only once it has held this long: the measured run starts late (10 % at 0.13..0.19 s) and is
    // in at the same 90 %
    static final float SPRINT_DELAY = 0.04F;
    // seconds for the sprint's swing to go and come back as the player starts or stops flying: chosen, not measured
    // (the measurements have the still pose in flight, not how long it takes to come in)
    static final float FLY_IN = 0.25F;
    // after a shot the walking bob calms down for a moment
    static final float SHOT_CALM = 0.3F;

    private static long last;
    private static float walk, sprint, calm, fly, sprintHeld;
    // run, walk or idle for the player, from the last client tick
    private static MoveState state = MoveState.IDLE;
    // the walk distance walked on the ground (the phase), and vanilla's walk distance at the last frame
    private static float groundDist, lastDist = Float.NaN;
    // groundDist where the walk and the run began (the player last stood before their weights left 0): the cycle is
    // counted from there; and groundDist at the last frame the player stood
    private static float walkFrom, sprintFrom, restDist;
    // the player on the ground at the last frame (a step counts between two frames on the ground), and the player the
    // distances belong to (a new world or a respawn starts over)
    private static boolean lastGround;
    private static Object lastPlayer;
    private static boolean shot;
    // view-space pivot of the turns, from the last frame: the middle of the gun's cloud of vertices (their mean across,
    // halfway along), as the measurements turn the gun around its middle; the ends' axis (x = y = 0 of the model) may
    // be far from it, and a turn of the run around it would carry the middle away
    private static final Vector3f PIVOT = new Vector3f(0.35F, -0.45F, -0.7F);
    // the gun's length in the view space, from the last frame: the L the shifts are measured in
    private static float length = 1F;
    private static final Matrix4f APPLIED = new Matrix4f();
    // this frame's move in view space from the start of NTM's first-person transform to its setupFirstPerson: the
    // turn's sway (WeaponSway), WalkSprint and NTM's own bob, all but NTM's half turn about y (identity at rest).
    // PistolHold places the gun from the hip in the frame before them, and the move goes on top (moved). BASE is the
    // pose at that start, before WeaponSway
    private static final Matrix4f MOVED = new Matrix4f(), IDENTITY = new Matrix4f(), BASE = new Matrix4f();
    private static boolean based;
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

    /** Once a client tick: run / walk / idle for the player, by its sprint and movement keys (tacz.MoveState). */
    public static void tick() {
        var player = net.minecraft.client.Minecraft.getInstance().player;
        state = player == null ? MoveState.IDLE : MoveState.of(player);
        if (player != lastPlayer) {
            lastPlayer = player;
            groundDist = walkFrom = sprintFrom = restDist = 0F;
            lastDist = Float.NaN;
            lastGround = false;
        }
    }

    /** A new shot of the held gun (GunFeel.checkShot). */
    public static void onShot() {
        shot = true;
    }

    /** A pistol or a revolver (kickPreset): the measured pistols are held, turned and swung otherwise than long guns. */
    static boolean pistol(String kickPreset) {
        return "pistol".equals(kickPreset) || "revolver".equals(kickPreset);
    }

    /**
     * Walking: u = cycle phase 0..1 (two steps, from where the walk began), aiming 0..1. From the hip the gun leans to
     * the left and swings, the muzzle goes left and right once a cycle and the gun dips at each step; aimed it stays
     * almost still (the measured walk while aimed).
     */
    static Pose walk(float u, float aiming, boolean pistol) {
        return walk(u, aiming, pistol, 1F);
    }

    /** The same by the weight w: the measured turn's vector and the shift times w, so all of the turn comes in together. */
    static Pose walk(float u, float aiming, boolean pistol, float w) {
        return pose(pistol ? WALK_PISTOL : WALK_LONG, pistol ? WALK_AIMED_PISTOL : WALK_AIMED_LONG, Math.clamp(aiming, 0F, 1F), u, w);
    }

    /**
     * Sprinting: u = cycle phase 0..1 (from where the run began), flying 0..1. The gun raised, turned to the left and
     * leaned over, swinging with the run; flying, the same pose without the swing.
     */
    static Pose sprint(float u, float flying, boolean pistol) {
        return sprint(u, flying, pistol, 1F);
    }

    /** The same by the weight w (see walk). */
    static Pose sprint(float u, float flying, boolean pistol, float w) {
        return pose(pistol ? SPRINT_PISTOL : SPRINT_LONG, pistol ? FLY_PISTOL : FLY_LONG, Math.clamp(flying, 0F, 1F), u, w);
    }

    /**
     * The move a at u, going over to b by t, by the weight w, as our pose. The weight scales the rotation vector, not
     * our three angles: scaled, the angles of a big turn would bring its parts in at different paces (at the long
     * gun's run the pitch 90 % in at 0.75 of the weight).
     */
    static Pose pose(Move a, Move b, float t, float u, float w) {
        return fromVector(w * Mth.lerp(t, a.rx().at(u), b.rx().at(u)), w * Mth.lerp(t, a.ry().at(u), b.ry().at(u)),
                w * Mth.lerp(t, a.rz().at(u), b.rz().at(u)), w * Mth.lerp(t, a.tx().at(u), b.tx().at(u)),
                w * Mth.lerp(t, a.ty().at(u), b.ty().at(u)), w * Mth.lerp(t, a.tz().at(u), b.tz().at(u)));
    }

    /**
     * A measured turn, the rotation vector rx, ry, rz (axis × angle, degrees, view space), as our three angles: the
     * same matrix, Rz(roll)·Ry(yaw)·Rx(pitch) as turn makes it. Its last row is (-sin yaw, cos yaw·sin pitch,
     * cos yaw·cos pitch), its first column (cos roll·cos yaw, sin roll·cos yaw, -sin yaw). Not the vector's parts
     * themselves: at the run's 20..50° they would point the gun several degrees off.
     */
    static Pose fromVector(float rx, float ry, float rz, float x, float y, float z) {
        float angle = (float) Math.sqrt(rx * rx + ry * ry + rz * rz);
        if (angle < 1e-6F) return new Pose(0F, 0F, 0F, x, y, z);
        Matrix3f m = new Matrix3f().rotation((float) Math.toRadians(angle), rx / angle, ry / angle, rz / angle);
        // JOML's mCR: column C, row R
        float yaw = (float) Math.asin(Math.clamp(-m.m02, -1F, 1F));
        float pitch = (float) Math.atan2(m.m12, m.m22);
        float roll = (float) Math.atan2(m.m01, m.m00);
        return new Pose((float) Math.toDegrees(pitch), (float) Math.toDegrees(yaw), (float) Math.toDegrees(roll), x, y, z);
    }

    /** The cycle's phase 0..1 at the walk distance d from where the move began: two steps a cycle. */
    static float phase(float d) {
        return d / 2F - Mth.floor(d / 2F);
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
    public static void apply(PoseStack pose, LocalPlayer player, ItemStack stack) {
        Item item = stack.getItem();
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
        // the last frame the player stood: a walk or a run counts its cycle from there, from the key, as the
        // measurements do (the state comes a tick later, the first steps already made)
        if (!Float.isFinite(lastDist) || Math.abs(dist - lastDist) < 1e-6F) restDist = groundDist;
        lastDist = dist;
        lastGround = ground;
        // every frame while walkSway is on: its timing counts from the last call (off, its filter's threads are not
        // even started). Its speed is the frame's height change over partialTicks, which is the tick's whole change
        // for any partialTicks above 0; at 0 it would be 0 / 0
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
        boolean sprintKeys = Config.sprintPose > 0F && running && !busy && aiming < 0.05F && calm <= 0F && !firing;
        sprintHeld = sprintKeys ? sprintHeld + dt : 0F;
        boolean sprintOn = sprintKeys && (sprint > 0F || sprintHeld >= SPRINT_DELAY);
        // a walk or a run coming in from rest counts its cycle from where the player last stood
        if (walk <= 0F && walkOn) walkFrom = restDist;
        if (sprint <= 0F && sprintOn) sprintFrom = restDist;
        walk = approach(walk, walkOn, dt, WALK_IN, WALK_OUT);
        // a shot, aiming or an NTM animation take the gun out of the sprint pose quickly
        boolean drop = calm > 0F || aiming >= 0.05F || busy || firing;
        sprint = approach(sprint, sprintOn, dt, SPRINT_IN, drop ? SPRINT_DROP : SPRINT_OUT);
        // flying (creative flight, gliding) the sprint pose stands without its swing
        fly = approach(fly, player.getAbilities().flying || player.isFallFlying(), dt, FLY_IN, FLY_IN);
        // a gun not yet measured would turn around the last gun's middle: it stays at rest until gunPlaced measures it,
        // then comes in as usual
        if (measuredItem != item) walk = sprint = 0F;
        // the gun is measured every frame, moving or not (gunPlaced)
        float js = Config.walkSway > 0F && measuredItem == item && Float.isFinite(jump) ? jump * Config.walkSway : 0F;
        if (walk <= 0F && sprint <= 0F && js == 0F) {
            applied(pose, item);
            return;
        }
        float d = groundDist;
        float uw = phase(d - walkFrom), us = phase(d - sprintFrom);
        float ws = ease(walk) * Config.walkSway * (1F - ease(sprint)), ss = ease(sprint) * Config.sprintPose;
        boolean pistol = pistol(Config.kickPreset);
        Pose w = walk(uw, aiming, pistol, ws), s = sprint(us, ease(fly), pistol, ss);
        // a pistol in pistolHold is where the measured pistols are: it takes the full drop
        float down = pistol && PistolHold.on() && !PistolHold.scoped(stack) ? 1F : DROP;
        float jumpL = (pistol ? JUMP_PISTOL : JUMP_LONG) * js;
        if (TestLog.frames()) TestLog.c(-1, "walkSprint", "walk", walk, "sprint", sprint, "fly", fly, "uWalk", uw, "uSprint", us,
                "aiming", aiming, "busy", busy, "calm", calm, "firing", firing, "sprinting", running, "pistol", pistol, "down", down,
                "walkTurn", new Vector3f(w.pitch(), w.yaw(), w.roll()), "walkPos", new Vector3f(w.x(), w.y(), w.z()),
                "sprintTurn", new Vector3f(s.pitch(), s.yaw(), s.roll()), "sprintPos", new Vector3f(s.x(), s.y(), s.z()),
                "pivot", PIVOT, "length", length, "state", state.name(), "ground", ground, "dist", d, "jump", jumpL);
        // the shifts are in the gun's length
        pose.translate((w.x() + s.x()) * length, ((w.y() + s.y()) * down + jumpL) * length, (w.z() + s.z()) * down * length);
        // both turn around the gun's middle, as the measurements turn it
        turn(pose, s, PIVOT.x, PIVOT.y, PIVOT.z);
        turn(pose, w, PIVOT.x, PIVOT.y, PIVOT.z);
        applied(pose, item);
    }

    /** The pose before the turn's sway (WeaponSway) and apply, at the start of NTM's first-person transform. */
    public static void base(PoseStack pose) {
        BASE.set(pose.last().pose());
        based = true;
    }

    /**
     * At NTM's setupFirstPerson: the move since base, past NTM's half turn about y (its only fixed step there), for
     * moved; identity when base was not called for this pass (the rest pose's measuring pass, our code off).
     */
    public static void setup(PoseStack pose) {
        MOVED.identity();
        if (!based) return;
        based = false;
        Matrix4f rest = new Matrix4f(BASE).rotateY((float) Math.PI);
        if (Math.abs(rest.determinant()) > 1e-12F) new Matrix4f(pose.last().pose()).mul(rest.invert(), MOVED);
    }

    /** The pose past apply for the gun drawn, for gunPlaced; moved belongs to this gun from now. */
    static void applied(PoseStack pose, Item item) {
        appliedItem = item;
        haveApplied = true;
        APPLIED.set(pose.last().pose());
    }

    /**
     * This frame's move of the gun drawn in view space before NTM places it: the turn's sway, WalkSprint (its shift
     * and turns around the gun's middle) and NTM's own bob, identity while none moves it; for PistolHold.hip, called
     * between setup and gunPlaced.
     */
    public static Matrix4f moved(Item item) {
        return haveApplied && appliedItem == item ? MOVED : IDENTITY;
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

    // the bounds of a measure that is not taken: NTM's longest gun, the Stinger, is 30.4 long in this space and its
    // middle 7.6 to 9.7 from the eye (the Panzerschreck 16.7 and 2.9, a G3 9.4 and 2.6); a gun not measured does not
    // bob at all. They only keep out a broken matrix
    static final float MAX_LENGTH = 100F, MAX_MIDDLE = 50F;

    static boolean measurable(float len, Vector3f mid) {
        return Float.isFinite(len) && len >= 1e-3F && len <= MAX_LENGTH && mid.isFinite() && mid.lengthSquared() <= MAX_MIDDLE * MAX_MIDDLE;
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
        Vector3f back = new Vector3f(), front = new Vector3f(), mid = new Vector3f();
        if (!Arms.gunEndsRendered(item, back, front) || !Arms.gunMiddleRendered(item, mid)) return;
        // APPLIED = view·T; a point in view space is view⁻¹·p, and before T it is T⁻¹ of that
        Matrix4f inv = new Matrix4f(APPLIED).invert();
        inv.transformPosition(back);
        inv.transformPosition(front);
        inv.transformPosition(mid);
        float len = back.distance(front);
        if (!measurable(len, mid)) {
            // a gun not measured stays still: say so in a trace
            if (TestLog.frames()) TestLog.c(-1, "walkSprintSkip", "item", item, "length", len, "middle", mid);
            return;
        }
        PIVOT.set(mid);
        length = len;
        measuredItem = item;
    }
}
