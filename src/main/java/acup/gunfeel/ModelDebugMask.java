package acup.gunfeel;

import java.nio.ByteBuffer;
import java.util.*;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;

// Неизменные треугольники загружаются один раз. На кадр отправляются только
// матрицы частей и идентификаторы владельцев, а не мировые вершины тела.
final class ModelDebugMask {
    static final int BATCH=128,STRIDE=80,BLOCK=BATCH*STRIDE;
    static final RenderPipeline PIPELINE=RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
            .withLocation(id("pipeline/model_hit_instances"))
            .withVertexShader(id("core/model_hit_instances")).withFragmentShader(id("core/model_hit_instances"))
            .withVertexBinding(0,DefaultVertexFormat.POSITION)
            .withPrimitiveTopology(com.mojang.blaze3d.PrimitiveTopology.TRIANGLES)
            .withBindGroupLayout(BindGroupLayout.builder().withUniform("HitPoses",UniformType.UNIFORM_BUFFER).build())
            .withColorTargetState(ColorTargetState.DEFAULT).withDepthStencilState(DepthStencilState.DEFAULT).withCull(false).build();
    private record Geometry(GpuBuffer buffer,int vertices) {}
    private record Part(ModelBox box,int owner) {}
    private record Batch(Geometry geometry,List<Part> parts,int first,int count,int offset) {}
    private static final LinkedHashMap<Long,Geometry> GEOMETRY=new LinkedHashMap<>(32,.75F,true);
    private static long geometryBytes;
    private static ByteBuffer staging;
    private static GpuBuffer poses;
    private ModelDebugMask() {}
    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("ntmgunfeel",path); }
    private static Geometry geometry(long mesh) {
        var cached=GEOMETRY.get(mesh);
        if(cached!=null) return cached;
        float[] data=mesh==0?ModelDebugRender.CUBE:ModelMesh.get(mesh).triangleData();
        var bytes=MemoryUtil.memAlloc(data.length*4);
        try {
            for(float f:data) bytes.putFloat(f);
            bytes.flip();
            var buffer=RenderSystem.getDevice().createBuffer(() -> "Model hit static triangles",
                    GpuBuffer.USAGE_VERTEX|GpuBuffer.USAGE_COPY_DST,bytes);
            var result=new Geometry(buffer,data.length/3); GEOMETRY.put(mesh,result); geometryBytes+=buffer.size(); return result;
        } finally { MemoryUtil.memFree(bytes); }
    }
    static void draw(List<ModelDebugRender.Shape> shapes,RenderTarget destination,com.mojang.blaze3d.buffers.GpuBufferSlice transforms) {
        var device=RenderSystem.getDevice();
        int alignment=device.getDeviceInfo().limits().minUniformOffsetAlignment();
        Map<Long,List<Part>> groups=new LinkedHashMap<>();
        for(var shape:shapes) for(var box:shape.boxes())
            groups.computeIfAbsent(box.mesh(),key -> new ArrayList<>()).add(new Part(box,shape.id()));
        List<Batch> batches=new ArrayList<>(); int offset=0;
        for(var group:groups.entrySet()) {
            var geometry=geometry(group.getKey()); var parts=group.getValue();
            for(int first=0;first<parts.size();first+=BATCH) {
                int count=Math.min(BATCH,parts.size()-first);
                batches.add(new Batch(geometry,parts,first,count,offset));
                offset=aligned(offset+count*STRIDE,alignment);
            }
        }
        if(batches.isEmpty()) return;
        // Срез покрывает весь объявленный GLSL-блок. Неиспользованный хвост
        // может перекрывать следующий пакет: shader читает лишь count поз.
        int length=batches.getLast().offset()+BLOCK;
        if(staging==null || staging.capacity()<length) {
            if(staging!=null) MemoryUtil.memFree(staging);
            staging=MemoryUtil.memCalloc(length);
        }
        if(poses==null || poses.size()<length) {
            if(poses!=null) poses.close();
            poses=device.createBuffer(() -> "Model hit instance poses",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_COPY_DST,length);
        }
        var camera=Minecraft.getInstance().gameRenderer.mainCamera().position();
        staging.clear(); staging.limit(length);
        for(var batch:batches) {
            staging.position(batch.offset());
            for(int i=0;i<batch.count();i++) {
                var part=batch.parts().get(batch.first()+i); var b=part.box();
                column(b.ux(),b.uy(),b.uz(),0); column(b.vx(),b.vy(),b.vz(),0); column(b.wx(),b.wy(),b.wz(),0);
                column(b.x()-camera.x,b.y()-camera.y,b.z()-camera.z,1);
                int id=part.owner(); column((id>>16&255)/255F,(id>>8&255)/255F,(id&255)/255F,1);
            }
        }
        staging.position(0);
        device.createCommandEncoder().writeToBuffer(poses.slice(0,length),staging);
        try(var pass=device.createCommandEncoder().createRenderPass(() -> "Model hit instanced ownership",destination.getColorTextureView(),
                Optional.of(new org.joml.Vector4f()),destination.getDepthTextureView(),OptionalDouble.empty())) {
            pass.setPipeline(PIPELINE); RenderSystem.bindDefaultUniforms(pass); pass.setUniform("DynamicTransforms",transforms);
            for(var batch:batches) {
                pass.setVertexBuffer(0,batch.geometry().buffer().slice());
                pass.setUniform("HitPoses",poses.slice(batch.offset(),BLOCK));
                pass.draw(batch.geometry().vertices(),batch.count(),0,0);
            }
        }
        // Удалять буферы можно после всех draw: один кадр может потребовать
        // больше лимита. Постоянный кэш ограничен и числом, и объёмом.
        var iterator=GEOMETRY.entrySet().iterator();
        while((GEOMETRY.size()>256 || geometryBytes>32L*1024*1024) && iterator.hasNext()) {
            var entry=iterator.next(); geometryBytes-=entry.getValue().buffer().size(); entry.getValue().buffer().close(); iterator.remove();
        }
    }
    static int aligned(int value,int alignment) { return (value+alignment-1)/alignment*alignment; }
    private static void column(double x,double y,double z,double w) {
        staging.putFloat((float)x).putFloat((float)y).putFloat((float)z).putFloat((float)w);
    }
    static void clear() {
        for(var geometry:GEOMETRY.values()) geometry.buffer().close();
        GEOMETRY.clear(); geometryBytes=0;
        if(poses!=null) { poses.close(); poses=null; }
        if(staging!=null) { MemoryUtil.memFree(staging); staging=null; }
    }
}
