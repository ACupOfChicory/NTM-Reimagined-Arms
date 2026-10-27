package acup.gunfeel;

import java.util.*;

/** Привязка роли к неизменной структуре; анимация меняет только аффинные рамки. */
public final class ModelHeads {
    public record Hit(boolean applied,boolean head,String source,String reason,float confidence) {}
    public record Resolution(List<HeadZone> zones,String source,String reason,float confidence,int missing) {}
    private record Key(String type,long layout) {}
    private record Definition(HeadProfiles.Binding binding,String source,float confidence) {}
    private static final LinkedHashMap<Key,Definition> CACHE=new LinkedHashMap<>(32,.75F,true);
    private ModelHeads() {}
    static boolean noHead(String type) { var p=HeadProfiles.get(type);return p!=null && p.none(); }
    private static long layout(List<ModelBox> boxes) {
        double largest=0;for(var b:boxes) for(double size:HeadProfiles.size(b)) largest=Math.max(largest,size);
        long hash=1469598103934665603L;
        for(var box:boxes) {
            hash=(hash^box.part().hashCode())*1099511628211L;hash=(hash^HeadProfiles.shape(box))*1099511628211L;
            for(double size:HeadProfiles.size(box)) hash=(hash^Math.round(size/Math.max(largest,1e-9)*200))*1099511628211L;
        }
        return hash;
    }
    public static synchronized Resolution resolve(String type,List<ModelBox> boxes,float yaw) {
        if(noHead(type)) return new Resolution(List.of(),"dataset","manual none",1F,0);
        var key=new Key(type,layout(boxes));var definition=CACHE.get(key);
        if(definition==null) {
            var profile=HeadProfiles.get(type);
            if(profile!=null) {
                var binding=HeadProfiles.bind(profile,boxes,yaw);
                definition=new Definition(binding,"dataset",binding.missing()==0?1F:.5F);
            } else {
                var inferred=HeadRules.infer(boxes,yaw);
                definition=new Definition(inferred.binding(),inferred.confidence()==0?"unknown":"procedural",inferred.confidence());
            }
            CACHE.put(key,definition);while(CACHE.size()>256) CACHE.remove(CACHE.keySet().iterator().next());
        }
        return new Resolution(HeadProfiles.zones(definition.binding(),boxes),definition.source(),definition.binding().reason(),definition.confidence(),definition.binding().missing());
    }
}
