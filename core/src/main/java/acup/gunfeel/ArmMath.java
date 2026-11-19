package acup.gunfeel;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

// The arms' geometry, in camera space, from the settings (Config) and the points the version measured on the gun
// (Arms draws the arms with it): an arm at rest under its grip (restArm, its thickness, a pistol's), the arm's cube
// placed and cut (local), an arm during an animation (pose: the hand sliding along the gun, the shoulder kept), the
// blend of two arm poses around the hand, a point on a hand's path, the pure rotation of a scaled and mirrored
// matrix, a point on the screen. Arm cubes are the vanilla arm's: 12 px long along +Y, the hand end at y = 12/16.
final class ArmMath {
    // pixels of arm shown by the last blendAtHand
    static int blendCrop = 12;

    private ArmMath() {
    }

    // point at fraction f (stock 0 - muzzle 1) of a hand's path, in camera space, hand offset added
    static Vector3f onPath(float[][] path, float f, Matrix4f bodyInCamera, Vector3f off) {
        float x = Math.clamp(f, 0F, 1F) * (path.length - 1);
        int k = Math.min((int) x, path.length - 2);
        float u = x - k;
        float[] a = path[k], b = path[k + 1];
        return bodyInCamera.transformPosition(a[0] + (b[0] - a[0]) * u, a[1] + (b[1] - a[1]) * u, a[2] + (b[2] - a[2]) * u,
                new Vector3f()).add(off);
    }

    // Pure rotation of a matrix that may carry scale and a mirror (NTM flips models with a negative scale).
    static Quaternionf rotation(Matrix4f m) {
        org.joml.Matrix3f r = new org.joml.Matrix3f();
        m.get3x3(r);
        Vector3f c0 = r.getColumn(0, new Vector3f()).normalize();
        Vector3f c1 = r.getColumn(1, new Vector3f()).normalize();
        Vector3f c2 = r.getColumn(2, new Vector3f()).normalize();
        if (c0.dot(new Vector3f(c1).cross(c2)) < 0F) c0.negate();
        r.setColumn(0, c0).setColumn(1, c1).setColumn(2, c2);
        return r.getNormalizedRotation(new Quaternionf()).normalize();
    }

    static float pistolThickness(float sOld, float target, float wp) {
        return sOld + (target - sOld) * Math.min(1F, wp);
    }

    // a point of a model through clip (projection x view x model) as screen fractions from the top left corner; null
    // outside the window or behind the eye
    static float[] screenPoint(Matrix4f clip, float x, float y, float z) {
        org.joml.Vector4f v = clip.transform(new org.joml.Vector4f(x, y, z, 1F));
        if (v.w <= 1e-4F) return null;
        float sx = (v.x / v.w + 1F) * 0.5F, sy = (1F - v.y / v.w) * 0.5F;
        return sx < 0F || sx > 1F || sy < 0F || sy > 1F ? null : new float[] {sx, sy};
    }

    // Between two arm poses, turned and sized around the hand (the point the arm holds stays on the line
    // between the two hands); the shown length is blended too, the cut goes pixel by pixel (blendCrop).
    static Matrix4f blendAtHand(Matrix4f a, int cropA, Matrix4f b, int cropB, float w, boolean right, boolean slim) {
        Vector3f pivot = new Vector3f((right ? -1F : 1F) * (slim ? 5.5F : 6F) / 16F, 12F / 16F, 0F);
        Vector3f h = a.transformPosition(pivot, new Vector3f()).lerp(b.transformPosition(pivot, new Vector3f()), w);
        Vector3f sa = a.getScale(new Vector3f()), sb = b.getScale(new Vector3f());
        float shown = sa.y * cropA + (sb.y * cropB - sa.y * cropA) * w;
        int crop = Math.clamp(Math.round(cropA + (cropB - cropA) * w), 1, 12);
        blendCrop = crop;
        Vector3f sc = new Vector3f(sa).lerp(sb, w);
        sc.y = shown / crop;
        // rotation(): JOML getNormalizedRotation is wrong for an arm scaled unevenly (length vs thickness)
        Quaternionf q = rotation(a).slerp(rotation(b), w);
        return new Matrix4f().translation(h).rotate(q).scale(sc).translate(new Vector3f(pivot).negate());
    }

    // a pistol's arm thickness per unit of the eye's distance to its hand (armPistolScale, pistolTarget): the shipped
    // pistols' arms keep the mean width of X472 (11.9 deg by 2 atan(s 2/16 / depth), a silhouette of 16.2 deg)
    static final float PISTOL_THICKNESS = 1.12F;

