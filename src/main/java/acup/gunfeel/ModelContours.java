package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;

// Обводка объединения частей: рёбра, спрятанные внутри соседней детали,
// не являются внешним контуром. Геометрия попаданий от этого не меняется.
public final class ModelContours {
    private ModelContours() {}
    private static final float[] BOX_EDGES=boxEdges();
    public static List<Vec3[]> edges(List<ModelBox> boxes) {
        List<Vec3[]> out=new ArrayList<>();
        for(var box:boxes) {
            // Вложенная внутренняя деталь (например, внутренний слой головы)
            // не добавляет второй каркас под внешней частью того же узла.
            if(boxes.stream().anyMatch(parent -> parent!=box && box.part().startsWith(parent.part()+"/") && parent.encloses(box))) continue;
            float[] edges=box.mesh()==0?BOX_EDGES:ModelMesh.get(box.mesh()).edges();
            List<Vec3> visible=new ArrayList<>();
            for(int i=0;i<edges.length;i+=6) {
                Vec3 a=point(box,edges[i],edges[i+1],edges[i+2]),b=point(box,edges[i+3],edges[i+4],edges[i+5]);
                List<double[]> intervals=new ArrayList<>(); intervals.add(new double[]{0,1});
                for(var other:boxes) {
                    if(other==box || other.mesh()!=0) continue;
                    var hidden=other.interiorSpan(a,b);
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
                for(var span:intervals) if(span[1]-span[0]>1e-5) { visible.add(a.lerp(b,span[0])); visible.add(a.lerp(b,span[1])); }
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
