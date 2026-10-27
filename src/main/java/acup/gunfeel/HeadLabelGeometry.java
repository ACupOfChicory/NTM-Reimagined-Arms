package acup.gunfeel;

import java.util.*;
import net.minecraft.world.phys.Vec3;

final class HeadLabelGeometry {
    record Pick(int box,int triangle,double t) {
        String token(boolean faces) { return box+":"+(faces?triangle:-1); }
    }
    private HeadLabelGeometry() {}
    static float[] triangles(ModelBox box) { return box.mesh()==0?ModelDebugRender.CUBE:ModelMesh.get(box.mesh()).triangles(); }
    static Pick pick(List<ModelBox> boxes,Vec3 from,Vec3 to) {
        Pick best=null;
        for(int i=0;i<boxes.size();i++) {
            var box=boxes.get(i); var data=triangles(box);
            if(box.clip(from.x,from.y,from.z,to.x,to.y,to.z)<0) continue;
            for(int at=0;at<data.length;at+=9) {
                Vec3 a=point(box,data,at),b=point(box,data,at+3),c=point(box,data,at+6);
                double t=intersection(from,to,a,b,c);
                if(t>=0 && (best==null || t<best.t())) best=new Pick(i,at/9,t);
            }
        }
        return best;
    }
    static Vec3 point(ModelBox box,float[] data,int at) { return ModelContours.point(box,data[at],data[at+1],data[at+2]); }
    private static double intersection(Vec3 from,Vec3 to,Vec3 a,Vec3 b,Vec3 c) {
        var d=to.subtract(from); var u=b.subtract(a); var v=c.subtract(a); var p=d.cross(v);
        double determinant=u.dot(p); if(Math.abs(determinant)<1e-12) return -1;
        var s=from.subtract(a); double x=s.dot(p)/determinant; if(x<0 || x>1) return -1;
        var q=s.cross(u); double y=d.dot(q)/determinant;
        double t=v.dot(q)/determinant;
        return y>=0 && x+y<=1 && t>=0 && t<=1?t:-1;
    }
    static List<Vec3[]> edges(List<ModelBox> boxes,Collection<String> tokens) {
        var edges=new ArrayList<Vec3[]>();
        for(String token:tokens) {
            String[] fields=token.split(":"); int i=Integer.parseInt(fields[0]),triangle=Integer.parseInt(fields[1]);
            if(i<0 || i>=boxes.size()) continue;
            var box=boxes.get(i);
            if(triangle<0) edges.addAll(ModelContours.edges(List.of(box)));
            else {
                var data=triangles(box); int at=triangle*9; if(at+9>data.length) continue;
                var a=point(box,data,at); var b=point(box,data,at+3); var c=point(box,data,at+6);
                edges.add(new Vec3[]{a,b,b,c,c,a});
            }
        }
        return edges;
    }
}
