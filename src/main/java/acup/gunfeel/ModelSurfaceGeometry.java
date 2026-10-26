package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;
import org.joml.Matrix4fc;

// Общая неизменяемая геометрия треугольников: OBJ, GPU и блочные слои.
// Вход: x,y,z,u,v. Маска режет сами поверхности, а не их ограничивающий куб.
public record ModelSurfaceGeometry(float[] bounds,List<ModelMesh> meshes,boolean solid) {
    public static ModelSurfaceGeometry bake(float[] data,ModelTextureMask.Alpha mask) {
        List<float[]> triangles=new ArrayList<>();
        for(int i=0;i<data.length;i+=15) {
            var poly=new ArrayList<float[]>();
            for(int v=0;v<3;v++) poly.add(java.util.Arrays.copyOfRange(data,i+v*5,i+v*5+5));
            if(mask==null) { add(triangles,poly); continue; }
            float u0=Float.POSITIVE_INFINITY,v0=u0,u1=Float.NEGATIVE_INFINITY,v1=u1;
            for(var p:poly) { u0=Math.min(u0,p[3]); u1=Math.max(u1,p[3]); v0=Math.min(v0,p[4]); v1=Math.max(v1,p[4]); }
            int x0=(int)Math.floor(u0*mask.width()),x1=(int)Math.floor(u1*mask.width());
            int y0=(int)Math.floor(v0*mask.height()),y1=(int)Math.floor(v1*mask.height());
            boolean all=true,any=false;
            boolean[][] pixels=new boolean[y1-y0+1][x1-x0+1];
            for(int y=y0;y<=y1;y++) for(int x=x0;x<=x1;x++) {
                boolean opaque=mask.pixel((x+.5F)/mask.width(),(y+.5F)/mask.height());
                pixels[y-y0][x-x0]=opaque;
                all&=opaque; any|=opaque;
            }
            if(all) { add(triangles,poly); continue; }
            if(!any) continue;
            // Объединяем пиксели в двумерные прямоугольники: полосы по одному
            // пикселю создавали бы тысячи лишних треугольников на целой грани.
            for(int y=y0;y<=y1;y++) for(int x=x0;x<=x1;x++) {
                if(!pixels[y-y0][x-x0]) continue;
                int end=x+1,bottom=y+1;
                while(end<=x1 && pixels[y-y0][end-x0]) end++;
                outer: while(bottom<=y1) {
                    for(int k=x;k<end;k++) if(!pixels[bottom-y0][k-x0]) break outer;
                    bottom++;
                }
                for(int j=y;j<bottom;j++) for(int k=x;k<end;k++) pixels[j-y0][k-x0]=false;
                List<float[]> clipped=clip(poly,3,(float)x/mask.width(),true);
                clipped=clip(clipped,3,(float)end/mask.width(),false);
                clipped=clip(clipped,4,(float)y/mask.height(),true);
                clipped=clip(clipped,4,(float)bottom/mask.height(),false);
                add(triangles,clipped); x=end-1;
            }
        }
        float[] bounds={Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY};
        for(var t:triangles) for(int p=0;p<9;p+=3) for(int a=0;a<3;a++) {
            bounds[a]=Math.min(bounds[a],t[p+a]); bounds[a+3]=Math.max(bounds[a+3],t[p+a]);
        }
        var meshes=new ArrayList<ModelMesh>();
        for(int start=0;start<triangles.size();start+=ModelMesh.MAX_TRIANGLES) {
            int count=Math.min(ModelMesh.MAX_TRIANGLES,triangles.size()-start);
            float[] normalized=new float[count*9];
            for(int t=0;t<count;t++) for(int v=0;v<9;v++) {
                int a=v%3; float span=bounds[a+3]-bounds[a];
                normalized[t*9+v]=span==0?0:Math.clamp((triangles.get(start+t)[v]-bounds[a])/span,0,1);
            }
            meshes.add(ModelMesh.register(new ModelMesh(normalized)));
        }
        return new ModelSurfaceGeometry(bounds,List.copyOf(meshes),solidBox(triangles,bounds));
    }
    private static boolean solidBox(List<float[]> triangles,float[] bounds) {
        double[] area=new double[6];
        for(int a=0;a<3;a++) if(bounds[a+3]-bounds[a]<1e-8) return false;
        for(var t:triangles) {
            int face=-1;
            for(int a=0;a<3;a++) for(int side=0;side<2;side++) {
                float v=bounds[a+side*3];
                if(Math.abs(t[a]-v)<1e-7 && Math.abs(t[a+3]-v)<1e-7 && Math.abs(t[a+6]-v)<1e-7) face=a*2+side;
            }
            if(face<0) return false;
            int a=(face/2+1)%3,b=(face/2+2)%3;
            area[face]+=Math.abs((t[a+3]-t[a])*(t[b+6]-t[b])-(t[b+3]-t[b])*(t[a+6]-t[a]))/2;
        }
        for(int f=0;f<6;f++) {
            int a=(f/2+1)%3,b=(f/2+2)%3;
            double expected=(bounds[a+3]-bounds[a])*(bounds[b+3]-bounds[b]);
            if(Math.abs(area[f]-expected)>expected*1e-5) return false;
        }
        return true;
    }
    private static List<float[]> clip(List<float[]> poly,int axis,float limit,boolean greater) {
        var out=new ArrayList<float[]>();
        if(poly.isEmpty()) return out;
        var prev=poly.getLast(); boolean pin=greater?prev[axis]>=limit:prev[axis]<=limit;
        for(var p:poly) {
            boolean in=greater?p[axis]>=limit:p[axis]<=limit;
            if(in!=pin) {
                float t=(limit-prev[axis])/(p[axis]-prev[axis]); float[] cut=new float[5];
                for(int a=0;a<5;a++) cut[a]=prev[a]+t*(p[a]-prev[a]);
                out.add(cut);
            }
            if(in) out.add(p);
            prev=p; pin=in;
        }
        return out;
    }
    private static void add(List<float[]> out,List<float[]> poly) {
        for(int i=1;i+1<poly.size();i++) {
            var a=poly.getFirst(); var b=poly.get(i); var c=poly.get(i+1);
            float ux=b[0]-a[0],uy=b[1]-a[1],uz=b[2]-a[2],vx=c[0]-a[0],vy=c[1]-a[1],vz=c[2]-a[2];
            if(Math.abs(uy*vz-uz*vy)+Math.abs(uz*vx-ux*vz)+Math.abs(ux*vy-uy*vx)<1e-10) continue;
            out.add(new float[]{a[0],a[1],a[2],b[0],b[1],b[2],c[0],c[1],c[2]});
        }
    }
    public void append(List<ModelBox> out,String name,Matrix4fc matrix,double x,double y,double z) {
        if(meshes.isEmpty()) return;
        var box=MeshModelPose.box(name,bounds,matrix,x,y,z);
        if(box!=null) {
            if(solid) out.add(box);
            else for(var mesh:meshes) out.add(box.withMesh(mesh.id()));
        }
    }
}
