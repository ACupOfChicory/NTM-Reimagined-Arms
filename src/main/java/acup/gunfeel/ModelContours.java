package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;

// Обводка объединения частей: рёбра, спрятанные внутри соседней детали,
// не являются внешним контуром. Геометрия попаданий от этого не меняется.
public final class ModelContours {
    private ModelContours() {}
    private static final float[] BOX_EDGES=boxEdges();
    // sum и alt — суммы чисел формы (простая и знакопеременная): дешёвый
    // отсев заведомо разных поз до поэлементного сравнения.
    private record Cached(List<ModelBox> shape,List<Vec3[]> edges,double sum,double alt) {}
    // Прежний кэш на 16 форм: только для замера LEGACY.
    private static final java.util.ArrayDeque<Cached> LEGACY_CACHE=new java.util.ArrayDeque<>();
    // Корзина — формы с одинаковым набором частей и мешей. Числа внутри
    // сверяются с допуском, поэтому в ключ они не входят.
    private static final int BUCKET=128,TOTAL=1024;
    private static final java.util.LinkedHashMap<Long,ArrayList<Cached>> CACHE=new java.util.LinkedHashMap<>(64,.75F,true);
    private static int cachedShapes;
    private static long builds;
    // Анимированный моб меняет позу каждый кадр. Кадр не ждёт новую форму:
    // пока она строится в фоне, показывается прежний контур этого моба
    // в текущей системе его тела. Конечности отстают не больше чем на кадр.
    private record Shown(long key,List<Vec3[]> edges) {}
    private static final class Flight {
        final Request request; final List<Object> owners=new ArrayList<>(2);
        Flight(Request request) { this.request=request; }
    }
    private static final java.util.Map<Object,Shown> SHOWN=new java.util.WeakHashMap<>();
    private static final java.util.Set<Object> WAITING=java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private static final List<Flight> FLIGHTS=new ArrayList<>();
    private static long generation;
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
    public static synchronized void clear() {
        LEGACY_CACHE.clear(); CACHE.clear(); cachedShapes=0;
        SHOWN.clear(); WAITING.clear(); FLIGHTS.clear(); generation++;
    }
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
    private record Request(Frame frame,List<ModelBox> shape,long key,double sum,double alt) {}
    static synchronized Outline outline(List<ModelBox> boxes) {
        var request=request(boxes);
        if(MobBenchMetrics.LEGACY) {
            var shape=request.shape();
            Cached cached=null;
            for(var candidate:LEGACY_CACHE) if(same(candidate.shape(),shape)) { cached=candidate; break; }
            if(cached==null) { builds++; cached=new Cached(shape,List.copyOf(collect(shape)),0,0); }
            else LEGACY_CACHE.remove(cached);
            LEGACY_CACHE.addFirst(cached); if(LEGACY_CACHE.size()>16) LEGACY_CACHE.removeLast();
            return new Outline(request.frame(),cached.edges());
        }
        var edges=lookup(request);
        if(edges==null) edges=store(request,List.copyOf(collect(request.shape())));
        return new Outline(request.frame(),edges);
    }
    // Контуры всех мобов кадра. Формы, которых нет в кэше, строятся
    // параллельно: сборка одной формы не зависит от других, результат тот же.
    // owners: владелец каждой формы (сущность) или null, если прежний контур
    // показывать нельзя.
    static synchronized List<Outline> outlines(List<List<ModelBox>> all,List<?> owners) {
        if(MobBenchMetrics.LEGACY) return all.stream().map(ModelContours::outline).toList();
        var out=new Outline[all.size()];
        var frames=new Frame[all.size()];
        var refs=new int[all.size()];
        List<Request> pending=new ArrayList<>();
        for(int i=0;i<out.length;i++) {
            var request=request(all.get(i));
            var owner=owners==null?null:owners.get(i);
            refs[i]=-1;
            // Одинаковая поза двух мобов строится один раз, как и прежде,
            // когда второй находил форму, только что добавленную первым.
            for(int p=pending.size()-1;p>=0 && refs[i]<0;p--)
                if(pending.get(p).key()==request.key() && near(pending.get(p).sum(),pending.get(p).alt(),request)
                        && same(pending.get(p).shape(),request.shape())) refs[i]=p;
            if(refs[i]>=0) { frames[i]=request.frame(); continue; }
            var edges=lookup(request);
            if(edges!=null) {
                out[i]=new Outline(request.frame(),edges);
                if(owner!=null) SHOWN.put(owner,new Shown(request.key(),edges));
                continue;
            }
            // Тот же набор частей уже показывался: прежний контур сейчас,
            // новая форма в фоне. Первый показ строится сразу, как раньше.
            var shown=owner==null?null:SHOWN.get(owner);
            if(shown!=null && shown.key()==request.key()) {
                out[i]=new Outline(request.frame(),shown.edges());
                if(WAITING.add(owner)) later(request,owner);
                continue;
            }
            refs[i]=pending.size(); frames[i]=request.frame(); pending.add(request);
        }
        List<List<Vec3[]>> built=pending.size()<2?pending.stream().map(r -> List.copyOf(collect(r.shape()))).toList()
                :pending.parallelStream().map(r -> List.copyOf(collect(r.shape()))).toList();
        List<List<Vec3[]>> stored=new ArrayList<>(built.size());
        for(int p=0;p<pending.size();p++) stored.add(store(pending.get(p),built.get(p)));
        for(int i=0;i<out.length;i++) if(out[i]==null) {
            out[i]=new Outline(frames[i],stored.get(refs[i]));
            var owner=owners==null?null:owners.get(i);
            if(owner!=null) SHOWN.put(owner,new Shown(pending.get(refs[i]).key(),stored.get(refs[i])));
        }
        return List.of(out);
    }
    // Одна фоновая сборка на позу: моб с той же позой ждёт уже начатую.
    private static void later(Request request,Object owner) {
        for(var flight:FLIGHTS)
            if(flight.request.key()==request.key() && near(flight.request.sum(),flight.request.alt(),request)
                    && same(flight.request.shape(),request.shape())) { flight.owners.add(owner); return; }
        var flight=new Flight(request); flight.owners.add(owner); FLIGHTS.add(flight);
        long started=generation;
        java.util.concurrent.ForkJoinPool.commonPool().execute(() -> {
            List<Vec3[]> edges=null;
            try { edges=List.copyOf(collect(request.shape())); }
            catch(RuntimeException ex) { ModelHitLog.event("client","contour_failed",null,ex::toString); }
            synchronized(ModelContours.class) {
                if(started!=generation) return;
                FLIGHTS.remove(flight);
                for(var waiting:flight.owners) WAITING.remove(waiting);
                if(edges==null) return;
                var stored=store(request,edges);
                for(var waiting:flight.owners) SHOWN.put(waiting,new Shown(request.key(),stored));
            }
        });
    }
    private static Request request(List<ModelBox> boxes) {
        // Общее перемещение и поворот не меняют топологию контура. Храним
        // его в ортонормированной системе первой детали, без масштаба:
        // изменение размеров или относительной позы всё ещё меняет ключ.
        var frame=Frame.of(boxes.getFirst());
        var local=new ModelBox[boxes.size()];
        for(int i=0;i<local.length;i++) local[i]=frame.local(boxes.get(i));
        var shape=List.of(local);
        long key=shape.size();
        for(var box:shape) key=(key*31+box.part().hashCode())*31+Long.hashCode(box.mesh());
        double sum=0,alt=0;
        for(var box:shape) {
            sum+=box.x()+box.y()+box.z()+box.ux()+box.uy()+box.uz()+box.vx()+box.vy()+box.vz()+box.wx()+box.wy()+box.wz();
            alt+=box.x()-box.y()+box.z()-box.ux()+box.uy()-box.uz()+box.vx()-box.vy()+box.vz()-box.wx()+box.wy()-box.wz();
        }
        return new Request(frame,shape,key,sum,alt);
    }
    // Попадание возвращает тот же список рёбер: по нему же GPU-кэш
    // ModelDebugLines находит готовый буфер.
    private static List<Vec3[]> lookup(Request request) {
        var bucket=CACHE.get(request.key());
        if(bucket==null) return null;
        for(int i=0;i<bucket.size();i++) {
            var candidate=bucket.get(i);
            if(!near(candidate.sum(),candidate.alt(),request) || !same(candidate.shape(),request.shape())) continue;
            if(i>0) { bucket.remove(i); bucket.addFirst(candidate); }
            return candidate.edges();
        }
        return null;
    }
    private static List<Vec3[]> store(Request request,List<Vec3[]> edges) {
        builds++;
        var bucket=CACHE.computeIfAbsent(request.key(),k -> new ArrayList<>());
        bucket.addFirst(new Cached(request.shape(),edges,request.sum(),request.alt())); cachedShapes++;
        if(bucket.size()>BUCKET) { bucket.removeLast(); cachedShapes--; }
        // Сначала уходят формы из давно не встречавшихся корзин.
        var iterator=CACHE.values().iterator();
        while(cachedShapes>TOTAL && iterator.hasNext()) {
            var eldest=iterator.next();
            if(eldest==bucket) continue;
            cachedShapes-=eldest.size(); iterator.remove();
        }
        return edges;
    }
    private static final double TOLERANCE=1./32768;
    // Необходимое условие same(): каждое из 12·n чисел отличается не больше
    // допуска, значит, и суммы — не больше 12·n·допуск (плюс округление).
    // Ответ same() не меняется, отсеиваются лишь заведомо разные позы.
    private static boolean near(double sum,double alt,Request request) {
        if(MobBenchMetrics.LEGACY) return true;
        double bound=12*request.shape().size()*TOLERANCE+1e-9;
        // Через отрицание, как в same(): NaN там не отсекает, не отсекает и здесь.
        return !(Math.abs(sum-request.sum())>bound) && !(Math.abs(alt-request.alt())>bound);
    }
    private static boolean same(List<ModelBox> a,List<ModelBox> b) {
        if(a.size()!=b.size()) return false;
        // Матрицы GPU содержат float: обратный поворот даёт шум в несколько
        // миллионных блока. Допуск меньше допуска объединения поверхностей.
        final double tolerance=TOLERANCE;
        if(MobBenchMetrics.LEGACY) {
            for(int i=0;i<a.size();i++) {
                var x=a.get(i); var y=b.get(i);
                if(x.mesh()!=y.mesh() || !x.part().equals(y.part())) return false;
                double[] p=x.numbers(),q=y.numbers();
                for(int n=0;n<p.length;n++) if(Math.abs(p[n]-q[n])>tolerance) return false;
            }
            return true;
        }
        // Сначала дешёвые числа и меши, имена частей — только у совпавшей формы.
        for(int i=0;i<a.size();i++) {
            var x=a.get(i); var y=b.get(i);
            if(x.mesh()!=y.mesh()) return false;
            if(Math.abs(x.x()-y.x())>tolerance || Math.abs(x.y()-y.y())>tolerance || Math.abs(x.z()-y.z())>tolerance
                    || Math.abs(x.ux()-y.ux())>tolerance || Math.abs(x.uy()-y.uy())>tolerance || Math.abs(x.uz()-y.uz())>tolerance
                    || Math.abs(x.vx()-y.vx())>tolerance || Math.abs(x.vy()-y.vy())>tolerance || Math.abs(x.vz()-y.vz())>tolerance
                    || Math.abs(x.wx()-y.wx())>tolerance || Math.abs(x.wy()-y.wy())>tolerance || Math.abs(x.wz()-y.wz())>tolerance) return false;
        }
        for(int i=0;i<a.size();i++) if(!a.get(i).part().equals(b.get(i).part())) return false;
        return true;
    }
    private static List<Vec3[]> collect(List<ModelBox> boxes) {
        List<Vec3[]> out=new ArrayList<>();
        var surface=new ContourSurface(boxes);
        var prefixes=new String[boxes.size()];
        for(int i=0;i<prefixes.length;i++) prefixes[i]=boxes.get(i).part()+"/";
        for(var box:boxes) {
            // Вложенная внутренняя деталь (например, внутренний слой головы)
            // не добавляет второй каркас под внешней частью того же узла.
            boolean nested=false;
            for(int i=0;i<prefixes.length && !nested;i++) {
                var parent=boxes.get(i);
                nested=parent!=box && box.part().startsWith(prefixes[i]) && parent.encloses(box);
            }
            if(nested) continue;
            float[] edges=box.mesh()==0?BOX_EDGES:ModelMesh.get(box.mesh()).edges();
            List<Vec3> visible=new ArrayList<>();
            for(int i=0;i<edges.length;i+=6) {
                Vec3 a=point(box,edges[i],edges[i+1],edges[i+2]),b=point(box,edges[i+3],edges[i+4],edges[i+5]);
                List<double[]> intervals=new ArrayList<>(); intervals.add(new double[]{0,1});
                for(int o=0;o<boxes.size();o++) {
                    var other=boxes.get(o);
                    if(other==box || other.mesh()!=0) continue;
                    var hidden=surface.interiorSpan(o,a,b);
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