    static Matrix4f releasePose(ArmRest rest, boolean right, float progress) {
        float[] m = right ? Config.armReleaseMoveRight : Config.armReleaseMove;
        float k = (0.5F - 0.5F * (float) Math.cos(2.0 * Math.PI * progress)) / 16F * rest.s;
        return new Matrix4f().translate(m[0] * k, m[1] * k, m[2] * k).mul(rest.local);
    }

    // The arm at rest, in camera space: from the hand under the grip point back along the configured
    // direction until depth `reach`. grip = point on the gun's underside; the arm's end face touches it there.
    static void restArm(ArmRest rest, boolean right, boolean slim, Vector3f grip, Vector3f down, Vector3f center,
            Vector3f axisPoint, float[] dirCfg, float reach) {
        restArm(rest, right, slim, grip, down, center, axisPoint, dirCfg, reach, holdScale(right, 0F));
    }

    static float holdScale(boolean right, float w) {
        float hip = right ? Config.armScaleRight : Config.armScaleLeft;
        float aim = right ? Config.armAimScaleRight : Config.armAimScaleLeft;
        if (!(aim > 0F) || w <= 0F) return hip;
        if (w >= 1F) return aim;
        return hip + (aim - hip) * w;
    }

    // holdScale: armScaleRight/Left, the thickness of an arm holding the gun (1 for a hand carrying something)
    static void restArm(ArmRest rest, boolean right, boolean slim, Vector3f grip, Vector3f down, Vector3f center,
            Vector3f axisPoint, float[] dirCfg, float reach, float holdScale) {
        restArm(rest, right, slim, grip, down, center, axisPoint, dirCfg, reach, holdScale, false, 0F);
    }

    // armPistolScale: the thickness (before armScale x holdScale = scale) at which the arm's silhouette across the hand
    // takes the same angle on the screen whatever the hand's depth and the arm's turn: a box shows one face seen face on,
    // two seen on its edge, and a near face looks wider. The angle is that of a box PISTOL_THICKNESS x its depth thick, as
    // wide as its mean over its roll. The hand hangs under the grip by half the thickness and the arm grows with it, so
    // the slice moves away as the arm thickens: the thickness is found by halving, within half to twice
    // PISTOL_THICKNESS x the grip's depth (an arm whose width can not reach the angle there stays at the bound).
    static float pistolTarget(boolean right, boolean slim, Vector3f grip, Vector3f down, Vector3f dir, float scale,
            boolean byScale, float length) {
        float t0 = PISTOL_THICKNESS * grip.length();
        if (!(t0 > 1e-6F)) return t0;
        float goal = PISTOL_THICKNESS * 4F / (float) Math.PI * ((slim ? 1.5F : 2F) + 2F) / 16F * scale;
        float lo = (float) Math.log(t0 * 0.5F), hi = (float) Math.log(t0 * 2F);
        float wHi = sliceWidth(right, slim, grip, down, dir, t0 * 2F * scale, byScale, length);
        if (Float.isNaN(wHi)) return t0;
        if (wHi <= goal) return t0 * 2F;
        float wLo = sliceWidth(right, slim, grip, down, dir, t0 * 0.5F * scale, byScale, length);
        if (Float.isNaN(wLo)) return t0;
        if (wLo >= goal) return t0 * 0.5F;
        for (int k = 0; k < 20; k++) {
            float mid = (lo + hi) * 0.5F;
            float w = sliceWidth(right, slim, grip, down, dir, (float) Math.exp(mid) * scale, byScale, length);
            if (Float.isNaN(w)) return t0;
            if (w < goal) lo = mid;
            else hi = mid;
        }
        return (float) Math.exp((lo + hi) * 0.5F);
    }

    // the width (tangent units) of the arm of scale s across its hand as the eye sees it, NaN when the arm points at the
    // eye: the corners of the slice 2 px back from its end (the middle of what is drawn when less is), on the plane square
    // to the line of sight. byScale: the arm's length grows with s (armBase and armLength set), else it is `length`.
    static float sliceWidth(boolean right, boolean slim, Vector3f grip, Vector3f down, Vector3f dir, float s, boolean byScale,
            float length) {
        float l = byScale ? 12F / 16F * s * Config.armLength : length;
        Vector3f hand = new Vector3f(down).mul(2F / 16F * s).add(grip);
        Matrix4f m = local(hand, new Quaternionf(), dir, right, slim, s, l);
        float cx = (right ? -1F : 1F) * (slim ? 5.5F : 6F) / 16F, y = (12F - Math.min(2F, lastCrop / 2F)) / 16F;
        float hx = (slim ? 1.5F : 2F) / 16F, hz = 2F / 16F;
        Vector3f slice = m.transformPosition(cx, y, 0F, new Vector3f());
        Vector3f view = new Vector3f(slice).normalize();
        Vector3f across = m.transformDirection(0F, 1F, 0F, new Vector3f()).cross(slice);
        if (!(across.lengthSquared() > 1e-12F)) return Float.NaN;
        across.normalize();
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        Vector3f q = new Vector3f();
        for (int i = -1; i <= 1; i += 2) for (int j = -1; j <= 1; j += 2) {
            m.transformPosition(cx + i * hx, y, j * hz, q);
            // a corner at or behind the eye counts as just in front of it, so the width does not jump there
            float w = q.dot(across) / Math.max(q.dot(view), 1e-3F);
            lo = Math.min(lo, w);
            hi = Math.max(hi, w);
        }
        return hi - lo;
    }

