package acup.gunfeel;

import java.util.*;
import net.minecraft.world.phys.Vec3;

/** Перенос только целой проверенной формы. Имена и анатомические догадки не используются. */
final class ZoneTemplates {
    private ZoneTemplates() {}
    static HeadProfiles.Binding find(Map<String,HeadProfiles.Profile> bank,List<ModelBox> boxes,float yaw) {
        HeadProfiles.Binding found=null;
        for(var profile:bank.values()) {
            var next=bind(profile,boxes,yaw);if(next==null) continue;
            if(found!=null && !signature(found).equals(signature(next))) return null;
            found=next;
        }
        return found;
    }
    private static List<String> signature(HeadProfiles.Binding binding) {
        return binding.zones().stream().map(z->z.part()+":"+z.planes()+":"+z.face()).sorted().toList();
    }
    static HeadProfiles.Binding bind(HeadProfiles.Profile profile,List<ModelBox> boxes,float yaw) {
        int count=boxes.size();if(count==0 || profile.parts().size()!=count) return null;
        double oldSize=0,newSize=0;
        var oldCenter=Vec3.ZERO;var newCenter=Vec3.ZERO;
        for(var ref:profile.parts()) { oldCenter=oldCenter.add(ref.center());for(double d:ref.size()) oldSize=Math.max(oldSize,d); }
        for(var box:boxes) { newCenter=newCenter.add(canonical(ModelContours.point(box,.5,.5,.5),yaw));for(double d:HeadProfiles.size(box)) newSize=Math.max(newSize,d); }
        if(oldSize<=0 || newSize<=0) return null;
        double scale=newSize/oldSize;oldCenter=oldCenter.scale(1.0/count);newCenter=newCenter.scale(1.0/count);
        var candidates=new ArrayList<List<Integer>>();
        for(var ref:profile.parts()) {
            var choices=new ArrayList<Integer>();
            for(int j=0;j<count;j++) {
                var box=boxes.get(j);if(ref.shape()!=HeadProfiles.shape(box)) continue;
                var size=HeadProfiles.size(box);boolean same=true;
                for(int axis=0;axis<3;axis++) if(Math.abs(Math.log(size[axis]/(ref.size()[axis]*scale)))>.04) same=false;
                var center=canonical(ModelContours.point(box,.5,.5,.5),yaw).subtract(newCenter).scale(1/scale);
                if(same && center.distanceTo(ref.center().subtract(oldCenter))<=oldSize*.35) choices.add(j);
            }
            if(choices.isEmpty()) return null;candidates.add(choices);
        }
        // Не выбирать одну из одинаковых деталей с разными ролями случайно.
        for(var zone:profile.zones()) if(candidates.get(zone.part()).size()!=1) return null;
        int[] owner=new int[count];Arrays.fill(owner,-1);
        for(int i=0;i<count;i++) if(!assign(i,candidates,owner,new boolean[count])) return null;
        int[] mapping=new int[count];for(int j=0;j<count;j++) mapping[owner[j]]=j;
        var zones=profile.zones().stream().map(z->new HeadProfiles.Zone(mapping[z.part()],z.group(),z.planes(),z.face())).toList();
        return new HeadProfiles.Binding(zones,0,"совпадение с проверенной формой датасета");
    }
    private static boolean assign(int ref,List<List<Integer>> candidates,int[] owner,boolean[] seen) {
        for(int box:candidates.get(ref)) if(!seen[box]) {
            seen[box]=true;
            if(owner[box]<0 || assign(owner[box],candidates,owner,seen)) { owner[box]=ref;return true; }
        }
        return false;
    }
    private static Vec3 canonical(Vec3 p,float yaw) {
        double a=Math.toRadians(yaw),c=Math.cos(a),s=Math.sin(a);
        return new Vec3(c*p.x+s*p.z,p.y,-s*p.x+c*p.z);
    }
}
