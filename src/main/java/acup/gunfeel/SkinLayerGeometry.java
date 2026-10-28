package acup.gunfeel;

import java.lang.reflect.Method;
import java.util.*;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.model.geom.ModelPart;

/** Необязательный мост к публичному API 3D Skin Layers; геометрия запекается один раз на mesh. */
public final class SkinLayerGeometry {
    private record Access(Class<?> injector,Method mesh,Method offset,Method apply,Method render) {}
    private static final Access ACCESS=access();
    private static final Map<Object,Map<Object,ModelSurfaceGeometry>> CACHE=new WeakHashMap<>();
    static long captures,bakes;
    private static boolean warned;
    private SkinLayerGeometry() {}
    private static Access access() {
        try {
            var injector=Class.forName("dev.tr7zw.skinlayers.accessor.ModelPartInjector");
            var mesh=Class.forName("dev.tr7zw.skinlayers.api.Mesh");
            var offset=Class.forName("dev.tr7zw.skinlayers.api.OffsetProvider");
            return new Access(injector,injector.getMethod("getInjectedMesh"),injector.getMethod("getOffsetProvider"),
                    offset.getMethod("applyOffset",PoseStack.class,mesh),mesh.getMethod("render",ModelPart.class,PoseStack.class,VertexConsumer.class,int.class,int.class,int.class));
        } catch(ReflectiveOperationException | LinkageError e) { return null; }
    }
    public static boolean enabled() { return ACCESS!=null; }
    static boolean append(ModelPart part,String name,PoseStack pose,List<ModelBox> out,double x,double y,double z) {
        if(ACCESS==null || !ACCESS.injector().isInstance(part)) return false;
        try {
            var mesh=ACCESS.mesh().invoke(part);if(mesh==null) return false;
            var offset=ACCESS.offset().invoke(part);if(offset==null) return false;
            pose.pushPose();
            try {
                ACCESS.apply().invoke(offset,pose,mesh);
                var byOffset=CACHE.computeIfAbsent(mesh,k->new IdentityHashMap<>());
                var geometry=byOffset.get(offset);
                if(geometry==null) {
                    var consumer=new Vertices();
                    ACCESS.render().invoke(mesh,part,new PoseStack(),consumer,0xF000F0,0,-1);
                    geometry=ModelSurfaceGeometry.bake(consumer.triangles(),null);byOffset.put(offset,geometry);bakes++;
                }
                geometry.append(out,name+"/skin3d",pose.last().pose(),x,y,z);captures++;
                return true;
            } finally { pose.popPose(); }
        } catch(ReflectiveOperationException | RuntimeException ex) {
            if(!warned) { warned=true;GunFeel.LOGGER.warn("Не удалось захватить 3D Skin Layers; сохранён обычный путь",ex); }
            return false;
        }
    }
    static void clear() { CACHE.clear(); }
    private static final class Vertices implements VertexConsumer {
        private float[] xyz=new float[3072];private int used;
        public VertexConsumer addVertex(float x,float y,float z) {
            if(used>=1200000) throw new IllegalArgumentException("Слишком много вершин слоя скина");
            if(used+3>xyz.length) xyz=Arrays.copyOf(xyz,xyz.length*2);
            xyz[used++]=x;xyz[used++]=y;xyz[used++]=z;return this;
        }
        float[] triangles() {
            if(used%12!=0) throw new IllegalArgumentException("Слой скина выдал неполные квады");
            float[] data=new float[used/12*30];int at=0;
            for(int q=0;q<used;q+=12) for(int v:new int[]{0,1,2,0,2,3}) {
                for(int a=0;a<3;a++) data[at++]=xyz[q+v*3+a];at+=2;
            }
            return data;
        }
        public VertexConsumer setColor(int r,int g,int b,int a) { return this; }
        public VertexConsumer setColor(int argb) { return this; }
        public VertexConsumer setUv(float u,float v) { return this; }
        public VertexConsumer setUv1(int u,int v) { return this; }
        public VertexConsumer setUv2(int u,int v) { return this; }
        public VertexConsumer setNormal(float x,float y,float z) { return this; }
        public VertexConsumer setLineWidth(float w) { return this; }
    }
}
