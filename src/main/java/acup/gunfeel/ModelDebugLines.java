package acup.gunfeel;

import java.util.*;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.*;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

// Контур хранится в системе тела. Вращение и перенос меняют только uniform,
// изменение относительной позы создаёт новый контур и новый буфер.
final class ModelDebugLines {
    static final RenderPipeline PIPELINE=RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
            .withLocation(id("pipeline/model_hit_local_lines"))
            .withVertexShader(id("core/model_hit_local_lines")).withFragmentShader(id("core/model_hit_lines"))
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER1)
            .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS,false)).build();
    private static final RenderType TYPE=type();
    private record Geometry(GpuBuffer buffer,int indices) {}
    private static final LinkedHashMap<List<Vec3[]>,Geometry> CACHE=new LinkedHashMap<>(32,.75F,true);
    private static long bytes;
    private ModelDebugLines() {}
    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("ntmgunfeel",path); }
    private static RenderType type() {
        try {
            var method=RenderType.class.getDeclaredMethod("create",String.class,RenderSetup.class); method.setAccessible(true);
            return (RenderType)method.invoke(null,"model_hit_local_lines",RenderSetup.builder(PIPELINE).createRenderSetup());
        } catch(ReflectiveOperationException ex) { throw new IllegalStateException(ex); }
    }
    private static Geometry geometry(List<Vec3[]> strips) {
        var cached=CACHE.get(strips); if(cached!=null) return cached;
        int count=0; for(var strip:strips) count+=strip.length;
        if(count==0) return null;
        try(var storage=new ByteBufferBuilder(count*TYPE.format().getVertexSize()*2)) {
            var builder=new BufferBuilder(storage,TYPE.primitiveTopology(),TYPE.format());
            for(var strip:strips) for(int i=0;i<strip.length;i+=2) {
                var a=strip[i]; var b=strip[i+1];
                double x=b.x-a.x,y=b.y-a.y,z=b.z-a.z,length=x*x+y*y+z*z;
                if(length<1e-16) continue;
                double inverse=1/Math.sqrt(length);
                builder.addVertex((float)a.x,(float)a.y,(float)a.z).setColor(-1)
                        .setNormal((float)(x*inverse),(float)(y*inverse),(float)(z*inverse)).setLineWidth(2.5F);
                builder.addVertex((float)b.x,(float)b.y,(float)b.z).setColor(-1)
                        .setNormal((float)(x*inverse),(float)(y*inverse),(float)(z*inverse)).setLineWidth(2.5F);
            }
            try(var mesh=builder.build()) {
                if(mesh==null) return null;
                var buffer=RenderSystem.getDevice().createBuffer(() -> "Model hit local contour",
                        GpuBuffer.USAGE_VERTEX|GpuBuffer.USAGE_COPY_DST,mesh.vertexBuffer());
                var result=new Geometry(buffer,mesh.drawState().indexCount());
                CACHE.put(strips,result); bytes+=buffer.size(); return result;
            }
        }
    }
    static void draw(List<ModelDebugRender.Shape> shapes,List<PreparedRenderType.Texture> textures) {
        var camera=Minecraft.getInstance().gameRenderer.mainCamera().position();
        var view=new Matrix4f(RenderSystem.getModelViewStack());
        var prepared=TYPE.prepare();
        var sequential=RenderSystem.getSequentialBuffer(TYPE.primitiveTopology());
        for(var shape:shapes) {
            var outline=shape.outline();
            var geometry=geometry(outline==null?shape.edges():outline.edges());
            if(geometry==null) continue;
            var matrix=new Matrix4f();
            if(outline==null) matrix.translation((float)-camera.x,(float)-camera.y,(float)-camera.z);
            else {
                var f=outline.frame();
                matrix.m00((float)f.u().x).m01((float)f.u().y).m02((float)f.u().z)
                        .m10((float)f.v().x).m11((float)f.v().y).m12((float)f.v().z)
                        .m20((float)f.w().x).m21((float)f.w().y).m22((float)f.w().z)
                        .m30((float)(f.origin().x-camera.x)).m31((float)(f.origin().y-camera.y)).m32((float)(f.origin().z-camera.z));
            }
            int owner=shape.id();
            var transforms=RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f(view).mul(matrix),
                    new Vector4f((owner>>16&255)/255F,(owner>>8&255)/255F,(owner&255)/255F,shape.custom()?1F:128/255F));
            new PreparedRenderType(prepared.pipeline(),prepared.outputTarget(),transforms,prepared.scissorState(),textures)
                    .drawFromBuffer(geometry.buffer(),sequential.getBuffer(geometry.indices()),sequential.type(),0,0,geometry.indices());
        }
        var iterator=CACHE.entrySet().iterator();
        while((CACHE.size()>256 || bytes>32L*1024*1024) && iterator.hasNext()) {
            var entry=iterator.next(); bytes-=entry.getValue().buffer().size(); entry.getValue().buffer().close(); iterator.remove();
        }
    }
    static void clear() {
        for(var geometry:CACHE.values()) geometry.buffer().close();
        CACHE.clear(); bytes=0;
    }
}