    static void restArm(ArmRest rest, boolean right, boolean slim, Vector3f grip, Vector3f down, Vector3f center,
            Vector3f axisPoint, float[] dirCfg, float reach, float holdScale, boolean pistolScale, float pistolWeight) {
        Vector3f dir = new Vector3f(dirCfg[0], dirCfg[1], dirCfg[2]);
        if (dir.lengthSquared() < 1e-6F) {
            rest.valid = false;
            return;
        }
        dir.normalize();
        // run back from the hand until depth `reach`; at least one vanilla arm length
        float length = dir.z < -1e-3F ? (grip.z - reach) / -dir.z : 18F / 16F;
        length = Math.max(length, 18F / 16F);
        // thickness: fixed (armBaseRight/Left, the same on every gun) or grown with the arm's length
        float base = right ? Config.armBaseRight : Config.armBaseLeft;
        float thickness = base > 0F ? base : Math.min(length / (18F / 16F), Config.armMaxScale);
        if (pistolScale && pistolWeight > 0F)
            thickness = pistolThickness(thickness, pistolTarget(right, slim, grip, down, dir, Config.armScale * holdScale,
                    base > 0F && Config.armLength > 0F, length), pistolWeight);
        float s = thickness * Config.armScale * holdScale;
        // Like TaCZ: an arm of fixed thickness is the whole arm, armLength x the vanilla arm of its thickness,
        // never cut at the plane in front of the eye (armReach): cut there, a hand near the screen kept only a few
        // pixels and looked tiny. It runs on out of view. Animations may still stretch it (armStretch).
        if (base > 0F && Config.armLength > 0F) length = 12F / 16F * s * Config.armLength;
        // hand hangs under the grip by half its thickness so the arm does not cut into the gun
        Vector3f hand = new Vector3f(down).mul(2F / 16F * s).add(grip);
        rest.valid = true;
        rest.length = length;
        rest.s = s;
        rest.hand.set(hand);
        rest.shoulder.set(dir).mul(length).add(hand);
        rest.axis.set(axisPoint);
        rest.center.set(center);
        rest.local.set(local(hand, new Quaternionf(), dir, right, slim, s, length));
        rest.crop = lastCrop;
    }

    // pixels of the arm the last local() call drew from the hand (12 = all of it)
    static int lastCrop = 12;

    // roll of the arm being drawn instead of armRollRight/Left, NaN = those (set while a hand carries shells)
    static float rollNow = Float.NaN;

    // arm cube placed with its hand end at `hand`, built along `dir` (hand -> shoulder), then turned by `turn`
    static Matrix4f local(Vector3f hand, Quaternionf turn, Vector3f dir, boolean right, boolean slim, float s, float length) {
        Vector3f y = new Vector3f(dir).negate().normalize();
        Vector3f x = new Vector3f(0F, 1F, 0F).cross(y);
        if (x.lengthSquared() < 1e-6F) x.set(1F, 0F, 0F);
        x.normalize();
        Vector3f z = new Vector3f(x).cross(y).normalize();
        Matrix4f basis = new Matrix4f(
                x.x, x.y, x.z, 0F,
                y.x, y.y, y.z, 0F,
                z.x, z.y, z.z, 0F,
                0F, 0F, 0F, 1F);
        float cx = (right ? -1F : 1F) * (slim ? 5.5F : 6F) / 16F;
        float end = 12F / 16F;
        // shorter than the vanilla proportions: draw only the hand end, whole pixels of it, at the arm's own scale
        float px = length * 16F / Math.max(s, 1e-4F);
        lastCrop = 12;
        float sy = length / end;
        if (Config.armLength > 0F && px < 11.5F) {
            lastCrop = Math.max(1, Math.round(px));
            sy = length / (lastCrop / 16F);
        }
        return new Matrix4f()
                .translate(hand)
                .rotate(turn)
                .mul(basis)
                .rotateY((float) Math.toRadians(!Float.isNaN(rollNow) ? rollNow : right ? Config.armRollRight : Config.armRollLeft))
                .scale(s, sy, s)
                .translate(-cx + (slim ? (right ? 1F : -1F) / 16F : 0F), -end, 0F);
    }

