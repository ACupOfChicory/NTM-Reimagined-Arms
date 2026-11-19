package acup.gunfeel;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

// Where each arm goes this frame, from what the version measured while the gun was drawn (the camera, the aimed gun
// frame, the body, the parts each hand holds or carries, the poses of the parts) and the gun's animation (ArmTimeline):
// at rest under its grip, hidden, on the magazine, on a path, carrying, letting go, around the part it holds, or
// holding the gun and sliding along it. The result is the arm's matrix for the vanilla arm cube and how many of its
// 12 pixels are shown (from the hand end); Arms draws it. Taken out of Arms.draw as it was. Render thread only.
final class ArmPoses {
    private ArmPoses() {
    }

    // the gun's shape the hands sit on (26.2: GunGeometry)
    interface Shape {
        // fraction (stock 0 - muzzle 1) of the gun length at model z
        float fraction(float z);

        // the cross-section {minX, maxX, minY, maxY} at model z, null = none
        float[] sectionAt(float z);
    }

    // the path a hand slides along (the gun's surface, stock to muzzle), asked only where a branch needs it
    interface Profiles {
        float[][] of(int hand);
    }

    // the gun body and the parts each hand holds relative to the aimed gun frame while no animation plays, per gun variant
    static final class GunRest {
        boolean valid;
        final Matrix4f body = new Matrix4f();
        final Matrix4f[] hold = {new Matrix4f(), new Matrix4f()};
    }

    // what hand() did with the arm
    static final int NONE = 0, HIDDEN = 1, DRAWN = 2;

    // the last hand()'s arm: its matrix (from the pose stack's start, the camera's frame applied) and pixels shown
    static Matrix4f out;
    static int outCrop;
    // the branch the last arm was placed by and what the debug log adds for the left hand
    static String branch = "", branchNote = "";
    // where a path's contact holds each hand (the drawn arm's grip point moves there)
    static final Vector3f[] pathContact = {new Vector3f(), new Vector3f()};

    // the frame, from begin()
    private static Matrix4f camera, fromAligned, cameraInv, bodyInCamera, aimedInCamera, restBody;
    private static Matrix4f[] follow;
    private static boolean[] haveFollow;
    private static GunRest gr;
    private static Shape shape;
    private static Matrix3f toAligned;
    private static Profiles profiles;
    private static ArmTimeline.PartPoses poses;
    private static float[][] grip;
    private static boolean slim, atRest, inspect, pistolHoldFrame, debug;
    private static float aimWeight, followRight, followLeft;
    private static final Quaternionf twist = new Quaternionf();
    private static Vector3f axisPoint, restAxis, down, restDown;

