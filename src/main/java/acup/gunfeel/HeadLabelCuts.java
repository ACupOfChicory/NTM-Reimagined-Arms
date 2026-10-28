package acup.gunfeel;

import java.util.*;
import net.minecraft.world.phys.Vec3;

/** Полупространства разметки. Геометрия столкновений сущности не меняется. */
final class HeadLabelCuts {
    record Plane(double nx,double ny,double nz,double d) {
        Plane {
            if(!Double.isFinite(nx+ny+nz+d) || nx*nx+ny*ny+nz*nz<1e-16) throw new IllegalArgumentException("Неверная плоскость");
        }
        double distance(Vec3 point) { return nx*point.x+ny*point.y+nz*point.z+d; }
        Plane flip() { return new Plane(-nx,-ny,-nz,-d); }
    }
    private HeadLabelCuts() {}
    static List<Vec3[]> edges(List<ModelBox> boxes,Collection<String> tokens,Map<String,List<Plane>> cuts) {
        if(!tokens.isEmpty() && tokens.stream().allMatch(t->t.endsWith(":-1") && cuts.getOrDefault(t,List.of()).isEmpty())) {
            var selected=tokens.stream().map(t->boxes.get(Integer.parseInt(t.substring(0,t.indexOf(':'))))).toList();
            return ModelContours.edges(selected);
        }
        var result=new ArrayList<Vec3[]>();
        for(String token:tokens) {
            var planes=cuts.getOrDefault(token,List.of());
            var original=HeadLabelGeometry.edges(boxes,List.of(token));
            for(var strip:original) for(int i=0;i<strip.length;i+=2) add(result,strip[i],strip[i+1],planes);
            if(planes.isEmpty()) continue;
            String[] fields=token.split(":");var box=boxes.get(Integer.parseInt(fields[0]));
            int triangle=Integer.parseInt(fields[1]);var data=HeadLabelGeometry.triangles(box);
            for(int at=triangle<0?0:triangle*9;at<(triangle<0?data.length:(triangle+1)*9);at+=9) {
                var vertices=List.of(HeadLabelGeometry.point(box,data,at),HeadLabelGeometry.point(box,data,at+3),HeadLabelGeometry.point(box,data,at+6));
                for(var plane:planes) {
                    var crossing=new ArrayList<Vec3>();
                    for(int i=0;i<3;i++) {
                        var a=vertices.get(i);var b=vertices.get((i+1)%3);
                        double da=plane.distance(a),db=plane.distance(b);
                        if(Math.abs(da)<1e-8 && crossing.stream().noneMatch(p->p.distanceToSqr(a)<1e-16)) crossing.add(a);
                        if(da*db<0) crossing.add(a.add(b.subtract(a).scale(da/(da-db))));
                    }
                    if(crossing.size()==2) add(result,crossing.get(0),crossing.get(1),planes);
                }
            }
        }
        return result;
    }
    private static void add(List<Vec3[]> result,Vec3 a,Vec3 b,List<Plane> planes) {
        for(var plane:planes) {
            double da=plane.distance(a),db=plane.distance(b);
            if(da< -1e-8 && db< -1e-8) return;
            if(da<0 && db>=0) a=a.add(b.subtract(a).scale(da/(da-db)));
            else if(db<0 && da>=0) b=a.add(b.subtract(a).scale(da/(da-db)));
        }
        if(a.distanceToSqr(b)>1e-16) result.add(new Vec3[]{a,b});
    }
    static boolean contains(Vec3 point,List<Plane> planes) { return planes.stream().allMatch(p->p.distance(point)>=-1e-8); }
}
