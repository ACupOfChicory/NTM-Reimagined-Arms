package acup.gunfeel;

import java.util.*;
import org.joml.Matrix4f;

// Одна неизменяемая поза на часть, общая для всех следов на ней. Отдельный
// след проверяет ссылку; матрица и плоскости сравниваются один раз на часть.
final class PartPoseMemo<K> {
    private Map<K,RenderedMesh.PartPose> previous=Map.of();
    Map<K,RenderedMesh.PartPose> frame(Map<K,RenderedMesh.PartPose> submitted) {
        boolean unchanged=previous.size()==submitted.size();
        if(unchanged) for(var entry:submitted.entrySet()) if(!same(previous.get(entry.getKey()),entry.getValue())) { unchanged=false; break; }
        if(unchanged) return previous;
        var next=new HashMap<K,RenderedMesh.PartPose>();
        for(var entry:submitted.entrySet()) {
            var pose=entry.getValue(); var old=previous.get(entry.getKey());
            if(same(old,pose)) next.put(entry.getKey(),old);
            else {
                float[][] planes=pose.planes();
                if(planes!=null) {
                    float[][] copy=new float[planes.length][];
                    for(int i=0;i<planes.length;i++) copy[i]=planes[i].clone();
                    planes=copy;
                }
                next.put(entry.getKey(),new RenderedMesh.PartPose(new Matrix4f(pose.pose()),planes));
            }
        }
        return previous=Map.copyOf(next);
    }
    private static boolean same(RenderedMesh.PartPose a,RenderedMesh.PartPose b) {
        return a!=null && a.pose().equals(b.pose()) && Arrays.deepEquals(a.planes(),b.planes());
    }
}