    // The frame's arms: camera, aimed, body = the frames read while the gun was drawn, follow/haveFollow = the parts each
    // hand holds (0, 1) and carries (2, 3); grip = the hands' points and hold centres (right, left, right centre, left
    // centre) in the gun's aligned frame. False: no rest pose of this gun yet (no arm is placed).
    static boolean begin(Matrix4f camera, Matrix4f aimed, Matrix4f body, Matrix4f[] follow, boolean[] haveFollow, Matrix4f fromAligned,
            GunRest gr, Shape shape, Matrix3f toAligned, Profiles profiles, ArmTimeline.PartPoses poses, float[][] grip, boolean slim,
            float aimWeight, boolean atRest, boolean pistolHoldFrame, boolean debug) {
        ArmPoses.camera = camera;
        ArmPoses.fromAligned = fromAligned;
        ArmPoses.follow = follow;
        ArmPoses.haveFollow = haveFollow;
        ArmPoses.gr = gr;
        ArmPoses.shape = shape;
        ArmPoses.toAligned = toAligned;
        ArmPoses.profiles = profiles;
        ArmPoses.poses = poses;
        ArmPoses.grip = grip;
        ArmPoses.slim = slim;
        ArmPoses.aimWeight = aimWeight;
        ArmPoses.atRest = atRest;
        ArmPoses.pistolHoldFrame = pistolHoldFrame;
        ArmPoses.debug = debug;
        cameraInv = new Matrix4f(camera).invert();
        bodyInCamera = new Matrix4f(cameraInv).mul(body);
        // Where the gun and the held parts sit relative to the aimed gun frame while nothing moves them.
        // The arms' rest pose is rebuilt from it every frame, so it follows aiming in and out even while
        // an animation plays (the gun keeps its place relative to the aimed frame, only the animation moves it).
        Matrix4f aimedInv = new Matrix4f(aimed).invert();
        if (atRest) {
            gr.body.set(aimedInv).mul(body);
            for (int i = 0; i < 2; i++) gr.hold[i].set(aimedInv).mul(haveFollow[i] ? follow[i] : body);
            gr.valid = true;
        }
        if (!gr.valid) return false;
        aimedInCamera = new Matrix4f(cameraInv).mul(aimed);
        restBody = new Matrix4f(aimedInCamera).mul(gr.body);
        Quaternionf now = ArmMath.rotation(bodyInCamera);
        Quaternionf rest = ArmMath.rotation(restBody);
        // When an animation turns the gun, the arms follow only its roll around the barrel; the rest of
        // the motion (lifting, tilting) is taken up by the hands sliding along the gun.
        twist.identity();
        if (!atRest) {
            Quaternionf t = new Quaternionf(now).mul(new Quaternionf(rest).conjugate()).normalize();
            // NTM rolls the gun around its barrel axis as it is at rest (the roll comes before lifting)
            Vector3f axis = rest.transform(new Vector3f(0F, 0F, 1F)).normalize();
            float d = t.x * axis.x + t.y * axis.y + t.z * axis.z;
            twist.set(axis.x * d, axis.y * d, axis.z * d, t.w);
            if (twist.lengthSquared() < 1e-8F) twist.identity();
            else twist.normalize();
            if (!Float.isFinite(twist.x + twist.y + twist.z + twist.w)) twist.identity();
        }
        // How much each arm rolls with the gun. While firing/cycling (e.g. canting a lever gun to throw
        // the shell) the support hand holds the forend loosely and the gun turns inside it; the trigger
        // hand turns with the gun. In inspect animations both hands turn the gun together.
        inspect = !atRest && GunAnimState.INSPECT.equals(ArmTimeline.ANIM.type());
        followRight = Math.clamp(Config.armFollow, 0F, 1F);
        followLeft = inspect ? followRight : Math.clamp(Config.armFollowSupport, 0F, 1F);
        axisPoint = bodyInCamera.transformPosition(new Vector3f());
        restAxis = restBody.transformPosition(new Vector3f());
        down = bodyInCamera.transformDirection(new Vector3f(0F, -1F, 0F)).normalize();
        restDown = restBody.transformDirection(new Vector3f(0F, -1F, 0F)).normalize();
        return true;
    }

