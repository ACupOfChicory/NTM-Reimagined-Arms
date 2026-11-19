package acup.gunfeel;

import java.util.*;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/** Развёртка только соединённых поверхностей внутри размера следа. */
final class DecalSurface {
    private static final double EPS=1e-6;
    record Frame(Vector3d origin,Vector3d u,Vector3d v,Vector3d normal) {
        double x(Vector3d point){return new Vector3d(point).sub(origin).dot(u);}
        double y(Vector3d point){return new Vector3d(point).sub(origin).dot(v);}
        Frame across(Vector3d hinge,Vector3d nextNormal) {
            var rotation=new Quaterniond().rotateTo(normal.x,normal.y,normal.z,nextNormal.x,nextNormal.y,nextNormal.z);
            return new Frame(rotation.transform(new Vector3d(origin).sub(hinge)).add(hinge),
                    rotation.transform(new Vector3d(u)),rotation.transform(new Vector3d(v)),new Vector3d(nextNormal));
        }
    }
    static final class Face {
        final List<Vector3d> points;
        final Vector3d normal,center=new Vector3d();
        final boolean area;
        final double[] bounds={Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY};
        Face(List<Vector3d> points,Vector3d normal) {
            this.points=points;this.normal=normal;
            for(var point:points){center.add(point);for(int axis=0;axis<3;axis++){double value=point.get(axis);bounds[axis*2]=Math.min(bounds[axis*2],value);bounds[axis*2+1]=Math.max(bounds[axis*2+1],value);}}
            center.div(points.size());
            var sum=new Vector3d();var first=points.getFirst();
            for(int i=1;i+1<points.size();i++)sum.add(new Vector3d(points.get(i)).sub(first).cross(new Vector3d(points.get(i+1)).sub(first)));
            area=Double.isFinite(sum.lengthSquared()) && sum.lengthSquared()>1e-24;
        }
    }
    private DecalSurface() {}
    static Frame[] unfold(List<Face> faces,int seed,Frame initial,double half) {
        Frame[] frames=new Frame[faces.size()];if(seed<0 || !faces.get(seed).area)return frames;
        var index=faces.size()>128?new Index(faces,Math.max(.0625,half*.5)):null;
        var candidates=new BitSet(faces.size());
        frames[seed]=initial;var queue=new ArrayDeque<Integer>();queue.add(seed);
        while(!queue.isEmpty()) {
            int parent=queue.removeFirst();var source=faces.get(parent);
            candidates.clear();
            if(index==null)candidates.set(0,faces.size());else index.near(source,candidates);
            for(int child=candidates.nextSetBit(0);child>=0;child=candidates.nextSetBit(child+1)) {
                if(frames[child]!=null)continue;
                var target=faces.get(child);Vector3d hinge=contact(source,target);if(hinge==null)continue;
                Frame mapped=frames[parent].across(hinge,target.normal);
                if(!overlaps(target,mapped,half))continue;
                frames[child]=mapped;queue.addLast(child);
            }
        }
        return frames;
    }
    // Плотная модель не должна заставлять каждую грань проверять все остальные.
    private static final class Index {
        private record Cell(int x,int y,int z) {}
        final Map<Cell,BitSet> cells=new HashMap<>();
        final double size;
        Index(List<Face> faces,double size) {
            this.size=size;
            for(int i=0;i<faces.size();i++) {
                final int at=i;var face=faces.get(i);if(!face.area)continue;
                each(face,cell->cells.computeIfAbsent(cell,key->new BitSet()).set(at));
            }
        }
        void near(Face face,BitSet out){each(face,cell->{var found=cells.get(cell);if(found!=null)out.or(found);});}
        private void each(Face face,java.util.function.Consumer<Cell> consumer) {
            int x0=(int)Math.floor((face.bounds[0]-EPS)/size),x1=(int)Math.floor((face.bounds[1]+EPS)/size);
            int y0=(int)Math.floor((face.bounds[2]-EPS)/size),y1=(int)Math.floor((face.bounds[3]+EPS)/size);
            int z0=(int)Math.floor((face.bounds[4]-EPS)/size),z1=(int)Math.floor((face.bounds[5]+EPS)/size);
            for(int x=x0;x<=x1;x++)for(int y=y0;y<=y1;y++)for(int z=z0;z<=z1;z++)consumer.accept(new Cell(x,y,z));
        }
    }
    // SAT: многоугольник должен действительно заходить в квадрат развёртки, а не только в его AABB.
    private static boolean overlaps(Face face,Frame frame,double half) {
        double[][] p=new double[face.points.size()][2];
        for(int i=0;i<p.length;i++){p[i][0]=frame.x(face.points.get(i));p[i][1]=frame.y(face.points.get(i));}
        if(separated(p,1,0,half)||separated(p,0,1,half))return false;
        for(int i=0;i<p.length;i++){var a=p[i];var b=p[(i+1)%p.length];if(separated(p,a[1]-b[1],b[0]-a[0],half))return false;}
        return true;
    }
    private static boolean separated(double[][] points,double x,double y,double half) {
        double lo=Double.POSITIVE_INFINITY,hi=Double.NEGATIVE_INFINITY;
        for(var point:points){double value=point[0]*x+point[1]*y;lo=Math.min(lo,value);hi=Math.max(hi,value);}
        double radius=half*(Math.abs(x)+Math.abs(y));return lo>radius+EPS || hi<-radius-EPS;
    }
    private static Vector3d contact(Face a,Face b) {
        if(!a.area||!b.area)return null;
        for(int axis=0;axis<3;axis++)if(a.bounds[axis*2]>b.bounds[axis*2+1]+EPS || b.bounds[axis*2]>a.bounds[axis*2+1]+EPS)return null;
        double dot=a.normal.dot(b.normal);if(dot<-.999999)return null;
        var direction=new Vector3d(a.normal).cross(b.normal);double length=direction.lengthSquared();
        // Считаем около модели, чтобы большие мировые координаты не портили пересечение плоскостей.
        double da=a.normal.dot(new Vector3d(a.points.getFirst()).sub(a.center));
        double db=b.normal.dot(new Vector3d(b.points.getFirst()).sub(a.center));
        if(length<1e-12) {
            if(Math.abs(a.normal.dot(new Vector3d(b.points.getFirst()).sub(a.points.getFirst())))>EPS)return null;
            if(coplanarSeparated(a,b)||coplanarSeparated(b,a))return null;
            return new Vector3d(a.center); // параллельный перенос здесь не нужен: плоскости совпадают
        }
        var origin=new Vector3d(b.normal).cross(direction).mul(da)
                .add(new Vector3d(direction).cross(a.normal).mul(db)).div(length).add(a.center);
        direction.normalize();
        double[] first=interval(a,origin,direction),second=interval(b,origin,direction);
        if(first==null||second==null)return null;
        double lo=Math.max(first[0],second[0]),hi=Math.min(first[1],second[1]);
        if(hi-lo<EPS)return null; // касания в одной вершине недостаточно для переноса площади следа
        return new Vector3d(direction).mul((lo+hi)*.5).add(origin);
    }
    private static boolean coplanarSeparated(Face a,Face b) {
        for(int i=0;i<a.points.size();i++) {
            var point=a.points.get(i);var edge=new Vector3d(a.points.get((i+1)%a.points.size())).sub(point);
            var inward=new Vector3d(a.normal).cross(edge);double len=inward.length();if(len<EPS)continue;inward.div(len);
            if(inward.dot(new Vector3d(a.center).sub(point))<0)inward.negate();
            boolean outside=true;for(var other:b.points)if(inward.dot(new Vector3d(other).sub(point))>=-EPS){outside=false;break;}
            if(outside)return true;
        }
        return false;
    }
    private static double[] interval(Face face,Vector3d origin,Vector3d direction) {
        double lo=Double.NEGATIVE_INFINITY,hi=Double.POSITIVE_INFINITY;
        for(int i=0;i<face.points.size();i++) {
            var point=face.points.get(i);var edge=new Vector3d(face.points.get((i+1)%face.points.size())).sub(point);
            var inward=new Vector3d(face.normal).cross(edge);double len=inward.length();if(len<EPS)continue;inward.div(len);
            if(inward.dot(new Vector3d(face.center).sub(point))<0)inward.negate();
            double at=inward.dot(new Vector3d(origin).sub(point)),rate=inward.dot(direction);
            if(Math.abs(rate)<1e-12){if(at<-EPS)return null;continue;}
            double t=-at/rate;
            if(rate>0)lo=Math.max(lo,t);else hi=Math.min(hi,t);
            if(lo>hi+EPS)return null;
        }
        return new double[]{lo,hi};
    }
}
