package acup.gunfeel;

import java.util.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Поправки поверх готового движения, включая обычный хват. */
final class ArmPoseEdits {
    private static final Map<String,Map<String,TreeMap<Float,float[]>>> CACHE=new LinkedHashMap<>();
    private ArmPoseEdits() {}
    static Map<String,TreeMap<Float,float[]>> read(String source) {
        return ArmRig.adjustments(source);
    }
    static String write(Map<String,TreeMap<Float,float[]>> tracks) {
        var doc=ArmRig.adjustmentTracks("");
        for(var track:tracks.entrySet())ArmRig.putAdjustments(doc,track.getKey(),track.getValue());
        return doc.write();
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
        apply(pose,values,right,slim,ArmContact.offset(1).mul(corner));
    }
    static void apply(Matrix4f pose,float[] values,boolean right,boolean slim,Vector3f contactOffset) {
        boolean changed=false;for(float value:values)if(value!=0){changed=true;break;}if(!changed)return;
        var pivot=ArmContact.pivot(right,slim,contactOffset);
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
