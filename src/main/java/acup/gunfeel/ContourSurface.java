package acup.gunfeel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.world.phys.Vec3;

// Объединение обводки разных частей. Физические коробки и треугольники
// не меняются. Малый допуск убирает служебный сдвиг от z-fighting (например,
// 0.001 пикселя у ног лисы), но не склеивает настоящие щели между деталями.
// Арифметика повторяет Vec3 и ModelBox операция в операцию: результат тот же
// до бита, но без временных векторов и массивов на каждое ребро.
final class ContourSurface {
    private static final double EPS=1.0/8192,STEP=EPS*4;
    private record Triangle(Vec3 a,Vec3 b,Vec3 c,Vec3 normal,ModelBox owner,Bounds bounds) {}
    private record Bounds(double x0,double y0,double z0,double x1,double y1,double z1) {
        static Bounds of(Vec3 a,Vec3 b,Vec3 c) {
            return new Bounds(Math.min(a.x,Math.min(b.x,c.x)),Math.min(a.y,Math.min(b.y,c.y)),
                    Math.min(a.z,Math.min(b.z,c.z)),Math.max(a.x,Math.max(b.x,c.x)),
                    Math.max(a.y,Math.max(b.y,c.y)),Math.max(a.z,Math.max(b.z,c.z)));
        }
        boolean meets(Vec3 a,Vec3 b) {
            return x1+EPS>=Math.min(a.x,b.x) && x0-EPS<=Math.max(a.x,b.x)
                    && y1+EPS>=Math.min(a.y,b.y) && y0-EPS<=Math.max(a.y,b.y)
                    && z1+EPS>=Math.min(a.z,b.z) && z0-EPS<=Math.max(a.z,b.z);
        }
        double center(int axis) { return axis==0?x0+x1:axis==1?y0+y1:z0+z1; }
    }
    private record Node(Bounds bounds,Node left,Node right,int from,int to) {}
    private static final class Plane {
        final Vec3 normal,perpendicular,side;
        final List<Triangle> triangles=new ArrayList<>();
        Plane(Vec3 normal,Vec3 direction) {
            this.normal=normal; perpendicular=normal.cross(direction).normalize(); side=perpendicular.scale(STEP);
        }
    }
    // Аффинная система коробки: те же кофакторы и определитель, что в ModelBox.
    private static final class Solid {
        final double x,y,z,det,r00,r01,r02,r10,r11,r12,r20,r21,r22;
        final boolean solid;
        final double x0,y0,z0,x1,y1,z1;
        Solid(ModelBox b) {
            x=b.x(); y=b.y(); z=b.z(); solid=b.mesh()==0;
            double ux=b.ux(),uy=b.uy(),uz=b.uz(),vx=b.vx(),vy=b.vy(),vz=b.vz(),wx=b.wx(),wy=b.wy(),wz=b.wz();
            r00=vy*wz-vz*wy; r01=vz*wx-vx*wz; r02=vx*wy-vy*wx;
            r10=wy*uz-wz*uy; r11=wz*ux-wx*uz; r12=wx*uy-wy*ux;
            r20=uy*vz-uz*vy; r21=uz*vx-ux*vz; r22=ux*vy-uy*vx;
            det=ux*r00+uy*r01+uz*r02;
            // Запас много больше ошибки округления локальных координат. NaN
            // оставляет границы NaN, и проверка тогда ничего не отбрасывает.
            double lx=x,ly=y,lz=z,hx=x,hy=y,hz=z;
            for(int i=1;i<8;i++) {
                double px=x+((i&1)==0?0:ux)+((i&2)==0?0:vx)+((i&4)==0?0:wx);
                double py=y+((i&1)==0?0:uy)+((i&2)==0?0:vy)+((i&4)==0?0:wy);
                double pz=z+((i&1)==0?0:uz)+((i&2)==0?0:vz)+((i&4)==0?0:wz);
                lx=Math.min(lx,px); ly=Math.min(ly,py); lz=Math.min(lz,pz);
                hx=Math.max(hx,px); hy=Math.max(hy,py); hz=Math.max(hz,pz);
            }
            double margin=1e-6*(1+Math.max(Math.max(Math.abs(lx),Math.abs(hx)),Math.max(Math.max(Math.abs(ly),Math.abs(hy)),Math.max(Math.abs(lz),Math.abs(hz)))));
            x0=lx-margin; y0=ly-margin; z0=lz-margin; x1=hx+margin; y1=hy+margin; z1=hz+margin;
        }
        // ModelBox.span без массивов: результат в out, false вместо null.
        boolean span(double fx,double fy,double fz,double tx,double ty,double tz,double min,double max,double[] out) {
            if(Math.max(fx,tx)<x0 || Math.min(fx,tx)>x1 || Math.max(fy,ty)<y0 || Math.min(fy,ty)>y1
                    || Math.max(fz,tz)<z0 || Math.min(fz,tz)>z1) return false;
            if(Math.abs(det)<1e-12) return false;
            double a=fx-x,b=fy-y,c=fz-z;
            double a0=(a*r00+b*r01+c*r02)/det,a1=(a*r10+b*r11+c*r12)/det,a2=(a*r20+b*r21+c*r22)/det;
            a=tx-x; b=ty-y; c=tz-z;
            double b0=(a*r00+b*r01+c*r02)/det,b1=(a*r10+b*r11+c*r12)/det,b2=(a*r20+b*r21+c*r22)/det;
            double lo=0,hi=1;
            for(int i=0;i<3;i++) {
                double from=i==0?a0:i==1?a1:a2,d=(i==0?b0:i==1?b1:b2)-from;
                if(Math.abs(d)<1e-12) { if(from<min || from>max) return false; }
                else {
                    double p=(min-from)/d,q=(max-from)/d;
                    lo=Math.max(lo,Math.min(p,q)); hi=Math.min(hi,Math.max(p,q));
                    if(lo>=hi) return false;
                }
            }
            out[0]=lo; out[1]=hi; return true;
        }
    }
    private record Point(long x,long y,long z) implements Comparable<Point> {
        Point(Vec3 p) { this(Math.round(p.x*1e6),Math.round(p.y*1e6),Math.round(p.z*1e6)); }
        public int compareTo(Point p) {
            int c=Long.compare(x,p.x); if(c==0) c=Long.compare(y,p.y); return c==0?Long.compare(z,p.z):c;
        }
    }
    private record Edge(Point a,Point b) {}
    private final Solid[] solids;
    private final Node root;
    private final Triangle[] ordered;
    private long triangleChecks;
    long triangleChecks() { return triangleChecks; }
    private final Set<Edge> seen=new HashSet<>();
    private final double[] span=new double[2];
    private double[] cuts=new double[64];
    private int cutCount;
    ContourSurface(List<ModelBox> boxes) {
        solids=new Solid[boxes.size()];
        for(int i=0;i<solids.length;i++) solids[i]=new Solid(boxes.get(i));
        List<Triangle> triangles=new ArrayList<>();
        for(var box:boxes) {
            float[] data=box.mesh()==0?CUBE:ModelMesh.get(box.mesh()).triangles();
            for(int i=0;i<data.length;i+=9) {
                var a=ModelContours.point(box,data[i],data[i+1],data[i+2]);
                var b=ModelContours.point(box,data[i+3],data[i+4],data[i+5]);
                var c=ModelContours.point(box,data[i+6],data[i+7],data[i+8]);
                var normal=b.subtract(a).cross(c.subtract(a));
                if(normal.lengthSqr()>1e-20) triangles.add(new Triangle(a,b,c,normal.normalize(),box,Bounds.of(a,b,c)));
            }
        }
        ordered=triangles.toArray(Triangle[]::new);
        root=build(0,ordered.length);
    }
    private Node build(int from,int to) {
        if(from==to) return null;
        var first=ordered[from].bounds;
        double x0=first.x0,y0=first.y0,z0=first.z0,x1=first.x1,y1=first.y1,z1=first.z1;
        for(int i=from+1;i<to;i++) {
            var t=ordered[i].bounds;
            x0=Math.min(x0,t.x0); y0=Math.min(y0,t.y0); z0=Math.min(z0,t.z0);
            x1=Math.max(x1,t.x1); y1=Math.max(y1,t.y1); z1=Math.max(z1,t.z1);
        }
        var b=new Bounds(x0,y0,z0,x1,y1,z1);
        if(to-from<=8) return new Node(b,null,null,from,to);
        int axis=b.y1-b.y0>b.x1-b.x0?1:0;
        if(b.z1-b.z0>(axis==0?b.x1-b.x0:b.y1-b.y0)) axis=2;
        final int split=axis;
        Arrays.sort(ordered,from,to,java.util.Comparator.comparingDouble(t -> t.bounds.center(split)));
        int mid=(from+to)/2;
        return new Node(b,build(from,mid),build(mid,to),from,to);
    }
    // Кандидаты обрабатываются сразу при обходе, в том же порядке, что
    // и прежний список: плоскости и разрезы получаются те же.
    private void visit(Node node,Vec3 a,Vec3 b,Vec3 direction,double lengthSquared,List<Plane> planes) {
        if(node==null || !node.bounds.meets(a,b)) return;
        if(node.left!=null) {
            visit(node.left,a,b,direction,lengthSquared,planes); visit(node.right,a,b,direction,lengthSquared,planes); return;
        }
        for(int i=node.from;i<node.to;i++) {
            triangleChecks++;
            var t=ordered[i];
            if(!t.bounds.meets(a,b) || !overlaps(t,a,b) || Math.abs(offsetDot(a,t.a,t.normal))>EPS
                    || Math.abs(offsetDot(b,t.a,t.normal))>EPS) continue;
            Plane plane=null;
            for(var p:planes) if(Math.abs(p.normal.dot(t.normal()))>1-1e-9) { plane=p; break; }
            if(plane==null) { plane=new Plane(t.normal(),direction); planes.add(plane); }
            plane.triangles.add(t);
            cutTriangle(t,a,direction,lengthSquared,plane.perpendicular);
        }
    }
    // То же, что boxes.get(index).interiorSpan(from,to).
    double[] interiorSpan(int index,Vec3 from,Vec3 to) {
        var solid=solids[index];
        if(!solid.solid || !solid.span(from.x,from.y,from.z,to.x,to.y,to.z,1e-6,1-1e-6,span)) return null;
        return new double[]{span[0],span[1]};
    }

