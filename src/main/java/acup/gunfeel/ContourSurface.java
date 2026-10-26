package acup.gunfeel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.world.phys.Vec3;

// Объединение обводки разных частей. Физические коробки и треугольники
// не меняются. Малый допуск убирает служебный сдвиг от z-fighting (например,
// 0.001 пикселя у ног лисы), но не склеивает настоящие щели между деталями.
final class ContourSurface {
    private static final double EPS=1.0/8192,STEP=EPS*4;
    private record Triangle(Vec3 a,Vec3 b,Vec3 c,Vec3 normal) {}
    private static final class Plane {
        final Vec3 normal;
        final List<Triangle> triangles=new ArrayList<>();
        Plane(Vec3 normal) { this.normal=normal; }
    }
    private record Point(long x,long y,long z) implements Comparable<Point> {
        Point(Vec3 p) { this(Math.round(p.x*1e6),Math.round(p.y*1e6),Math.round(p.z*1e6)); }
        public int compareTo(Point p) {
            int c=Long.compare(x,p.x); if(c==0) c=Long.compare(y,p.y); return c==0?Long.compare(z,p.z):c;
        }
    }
    private record Edge(Point a,Point b) {}
    private final List<ModelBox> boxes;
    private final List<Triangle> triangles=new ArrayList<>();
    private final Set<Edge> seen=new HashSet<>();
    ContourSurface(List<ModelBox> boxes) {
        this.boxes=boxes;
        for(var box:boxes) {
            float[] data=box.mesh()==0?CUBE:ModelMesh.get(box.mesh()).triangles();
            for(int i=0;i<data.length;i+=9) {
                var a=ModelContours.point(box,data[i],data[i+1],data[i+2]);
                var b=ModelContours.point(box,data[i+3],data[i+4],data[i+5]);
                var c=ModelContours.point(box,data[i+6],data[i+7],data[i+8]);
                var normal=b.subtract(a).cross(c.subtract(a));
                if(normal.lengthSqr()>1e-20) triangles.add(new Triangle(a,b,c,normal.normalize()));
            }
        }
    }

    void append(List<Vec3> output,Vec3 a,Vec3 b,List<double[]> visible) {
        Vec3 direction=b.subtract(a); double lengthSquared=direction.lengthSqr();
        if(lengthSquared<1e-16 || visible.isEmpty()) return;
        var cuts=new TreeSet<Double>();
        for(var span:visible) { cuts.add(span[0]); cuts.add(span[1]); }
        for(var box:boxes) {
            var span=box.carrierSpan(a,b);
            if(span!=null) { cuts.add(span[0]); cuts.add(span[1]); }
        }
        List<Plane> planes=new ArrayList<>();
        for(var t:triangles) {
            if(!overlaps(t,a,b) || Math.abs(a.subtract(t.a()).dot(t.normal()))>EPS
                    || Math.abs(b.subtract(t.a()).dot(t.normal()))>EPS) continue;
            Plane plane=null;
            for(var p:planes) if(Math.abs(p.normal.dot(t.normal()))>1-1e-9) { plane=p; break; }
            if(plane==null) { plane=new Plane(t.normal()); planes.add(plane); }
            plane.triangles.add(t);
            var perpendicular=plane.normal.cross(direction).normalize();
            cutTriangle(cuts,t,a,direction,lengthSquared,perpendicular);
        }
        Double previous=null;
        for(double next:cuts) {
            if(previous==null) { previous=next; continue; }
            double start=previous; previous=next;
            if(next-start<1e-7) continue;
            double mid=(start+next)/2;
            if(visible.stream().noneMatch(s -> mid>=s[0] && mid<=s[1])) continue;
            var point=a.add(direction.scale(mid)); boolean seam=false;
            for(var plane:planes) {
                var side=plane.normal.cross(direction).normalize().scale(STEP);
                var left=point.add(side); var right=point.subtract(side);
                if(covered(plane,left) && covered(plane,right)) {
                    int l=exteriorSide(left,plane.normal),r=exteriorSide(right,plane.normal);
                    if(l!=0 && r!=0 && (l==r || l==2 || r==2)) { seam=true; break; }
                }
            }
            if(seam) continue;
            Vec3 from=a.add(direction.scale(start)),to=a.add(direction.scale(next));
            Point p=new Point(from),q=new Point(to);
            Edge key=p.compareTo(q)<0?new Edge(p,q):new Edge(q,p);
            if(!p.equals(q) && seen.add(key)) { output.add(from); output.add(to); }
        }
    }

