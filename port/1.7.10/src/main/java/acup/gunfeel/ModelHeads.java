package acup.gunfeel;

// port: copy of 26.2 src/main/java/acup/gunfeel/ModelHeads.java at 0762086; changes marked "port:"

import java.util.*;

/** Привязка роли к неизменной структуре; анимация меняет только аффинные рамки. */
public final class ModelHeads {
    public record Hit(boolean applied,boolean head,String source,String reason,float confidence) {}
    public record Resolution(List<HeadZone> zones,String source,String reason,float confidence,int missing) {}
    private record Key(String type,long layout) {}
    private record Definition(HeadProfiles.Binding binding,String source,float confidence) {}
    private static final LinkedHashMap<Key,Definition> CACHE=new LinkedHashMap<>(32,.75F,true);
    private static final Map<String,HeadProfiles.Profile> LIMBS=HeadProfiles.loadBank("/ntmgunfeel-limbs.json",java.nio.file.Path.of("config/ntmgunfeel-limbs.json"),false);
    private ModelHeads() {}
    public static float multiplier(boolean head,boolean limb) { return head?1.75F:limb?.5F:1F; }
    // port: the type by its 26.2 name (EntityKeys); 1.7.10's isChild for isBaby
    static String key(net.minecraft.entity.Entity entity) {
        return EntityKeys.type(entity)
                +(entity instanceof net.minecraft.entity.EntityLivingBase living && living.isChild()?"#baby":"");
    }
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
        return resolve(type,boxes,yaw,HeadProfiles.all(),"head","height");
    }
    static synchronized Resolution limbs(String type,List<ModelBox> boxes,float yaw) {
        return resolve(type,boxes,yaw,LIMBS,"limbs","body");
    }
    private static Resolution resolve(String type,List<ModelBox> boxes,float yaw,Map<String,HeadProfiles.Profile> bank,String role,String fallback) {
        if(bank.isEmpty()) return new Resolution(List.of(),fallback,"нет проверенной разметки",0F,0);
        var profile=HeadProfiles.get(bank,type);
        if(profile!=null && profile.none()) return new Resolution(List.of(),"dataset","manual none",1F,0);
        var key=new Key(role+":"+type,layout(boxes));var definition=CACHE.get(key);
        if(definition==null) {
            if(profile!=null) {
                var binding=HeadProfiles.bind(profile,boxes,yaw);
                if(binding.missing()==0) definition=new Definition(binding,"dataset",1F);
            }
            if(definition==null) {
                var template=ZoneTemplates.find(bank,boxes,yaw);
                definition=template!=null?new Definition(template,"template",1F):
                        new Definition(new HeadProfiles.Binding(List.of(),0,"нет проверенной совместимой формы"),fallback,0F);
            }
            CACHE.put(key,definition);while(CACHE.size()>256) CACHE.remove(CACHE.keySet().iterator().next());
        }
        return new Resolution(HeadProfiles.zones(definition.binding(),boxes),definition.source(),definition.binding().reason(),definition.confidence(),definition.binding().missing());
    }
}
