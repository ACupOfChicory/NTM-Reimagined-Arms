package acup.gunfeel;

// port: copy of 26.2 src/main/java/acup/gunfeel/ModelCubeGeometry.java at 0762086; changes marked "port:"
// port: the cube is Cube below (its faces as 26.2's ModelPart.Cube.polygons: four vertices with x, y, z in pixels and
// u, v from 0 to 1), filled from a 1.7.10 ModelBox's TexturedQuads by Cube.of; the matrix is a GL float[16]. The baking
// is 26.2's line for line.

import java.util.ArrayList;
import java.util.List;

// Неизменная поверхность куба: прозрачные пиксели вырезаются один раз, до анимации.
public record ModelCubeGeometry(float[] bounds, List<ModelMesh> meshes, boolean visible) {
    public static ModelCubeGeometry bake(Cube cube, ModelTextureMask.Alpha mask) {
        float[] bounds = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        for (var face : cube.polygons) for (var v : face.vertices()) {
            float[] p = {v.x(), v.y(), v.z()};
            for (int a = 0; a < 3; a++) { bounds[a] = Math.min(bounds[a], p[a]); bounds[a+3] = Math.max(bounds[a+3], p[a]); }
        }
        if (!MeshModelPose.hasSurface(bounds[3]-bounds[0], bounds[4]-bounds[1], bounds[5]-bounds[2]))
            return new ModelCubeGeometry(bounds, List.of(), false);
        if (mask == null) return new ModelCubeGeometry(bounds, List.of(), true);
        for(int axis=0;axis<3;axis++) if(bounds[axis]==bounds[axis+3]) return plane(cube,bounds,mask,axis);
        List<Float> triangles = new ArrayList<>();
        boolean solid = true;
        int faces=0;
        for (var face : cube.polygons) {
            var v = face.vertices();
            var a = v[0]; var b = v[1]; var d = v[3];
            float ux=b.x()-a.x(), uy=b.y()-a.y(), uz=b.z()-a.z();
            float vx=d.x()-a.x(), vy=d.y()-a.y(), vz=d.z()-a.z();
            if (Math.abs(uy*vz-uz*vy)+Math.abs(uz*vx-ux*vz)+Math.abs(ux*vy-uy*vx)<1e-8) continue;
            faces++;
            int ns=Math.max(1,Math.round(Math.max(Math.abs(b.u()-a.u())*mask.width(),Math.abs(b.v()-a.v())*mask.height())));
            int nt=Math.max(1,Math.round(Math.max(Math.abs(d.u()-a.u())*mask.width(),Math.abs(d.v()-a.v())*mask.height())));
            boolean[][] pixels=new boolean[nt][ns];
            for(int t=0;t<nt;t++) for(int s=0;s<ns;s++) {
                float fs=(s+.5F)/ns, ft=(t+.5F)/nt;
                pixels[t][s]=mask.pixel(a.u()+fs*(b.u()-a.u())+ft*(d.u()-a.u()),a.v()+fs*(b.v()-a.v())+ft*(d.v()-a.v()));
                solid &= pixels[t][s];
            }
            // Соседние непрозрачные пиксели объединяются в прямоугольники.
            for(int t=0;t<nt;t++) for(int s=0;s<ns;s++) if(pixels[t][s]) {
                int end=s+1, bottom=t+1;
                while(end<ns && pixels[t][end]) end++;
                outer: while(bottom<nt) {
                    for(int k=s;k<end;k++) if(!pixels[bottom][k]) break outer;
                    bottom++;
                }
                for(int y=t;y<bottom;y++) for(int x=s;x<end;x++) pixels[y][x]=false;
                float[] ss={(float)s/ns,(float)end/ns,(float)end/ns,(float)s/ns};
                float[] tt={(float)t/nt,(float)t/nt,(float)bottom/nt,(float)bottom/nt};
                for(int corner:new int[]{0,1,2,0,2,3}) {
                    float[] p={a.x()+ss[corner]*ux+tt[corner]*vx,a.y()+ss[corner]*uy+tt[corner]*vy,a.z()+ss[corner]*uz+tt[corner]*vz};
                    for(int axis=0;axis<3;axis++) triangles.add(bounds[axis+3]==bounds[axis]?0F:
                            Math.clamp((p[axis]-bounds[axis])/(bounds[axis+3]-bounds[axis]),0F,1F));
                }
            }
        }
        if(solid && faces==6) return new ModelCubeGeometry(bounds,List.of(),true);
        return mesh(bounds,triangles);
    }

    private static ModelCubeGeometry mesh(float[] bounds,List<Float> triangles) {
        List<ModelMesh> meshes=new ArrayList<>();
        for(int start=0;start<triangles.size();start+=ModelMesh.MAX_TRIANGLES*9) {
            float[] data=new float[Math.min(ModelMesh.MAX_TRIANGLES*9,triangles.size()-start)];
            for(int i=0;i<data.length;i++) data[i]=triangles.get(start+i);
            meshes.add(ModelMesh.register(new ModelMesh(data)));
        }
        return new ModelCubeGeometry(bounds,List.copyOf(meshes),!triangles.isEmpty());
    }

