package acup.gunfeel.ai.client;

import org.joml.Matrix4f;
import org.joml.Vector3fc;

import com.hbm.render.loader.GroupObject;

/** SuitBend's package parts for the tests */
public final class SuitBendAccess {
    private SuitBendAccess() {
    }

    public static SuitBend.Cut cut(GroupObject g, float plane) {
        return SuitBend.cut(g.name, g.quads(true), g.faceCount(), plane);
    }

    public static final int SEGMENTS = SuitBend.SEGMENTS;

    public static boolean opens(Matrix4f bend, float[] seam, int e) {
        return SuitBend.opens(bend, seam, e, new org.joml.Vector3f());
    }

    public static Matrix4f partway(Matrix4f m, Vector3fc pivot, float t) {
        return SuitBend.partway(m, pivot, t);
    }
}
