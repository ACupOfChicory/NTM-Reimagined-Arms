package acup.gunfeel;

import net.minecraft.world.phys.Vec3;

/** 26.2's Vec3 and core's Vec3d, one into the other where the version's code meets core's. */
public final class Vecs {
    private Vecs() {
    }

    public static Vec3d of(Vec3 v) {
        return new Vec3d(v.x, v.y, v.z);
    }

    public static Vec3 mc(Vec3d v) {
        return new Vec3(v.x(), v.y(), v.z());
    }
}