    // Обе стороны нулевой толщины объединяются в одну маску в координатах
    // модели. Наложенные треугольники с разной диагональю не гасят контур.
    private static ModelCubeGeometry plane(Cube cube,float[] bounds,ModelTextureMask.Alpha mask,int axis) {
        int a=(axis+1)%3,b=(axis+2)%3;
        List<float[]> faces=new ArrayList<>();
        int ns=1,nt=1;
        for(var face:cube.polygons) {
            var v=face.vertices();
            float[] p={v[0].x(),v[0].y(),v[0].z()};
            float[] u={v[1].x()-p[0],v[1].y()-p[1],v[1].z()-p[2]};
            float[] w={v[3].x()-p[0],v[3].y()-p[1],v[3].z()-p[2]};
            float det=u[a]*w[b]-u[b]*w[a];
            if(Math.abs(det)<1e-8) continue;
            float us=v[1].u()-v[0].u(),vs=v[1].v()-v[0].v(),ut=v[3].u()-v[0].u(),vt=v[3].v()-v[0].v();
            faces.add(new float[]{p[a],p[b],u[a],u[b],w[a],w[b],det,v[0].u(),v[0].v(),us,vs,ut,vt});
            int s=Math.max(1,Math.round(Math.max(Math.abs(us)*mask.width(),Math.abs(vs)*mask.height())));
            int t=Math.max(1,Math.round(Math.max(Math.abs(ut)*mask.width(),Math.abs(vt)*mask.height())));
            ns=Math.max(ns,u[a]!=0?s:t); nt=Math.max(nt,u[b]!=0?s:t);
        }
        boolean[][] pixels=new boolean[nt][ns];
        boolean solid=true;
        for(int y=0;y<nt;y++) for(int x=0;x<ns;x++) {
            float pa=bounds[a]+(x+.5F)/ns*(bounds[a+3]-bounds[a]);
            float pb=bounds[b]+(y+.5F)/nt*(bounds[b+3]-bounds[b]);
            for(var f:faces) {
                float da=pa-f[0],db=pb-f[1];
                float s=(da*f[5]-db*f[4])/f[6],t=(f[2]*db-f[3]*da)/f[6];
                pixels[y][x]|=mask.pixel(f[7]+s*f[9]+t*f[11],f[8]+s*f[10]+t*f[12]);
            }
            solid&=pixels[y][x];
        }
        if(solid) return new ModelCubeGeometry(bounds,List.of(),true);
        List<Float> triangles=new ArrayList<>();
        for(int y=0;y<nt;y++) for(int x=0;x<ns;x++) if(pixels[y][x]) {
            int right=x+1,bottom=y+1;
            while(right<ns && pixels[y][right]) right++;
            outer: while(bottom<nt) {
                for(int k=x;k<right;k++) if(!pixels[bottom][k]) break outer;
                bottom++;
            }
            for(int j=y;j<bottom;j++) for(int i=x;i<right;i++) pixels[j][i]=false;
            for(int corner:new int[]{0,1,3,0,3,2}) {
                float[] p=new float[3];
                p[a]=(float)((corner&1)==0?x:right)/ns; p[b]=(float)((corner&2)==0?y:bottom)/nt;
                for(float value:p) triangles.add(value);
            }
        }
        return mesh(bounds,triangles);
    }

    public void append(List<ModelBox> out,String name,float[] matrix,double x,double y,double z) {
        if(!visible) return;
        var box=MeshModelPose.surface(name,bounds[0]/16,bounds[1]/16,bounds[2]/16,
                (bounds[3]-bounds[0])/16,(bounds[4]-bounds[1])/16,(bounds[5]-bounds[2])/16,matrix,x,y,z);
        if(meshes.isEmpty()) out.add(box);
        else for(var mesh:meshes) out.add(box.withMesh(mesh.id()));
    }

    // port: 26.2's ModelPart.Cube as far as bake reads it: cube.polygons[i].vertices()[k].x() ... v()
    public static final class Cube {
        public final Polygon[] polygons;
        public Cube(Polygon[] polygons) { this.polygons=polygons; }

        // A 1.7.10 cube: its six TexturedQuads (ModelBox.quadList, private) with the inflated, mirrored corners
        // the display list draws, in pixels, and the UVs TexturedQuad set (texture offset / texture size).
        public static Cube of(net.minecraft.client.model.ModelBox box) {
            net.minecraft.client.model.TexturedQuad[] quads;
            try { quads=(net.minecraft.client.model.TexturedQuad[])QUADS.get(box); }
            catch(IllegalAccessException ex) { throw new IllegalStateException(ex); }
            Polygon[] out=new Polygon[quads.length];
            for(int i=0;i<quads.length;i++) {
                var q=quads[i].vertexPositions;Vertex[] v=new Vertex[q.length];
                for(int k=0;k<q.length;k++) v[k]=new Vertex((float)q[k].vector3D.xCoord,(float)q[k].vector3D.yCoord,(float)q[k].vector3D.zCoord,
                        q[k].texturePositionX,q[k].texturePositionY);
                out[i]=new Polygon(v);
            }
            return new Cube(out);
        }
        private static final java.lang.reflect.Field QUADS=cpw.mods.fml.relauncher.ReflectionHelper.findField(
                net.minecraft.client.model.ModelBox.class,"quadList","field_78254_i");
    }
    public record Polygon(Vertex[] vertices) {}
    public record Vertex(float x,float y,float z,float u,float v) {}
}
