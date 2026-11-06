package acup.gunfeel;

import java.util.List;
import net.minecraft.world.phys.Vec3;

/** Область головы — настоящая геометрия части, ограниченная плоскостями. Часть, которую без Flywheel сопоставили
 * коробкой вокруг её кубов (HeadProfiles.unite), — ещё и сами кубы (pieces): точка лежит хотя бы в одном из них, как
 * с Flywheel она лежит в сетке части. Пустой список — вся коробка. */
public record HeadZone(ModelBox box,List<Plane> planes,List<ModelBox> pieces) {
    public HeadZone { planes=List.copyOf(planes);pieces=List.copyOf(pieces); }
    public HeadZone(ModelBox box,List<Plane> planes) { this(box,planes,List.of()); }
    public record Plane(double x,double y,double z,double d) {
        public Plane {
            if(!Double.isFinite(x+y+z+d) || x*x+y*y+z*z<1e-16) throw new IllegalArgumentException("Неверная плоскость головы");
        }
        public boolean accepts(Vec3 p) { return x*p.x+y*p.y+z*p.z+d>=-1e-8; }
    }
    public boolean contains(Vec3 point) {
        if(planes.stream().anyMatch(plane->!plane.accepts(point)) || !box.containsPoint(point)) return false;
        if(pieces.isEmpty()) return true;
        for(var piece:pieces) if(piece.containsPoint(point)) return true;
        return false;
    }
    /** Коэффициенты плоскости заданы в аффинной системе самой части. */
    public static HeadZone local(ModelBox box,List<Plane> local) { return local(box,local,List.of()); }
    public static HeadZone local(ModelBox box,List<Plane> local,List<ModelBox> pieces) {
        var u=new Vec3(box.ux(),box.uy(),box.uz());var v=new Vec3(box.vx(),box.vy(),box.vz());var w=new Vec3(box.wx(),box.wy(),box.wz());
        double determinant=u.dot(v.cross(w));
        if(Math.abs(determinant)<1e-12) throw new IllegalArgumentException("Вырожденная часть головы");
        var origin=new Vec3(box.x(),box.y(),box.z());
        var world=local.stream().map(p->{
            var normal=v.cross(w).scale(p.x()).add(w.cross(u).scale(p.y())).add(u.cross(v).scale(p.z())).scale(1/determinant);
            return new Plane(normal.x,normal.y,normal.z,p.d()-normal.dot(origin));
        }).toList();
        return new HeadZone(box,world,pieces);
    }
    public static Plane localPlane(ModelBox box,Plane world) {
        return new Plane(world.x()*box.ux()+world.y()*box.uy()+world.z()*box.uz(),
                world.x()*box.vx()+world.y()*box.vy()+world.z()*box.vz(),
                world.x()*box.wx()+world.y()*box.wy()+world.z()*box.wz(),
                world.x()*box.x()+world.y()*box.y()+world.z()*box.z()+world.d());
    }
    public static boolean contains(List<HeadZone> zones,Vec3 point) {
        for(var zone:zones) if(zone.contains(point)) return true;
        return false;
    }
    public static boolean visible(List<ModelBox> body,Vec3 point,Vec3 normal,double reach) {
        for(int sign:new int[]{-1,1}) {
            var from=point.add(normal.scale(sign*reach));boolean blocked=false;
            for(var box:body) {
                double t=box.clip(from.x,from.y,from.z,point.x,point.y,point.z);
                if(t>=0 && t<1-1e-6) { blocked=true;break; }
            }
            if(!blocked) return true;
        }
        return false;
    }
}