    // One arm (i: 0 right, 1 left) of the frame begin() set; r = its rest pose for this gun (kept between frames).
    // DRAWN: out and outCrop hold it; HIDDEN: the animation hides the hand now; NONE: no place for it.
    static int hand(int i, ArmRest r) {
        boolean right = i == 0;
        pathContact[i].zero();
        // the arm as it would be at rest with the gun where it is now (aimed or not)
        Matrix4f restHolder = new Matrix4f(aimedInCamera).mul(gr.hold[i]);
        Vector3f restGrip = restHolder.transformPosition(grip[i][0], grip[i][1], grip[i][2], new Vector3f());
        Vector3f restCenter = restHolder.transformPosition(grip[i + 2][0], grip[i + 2][1], grip[i + 2][2], new Vector3f());
        ArmMath.restArm(r, right, slim, restGrip, restDown, restCenter, restAxis,
                right ? Config.armDirRight : Config.armDirLeft, right ? Config.armReachRight : Config.armReachLeft,
                ArmMath.holdScale(right, aimWeight), pistolHoldFrame && Config.armPistolScale, Config.pistolHold);
        if (!r.valid) return NONE;
        if (atRest) {
            branch = "rest";
            return drawn(new Matrix4f(camera).mul(r.local), r.crop);
        }
        // armLeftHide / armRightHide (per gun): the hand is not drawn in these stretches of an animation
        if (ArmTimeline.hidden(i)) return HIDDEN;
        // A hand goes from part to part during an animation (armLeftPath / armRightPath).
        if (ArmTimeline.magazineInspect(i)) {
            Matrix4f rifle=poses.pose("Rifle",0);
            if (rifle!=null) {
                branch = "magazine";
                // Одна постоянная поза руки в системе магазина, без повторного
                // решения плеча, изменения длины или отдельной траектории кисти.
                Matrix4f bind=new Matrix4f(restBody).invert().mul(r.local);
                ArmContact.attach(bind,new Vector3f(0,-2,-.5F),right,slim,1,ArmContact.offset(9));
                int time=(int)ArmTimeline.animMillis();
                int handTime=MagazineInspect.handTime(time,Config.armInspectReleaseMs);
                double[] mag=ArmTimeline.ANIM.busAt("MAG",handTime);
                double[] spin=ArmTimeline.ANIM.busAt("SPEEN",handTime);
                Matrix4f movement=new Matrix4f().set(toAligned)
                        .translate((float)mag[0],(float)mag[1],(float)mag[2])
                        .translate(0,-1.75F,-.5F)
                        .rotateZ((float)Math.toRadians(spin[2])).rotateY((float)Math.toRadians(spin[1]))
                        .translate(0,1.75F,.5F).mul(fromAligned);
                Matrix4f attached=new Matrix4f(cameraInv).mul(rifle).mul(movement).mul(bind);
                float weight=MagazineInspect.handWeight(time,Config.armInspectReleaseMs);
                Matrix4f m=weight>=1?attached:weight<=0?r.local:
                        ArmMath.blendAtHand(r.local,r.crop,attached,r.crop,weight,right,slim);
                return drawn(new Matrix4f(camera).mul(m),r.crop);
            }
        }
        float[] onPath = ArmTimeline.pathState(i, cameraInv, r.shoulder, poses);
        if (onPath != null) {
            Matrix4f home = haveFollow[i] ? new Matrix4f(cameraInv).mul(follow[i]) : bodyInCamera;
            Vector3f g = home.transformPosition(grip[i][0], grip[i][1], grip[i][2], new Vector3f());
            Vector3f at = new Vector3f(onPath[1], onPath[2], onPath[3]);
            Vector3f side = new Vector3f(onPath[4], onPath[5], onPath[6]);
            float w = onPath[0];
            Vector3f p = new Vector3f(g).lerp(at, w);
            Vector3f off = new Vector3f(down).lerp(side, w);
            if (off.lengthSquared() < 1e-6F) off.set(side);
            float holdRoll = right ? Config.armRollRight : Config.armRollLeft;
            float loadRoll = right ? Config.armLoadRollRight : Config.armLoadRollLeft;
            if (Float.isFinite(onPath[11])) loadRoll = holdRoll + onPath[11];
            ArmRest there = new ArmRest();
            // the arm at the path's point is sized as it is turned there (armPistolScale reads the turn)
            ArmMath.rollNow = loadRoll;
            ArmMath.restArm(there, right, slim, p, off, p, axisPoint,
                    right ? Config.armDirRight : Config.armDirLeft, right ? Config.armReachRight : Config.armReachLeft,
                    right ? Config.armLoadScaleRight : Config.armLoadScaleLeft,
                    pistolHoldFrame && Config.armPistolScale, Config.pistolHold);
            ArmMath.rollNow = Float.NaN;
            ArmRest mix = r;
            if (there.valid) {
                mix = new ArmRest();
                mix.valid = true;
                mix.length = r.length + (there.length - r.length) * w;
                mix.s = r.s + (there.s - r.s) * w;
                mix.hand.set(r.hand);
                mix.shoulder.set(r.shoulder).add(onPath[7], onPath[8], onPath[9]);
                mix.axis.set(r.axis);
                mix.center.set(r.center);
                mix.local.set(r.local);
                mix.crop = r.crop;
            }
            // a hand near the eye: show at least armPathMinLength pixels of the arm, not the last one or two
            if (Config.armPathMinLength > 0F) {
                if (mix == r) {
                    mix = new ArmRest();
                    mix.valid = true;
                    mix.length = r.length;
                    mix.s = r.s;
                    mix.hand.set(r.hand);
                    mix.shoulder.set(r.shoulder).add(onPath[7], onPath[8], onPath[9]);
                    mix.axis.set(r.axis);
                    mix.center.set(r.center);
                    mix.local.set(r.local);
                    mix.crop = r.crop;
                }
                mix.length = Math.max(mix.length, Config.armPathMinLength / 16F * mix.s);
            }
            ArmMath.rollNow = Float.isNaN(loadRoll) ? Float.NaN : holdRoll + (loadRoll - holdRoll) * w;
            Matrix4f m = ArmMath.pose(right, slim, p, null, shape.fraction(grip[i][2]), bodyInCamera, off, p, false,
                    new Quaternionf(), 0F, mix);
            ArmMath.rollNow = Float.NaN;
            int crop = ArmMath.lastCrop;
            // armPathBlend (per gun): near the grip the path arm turns into the usual hold (size, turn, cut),
            // blended around the hand, so leaving and taking the grip does not jump
            if (Config.armPathBlend && w < 1F) {
                Vector3f hc = home.transformPosition(grip[i + 2][0], grip[i + 2][1], grip[i + 2][2], new Vector3f());
                Quaternionf hown = new Quaternionf().slerp(twist, right ? followRight : followLeft).normalize();
                if (!Float.isFinite(hown.x + hown.y + hown.z + hown.w)) hown.identity();
                Matrix4f usual = ArmMath.pose(right, slim, g, haveFollow[i] ? null : profiles.of(i), shape.fraction(grip[i][2]), bodyInCamera,
                        down, hc, !right && !inspect, hown, inspect && !right ? Config.armInspectDrop : 0F, r);
                float e = w * w * (3F - 2F * w);
                m = ArmMath.blendAtHand(usual, ArmMath.lastCrop, m, crop, e, right, slim);
                crop = ArmMath.blendCrop;
            }
            var contactOffset=new Vector3f(onPath[12],onPath[13],onPath[14]);
            if (onPath[10] > 0F) ArmContact.attach(m,p,right,slim,onPath[10],contactOffset);
            pathContact[i].set(contactOffset).mul(onPath[10]);
            branch = "path w=" + fmt(onPath[0]) + (Config.armPathBlend ? " blend" : "");
            return drawn(new Matrix4f(camera).mul(m), crop);
        }
        if (debug) branchNote = "carry " + (ArmTimeline.carryState(i, toAligned) == null ? "none" : "w=" + fmt(ArmTimeline.carryState(i, toAligned)[0])) + " follow=" + haveFollow[2 + i];
        // A hand leaves the gun to carry something NTM animates (shells into the chambers or the tube) and comes back.
        float[] carried = ArmTimeline.carryState(i, toAligned);
        if (carried != null && haveFollow[2 + i]) {
            Matrix4f home = haveFollow[i] ? new Matrix4f(cameraInv).mul(follow[i]) : bodyInCamera;
            Vector3f g = home.transformPosition(grip[i][0], grip[i][1], grip[i][2], new Vector3f());
            Matrix4f within = new Matrix4f(cameraInv).mul(follow[2 + i]);
            ArmTimeline.Carry c = ArmTimeline.carry(i);
            Vector3f at = within.transformPosition(carried[1], carried[2], carried[3], new Vector3f());
            // the side's length is the hand's distance from the point, in half arm widths (1 = the hand touches it)
            Vector3f side = within.transformDirection(new Vector3f(c.dir())).normalize().mul(new Vector3f(c.dir()).length());
            Vector3f p = new Vector3f(g).lerp(at, carried[0]);
            Vector3f off = new Vector3f(down).lerp(side, carried[0]);
            if (off.lengthSquared() < 1e-6F) off.set(side);
            // the arm sized and aimed as if it rested at the carrying spot (a hand near the camera is thinner
            // than one reaching far forward), blended with the arm on the gun by the carry weight; sized as it is
            // turned there (armLoadRollRight/Left, read by armPistolScale)
            float holdRoll = right ? Config.armRollRight : Config.armRollLeft;
            float loadRoll = right ? Config.armLoadRollRight : Config.armLoadRollLeft;
            ArmRest there = new ArmRest();
            ArmMath.rollNow = loadRoll;
            ArmMath.restArm(there, right, slim, p, off, p, axisPoint,
                    right ? Config.armDirRight : Config.armDirLeft, right ? Config.armReachRight : Config.armReachLeft,
                    right ? Config.armLoadScaleRight : Config.armLoadScaleLeft,
                    pistolHoldFrame && Config.armPistolScale, Config.pistolHold);
            ArmMath.rollNow = Float.NaN;
            ArmRest mix = r;
            if (there.valid) {
                float w = carried[0];
                mix = new ArmRest();
                mix.valid = true;
                mix.length = r.length + (there.length - r.length) * w;
                mix.s = r.s + (there.s - r.s) * w;
                // the shoulder stays where it is (reaching back past the camera would sweep the arm across it)
                mix.hand.set(r.hand);
                mix.shoulder.set(r.shoulder);
                mix.axis.set(r.axis);
                mix.center.set(r.center);
                mix.local.set(r.local);
                mix.crop = r.crop;
            }
            // a hand loading shells may turn differently around its own axis (armLoadRollRight/Left)
            ArmMath.rollNow = Float.isNaN(loadRoll) ? Float.NaN : holdRoll + (loadRoll - holdRoll) * carried[0];
            Matrix4f m = ArmMath.pose(right, slim, p, null, shape.fraction(grip[i][2]), bodyInCamera, off, p, false,
                    new Quaternionf(), 0F, mix);
            ArmMath.rollNow = Float.NaN;
            // armCarryTurnRight/Left (per gun): the arm turned around the carried point about the shell's long
            // axis (parallel to its flat end), so it does not sink into the gun; + = the shoulder end to the right
            float turn = right ? Config.armCarryTurnRight : Config.armCarryTurnLeft;
            if (turn != 0F) {
                Vector3f axis = within.transformDirection(new Vector3f(0F, 0F, 1F)).normalize();
                float ang = (float) Math.toRadians(-turn * carried[0]);
                m = new Matrix4f().translate(at).rotate(ang, axis).translate(new Vector3f(at).negate()).mul(m);
            }
            int crop = ArmMath.lastCrop;
            if (Config.armCarryKeepAngle) {
                // armCarryKeepAngle (per gun): the arm keeps its hold on the gun (angle, length) and only moves,
                // the hand from the grip to beside the carried thing (Sexy's bottle)
                Matrix4f hold = haveFollow[i] ? new Matrix4f(cameraInv).mul(follow[i]) : bodyInCamera;
                Vector3f hg = hold.transformPosition(grip[i][0], grip[i][1], grip[i][2], new Vector3f());
                Vector3f hc = hold.transformPosition(grip[i + 2][0], grip[i + 2][1], grip[i + 2][2], new Vector3f());
                Quaternionf hown = new Quaternionf().slerp(twist, right ? followRight : followLeft).normalize();
                if (!Float.isFinite(hown.x + hown.y + hown.z + hown.w)) hown.identity();
                Matrix4f usual = ArmMath.pose(right, slim, hg, haveFollow[i] ? null : profiles.of(i), shape.fraction(grip[i][2]), bodyInCamera,
                        down, hc, !right && !inspect, hown, 0F, r);
                float k = 2F / 16F * r.s;
                Vector3f from = new Vector3f(down).mul(k).add(hg);
                Vector3f to = new Vector3f(side).mul(k).add(at);
                float e = carried[0] * carried[0] * (3F - 2F * carried[0]);
                Vector3f move = to.sub(from).mul(e);
                m = new Matrix4f().translation(move).mul(usual);
                crop = ArmMath.lastCrop;
            }
            branch = "carry w=" + fmt(carried[0]) + " at=" + fmt(at) + (Config.armCarryKeepAngle ? " keep" : "");
            return drawn(new Matrix4f(camera).mul(m), crop);
        }
        // A hand lets go while the gun spins (sawn-off Maresleg cocking, Henry inspect): it keeps its rest pose,
        // moves aside and back, and takes the gun again when the turn is complete.
        float release = ArmTimeline.release(right ? Config.armReleaseRight : Config.armReleaseLeft);
        if (release > 0F) {
            Matrix4f moved = ArmMath.releasePose(r, right, release);
            branch = "release " + fmt(release);
            return drawn(new Matrix4f(camera).mul(moved), r.crop);
        }
        // a hand on a moving part (a pump) goes along with that part
        Matrix4f holder = haveFollow[i] ? new Matrix4f(cameraInv).mul(follow[i]) : bodyInCamera;
        Vector3f g0 = holder.transformPosition(grip[i][0], grip[i][1], grip[i][2], new Vector3f());
        // a hand on a moving part (a pump) stays on it: no sliding along the body, the arm reaches instead
        float[][] path = haveFollow[i] ? null : profiles.of(i);
        Vector3f center = holder.transformPosition(grip[i + 2][0], grip[i + 2][1], grip[i + 2][2], new Vector3f());
        // while firing/cycling the gun turns inside the loosely holding support hand
        boolean spin = !right && !inspect;
        float gripF = shape.fraction(grip[i][2]);
        // How much of the gun's roll this arm turns with. The hand itself always stays on the gun's
        // surface; a hand that follows less of the roll shows the gun turning inside it.
        Quaternionf own = new Quaternionf().slerp(twist, right ? followRight : followLeft).normalize();
        if (!Float.isFinite(own.x + own.y + own.z + own.w)) own.identity();
        float drop = inspect && !right ? Config.armInspectDrop : 0F;
        // scripted: the left hand goes around the part it holds (over a break-action's barrels to open them)
        float around = right ? Float.NaN : ArmTimeline.leftAngle();
        if (!Float.isNaN(around)) {
            float[] sec = shape.sectionAt(grip[i][2]);
            if (sec != null) {
                float a = (float) Math.toRadians(around), c = (float) Math.cos(a), sn = (float) Math.sin(a);
                float cx = (sec[0] + sec[1]) * 0.5F, cy = (sec[2] + sec[3]) * 0.5F;
                Vector3f g = holder.transformPosition(cx + (sec[1] - sec[0]) * 0.5F * c, cy + (sec[3] - sec[2]) * 0.5F * sn, grip[i][2], new Vector3f());
                Vector3f out = holder.transformDirection(new Vector3f(c, sn, 0F)).normalize();
                // armAroundShift (per gun): going round, the hand sits this many arm pixels to the left (+x), none under the part
                // reloads may have their own values (armAroundShiftReload / armAroundTopDropReload)
                String playing = ArmTimeline.ANIM.type();
                boolean reloading = GunAnimState.RELOAD.equals(playing) || GunAnimState.RELOAD_CYCLE.equals(playing)
                        || GunAnimState.RELOAD_END.equals(playing);
                float shift = reloading && !Float.isNaN(Config.armAroundShiftReload) ? Config.armAroundShiftReload : Config.armAroundShift;
                float topDrop = reloading && !Float.isNaN(Config.armAroundTopDropReload) ? Config.armAroundTopDropReload : Config.armAroundTopDrop;
                if (shift != 0F) {
                    float side = Math.clamp((around + 90F) / 90F, 0F, 1F);
                    g.add(holder.transformDirection(new Vector3f(1F, 0F, 0F)).normalize().mul(shift / 16F * r.s * side));
                }
                // armAroundTopDrop (per gun): over the part the hand sits this many arm pixels lower, fading out toward the side
                if (topDrop != 0F && sn > 0F) {
                    g.add(holder.transformDirection(new Vector3f(0F, -1F, 0F)).normalize().mul(topDrop / 16F * r.s * sn));
                }
                // back under the gun (e.g. as it is flicked up to close) the hand slides along it like at rest
                boolean under = Math.abs(around + 90F) < 0.5F;
                branch = "around " + fmt(around);
                return arm(right, g, under ? profiles.of(i) : null, gripF, holder, out, g, false,
                        new Quaternionf(), 0F, r);
            }
        }
        branch = "hold g=" + fmt(g0) + " spin=" + spin;
        return arm(right, g0, path, gripF, bodyInCamera, down, center, spin, own,
                drop, r);
    }

