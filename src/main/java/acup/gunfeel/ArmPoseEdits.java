package acup.gunfeel;

import java.util.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Поправки поверх готового движения, включая обычный хват. */
final class ArmPoseEdits {
    private static final Map<String,Map<String,TreeMap<Float,float[]>>> CACHE=new LinkedHashMap<>();
    private ArmPoseEdits() {}
    static Map<String,TreeMap<Float,float[]>> read(String source) {
        var result=new LinkedHashMap<String,TreeMap<Float,float[]>>();
        for(var segment:source.split(";")) {
            var words=segment.trim().split("\\s+"); if(words.length<2) continue;
            var values=new TreeMap<Float,float[]>();
            for(int i=1;i<words.length;i++) {
                int colon=words[i].indexOf(':'); float time=Float.parseFloat(words[i].substring(0,colon));
                var numbers=words[i].substring(colon+1).split(","); if(numbers.length!=6) throw new IllegalArgumentException();
                var pose=new float[6]; for(int j=0;j<6;j++){pose[j]=Float.parseFloat(numbers[j]);if(!Float.isFinite(pose[j]))throw new IllegalArgumentException();}
                if(!Float.isFinite(time))throw new IllegalArgumentException(); values.put(time,pose);
            }
            result.put(words[0],values);
        }
        return result;
    }
    static String write(Map<String,TreeMap<Float,float[]>> tracks) {
        var segments=new ArrayList<String>();
        for(var track:tracks.entrySet()) {
            var line=new StringBuilder(track.getKey());
            for(var entry:track.getValue().entrySet()) {
                line.append(' ').append(entry.getKey()).append(':');
                for(int i=0;i<6;i++){if(i>0)line.append(',');line.append(entry.getValue()[i]);}
            }
            if(!track.getValue().isEmpty())segments.add(line.toString());
        }
        return String.join("; ",segments);
    }
    static float[] sample(TreeMap<Float,float[]> track,float time) {
        if(track==null || track.isEmpty())return new float[6];
        var a=track.floorEntry(time);var b=track.ceilingEntry(time);
        if(a==null)return track.firstEntry().getValue().clone();
        if(b==null || a.getKey().equals(b.getKey()))return a.getValue().clone();
        float t=(time-a.getKey())/(b.getKey()-a.getKey()); t=t*t*(3-2*t);
        var out=new float[6];for(int i=0;i<6;i++)out[i]=a.getValue()[i]+(b.getValue()[i]-a.getValue()[i])*t;return out;
    }
    static float[] sample(String source,String cue,float time) {
        if(source.isBlank())return new float[6];
        var tracks=CACHE.get(source);
        if(tracks==null) {
            try { tracks=read(source); } catch(RuntimeException e) { tracks=Map.of(); }
            if(CACHE.size()>=16)CACHE.remove(CACHE.keySet().iterator().next());CACHE.put(source,tracks);
        }
        return sample(tracks.get(cue),time);
    }
    static void apply(Matrix4f pose,float[] values,boolean right,boolean slim,float corner) {
        boolean changed=false;for(float value:values)if(value!=0){changed=true;break;}if(!changed)return;
        float center=(right?-1:1)*(slim?5.5F:6F)/16F;
        float half=(slim?1.5F:2F)/16F;
        var pivot=new Vector3f(center+(right?half:-half)*corner,.75F,-.125F*corner);
        var scale=pose.getScale(new Vector3f());
        var contact=pose.transformPosition(pivot,new Vector3f());
        var rotation=new org.joml.Matrix3f(pose).scale(1/scale.x,1/scale.y,1/scale.z)
                .getNormalizedRotation(new org.joml.Quaternionf())
                .rotateXYZ((float)Math.toRadians(values[3]),(float)Math.toRadians(values[4]),(float)Math.toRadians(values[5]));
        pose.identity().translation(contact).rotate(rotation).scale(scale).translate(new Vector3f(pivot).negate());
        float pixel=scale.x/16F;
        pose.m30(pose.m30()+values[0]*pixel).m31(pose.m31()+values[1]*pixel).m32(pose.m32()+values[2]*pixel);
    }
}
