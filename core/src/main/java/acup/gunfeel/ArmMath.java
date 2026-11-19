package acup.gunfeel;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

// The arms' geometry that needs no setting and no game (Arms draws the arms with it): a point on a hand's path, the
// pure rotation of a scaled and mirrored matrix, the blend of two arm poses around the hand, a point on the screen.
// Arm cubes are the vanilla arm's: 12 px long along +Y, the hand end at y = 12/16.
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
}
