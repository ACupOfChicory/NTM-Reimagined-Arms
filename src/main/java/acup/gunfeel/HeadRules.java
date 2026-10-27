package acup.gunfeel;

import java.util.*;
import net.minecraft.world.phys.Vec3;

/** Запасные объяснимые слои для моделей, которых нет в ручной разметке. */
final class HeadRules {
    record Result(HeadProfiles.Binding binding,float confidence) {}
    private static final float[] CUBE=cube();
    private static float[] cube() {
        float[] out=new float[108];int at=0;
        for(int axis=0;axis<3;axis++) for(int side=0;side<2;side++) for(int corner:new int[]{0,1,3,0,3,2}) {
            float[] p=new float[3];p[axis]=side;p[(axis+1)%3]=corner&1;p[(axis+2)%3]=corner>>1&1;
            for(float f:p) out[at++]=f;
        }
        return out;
    }
    private static Set<String> words(String name) {
        return new HashSet<>(Arrays.asList(name.replaceAll("([a-z])([A-Z])","$1_$2").toLowerCase(Locale.ROOT).split("[^a-z]+")));
    }
    private static boolean any(Set<String> words,String... choices) { for(String c:choices) if(words.contains(c)) return true;return false; }
    private static final class Part {
        final int index;final String name;Vec3 low,high,center,extent;double volume;
        Part(int i,ModelBox box,float yaw) {
            index=i;name=box.part();double[] lo={Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY},hi={-lo[0],-lo[1],-lo[2]};
            float[] data=box.mesh()==0?CUBE:ModelMesh.get(box.mesh()).triangles();
            double[][] bary={{1.0/3,1.0/3,1.0/3},{.6,.2,.2},{.2,.6,.2},{.2,.2,.6}};
            for(int at=0;at<data.length;at+=9) for(var weights:bary) {
                double a=0,b=0,c=0;for(int v=0;v<3;v++) { a+=data[at+v*3]*weights[v];b+=data[at+v*3+1]*weights[v];c+=data[at+v*3+2]*weights[v]; }
                var p=canonical(ModelContours.point(box,a,b,c),yaw);
                for(int k=0;k<3;k++) { double value=coordinate(p,k);lo[k]=Math.min(lo[k],value);hi[k]=Math.max(hi[k],value); }
            }
            low=new Vec3(lo[0],lo[1],lo[2]);high=new Vec3(hi[0],hi[1],hi[2]);
        }
    }
    private static Vec3 canonical(Vec3 p,float yaw) {
        double angle=Math.toRadians(yaw),c=Math.cos(angle),s=Math.sin(angle);
        return new Vec3(c*p.x+s*p.z,p.y,-s*p.x+c*p.z);
    }
    private static double coordinate(Vec3 p,int axis) { return axis==0?p.x:axis==1?p.y:p.z; }
    private static Vec3 divide(Vec3 a,Vec3 b) { return new Vec3(a.x/b.x,a.y/b.y,a.z/b.z); }
    private static double min(Vec3 p) { return Math.min(p.x,Math.min(p.y,p.z)); }
    private static double max(Vec3 p) { return Math.max(p.x,Math.max(p.y,p.z)); }
    private static final class Context {
        final List<ModelBox> boxes;final List<Part> parts=new ArrayList<>();final Vec3 low,size;final float yaw;
        Context(List<ModelBox> boxes,float yaw) {
            this.boxes=boxes;this.yaw=yaw;
            double[] lo={Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY},hi={-lo[0],-lo[1],-lo[2]};
            for(int i=0;i<boxes.size();i++) {
                var p=new Part(i,boxes.get(i),yaw);parts.add(p);
                for(int k=0;k<3;k++) { lo[k]=Math.min(lo[k],coordinate(p.low,k));hi[k]=Math.max(hi[k],coordinate(p.high,k)); }
            }
            low=new Vec3(lo[0],lo[1],lo[2]);size=new Vec3(Math.max(.001,hi[0]-lo[0]),Math.max(.001,hi[1]-lo[1]),Math.max(.001,hi[2]-lo[2]));
            for(var p:parts) {
                p.low=divide(p.low.subtract(low),size);p.high=divide(p.high.subtract(low),size);
                var d=p.high.subtract(p.low);p.extent=new Vec3(Math.max(.001,d.x),Math.max(.001,d.y),Math.max(.001,d.z));p.center=p.low.add(p.high).scale(.5);p.volume=p.extent.x*p.extent.y*p.extent.z;
            }
        }
        HeadZone.Plane plane(int axis,double boundary,boolean greater) {
            double c=Math.cos(Math.toRadians(yaw)),s=Math.sin(Math.toRadians(yaw)),scale=1/coordinate(size,axis),sign=greater?1:-1;
            Vec3 n=axis==0?new Vec3(c*scale,0,s*scale):axis==1?new Vec3(0,scale,0):new Vec3(-s*scale,0,c*scale);
            return new HeadZone.Plane(n.x*sign,n.y*sign,n.z*sign,(-coordinate(low,axis)*scale-boundary)*sign);
        }
        HeadProfiles.Zone zone(Part part,List<HeadZone.Plane> world) {
            return new HeadProfiles.Zone(part.index,1,world.stream().map(p->HeadZone.localPlane(boxes.get(part.index),p)).toList(),0);
        }
    }
    static Result infer(List<ModelBox> boxes,float yaw) {
        if(boxes.isEmpty()) return result(List.of(),"нет геометрии",0);
        var context=new Context(boxes,yaw);var parts=context.parts;var anchors=new HashSet<String>();var allWords=new HashSet<String>();
        for(var part:parts) {
            allWords.addAll(words(part.name));var segments=part.name.split("/");String prefix="";
            for(String segment:segments) { prefix+=prefix.isEmpty()?segment:"/"+segment;if(any(words(segment),"head","skull")) anchors.add(prefix); }
        }
        for(String anchor:List.copyOf(anchors)) if(anchors.stream().anyMatch(other->other.startsWith(anchor+"/") && any(words(other.substring(anchor.length()+1)),"body","torso"))) anchors.remove(anchor);
        for(var part:parts) {
            String prefix="";for(String segment:part.name.split("/")) {
                prefix+=prefix.isEmpty()?segment:"/"+segment;String at=prefix;
                if(words(segment).contains("neck") && anchors.stream().noneMatch(a->a.startsWith(at+"/"))) anchors.add(prefix);
            }
        }
        if(allWords.contains("rotor") || allWords.containsAll(Set.of("base","plate","stick"))) return result(List.of(),"опорная или техническая конструкция",.9F);
        var chosen=new ArrayList<Part>();
        for(var part:parts) if(anchors.stream().anyMatch(a->part.name.equals(a)||part.name.startsWith(a+"/"))) chosen.add(part);
        if(chosen.isEmpty()) {
            var segments=new HashSet<Integer>();for(var part:parts) { var matcher=java.util.regex.Pattern.compile("(?:^|/)segment(\\d+)(?:/|$)").matcher(part.name);if(matcher.find()) segments.add(Integer.parseInt(matcher.group(1))); }
            if(segments.size()>=3 && segments.contains(0)) for(var part:parts) if(part.name.matches(".*(?:^|/)segment0(?:/.*)?")) chosen.add(part);
        }
        if(!chosen.isEmpty()) {
            var zones=new ArrayList<HeadProfiles.Zone>();
            for(var part:chosen) {
                var planes=new ArrayList<HeadZone.Plane>();var e=part.extent;
                if(e.y>.35 && e.y>Math.max(e.x,e.z)*1.3 && !any(words(part.name.substring(part.name.lastIndexOf('/')+1)),"jaw","nose","beak","bill")) {
                    double boundary=Double.POSITIVE_INFINITY;
                    for(var other:chosen) if(other!=part && other.center.y>part.low.y+e.y*.6 && other.extent.y<e.y*.5) boundary=Math.min(boundary,other.low.y);
                    if(Double.isFinite(boundary)) planes.add(context.plane(1,boundary-.02,true));
                }
                zones.add(context.zone(part,planes));
            }
            return result(zones,"семантика ветвей модели",.85F);
        }
        return geometry(context);
    }
    private static Result geometry(Context context) {
        var parts=context.parts;int n=parts.size();double[] ys=new double[n],zs=new double[n];
        for(int i=0;i<n;i++) { ys[i]=parts.get(i).center.y;zs[i]=parts.get(i).center.z; }Arrays.sort(ys);Arrays.sort(zs);
        double medianY=(ys[(n-1)/2]+ys[n/2])/2,medianZ=(zs[(n-1)/2]+zs[n/2])/2;
        Part torso=null;
        for(var p:parts) if(Math.abs(p.center.x-.5)<p.extent.x/2+.015 && Math.abs(p.center.y-medianY)<=p.extent.y/2+.01 && Math.abs(p.center.z-medianZ)<=p.extent.z/2+.01 && (torso==null || p.volume>torso.volume)) torso=p;
        if(torso==null) torso=parts.stream().max(Comparator.comparingDouble(p->p.volume)).orElseThrow();
        var c=torso.center;var e=torso.extent;int repeated=0,thin=0;boolean support=false;double thickVolume=0,centralVolume=0;
        var centralMass=new ArrayList<Part>();var eligible=new ArrayList<Part>();var frontSeeds=new HashSet<Integer>();Part seed=null;double best=-Double.MAX_VALUE;
        for(var p:parts) {
            var d=p.extent;boolean thick=min(d)>max(d)*.12;
            if(thick) thickVolume+=p.volume;else thin++;
            double similarity=Math.pow(Math.log(d.x/e.x),2)+Math.pow(Math.log(d.y/e.y),2)+Math.pow(Math.log(d.z/e.z),2);
            if(similarity<.36 && p.volume>torso.volume*.35) repeated++;
            if(p.center.y<c.y-.15 && thick && d.y>e.y*.6) support=true;
            if(Math.abs(p.center.x-c.x)<e.x*.55 && thick && p.volume>torso.volume*.12 && p.center.z>c.z-e.z*.3) { centralVolume+=p.volume;centralMass.add(p); }
            boolean central=Math.abs(p.center.x-.5)<=Math.max(.08,e.x*.32),front=p.center.z>c.z+e.z/2-.025,above=p.center.y>c.y+e.y/2-.025;
            boolean equivalent=p.center.distanceTo(c)<.08 && d.distanceTo(e)<.12;
            if(central && thick && !equivalent) eligible.add(p);
            if(!central || !thick || equivalent || d.y>Math.max(d.x,d.z)*2.3 || max(d)>=.8 || p.volume<=torso.volume*(above&&!front?.05:.005) || !front&&!above) continue;
            double score=(p.center.z-c.z)/Math.max(e.z,.1)+(p.center.y-c.y)/Math.max(e.y,.1);
            if(score>best) { best=score;seed=p; }
            if(front && !above) frontSeeds.add(p.index);
        }
        if(min(e)/max(e)>.32 && centralVolume/Math.max(thickVolume,1e-9)>.75 && !support && thin>=2 && n>=3 && repeated<4)
            return result(centralMass.stream().map(p->context.zone(p,List.of())).toList(),"компактная голова-корпус",.65F);
        if(seed!=null) {
            boolean forward=frontSeeds.contains(seed.index);var keep=new LinkedHashSet<Part>();keep.add(seed);
            for(int iteration=0;iteration<n;iteration++) {
                boolean changed=false;
                for(var p:eligible) if(!keep.contains(p) && (forward?p.center.z>c.z+e.z/2-.025:p.center.y>c.y+.07)) {
                    for(var k:List.copyOf(keep)) {
                        var delta=p.center.subtract(k.center);var half=p.extent.add(k.extent).scale(.5);
                        double gap=Math.pow(Math.max(Math.abs(delta.x)-half.x,0),2)+Math.pow(Math.max(Math.abs(delta.y)-half.y,0),2)+Math.pow(Math.max(Math.abs(delta.z)-half.z,0),2);
                        if(gap<.045*.045) { keep.add(p);changed=true;break; }
                    }
                }
                if(!changed) break;
            }
            var zones=new ArrayList<HeadProfiles.Zone>();for(var p:keep) zones.add(context.zone(p,List.of()));
            if(forward && seed.extent.x*seed.extent.y/Math.max(e.x*e.y,.001)>.45) zones.add(context.zone(torso,List.of(context.plane(2,torso.low.z+e.z*2/3,true))));
            return result(zones,"передний или верхний связный кластер",.65F);
        }
        return result(List.of(),repeated>=4?"повторяющееся тело без отдельной головы":"не удалось надёжно выделить голову",repeated>=4?.65F:0);
    }
    private static Result result(List<HeadProfiles.Zone> zones,String reason,float confidence) { return new Result(new HeadProfiles.Binding(List.copyOf(zones),0,reason),confidence); }
}