    void append(List<Vec3> output,Vec3 a,Vec3 b,List<double[]> visible) {
        append(output,a,b,visible,null);
    }
    void append(List<Vec3> output,Vec3 a,Vec3 b,List<double[]> visible,ModelBox source) {
        Vec3 direction=b.subtract(a); double lengthSquared=direction.lengthSqr();
        if(lengthSquared<1e-16 || visible.isEmpty()) return;
        cutCount=0;
        for(var s:visible) { cut(s[0]); cut(s[1]); }
        for(var solid:solids) if(solid.span(a.x,a.y,a.z,b.x,b.y,b.z,0,1,span)) { cut(span[0]); cut(span[1]); }
        List<Plane> planes=new ArrayList<>(4);
        visit(root,a,b,direction,lengthSquared,planes);
        // Как TreeSet<Double>: порядок Double.compare, равные значения один раз.
        Arrays.sort(cuts,0,cutCount);
        for(int k=1;k<cutCount;k++) {
            double start=cuts[k-1],next=cuts[k];
            if(Double.compare(start,next)==0) continue;
            if(next-start<1e-7) continue;
            double mid=(start+next)/2;
            boolean shown=false;
            for(var s:visible) if(mid>=s[0] && mid<=s[1]) { shown=true; break; }
            if(!shown) continue;
            var point=a.add(direction.scale(mid)); boolean seam=false,sourceSeam=false,sourceCorner=false;
            double px=point.x,py=point.y,pz=point.z;
            for(var plane:planes) {
                var side=plane.side;
                double lx=px+side.x,ly=py+side.y,lz=pz+side.z,rx=px+-side.x,ry=py+-side.y,rz=pz+-side.z;
                boolean flatSeam=false;
                if(covered(plane,lx,ly,lz,null) && covered(plane,rx,ry,rz,null)) {
                    int l=exteriorSide(lx,ly,lz,plane.normal),r=exteriorSide(rx,ry,rz,plane.normal);
                    flatSeam=l!=0 && r!=0 && (l==r || l==2 || r==2);
                }
                if(flatSeam) seam=true;
                if(source!=null && source.mesh()!=0) {
                    boolean leftOwned=covered(plane,lx,ly,lz,source),rightOwned=covered(plane,rx,ry,rz,source);
                    if(leftOwned && rightOwned) sourceSeam=true;
                    else if(leftOwned || rightOwned) sourceCorner=true;
                }
            }
            // Собственный шов меша может одновременно быть его настоящим
            // углом. Шов, возникший между разными частями, по-прежнему убираем.
            if(sourceSeam && sourceCorner) seam=false;
            if(seam) continue;
            Vec3 from=a.add(direction.scale(start)),to=a.add(direction.scale(next));
            Point p=new Point(from),q=new Point(to);
            Edge key=p.compareTo(q)<0?new Edge(p,q):new Edge(q,p);
            if(!p.equals(q) && seen.add(key)) { output.add(from); output.add(to); }
        }
    }
    private void cut(double t) {
        if(cutCount==cuts.length) cuts=Arrays.copyOf(cuts,cutCount*2);
        cuts[cutCount++]=t;
    }

