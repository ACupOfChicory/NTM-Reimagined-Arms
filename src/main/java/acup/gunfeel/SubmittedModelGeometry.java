package acup.gunfeel;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;

// Общий выход CustomGeometryRenderer: читаем те же вершины, которые получает
// игра, не вызывая рендер или анимацию второй раз. Названия модов не нужны.
final class SubmittedModelGeometry implements VertexConsumer {
    private static final int MAX_VERTICES=32768, MAX_SHAPES=2048;
    private record Key(Identifier texture,boolean shell,int generation,float[] data) {
        @Override public int hashCode() {
            return 31*(31*(31*java.util.Objects.hashCode(texture)+(shell?1:0))+generation)+Arrays.hashCode(data);
        }
        @Override public boolean equals(Object value) {
            return value instanceof Key key && shell==key.shell && generation==key.generation
                    && java.util.Objects.equals(texture,key.texture) && Arrays.equals(data,key.data);
        }
    }
    private static final Map<Key,ModelSurfaceGeometry> CACHE=new java.util.LinkedHashMap<>(128,.75F,true);
    private final VertexConsumer delegate;
    private float[] vertices=new float[120];
    private int count;
    private boolean overflow;
    private boolean ignored;

    SubmittedModelGeometry(VertexConsumer delegate) { this.delegate=delegate; }
    static void clear() { CACHE.clear(); }
    void ignore() { ignored=true; count=0; }

    @Override public VertexConsumer addVertex(float x,float y,float z) {
        delegate.addVertex(x,y,z);
        if(ignored) return delegate;
        if(count>=MAX_VERTICES) { overflow=true; return this; }
        if((count+1)*5>vertices.length) vertices=Arrays.copyOf(vertices,Math.min(MAX_VERTICES*5,vertices.length*2));
        int p=count++*5; vertices[p]=x; vertices[p+1]=y; vertices[p+2]=z;
        vertices[p+3]=vertices[p+4]=0;
        return this;
    }
    @Override public VertexConsumer setUv(float u,float v) {
        delegate.setUv(u,v);
        if(count>0 && !overflow && !ignored) { vertices[count*5-2]=u; vertices[count*5-1]=v; }
        return this;
    }
    @Override public VertexConsumer setColor(int r,int g,int b,int a) { delegate.setColor(r,g,b,a); return this; }
    @Override public VertexConsumer setColor(int value) { delegate.setColor(value); return this; }
    @Override public VertexConsumer setUv1(int u,int v) { delegate.setUv1(u,v); return this; }
    @Override public VertexConsumer setUv2(int u,int v) { delegate.setUv2(u,v); return this; }
    @Override public VertexConsumer setNormal(float x,float y,float z) { delegate.setNormal(x,y,z); return this; }
    @Override public VertexConsumer setLineWidth(float width) { delegate.setLineWidth(width); return this; }

    List<ModelBox> boxes(PrimitiveTopology topology,Identifier texture,boolean blended,double x,double y,double z) {
        if(overflow) throw new IllegalArgumentException("слишком много вершин custom geometry");
        return capture(Arrays.copyOf(vertices,count*5),topology,texture,blended,x,y,z);
    }

    // Отдельная функция принимает настоящий поток вершин и пригодна для
    // проверки разными моделями, включая не использующие ModelPart.
    static List<ModelBox> capture(float[] raw,PrimitiveTopology topology,Identifier texture,boolean blended,
            double x,double y,double z) {
        if(raw.length%5!=0 || raw.length>MAX_VERTICES*5) throw new IllegalArgumentException("поток вершин");
        if(topology!=PrimitiveTopology.QUADS && topology!=PrimitiveTopology.TRIANGLES) return List.of();
        int stride=topology==PrimitiveTopology.QUADS?4:3;
        if(raw.length/5%stride!=0) throw new IllegalArgumentException("неполный примитив");
        for(int i=0;i<raw.length;i++) if(!Float.isFinite(raw[i]) || (i%5>=3 && Math.abs(raw[i])>1000000))
            throw new IllegalArgumentException("недопустимая координата или UV: индекс="+i+", значение="+raw[i]);
        var result=new ArrayList<ModelBox>();
        int at=0;
        while(at<raw.length/5) {
            // В потоке куба шесть граней. Проверяем их настоящую аффинную
            // геометрию, а не предполагаем, что любые 24 вершины — коробка.
            Basis basis=null; int count=stride;
            if(stride==4 && at+24<=raw.length/5) {
                basis=cube(raw,at); if(basis!=null) count=24;
            }
            if(basis==null) basis=surface(raw,at,stride);
            if(basis!=null) {
                float[] data=triangles(raw,at,count,stride,basis);
                var shape=geometry(data,texture,blended && count==24);
                shape.append(result,"submitted",basis.matrix(),x,y,z);
            }
            at+=count;
        }
        return result;
    }

