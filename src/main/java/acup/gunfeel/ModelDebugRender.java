package acup.gunfeel;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import com.mojang.blaze3d.GpuFormat;
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

// Отладочная глубина объединяет мир и точную геометрию мобов без одежды.
// Внешняя оболочка не прячет передние линии; задние скрываются телом.
public final class ModelDebugRender {
    record Shape(int id,boolean custom,List<ModelBox> boxes,List<Vec3[]> edges,ModelContours.Outline outline,float lineAlpha) {
        Shape(int id,boolean custom,List<ModelBox> boxes,List<Vec3[]> edges,ModelContours.Outline outline) { this(id,custom,boxes,edges,outline,custom?1F:128/255F); }
        Shape(int id,boolean custom,List<ModelBox> boxes,List<Vec3[]> edges) { this(id,custom,boxes,edges,null); }
    }
    private static Identifier id(String name) { return Identifier.fromNamespaceAndPath("ntmgunfeel",name); }
    private static final RenderPipeline MASK=RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
            .withLocation(id("pipeline/model_hit_mask")).withPrimitiveTopology(com.mojang.blaze3d.PrimitiveTopology.TRIANGLES)
            .withColorTargetState(ColorTargetState.DEFAULT).withDepthStencilState(DepthStencilState.DEFAULT).withCull(false).build();
    private static final RenderPipeline LINES=RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
            .withLocation(id("pipeline/model_hit_lines")).withFragmentShader(id("core/model_hit_lines"))
            .withVertexShader(id("core/model_hit_lines"))
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER1)
            .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS,false)).build();
    private static final RenderType TYPE=type();
    private static TextureTarget terrain,mask;
    private static GpuBuffer maskBuffer,lineBuffer;
    private static boolean captured;
    private ModelDebugRender() {}
    public static void register(net.neoforged.bus.api.IEventBus bus) {
        bus.addListener(net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent.class,e -> {
            e.registerPipeline(MASK); e.registerPipeline(LINES); e.registerPipeline(ModelDebugMask.PIPELINE); e.registerPipeline(ModelDebugLines.PIPELINE);
        });
    }
    private static RenderType type() {
        try {
            var method=RenderType.class.getDeclaredMethod("create",String.class,RenderSetup.class); method.setAccessible(true);
            return (RenderType)method.invoke(null,"model_hit_lines",RenderSetup.builder(LINES).createRenderSetup());
        } catch(ReflectiveOperationException ex) { throw new IllegalStateException(ex); }
    }
    public static void captureTerrain(boolean needed) {
        captured=false;
        if(!needed) { clear(); return; }
        var main=Minecraft.getInstance().gameRenderer.mainRenderTarget();
        if(terrain==null || terrain.width!=main.width || terrain.height!=main.height) {
            releaseTargets();
            terrain=new TextureTarget("Model hit terrain",main.width,main.height,true,GpuFormat.RGBA8_UNORM);
            mask=new TextureTarget("Model hit ownership",main.width,main.height,true,GpuFormat.RGBA8_UNORM);
        }
        terrain.copyDepthFrom(main); captured=true;
    }
    private static void faces(List<Shape> shapes,RenderTarget destination) {
        if(!MobBenchMetrics.LEGACY) {
            ModelDebugMask.draw(shapes,destination,TYPE.prepare().dynamicTransforms()); return;
        }
        var cam=Minecraft.getInstance().gameRenderer.mainCamera().position();
        int vertices=shapes.stream().flatMap(s -> s.boxes().stream()).mapToInt(b -> b.mesh()==0?36:ModelMesh.get(b.mesh()).triangles().length/3).sum();
        if(vertices==0) return;
        var device=RenderSystem.getDevice();
        try(var bytes=new ByteBufferBuilder(vertices*DefaultVertexFormat.POSITION_COLOR.getVertexSize())) {
            var builder=new BufferBuilder(bytes,com.mojang.blaze3d.PrimitiveTopology.TRIANGLES,DefaultVertexFormat.POSITION_COLOR);
            for(var shape:shapes) for(var box:shape.boxes()) {
                float[] triangles=box.mesh()==0?CUBE:ModelMesh.get(box.mesh()).triangles();
                for(int i=0;i<triangles.length;i+=3) {
                    var p=ModelContours.point(box,triangles[i],triangles[i+1],triangles[i+2]).subtract(cam);
                    builder.addVertex((float)p.x,(float)p.y,(float)p.z).setColor(color(shape.id(),255));
                }
            }
            try(var mesh=builder.build()) {
                maskBuffer=upload(maskBuffer,mesh);
                try(var pass=device.createCommandEncoder().createRenderPass(() -> "Model hit ownership",destination.getColorTextureView(),
                        Optional.of(new org.joml.Vector4f()),destination.getDepthTextureView(),OptionalDouble.empty())) {
                    pass.setPipeline(MASK); RenderSystem.bindDefaultUniforms(pass);
                    pass.setUniform("DynamicTransforms",TYPE.prepare().dynamicTransforms());
                    pass.setVertexBuffer(0,maskBuffer.slice());
                    var indices=RenderSystem.getSequentialBuffer(mesh.drawState().primitiveTopology());
                    int count=mesh.drawState().indexCount(); pass.setIndexBuffer(indices.getBuffer(count),indices.type());
                    pass.drawIndexed(count,1,0,0,0);
                }
            }
        }
    }
    public static void draw(List<Shape> shapes) {
        if(!captured || shapes.isEmpty()) return;
        var cam=Minecraft.getInstance().gameRenderer.mainCamera().position();
        RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(terrain.getDepthTexture(),mask.getDepthTexture(),0,0,0,0,0,mask.width,mask.height);
        faces(shapes,mask);
        if(!MobBenchMetrics.LEGACY) {
            var sampler=RenderSystem.getSamplerCache().getClampToEdge(com.mojang.blaze3d.textures.FilterMode.NEAREST);
            ModelDebugLines.draw(shapes,List.of(new PreparedRenderType.Texture("Sampler0",mask.getColorTextureView(),sampler),
                    new PreparedRenderType.Texture("Sampler1",mask.getDepthTextureView(),sampler)));
            captured=false; return;
        }
        int count=shapes.stream().flatMap(s -> s.edges().stream()).mapToInt(e -> e.length).sum();
        if(count==0) return;
        try(var bytes=new ByteBufferBuilder(count*TYPE.format().getVertexSize())) {
            var builder=new BufferBuilder(bytes,TYPE.primitiveTopology(),TYPE.format());
            for(var shape:shapes) for(var edges:shape.edges()) for(int i=0;i<edges.length;i+=2) {
                var a=edges[i].subtract(cam); var b=edges[i+1].subtract(cam); var n=b.subtract(a);
                if(n.lengthSqr()<1e-16) continue;
                for(var p:new Vec3[]{a,b}) builder.addVertex((float)p.x,(float)p.y,(float)p.z)
                        .setNormal((float)n.x,(float)n.y,(float)n.z).setColor(color(shape.id(),Math.round(shape.lineAlpha()*255))).setLineWidth(2.5F);
            }
            try(var mesh=builder.build()) {
                if(mesh==null) return;
                lineBuffer=upload(lineBuffer,mesh);
                var prepared=TYPE.prepare();
                var sampler=RenderSystem.getSamplerCache().getClampToEdge(com.mojang.blaze3d.textures.FilterMode.NEAREST);
                var textures=List.of(new PreparedRenderType.Texture("Sampler0",mask.getColorTextureView(),sampler),
                        new PreparedRenderType.Texture("Sampler1",mask.getDepthTextureView(),sampler));
                var indices=RenderSystem.getSequentialBuffer(mesh.drawState().primitiveTopology());
                int indicesCount=mesh.drawState().indexCount();
                new PreparedRenderType(prepared.pipeline(),prepared.outputTarget(),prepared.dynamicTransforms(),prepared.scissorState(),textures)
                        .drawFromBuffer(lineBuffer,indices.getBuffer(indicesCount),indices.type(),0,0,indicesCount);
            }
        }
        captured=false;
    }
    private static boolean probed;
    private static int probeDone;
    static boolean probesFinished() { return probeDone==6; }
    static void probe() {
        if(probed || terrain==null || !MobSmoke.enabled() || MobSmoke.review()) return;
        probed=true;
        net.minecraft.client.Screenshot.takeScreenshot(Minecraft.getInstance().gameRenderer.mainRenderTarget(),image -> {
            try(image) { image.writeToFile(java.nio.file.Path.of("../notes/accurate-hitboxes/debug-scene.png")); }
            catch(java.io.IOException ex) { MobSmoke.debugProbe(false,"не удалось сохранить кадр: "+ex); }
        });
        var cam=Minecraft.getInstance().gameRenderer.mainCamera().position();
        var body=new ModelBox("probe",cam.x-1,cam.y+1,cam.z+10,2,0,0,0,2,0,0,0,1);
        var blocker=new ModelBox("blocker",cam.x-2,cam.y,cam.z+8,4,0,0,0,4,0,0,0,1);
        var own=new Shape(1,true,List.of(body),ModelContours.edges(List.of(body)),ModelContours.outline(List.of(body)));
        var other=new Shape(2,true,List.of(blocker),List.of());
        var outer=new ModelBox("outer",cam.x-1.2,cam.y+.8,cam.z+9.8,2.4,0,0,0,2.4,0,0,0,1.4);
        var back=new Shape(1,true,List.of(body),List.<Vec3[]>of(new Vec3[]{ModelContours.point(body,.25,.5,1),ModelContours.point(body,.75,.5,1)}));
        for(int scenario=0;scenario<6;scenario++) {
            var target=new TextureTarget("Debug visibility probe",terrain.width,terrain.height,true,GpuFormat.RGBA8_UNORM);
            var device=RenderSystem.getDevice();
            try(var pass=device.createCommandEncoder().createRenderPass(() -> "Clear debug probe",target.getColorTextureView(),
                    Optional.of(new org.joml.Vector4f()),target.getDepthTextureView(),OptionalDouble.of(0))) {}
            var oldColor=RenderSystem.outputColorTextureOverride; var oldDepth=RenderSystem.outputDepthTextureOverride;
            try {
                RenderSystem.outputColorTextureOverride=target.getColorTextureView();
                RenderSystem.outputDepthTextureOverride=target.getDepthTextureView();
                if(scenario==0) faces(List.of(new Shape(1,true,List.of(outer),List.of())),target);
                if(scenario==3) faces(List.of(other),terrain);
                if(scenario==4) {
                    // Стена была только в предыдущем сценарии. Здесь глубина
                    // окружения пустая, а линию должно скрыть само тело.
                    device.createCommandEncoder().clearDepthTexture(terrain.getDepthTexture(),0);
                }
                List<Shape> selected=scenario==1?List.of(own,other):scenario==2?List.of(other,own):scenario==4?List.of(back):List.of(own);
                if(scenario==5) {
                    var many=new java.util.ArrayList<Shape>();
                    var distant=new ModelBox("outside",cam.x+1000,cam.y,cam.z+10,2,0,0,0,2,0,0,0,1);
                    for(int i=1;i<130;i++) many.add(new Shape(i,true,List.of(distant),List.of()));
                    many.add(new Shape(130,true,List.of(body),own.edges())); selected=many;
                }
                captured=true; draw(selected);
                final int mode=scenario;
                net.minecraft.client.Screenshot.takeScreenshot(target,image -> {
                    try(image) {
                        int orange=0;
                        for(int pixel:image.getPixels()) if((pixel&0xFFFFFF)==0xFFCC55) orange++;
                        try { image.writeToFile(java.nio.file.Path.of("../notes/accurate-hitboxes/debug-gpu-"+mode+".png")); }
                        catch(java.io.IOException ex) { MobSmoke.debugProbe(false,"не удалось сохранить маску: "+ex); }
                        MobSmoke.debugProbe(mode==0 || mode==5?orange>0:orange==0,
                                (mode==0?"внешняя оболочка не скрывает передние линии":mode==1?"чужой моб скрывает линии":mode==2?"перекрытие не зависит от порядка мобов":mode==3?"стена скрывает линии":mode==4?"своё тело скрывает задние линии":"владелец маски сохраняется за границей пакета из 128 частей")+", видимых пикселей="+orange);
                        probeDone++;
                    } finally { target.destroyBuffers(); }
                });
            } finally {
                RenderSystem.outputColorTextureOverride=oldColor; RenderSystem.outputDepthTextureOverride=oldDepth;
            }
        }
    }
    private static int color(int id,int alpha) { return alpha<<24 | id&0xFFFFFF; }
    private static GpuBuffer upload(GpuBuffer buffer,MeshData mesh) {
        var data=mesh.vertexBuffer(); var device=RenderSystem.getDevice();
        if(buffer==null || buffer.size()<data.remaining()) {
            if(buffer!=null) buffer.close(); buffer=device.createBuffer(() -> "Model hit geometry",40,data.remaining());
        }
        device.createCommandEncoder().writeToBuffer(buffer.slice(),data); return buffer;
    }
    public static void clear() {
        ModelContours.clear();
        ModelDebugMask.clear();
        ModelDebugLines.clear();
        releaseTargets();
        if(maskBuffer!=null) { maskBuffer.close(); maskBuffer=null; }
        if(lineBuffer!=null) { lineBuffer.close(); lineBuffer=null; }
    }
    private static void releaseTargets() {
        if(terrain!=null) { terrain.destroyBuffers(); terrain=null; }
        if(mask!=null) { mask.destroyBuffers(); mask=null; }
        captured=false;
    }
    static final float[] CUBE=cube();
    private static float[] cube() {
        float[] data=new float[108]; int at=0;
        for(int axis=0;axis<3;axis++) for(int side=0;side<2;side++) for(int corner:new int[]{0,1,3,0,3,2}) {
            float[] p=new float[3]; p[axis]=side; p[(axis+1)%3]=corner&1; p[(axis+2)%3]=(corner>>1)&1;
            for(float value:p) data[at++]=value;
        }
        return data;
    }
}