    // (p-origin)·normal так же, как p.subtract(origin).dot(normal).
    private static double offsetDot(Vec3 p,Vec3 origin,Vec3 normal) {
        return (p.x+-origin.x)*normal.x+(p.y+-origin.y)*normal.y+(p.z+-origin.z)*normal.z;
    }
    private int exteriorSide(double x,double y,double z,Vec3 normal) {
        double sx=normal.x*STEP,sy=normal.y*STEP,sz=normal.z*STEP;
        boolean positive=inside(x+sx,y+sy,z+sz),negative=inside(x+-sx,y+-sy,z+-sz);
        // При смене стороны занятого объёма это настоящий угол, а не шов.
        if(positive && negative) return 0;
        return positive?-1:negative?1:2;
    }
    private boolean inside(double x,double y,double z) {
        for(var solid:solids) if(solid.solid && solid.span(x,y,z,x,y,z,1e-6,1-1e-6,span)) return true;
        return false;
    }
    private static boolean overlaps(Triangle t,Vec3 a,Vec3 b) {
        var o=t.bounds;
        return Math.max(a.x,b.x)+EPS>=o.x0 && Math.min(a.x,b.x)-EPS<=o.x1
                && Math.max(a.y,b.y)+EPS>=o.y0 && Math.min(a.y,b.y)-EPS<=o.y1
                && Math.max(a.z,b.z)+EPS>=o.z0 && Math.min(a.z,b.z)-EPS<=o.z1;
    }
    private void cutTriangle(Triangle triangle,Vec3 a,Vec3 d,double lengthSquared,Vec3 side) {
        double t0=0,t1=0,t2=0,s0=0,s1=0,s2=0;
        for(int i=0;i<3;i++) {
            var p=i==0?triangle.a:i==1?triangle.b:triangle.c;
            double ox=p.x+-a.x,oy=p.y+-a.y,oz=p.z+-a.z;
            double t=(ox*d.x+oy*d.y+oz*d.z)/lengthSquared,s=ox*side.x+oy*side.y+oz*side.z;
            if(i==0) { t0=t; s0=s; } else if(i==1) { t1=t; s1=s; } else { t2=t; s2=s; }
        }
        edgeCut(t0,s0,t1,s1); edgeCut(t1,s1,t2,s2); edgeCut(t2,s2,t0,s0);
    }
    private void edgeCut(double ti,double si,double tj,double sj) {
        if(Math.abs(si)<=EPS) cutInside(ti);
        if(si*sj<0) cutInside(ti+(tj-ti)*si/(si-sj));
    }
    private void cutInside(double t) { if(t>0 && t<1) cut(t); }
    private static boolean covered(Plane plane,double x,double y,double z,ModelBox owner) {
        for(var t:plane.triangles) {
            if(owner!=null && t.owner()!=owner) continue;
            var o=t.bounds;
            if(!(x+EPS>=o.x0 && x-EPS<=o.x1 && y+EPS>=o.y0 && y-EPS<=o.y1 && z+EPS>=o.z0 && z-EPS<=o.z1)) continue;
            boolean positive=true,negative=true;
            for(int i=0;i<3 && (positive || negative);i++) {
                var v=i==0?t.a:i==1?t.b:t.c; var w=i==0?t.b:i==1?t.c:t.a;
                double ex=w.x+-v.x,ey=w.y+-v.y,ez=w.z+-v.z;
                double qx=x+-v.x,qy=y+-v.y,qz=z+-v.z;
                double value=(ey*qz-ez*qy)*t.normal.x+(ez*qx-ex*qz)*t.normal.y+(ex*qy-ey*qx)*t.normal.z;
                double tolerance=EPS*Math.sqrt(ex*ex+ey*ey+ez*ez);
                positive&=value>=-tolerance; negative&=value<=tolerance;
            }
            if(positive || negative) return true;
        }
        return false;
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
