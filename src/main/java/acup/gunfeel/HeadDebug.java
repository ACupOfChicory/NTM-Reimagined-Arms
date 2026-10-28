package acup.gunfeel;

import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Контур среза кэшируется в координатах части; анимация меняет лишь матрицу. */
final class HeadDebug {
    private record Key(long mesh,List<HeadZone.Plane> planes) {}
    private static final LinkedHashMap<Key,List<Vec3[]>> CACHE=new LinkedHashMap<>(32,.75F,true);
    private HeadDebug() {}
    static void add(List<ModelDebugRender.Shape> shapes,int owner,Entity entity) {
        boolean manual=HeadProfiles.get(ModelHeads.key(entity))!=null;
        for(var zone:ModelHits.headZones(entity)) {
            var box=zone.box();var local=new ArrayList<HeadZone.Plane>();
            for(var plane:zone.planes()) {
                var p=HeadZone.localPlane(box,plane);double length=Math.sqrt(p.x()*p.x()+p.y()*p.y()+p.z()*p.z());
                local.add(new HeadZone.Plane(round(p.x()/length),round(p.y()/length),round(p.z()/length),round(p.d()/length)));
            }
            var key=new Key(box.mesh(),List.copyOf(local));var edges=CACHE.get(key);
            if(edges==null) {
                var unit=new ModelBox("head",0,0,0,1,0,0,0,1,0,0,0,1,box.mesh());
                var cuts=local.stream().map(p->new HeadLabelCuts.Plane(p.x(),p.y(),p.z(),p.d())).toList();
                edges=HeadLabelCuts.edges(List.of(unit),List.of("0:-1"),Map.of("0:-1",cuts));
                CACHE.put(key,edges);while(CACHE.size()>128) CACHE.remove(CACHE.keySet().iterator().next());
            }
            var frame=new ModelContours.Frame(new Vec3(box.x(),box.y(),box.z()),new Vec3(box.ux(),box.uy(),box.uz()),new Vec3(box.vx(),box.vy(),box.vz()),new Vec3(box.wx(),box.wy(),box.wz()));
            shapes.add(new ModelDebugRender.Shape(owner,true,List.of(box),List.of(),new ModelContours.Outline(frame,edges),manual?.15F:.35F));
        }
    }
    private static double round(double value) { return Math.round(value*1e6)/1e6; }
    static void clear() { CACHE.clear(); }
}