    // An arm during an animation, in camera space. The arm cube runs from the shoulder end (y = 0) to the
    // hand end (y = 12/16) along +Y.
    private static int arm(boolean right, Vector3f grip, float[][] path, float gripF, Matrix4f bodyInCamera, Vector3f down, Vector3f center,
            boolean spin, Quaternionf twist, float drop, ArmRest rest) {
        Matrix4f m = ArmMath.pose(right, slim, grip, path, gripF, bodyInCamera, down, center, spin, twist, drop, rest);
        return drawn(new Matrix4f(camera).mul(m), ArmMath.lastCrop);
    }

    // armSwitchMs state per hand (0 = right, 1 = left): the animation drawn last, the arm drawn last, and the
    // pose a new animation starts from
    private static final String[] switchKey = new String[2];
    private static final Matrix4f[] switchLast = new Matrix4f[2], switchFrom = new Matrix4f[2];
    private static final int[] switchLastCrop = new int[2], switchFromCrop = new int[2];
    private static final long[] switchAt = new long[2];

    // The arm about to be drawn (m from the pose stack's start, crop pixels shown) with the per gun edits of its pose
    // (armRightAdjustments / armLeftAdjustments) and the blend into a new animation (armSwitchMs); held = the item
    // in the main hand (its identity tells guns apart), null = no player. out and outCrop hold the result.
    static void finish(Matrix4f m, int crop, boolean right, boolean slim, Object held) {
        // armSwitchMs (per gun): a new animation (or its end) starts from where the arm was drawn last, over
        // this many ms. NTM's first frame of an animation comes ~50 ms in, keyed hands jumped there.
        GunAnimState anim = ArmTimeline.ANIM;
        int hand = right ? 0 : 1;
        String adjustments = right ? Config.armRightAdjustments : Config.armLeftAdjustments;
        if(!adjustments.isBlank() && anim.playing()) {
            var values=ArmPoseEdits.sample(adjustments,java.util.Objects.requireNonNull(anim.type()),ArmTimeline.animMillis());
            var cameraPose=new Matrix4f(camera).invert().mul(m);
            ArmPoseEdits.apply(cameraPose,values,right,slim,pathContact[hand]);
            m=new Matrix4f(camera).mul(cameraPose);
        }
        if (Config.armSwitchMs > 0F && !anim.previewing()) {
            boolean playing = anim.playing();
            String item = held == null ? "" : String.valueOf(System.identityHashCode(held));
            String key = item + "|" + (!playing ? "none" : anim.type() + "@" + anim.startMillis());
            // another gun: no blend from the last one's arm
            if (switchKey[hand] != null && !switchKey[hand].startsWith(item + "|")) switchLast[hand] = null;
            long now = anim.now();
            Matrix4f here = new Matrix4f(camera).invert().mul(m);
            if (switchKey[hand] != null && !key.equals(switchKey[hand]) && switchLast[hand] != null) {
                switchFrom[hand] = new Matrix4f(switchLast[hand]);
                switchFromCrop[hand] = switchLastCrop[hand];
                switchAt[hand] = now;
            }
            switchKey[hand] = key;
            float u = switchFrom[hand] == null ? 1F : (now - switchAt[hand]) / Config.armSwitchMs;
            if (u < 1F) {
                u = Math.max(0F, u);
                float e = u * u * (3F - 2F * u);
                here = ArmMath.blendAtHand(switchFrom[hand], switchFromCrop[hand], here, crop, e, right, slim);
                crop = ArmMath.blendCrop;
                m = new Matrix4f(camera).mul(here);
            } else {
                switchFrom[hand] = null;
            }
            switchLast[hand] = here;
            switchLastCrop[hand] = crop;
        }
        out = m;
        outCrop = crop;
    }

    private static int drawn(Matrix4f m, int crop) {
        out = m;
        outCrop = crop;
        return DRAWN;
    }

    static String fmt(float v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    static String fmt(Vector3f v) {
        return String.format(java.util.Locale.ROOT, "(%.3f %.3f %.3f)", v.x, v.y, v.z);
    }
}
