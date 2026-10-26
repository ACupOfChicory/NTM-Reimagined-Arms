package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.GizmoProperties;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

// Отбор видимых целей остаётся ванильным. Геометрия читается после анимации
// текущего кадра; обычная проверка глубины скрывает линии за поверхностями.
public final class ModelHitDebug {
    private record Target(Entity entity,AABB fallback) {}
    private record Line(Vec3 a,Vec3 b,int color) {}
    private static final List<Target> TARGETS=new ArrayList<>();
    private static com.mojang.blaze3d.buffers.GpuBuffer buffer;
    public static final GizmoProperties DEFERRED=new GizmoProperties() {
        public GizmoProperties setAlwaysOnTop() { return this; }
        public GizmoProperties persistForMillis(int ms) { return this; }
        public GizmoProperties fadeOut() { return this; }
    };
    private ModelHitDebug() {}
    public static void beginFrame() { TARGETS.clear(); }
    public static boolean defer(Entity entity,AABB fallback) {
        if(!(entity instanceof LivingEntity) || !ModelHitClient.enabled()
                || !acup.gunfeel.compat.Feature.MODEL_HITBOXES.on()) return false;
        TARGETS.add(new Target(entity,fallback));
        return true;
    }
    public static void render() {
        if(TARGETS.isEmpty()) return;
        List<Line> lines=new ArrayList<>();
        for(var target:TARGETS) {
            var parts=ModelHits.outlines(target.entity());
            boolean custom=!parts.isEmpty();
            MobSmoke.debug(target.entity(),custom);
            if(custom) {
                for(var edges:parts) for(int i=0;i<edges.length;i+=2) lines.add(new Line(edges[i],edges[i+1],0xFFFFCC55));
            } else {
                cuboid(lines,target.fallback(),0xFFFFFFFF);
                var b=target.fallback(); double y=b.minY+target.entity().getEyeHeight();
                cuboid(lines,new AABB(b.minX,y-.01,b.minZ,b.maxX,y+.01,b.maxZ),0xFFFF0000);
            }
        }
        TARGETS.clear();
        if(lines.isEmpty()) return;
        var type=net.minecraft.client.renderer.rendertype.RenderTypes.lines();
        var cam=Minecraft.getInstance().gameRenderer.mainCamera().position();
        try(var bytes=new com.mojang.blaze3d.vertex.ByteBufferBuilder(lines.size()*2*type.format().getVertexSize())) {
            var builder=new com.mojang.blaze3d.vertex.BufferBuilder(bytes,type.primitiveTopology(),type.format());
            for(var line:lines) {
                var a=line.a().subtract(cam); var b=line.b().subtract(cam); var n=b.subtract(a);
                if(n.lengthSqr()<1e-16) continue;
                for(var p:new Vec3[]{a,b}) builder.addVertex((float)p.x,(float)p.y,(float)p.z)
                        .setNormal((float)n.x,(float)n.y,(float)n.z).setColor(line.color()).setLineWidth(2.5F);
            }
            try(var mesh=builder.build()) {
                if(mesh==null) return;
                var device=com.mojang.blaze3d.systems.RenderSystem.getDevice();
                var data=mesh.vertexBuffer();
                if(buffer==null || buffer.size()<data.remaining()) {
                    if(buffer!=null) buffer.close();
                    buffer=device.createBuffer(() -> "Model hitbox lines",40,data.remaining());
                }
                device.createCommandEncoder().writeToBuffer(buffer.slice(),data);
                var indices=com.mojang.blaze3d.systems.RenderSystem.getSequentialBuffer(mesh.drawState().primitiveTopology());
                int count=mesh.drawState().indexCount();
                type.prepare().drawFromBuffer(buffer,indices.getBuffer(count),indices.type(),0,0,count);
            }
        }
    }
    private static void cuboid(List<Line> lines,AABB b,int color) {
        for(int i=0;i<8;i++) for(int bit=1;bit<=4;bit<<=1) if((i&bit)==0)
            lines.add(new Line(corner(b,i),corner(b,i|bit),color));
    }
    private static Vec3 corner(AABB b,int i) {
        return new Vec3((i&1)==0?b.minX:b.maxX,(i&2)==0?b.minY:b.maxY,(i&4)==0?b.minZ:b.maxZ);
    }
}
