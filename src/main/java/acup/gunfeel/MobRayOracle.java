package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** Медленный независимый перебор поверхности: не вызывает ModelBox.clip или BVH. */
final class MobRayOracle {
    record Triangle(Vec3 a, Vec3 b, Vec3 c) {}
    record Ray(Vec3 from, Vec3 to, boolean hit) {}
    private static final int[] FACES = {0,1,3,0,3,2, 4,6,7,4,7,5, 0,4,5,0,5,1,
            2,3,7,2,7,6, 0,2,6,0,6,4, 1,5,7,1,7,3};

    static List<Triangle> surface(LivingEntity target, float anchor, List<ModelBox> boxes) {
        List<Triangle> out = new ArrayList<>();
        float turn = -(float) Math.toRadians(net.minecraft.util.Mth.wrapDegrees(target.yBodyRot - anchor));
        for (ModelBox box : boxes) {
            if (box.mesh() == 0) {
                Vec3[] corners = new Vec3[8];
                for (int i = 0; i < 8; i++) corners[i] = point(target, box, turn, i & 1, (i >> 1) & 1, (i >> 2) & 1);
                for (int i = 0; i < FACES.length; i += 3)
                    out.add(new Triangle(corners[FACES[i]], corners[FACES[i + 1]], corners[FACES[i + 2]]));
            } else {
                float[] data = ModelMesh.get(box.mesh()).triangles();
                for (int i = 0; i < data.length; i += 9)
                    out.add(new Triangle(point(target, box, turn, data[i], data[i+1], data[i+2]),
                            point(target, box, turn, data[i+3], data[i+4], data[i+5]),
                            point(target, box, turn, data[i+6], data[i+7], data[i+8])));
            }
        }
        return out;
    }

    private static Vec3 point(LivingEntity target, ModelBox b, float turn, double x, double y, double z) {
        return new Vec3(b.x() + x*b.ux() + y*b.vx() + z*b.wx(), b.y() + x*b.uy() + y*b.vy() + z*b.wy(),
                b.z() + x*b.uz() + y*b.vz() + z*b.wz()).yRot(turn).add(target.position());
    }

    static List<Ray> grid(List<Triangle> surface) {
        double minX=Double.POSITIVE_INFINITY, minY=minX, minZ=minX;
        double maxX=Double.NEGATIVE_INFINITY, maxY=maxX, maxZ=maxX;
        for (Triangle t : surface) for (Vec3 p : List.of(t.a(),t.b(),t.c())) {
            minX=Math.min(minX,p.x); minY=Math.min(minY,p.y); minZ=Math.min(minZ,p.z);
            maxX=Math.max(maxX,p.x); maxY=Math.max(maxY,p.y); maxZ=Math.max(maxZ,p.z);
        }
        List<Ray> out = new ArrayList<>();
        for (int x=0; x<17; x++) for (int y=0; y<19; y++) {
            Vec3 a = new Vec3(minX-.2+(maxX-minX+.4)*(x+.37)/17, minY-.2+(maxY-minY+.4)*(y+.41)/19, minZ-2);
            Vec3 b = new Vec3(a.x,a.y,maxZ+2);
            out.add(new Ray(a,b,hit(surface,a,b)));
        }
        return out;
    }

    static boolean hit(List<Triangle> surface, Vec3 a, Vec3 b) {
        Vec3 dir = b.subtract(a);
        for (Triangle t : surface) {
            Vec3 e1=t.b().subtract(t.a()), e2=t.c().subtract(t.a()), p=dir.cross(e2);
            double det=e1.dot(p);
            if (Math.abs(det)<1e-10) continue;
            Vec3 s=a.subtract(t.a());
            double u=s.dot(p)/det;
            if (u<0 || u>1) continue;
            Vec3 q=s.cross(e1);
            double v=dir.dot(q)/det, distance=e2.dot(q)/det;
            if (v>=0 && u+v<=1 && distance>=0 && distance<=1) return true;
        }
        return false;
    }
}
