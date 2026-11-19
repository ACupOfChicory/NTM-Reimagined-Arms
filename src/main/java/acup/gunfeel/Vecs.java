package acup.gunfeel;

import net.minecraft.world.phys.Vec3;

/** 26.2's Vec3 and core's Vec3d, one into the other where the version's code meets core's. */
public final class Vecs {
    private Vecs() {
    }

    public static Vec3d of(Vec3 v) {
        return new Vec3d(v.x, v.y, v.z);
    }

    /** each of the list's points as Vec3d, in its order (a new list) */
    public static java.util.List<Vec3d> of(java.util.List<Vec3> l) {
        java.util.List<Vec3d> out = new java.util.ArrayList<>(l.size());
        for (Vec3 v : l) out.add(of(v));
        return out;
    }

    public static Vec3 mc(Vec3d v) {
        return new Vec3(v.x(), v.y(), v.z());
    }
}