    private int exteriorSide(Vec3 p,Vec3 normal) {
        boolean positive=inside(p.add(normal.scale(STEP))),negative=inside(p.subtract(normal.scale(STEP)));
        // При смене стороны занятого объёма это настоящий угол, а не шов.
        if(positive && negative) return 0;
        return positive?-1:negative?1:2;
    }
    private boolean inside(Vec3 point) {
        for(var box:boxes) if(box.mesh()==0 && box.interiorSpan(point,point)!=null) return true;
        return false;
    }
    private static boolean overlaps(Triangle t,Vec3 a,Vec3 b) {
        return Math.max(a.x,b.x)+EPS>=Math.min(t.a.x,Math.min(t.b.x,t.c.x)) && Math.min(a.x,b.x)-EPS<=Math.max(t.a.x,Math.max(t.b.x,t.c.x))
                && Math.max(a.y,b.y)+EPS>=Math.min(t.a.y,Math.min(t.b.y,t.c.y)) && Math.min(a.y,b.y)-EPS<=Math.max(t.a.y,Math.max(t.b.y,t.c.y))
                && Math.max(a.z,b.z)+EPS>=Math.min(t.a.z,Math.min(t.b.z,t.c.z)) && Math.min(a.z,b.z)-EPS<=Math.max(t.a.z,Math.max(t.b.z,t.c.z));
    }
    private static void cutTriangle(TreeSet<Double> cuts,Triangle triangle,Vec3 a,Vec3 d,double lengthSquared,Vec3 side) {
        Vec3[] p={triangle.a,triangle.b,triangle.c};
        double[] t=new double[3],s=new double[3];
        for(int i=0;i<3;i++) { var offset=p[i].subtract(a); t[i]=offset.dot(d)/lengthSquared; s[i]=offset.dot(side); }
        for(int i=0;i<3;i++) {
            int j=(i+1)%3;
            if(Math.abs(s[i])<=EPS) cut(cuts,t[i]);
            if(s[i]*s[j]<0) cut(cuts,t[i]+(t[j]-t[i])*s[i]/(s[i]-s[j]));
        }
    }
    private static void cut(TreeSet<Double> cuts,double t) { if(t>0 && t<1) cuts.add(t); }
    private static boolean covered(Plane plane,Vec3 p) {
        for(var t:plane.triangles) {
            if(!pointBounds(t,p)) continue;
            Vec3[] vertices={t.a,t.b,t.c}; boolean positive=true,negative=true;
            for(int i=0;i<3;i++) {
                var edge=vertices[(i+1)%3].subtract(vertices[i]);
                double value=edge.cross(p.subtract(vertices[i])).dot(t.normal);
                double tolerance=EPS*edge.length();
                positive&=value>=-tolerance; negative&=value<=tolerance;
            }
            if(positive || negative) return true;
        }
        return false;
    }
    private static boolean pointBounds(Triangle t,Vec3 p) {
        return p.x+EPS>=Math.min(t.a.x,Math.min(t.b.x,t.c.x)) && p.x-EPS<=Math.max(t.a.x,Math.max(t.b.x,t.c.x))
                && p.y+EPS>=Math.min(t.a.y,Math.min(t.b.y,t.c.y)) && p.y-EPS<=Math.max(t.a.y,Math.max(t.b.y,t.c.y))
                && p.z+EPS>=Math.min(t.a.z,Math.min(t.b.z,t.c.z)) && p.z-EPS<=Math.max(t.a.z,Math.max(t.b.z,t.c.z));
    }
    private static final float[] CUBE=cube();
    private static float[] cube() {
        float[] data=new float[108]; int at=0;
        for(int axis=0;axis<3;axis++) for(int side=0;side<2;side++) for(int corner:new int[]{0,1,3,0,3,2}) {
            float[] p=new float[3]; p[axis]=side; p[(axis+1)%3]=corner&1; p[(axis+2)%3]=(corner>>1)&1;
            for(float value:p) data[at++]=value;
        }
        return data;
    }
}
