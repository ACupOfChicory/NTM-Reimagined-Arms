package acup.gunfeel;

import java.nio.ByteBuffer;
import java.util.*;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.*;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.renderer.DynamicUniforms;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

// Контур хранится в системе тела. Вращение и перенос меняют только uniform,
// изменение относительной позы создаёт новый контур и новый буфер.
final class ModelDebugLines {
    static final RenderPipeline PIPELINE=RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
            .withLocation(id("pipeline/model_hit_local_lines"))
            .withVertexShader(id("core/model_hit_local_lines")).withFragmentShader(id("core/model_hit_lines"))
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER1)
            .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS,false)).build();
    private static final RenderType TYPE=type();
    private static final class Geometry {
        final GpuBuffer buffer; final int indices; long frame;
        Geometry(GpuBuffer buffer,int indices) { this.buffer=buffer; this.indices=indices; }
    }
    private static final LinkedHashMap<List<Vec3[]>,Geometry> CACHE=new LinkedHashMap<>(256,.75F,true);
    private static final Vector3f NO_OFFSET=new Vector3f();
    private static final Matrix4f NO_TEXTURE=new Matrix4f();
    private static final List<String> DYNAMIC=List.of("DynamicTransforms");
    private static long bytes,frame;
    // the stand (MobBenchmark): line buffers made and their vertices, the vertices written into the frame's buffer
    static long created,createdVertices,streamedVertices;
    private static final ModelDebugLineVertices VERTICES=ModelDebugLineVertices.of(TYPE.format());
    // Lines drawn KEEP_AFTER frames running get a buffer of their own. The others (the outline of a moving mob's cubes,
    // new every frame or two) are written each frame into one buffer, as the mask's poses are, and drawn from their
    // place in it: a buffer of their own a frame cost its vertices through BufferBuilder and the driver's checks on
    // making and dropping it.
    static final int KEEP_AFTER=8;
    private static HashMap<List<Vec3[]>,Integer> seen=new HashMap<>(),seenNow=new HashMap<>();
    private static GpuBuffer stream;
    private static ByteBuffer staging;
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
        if(VERTICES!=null) {
            int vertices=ModelDebugLineVertices.count(strips);
            if(vertices==0) return null;
            var data=staging(vertices*VERTICES.size);
            VERTICES.write(data,0,strips);
            var buffer=RenderSystem.getDevice().createBuffer(() -> "Model hit local contour",
                    GpuBuffer.USAGE_VERTEX|GpuBuffer.USAGE_COPY_DST,data.slice(0,vertices*VERTICES.size));
            return keep(strips,new Geometry(buffer,TYPE.primitiveTopology().indexCount(vertices)),vertices);
        }
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
                        .setNormal((float)(x*inverse),(float)(y*inverse),(float)(z*inverse)).setLineWidth(ModelDebugLineVertices.WIDTH);
                builder.addVertex((float)b.x,(float)b.y,(float)b.z).setColor(-1)
                        .setNormal((float)(x*inverse),(float)(y*inverse),(float)(z*inverse)).setLineWidth(ModelDebugLineVertices.WIDTH);
            }
            try(var mesh=builder.build()) {
                if(mesh==null) return null;
                var buffer=RenderSystem.getDevice().createBuffer(() -> "Model hit local contour",
                        GpuBuffer.USAGE_VERTEX|GpuBuffer.USAGE_COPY_DST,mesh.vertexBuffer());
                return keep(strips,new Geometry(buffer,mesh.drawState().indexCount()),count);
            }
        }
    }
    private static Geometry keep(List<Vec3[]> strips,Geometry result,int vertices) {
        CACHE.put(strips,result); bytes+=result.buffer.size(); created++; createdVertices+=vertices; return result;
    }
    // the staging memory, at least length bytes (createBuffer and writeToBuffer copy out of it before they return)
    private static ByteBuffer staging(int length) {
        if(staging==null || staging.capacity()<length) {
            if(staging!=null) MemoryUtil.memFree(staging);
            staging=MemoryUtil.memAlloc(Math.max(length,staging==null?0:staging.capacity()*2));
        }
        return staging;
    }
    static void draw(List<ModelDebugRender.Shape> shapes,List<PreparedRenderType.Texture> textures) {
        var camera=Minecraft.getInstance().gameRenderer.mainCamera().position();
        var view=new Matrix4f(RenderSystem.getModelViewStack());
        var prepared=TYPE.prepare();
        frame++;
        // Буферы и матрицы готовятся до прохода: создание буфера внутри
        // открытого render pass недопустимо.
        var buffers=new GpuBuffer[shapes.size()];
        int[] indices=new int[shapes.size()],bases=new int[shapes.size()];
        var transforms=new DynamicUniforms.Transform[shapes.size()];
        // the lines written into the frame's buffer: their first vertex there and their vertices
        var streamed=new LinkedHashMap<List<Vec3[]>,int[]>();
        int count=0,maxIndices=0,streamVertices=0;
        for(var shape:shapes) {
            var outline=shape.outline();
            var strips=outline==null?shape.edges():outline.edges();
            var geometry=CACHE.get(strips);
            if(geometry==null && VERTICES!=null) {
                int frames=seen.getOrDefault(strips,0)+1;
                if(frames<KEEP_AFTER) {
                    seenNow.put(strips,frames);
                    var at=streamed.get(strips);
                    if(at==null) {
                        int vertices=ModelDebugLineVertices.count(strips);
                        if(vertices==0) continue;
                        streamed.put(strips,at=new int[]{streamVertices,vertices}); streamVertices+=vertices;
                    }
                    bases[count]=at[0]; indices[count]=TYPE.primitiveTopology().indexCount(at[1]);
                }
            }
            if(indices[count]==0) {
                if(geometry==null) geometry=geometry(strips);
                if(geometry==null) continue;
                geometry.frame=frame;
                buffers[count]=geometry.buffer; indices[count]=geometry.indices;
            }
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
            transforms[count]=new DynamicUniforms.Transform(new Matrix4f(view).mul(matrix),
                    new Vector4f((owner>>16&255)/255F,(owner>>8&255)/255F,(owner&255)/255F,shape.lineAlpha()),NO_OFFSET,NO_TEXTURE);
            maxIndices=Math.max(maxIndices,indices[count++]);
        }
        var swap=seen; seen=seenNow; seenNow=swap; seenNow.clear();
        if(streamVertices>0) {
            int length=streamVertices*VERTICES.size;
            var data=staging(length);
            for(var entry:streamed.entrySet()) VERTICES.write(data,entry.getValue()[0]*VERTICES.size,entry.getKey());
            if(stream==null || stream.size()<length) {
                long size=Math.max(length,stream==null?0:stream.size()*2);
                if(stream!=null) stream.close();
                stream=RenderSystem.getDevice().createBuffer(() -> "Model hit moving contours",GpuBuffer.USAGE_VERTEX|GpuBuffer.USAGE_COPY_DST,size);
            }
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(stream.slice(0,length),data.slice(0,length));
            for(int i=0;i<count;i++) if(buffers[i]==null) buffers[i]=stream;
            streamedVertices+=streamVertices;
        }
        if(count>0) {
            // Одна запись всех матриц и один проход вместо прохода на каждого
            // моба; состояние прохода то же, что у PreparedRenderType.
            var slices=RenderSystem.getDynamicUniforms().writeTransforms(java.util.Arrays.copyOf(transforms,count));
            var sequential=RenderSystem.getSequentialBuffer(TYPE.primitiveTopology());
            var indexBuffer=sequential.getBuffer(maxIndices);
            var target=prepared.outputTarget().getRenderTarget();
            var color=RenderSystem.outputColorTextureOverride!=null?RenderSystem.outputColorTextureOverride:target.getColorTextureView();
            var depth=target.useDepth?(RenderSystem.outputDepthTextureOverride!=null?RenderSystem.outputDepthTextureOverride:target.getDepthTextureView()):null;
            try(var pass=RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Model hit local contours",
                    color,Optional.empty(),depth,OptionalDouble.empty())) {
                pass.setPipeline(prepared.pipeline());
                var scissor=prepared.scissorState();
                if(scissor.enabled()) pass.enableScissor(scissor.x(),scissor.y(),scissor.width(),scissor.height());
                RenderSystem.bindDefaultUniforms(pass);
                for(var texture:textures) pass.bindTexture(texture.name(),texture.textureView(),texture.sampler());
                // Пакет готовит состояние конвейера один раз, а не перед каждым
                // мобом; на каждый draw меняются только буфер и его матрица.
                var draws=new ArrayList<RenderPass.Draw<Object>>(count);
                for(int i=0;i<count;i++) {
                    var slice=slices[i];
                    draws.add(new RenderPass.Draw<>(0,buffers[i],indexBuffer,sequential.type(),0,indices[i],bases[i],
                            (unused,uniforms) -> uniforms.upload("DynamicTransforms",slice)));
                }
                pass.drawMultipleIndexed(draws,indexBuffer,sequential.type(),DYNAMIC,null);
            }
        }
        // Нужное в этом кадре не вытесняется: иначе при множестве мобов
        // буферы пересоздавались бы каждый кадр.
        var iterator=CACHE.entrySet().iterator();
        while((CACHE.size()>2048 || bytes>64L*1024*1024) && iterator.hasNext()) {
            var entry=iterator.next(); if(entry.getValue().frame==frame) break;
            bytes-=entry.getValue().buffer.size(); entry.getValue().buffer.close(); iterator.remove();
        }
    }
    static void clear() {
        for(var geometry:CACHE.values()) geometry.buffer.close();
        CACHE.clear(); bytes=0; seen.clear(); seenNow.clear();
        if(stream!=null) { stream.close(); stream=null; }
    }
}