    private record Basis(Matrix4f matrix,Matrix4f inverse,boolean cube) {}
    private static Vector3f point(float[] raw,int vertex) {
        int p=vertex*5; return new Vector3f(raw[p],raw[p+1],raw[p+2]);
    }
    private static Basis basis(Vector3f p,Vector3f u,Vector3f v,Vector3f w,boolean cube) {
        var matrix=new Matrix4f().m00(u.x).m01(u.y).m02(u.z).m10(v.x).m11(v.y).m12(v.z)
                .m20(w.x).m21(w.y).m22(w.z).m30(p.x).m31(p.y).m32(p.z);
        if(Math.abs(matrix.determinant())<1e-12) return null;
        return new Basis(matrix,new Matrix4f(matrix).invert(),cube);
    }
    private static Basis cube(float[] raw,int at) {
        var p=point(raw,at); var u=point(raw,at+1).sub(p); var v=point(raw,at+3).sub(p);
        if(new Vector3f(u).cross(v).lengthSquared()<1e-14) return null;
        for(int i=4;i<24;i++) {
            var candidate=basis(p,u,v,point(raw,at+i).sub(p),true);
            if(candidate==null) continue;
            boolean valid=true; int corners=0;
            for(int j=0;j<24;j++) {
                var q=candidate.inverse().transformPosition(point(raw,at+j));
                int corner=0;
                for(int a=0;a<3;a++) {
                    float value=q.get(a),rounded=Math.round(value);
                    if(rounded<0 || rounded>1 || Math.abs(value-rounded)>.001F) { valid=false; break; }
                    corner|=(int)rounded<<a;
                }
                if(!valid) break;
                corners|=1<<corner;
            }
            // Все восемь углов и шесть настоящих граней: наклонный меш
            // или шесть независимых плоскостей не превращаются в полный куб.
            if(valid && corners==255 && sixFaces(raw,at,candidate)) return candidate;
        }
        return null;
    }
    private static boolean sixFaces(float[] raw,int at,Basis basis) {
        int faces=0;
        for(int f=0;f<6;f++) {
            int found=-1;
            for(int a=0;a<3;a++) {
                int side=Math.round(basis.inverse().transformPosition(point(raw,at+f*4)).get(a));
                boolean same=true;
                for(int j=1;j<4;j++) if(Math.round(basis.inverse().transformPosition(point(raw,at+f*4+j)).get(a))!=side) same=false;
                if(same) { found=a*2+side; break; }
            }
            if(found<0 || (faces&(1<<found))!=0) return false;
            faces|=1<<found;
        }
        return faces==63;
    }
    private static Basis surface(float[] raw,int at,int count) {
        var p=point(raw,at); var u=point(raw,at+1).sub(p);
        var v=point(raw,at+count-1).sub(p); var w=new Vector3f(u).cross(v);
        if(w.lengthSquared()<1e-14) return null;
        w.normalize(); return basis(p,u,v,w,false);
    }
    private static float[] triangles(float[] raw,int at,int count,int stride,Basis basis) {
        int[] order=stride==4?new int[]{0,1,2,0,2,3}:new int[]{0,1,2};
        float[] out=new float[count/stride*order.length*5]; int p=0;
        for(int face=0;face<count;face+=stride) for(int vertex:order) {
            int index=at+face+vertex; var point=basis.inverse().transformPosition(point(raw,index));
            for(int a=0;a<3;a++) {
                float value=point.get(a),rounded=Math.round(value);
                // У восстановленного аффинного прямоугольника дробный шум
                // после GPU-переноса не должен создавать новый меш каждый кадр.
                out[p++]=basis.cube() || Math.abs(value-rounded)<.001F?rounded:Math.round(value*10000F)/10000F;
            }
            out[p++]=raw[index*5+3]; out[p++]=raw[index*5+4];
        }
        return out;
    }
    private static ModelSurfaceGeometry geometry(float[] raw,Identifier texture,boolean shell) {
        var key=new Key(texture,shell,ModelTextureMask.generation(),raw);
        var geometry=CACHE.get(key);
        if(geometry==null) {
            if(CACHE.size()>=MAX_SHAPES) CACHE.remove(CACHE.keySet().iterator().next());
            geometry=ModelSurfaceGeometry.bake(raw,shell?null:ModelTextureMask.mask(texture));
            CACHE.put(key,geometry);
        }
        return geometry;
    }
}
