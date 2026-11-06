package acup.gunfeel;

import java.util.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

// Возраст контура F3+B (-PcontourAge, в замере мобов): каждый кадр каждую
// точку контура моба (три на отрезок, в системе его тела) кладём на рёбра
// модели этого кадра, затем прошлого и тех, что моложе ModelContours.LAG.
// Не легла ни на одну — контур старше правила: «прошлый кадр или 1/60 с».
// Время кадра — ModelContours.callAt(), те же часы, по которым контур
// держит свой возраст. Проверка дорогая: время кадра в этом прогоне не мерить.
final class ContourAge {
    static final boolean ENABLED=Boolean.getBoolean("ntmgunfeel.contourAge");
    private static final double UNDER=1e-3;
    private record Past(long at,List<ModelBox> boxes) {}
    private static final Map<Entity,ArrayDeque<Past>> PAST=new WeakHashMap<>();
    // счёт фазы: точек, на модели кадра, на прошлой или моложе порога, старше
    static long points,now,earlier,older;
    private ContourAge() {}

    static void reset() { points=now=earlier=older=0; }
    static void clear() { PAST.clear(); reset(); }

    // после ModelContours.outlines в ModelHitDebug: моб, его части этого кадра и его контур
    static void check(Entity entity,List<ModelBox> boxes,ModelContours.Outline outline) {
        if(boxes.isEmpty()) return;
        long at=ModelContours.callAt();
        var past=PAST.computeIfAbsent(entity,k -> new ArrayDeque<>());
        var current=edges(boxes);
        var allowed=new ArrayList<Index>();
        boolean last=true;
        for(var it=past.descendingIterator();it.hasNext();last=false) {
            var p=it.next();
            if(!last && at-p.at()>ModelContours.LAG) break;
            if(p.boxes().size()==boxes.size()) allowed.add(edges(p.boxes()));
        }
        for(var edge:outline.edges())
            for(int i=0;i+1<edge.length;i+=2)
                for(double f:new double[]{.25,.5,.75}) {
                    Vec3 p=edge[i].lerp(edge[i+1],f);
                    points++;
                    if(current.on(p)) now++;
                    else if(allowed.stream().anyMatch(e -> e.on(p))) earlier++;
                    else older++;
                }
        past.addLast(new Past(at,List.copyOf(boxes)));
        while(past.size()>1 && at-past.getFirst().at()>2*ModelContours.LAG) past.removeFirst();
    }

    // рёбра частей в системе тела (ModelContours.Frame первой части, в ней же лежит контур): кубы по 12 рёбер,
    // меш — его собственный контур
    private static Index edges(List<ModelBox> boxes) {
        var frame=ModelContours.Frame.of(boxes.getFirst());
        var out=new ArrayList<Vec3[]>();
        for(var box:boxes) {
            if(box.mesh()!=0) {
                for(var strip:ModelContours.edges(List.of(box)))
                    for(int i=0;i+1<strip.length;i+=2) out.add(new Vec3[]{frame.local(strip[i]),frame.local(strip[i+1])});
                continue;
            }
            for(int corner=0;corner<8;corner++)
                for(int axis=1;axis<=4;axis<<=1)
                    if((corner&axis)==0) {
                        int other=corner|axis;
                        out.add(new Vec3[]{frame.local(ModelContours.point(box,corner&1,corner>>1&1,corner>>2&1)),
                                frame.local(ModelContours.point(box,other&1,other>>1&1,other>>2&1))});
                    }
        }
        return new Index(out);
    }

    // отрезки по ячейкам сетки (ячейка — две средние длины, от 4 UNDER до четверти блока); отрезок кладётся
    // кусками длиной в ячейку, каждый в ячейки своих границ, расширенных на UNDER
    private static final class Index {
        private final double cell;
        private final Map<Long,List<Vec3[]>> cells=new HashMap<>();
        Index(List<Vec3[]> segments) {
            double sum=0;
            for(var s:segments) sum+=s[0].distanceTo(s[1]);
            cell=Math.max(4*UNDER,Math.min(.25,segments.isEmpty()?.25:2*sum/segments.size()));
            for(var s:segments) {
                int pieces=Math.max(1,(int)Math.ceil(s[0].distanceTo(s[1])/cell));
                for(int k=0;k<pieces;k++) {
                    Vec3 a=s[0].lerp(s[1],(double)k/pieces),b=s[0].lerp(s[1],(double)(k+1)/pieces);
                    for(int x=cell(Math.min(a.x,b.x)-UNDER);x<=cell(Math.max(a.x,b.x)+UNDER);x++)
                        for(int y=cell(Math.min(a.y,b.y)-UNDER);y<=cell(Math.max(a.y,b.y)+UNDER);y++)
                            for(int z=cell(Math.min(a.z,b.z)-UNDER);z<=cell(Math.max(a.z,b.z)+UNDER);z++) {
                                var list=cells.computeIfAbsent(key(x,y,z),key -> new ArrayList<>());
                                if(list.isEmpty() || list.getLast()!=s) list.add(s);
                            }
                }
            }
        }
        private int cell(double v) { return (int)Math.floor(v/cell); }
        private static long key(int x,int y,int z) { return ((long)x&0x1FFFFF)<<42|((long)y&0x1FFFFF)<<21|(long)z&0x1FFFFF; }
        // в пределах UNDER от одного из отрезков
        boolean on(Vec3 p) {
            var list=cells.get(key(cell(p.x),cell(p.y),cell(p.z)));
            if(list==null) return false;
            for(var s:list) {
                Vec3 d=s[1].subtract(s[0]);
                double length=d.lengthSqr();
                double t=length<1e-18?0:Math.max(0,Math.min(1,p.subtract(s[0]).dot(d)/length));
                if(s[0].add(d.scale(t)).distanceToSqr(p)<UNDER*UNDER) return true;
            }
            return false;
        }
    }
}