    // The arm's pose in camera space (before CAMERA); lastCrop is set to the pixels of arm it shows.
    static Matrix4f pose(boolean right, boolean slim, Vector3f grip, float[][] path, float gripF, Matrix4f bodyInCamera,
            Vector3f down, Vector3f center, boolean spin, Quaternionf twist, float drop, ArmRest rest) {
        if (spin) {
            // The rest arm, carried along with the held part and turned (by its share of the roll) around
            // that part's centre: a round forend turning inside the hand never leaves the palm.
            lastCrop = rest.crop;
            return new Matrix4f().translate(center).rotate(twist).translate(new Vector3f(rest.center).negate()).mul(rest.local);
        }
        float length = rest.length, s = rest.s;
        Vector3f off = new Vector3f(down).mul(2F / 16F * s);
        if (drop != 0F) {
            // inspect: lower the hand on screen (in arm pixels) as the gun rolls onto its side,
            // so less of the arm shows above the turned gun
            float angle = 2F * (float) Math.acos(Math.min(1F, Math.abs(twist.w)));
            off.y -= drop / 16F * s * (float) Math.abs(Math.sin(angle));
        }
        Vector3f restArm = new Vector3f(rest.shoulder).sub(rest.hand);
        // The shoulder stays where it is at rest, except that the gun's roll swings the arm around
        // the barrel axis as it is at rest. Other motion (lifting) is taken up by the hand sliding.
        Vector3f handAtGrip = new Vector3f(grip).add(off);
        Vector3f rolledHand = twist.transform(new Vector3f(rest.hand).sub(rest.axis)).add(rest.axis);
        Vector3f shoulder = twist.transform(new Vector3f(restArm)).add(rolledHand);
        // The arm may stretch a little; beyond that the hand slides along the gun's surface toward
        // the stock until the arm reaches it.
        float limit = length * (1F + Math.max(0F, Config.armStretch));
        Vector3f hand = handAtGrip;
        if (Config.armSlide && path != null && handAtGrip.distance(shoulder) > limit) {
            float lo = Math.min(Config.armSlideTo, gripF), hi = gripF;
            if (Config.armSlideNearest) {
                // armSlideNearest (per gun): the first point from the grip on that the arm reaches, stepping along the
                // gun; halving the whole stretch could land far from the grip when the distance is not monotonic
                // (Henry being lifted: the right hand jumped down the stock in one frame)
                // none reachable (the lifted Henry: the stock is no nearer the shoulder): the point nearest it, no halving
                float prev = hi, found = Float.NaN, nearest = hi, nearestD = Float.MAX_VALUE;
                for (int k = 1; k <= 64; k++) {
                    float f = hi + (lo - hi) * k / 64F;
                    float d = onPath(path, f, bodyInCamera, off).distance(shoulder);
                    if (d <= limit) {
                        found = f;
                        break;
                    }
                    if (d < nearestD) {
                        nearestD = d;
                        nearest = f;
                    }
                    prev = f;
                }
                if (Float.isNaN(found)) {
                    lo = hi = nearest;
                } else {
                    lo = found;
                    hi = prev;
                }
            }
            for (int k = 0; k < 16; k++) {
                float mid = (lo + hi) * 0.5F;
                if (onPath(path, mid, bodyInCamera, off).distance(shoulder) > limit) hi = mid;
                else lo = mid;
            }
            hand = onPath(path, lo, bodyInCamera, off);
        }
        // keep the rest length: the shoulder end moves along the arm instead of the arm stretching
        Vector3f back = new Vector3f(shoulder).sub(hand);
        if (back.lengthSquared() < 1e-8F) back.set(restArm);
        float reachNow = Math.min(back.length(), limit);
        length = Math.max(reachNow, length);
        // arm orientation: the rest orientation rolled with the gun, then aimed at the new direction
        Vector3f restY = new Vector3f(restArm).negate().normalize();
        Vector3f rolledY = twist.transform(new Vector3f(restY));
        Vector3f newY = new Vector3f(hand).sub(new Vector3f(back).normalize().mul(length).add(hand)).normalize();
        Quaternionf turn = new Quaternionf().rotationTo(rolledY, newY).mul(twist).normalize();
        if (!Float.isFinite(turn.x + turn.y + turn.z + turn.w)) turn.identity();
        return local(hand, turn, new Vector3f(restY).negate(), right, slim, s, length);
    }
}
