package acup.gunfeel;

import java.util.List;

import net.minecraft.world.phys.Vec3;

// the tests' points are the game's Vec3; HeadZone (core) takes numbers and Vec3d
final class HeadZoneTests {
    private HeadZoneTests() {
    }

    static boolean contains(List<HeadZone> zones, Vec3 p) {
        return HeadZone.contains(zones, p.x, p.y, p.z);
    }

    static boolean contains(List<HeadZone> zones, Vec3d p) {
        return HeadZone.contains(zones, p.x(), p.y(), p.z());
    }

    static boolean contains(HeadZone zone, Vec3 p) {
        return zone.contains(p.x, p.y, p.z);
    }

    static boolean visible(List<ModelBox> body, Vec3 point, Vec3 normal, double reach) {
        return HeadZone.visible(body, Vecs.of(point), Vecs.of(normal), reach);
    }
}
