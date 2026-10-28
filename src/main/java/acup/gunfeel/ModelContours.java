package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;

// Обводка объединения частей: рёбра, спрятанные внутри соседней детали,
// не являются внешним контуром. Геометрия попаданий от этого не меняется.
public final class ModelContours {
    private ModelContours() {}
    private static final float[] BOX_EDGES=boxEdges();
    private record Cached(List<ModelBox> shape,List<Vec3[]> edges) {}
    private static final java.util.ArrayDeque<Cached> CACHE=new java.util.ArrayDeque<>();
    private static long builds;
    static synchronized long builds() { return builds; }
    record Frame(Vec3 origin,Vec3 u,Vec3 v,Vec3 w) {
        Vec3 direction(Vec3 p) { return new Vec3(p.dot(u),p.dot(v),p.dot(w)); }
        Vec3 local(Vec3 p) { return direction(p.subtract(origin)); }
        Vec3 world(Vec3 p) {
            if(MobBenchMetrics.LEGACY) return origin.add(u.scale(p.x)).add(v.scale(p.y)).add(w.scale(p.z));
            return new Vec3(origin.x+u.x*p.x+v.x*p.y+w.x*p.z,origin.y+u.y*p.x+v.y*p.y+w.y*p.z,
                    origin.z+u.z*p.x+v.z*p.y+w.z*p.z);
        }
        static Frame of(ModelBox b) {
            Vec3 u=unit(new Vec3(b.ux(),b.uy(),b.uz()));
            Vec3 v=new Vec3(b.vx(),b.vy(),b.vz()); v=unit(v.subtract(u.scale(u.dot(v))));
            Vec3 w=u.cross(v);
            if(w.dot(new Vec3(b.wx(),b.wy(),b.wz()))<0) w=w.scale(-1);
            return new Frame(new Vec3(b.x(),b.y(),b.z()),u,v,w);
        }
        private static Vec3 unit(Vec3 p) { return p.scale(1/Math.sqrt(p.lengthSqr())); }
        ModelBox local(ModelBox b) {
            Vec3 p=local(new Vec3(b.x(),b.y(),b.z())),a=direction(new Vec3(b.ux(),b.uy(),b.uz())),
                    c=direction(new Vec3(b.vx(),b.vy(),b.vz())),d=direction(new Vec3(b.wx(),b.wy(),b.wz()));
            return new ModelBox(b.part(),p.x,p.y,p.z,a.x,a.y,a.z,c.x,c.y,c.z,d.x,d.y,d.z,b.mesh());
        }
    }
    record Outline(Frame frame,List<Vec3[]> edges) {}
    public static synchronized void clear() { CACHE.clear(); }
    public static synchronized List<Vec3[]> edges(List<ModelBox> boxes) {
        if(boxes.isEmpty()) return List.of();
        var outline=outline(boxes);
        List<Vec3[]> result=new ArrayList<>(outline.edges().size());
        for(var strip:outline.edges()) {
            Vec3[] transformed=new Vec3[strip.length];
            for(int i=0;i<strip.length;i++) transformed[i]=outline.frame().world(strip[i]);
            result.add(transformed);
        }
        return result;
    }
    static synchronized Outline outline(List<ModelBox> boxes) {
        // Общее перемещение и поворот не меняют топологию контура. Храним
        // его в ортонормированной системе первой детали, без масштаба:
        // изменение размеров или относительной позы всё ещё меняет ключ.
        var frame=Frame.of(boxes.getFirst());
        var shape=boxes.stream().map(frame::local).toList();
        Cached cached=null;
        for(var candidate:CACHE) if(same(candidate.shape(),shape)) { cached=candidate; break; }
        if(cached==null) { builds++; cached=new Cached(shape,List.copyOf(collect(shape))); }
        else CACHE.remove(cached);
        CACHE.addFirst(cached); if(CACHE.size()>16) CACHE.removeLast();
        return new Outline(frame,cached.edges());
    }
    private static boolean same(List<ModelBox> a,List<ModelBox> b) {
        if(a.size()!=b.size()) return false;
        // Матрицы GPU содержат float: обратный поворот даёт шум в несколько
        // миллионных блока. Допуск меньше допуска объединения поверхностей.
        final double tolerance=1./32768;
        for(int i=0;i<a.size();i++) {
            var x=a.get(i); var y=b.get(i);
            if(x.mesh()!=y.mesh() || !x.part().equals(y.part())) return false;
            if(MobBenchMetrics.LEGACY) {
                double[] p=x.numbers(),q=y.numbers();
                for(int n=0;n<p.length;n++) if(Math.abs(p[n]-q[n])>tolerance) return false;
            } else if(Math.abs(x.x()-y.x())>tolerance || Math.abs(x.y()-y.y())>tolerance || Math.abs(x.z()-y.z())>tolerance
                    || Math.abs(x.ux()-y.ux())>tolerance || Math.abs(x.uy()-y.uy())>tolerance || Math.abs(x.uz()-y.uz())>tolerance
                    || Math.abs(x.vx()-y.vx())>tolerance || Math.abs(x.vy()-y.vy())>tolerance || Math.abs(x.vz()-y.vz())>tolerance
                    || Math.abs(x.wx()-y.wx())>tolerance || Math.abs(x.wy()-y.wy())>tolerance || Math.abs(x.wz()-y.wz())>tolerance) return false;
        }
        return true;
    }
    private static List<Vec3[]> collect(List<ModelBox> boxes) {
        List<Vec3[]> out=new ArrayList<>();
        var surface=new ContourSurface(boxes);
        for(var box:boxes) {
            // Вложенная внутренняя деталь (например, внутренний слой головы)
            // не добавляет второй каркас под внешней частью того же узла.
            if(boxes.stream().anyMatch(parent -> parent!=box && box.part().startsWith(parent.part()+"/") && parent.encloses(box))) continue;
            float[] edges=box.mesh()==0?BOX_EDGES:ModelMesh.get(box.mesh()).edges();
            List<Vec3> visible=new ArrayList<>();
            for(int i=0;i<edges.length;i+=6) {
                Vec3 a=point(box,edges[i],edges[i+1],edges[i+2]),b=point(box,edges[i+3],edges[i+4],edges[i+5]);
                List<double[]> intervals=new ArrayList<>(); intervals.add(new double[]{0,1});
                for(var other:boxes) {
                    if(other==box || other.mesh()!=0) continue;
                    var hidden=other.interiorSpan(a,b);
                    if(hidden==null) continue;
                    List<double[]> next=new ArrayList<>();
                    for(var span:intervals) {
                        if(hidden[1]<=span[0] || hidden[0]>=span[1]) next.add(span);
                        else {
                            if(hidden[0]>span[0]) next.add(new double[]{span[0],hidden[0]});
                            if(hidden[1]<span[1]) next.add(new double[]{hidden[1],span[1]});
                        }
                    }
                    intervals=next;
                    if(intervals.isEmpty()) break;
                }
                surface.append(visible,a,b,intervals,box);
            }
            if(!visible.isEmpty()) out.add(visible.toArray(Vec3[]::new));
        }
        return out;
    }
    static Vec3 point(ModelBox b,double x,double y,double z) {
        return new Vec3(b.x()+x*b.ux()+y*b.vx()+z*b.wx(),b.y()+x*b.uy()+y*b.vy()+z*b.wy(),b.z()+x*b.uz()+y*b.vz()+z*b.wz());
    }
    private static float[] boxEdges() {
        float[] out=new float[72]; int at=0;
        for(int i=0;i<8;i++) for(int bit=1;bit<=4;bit<<=1) if((i&bit)==0) for(int p:new int[]{i,i|bit}) {
            out[at++]=(p&1)==0?0:1; out[at++]=(p&2)==0?0:1; out[at++]=(p&4)==0?0:1;
        }
        return out;
    }
}
