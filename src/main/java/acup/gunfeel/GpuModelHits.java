package acup.gunfeel;

import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.backend.engine.InstanceHandleImpl;
import dev.engine_room.flywheel.lib.model.part.InstanceTree;
import dev.engine_room.flywheel.lib.vertex.FullVertexView;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

// Читаем именно загруженные в GPU модели и матрицы, независимо от вида моба.
// Сбор выполняется после завершения рабочих потоков Flywheel. Геометрия и
// списки полей кэшируются; на кадр остаются видимость и аффинные матрицы.
public final class GpuModelHits {
    private record Pending(Object visual,LivingEntity entity,float yaw,Vec3 position,int x,int y,int z) {}
    private static final Map<UUID,Pending> PENDING=new ConcurrentHashMap<>();
    private record Layer(ModelSurfaceGeometry geometry,String suffix) {}
    private record Geometry(int generation,boolean avatar,List<Layer> meshes) {}
    private static final Map<Model,Geometry> MODELS=new WeakHashMap<>();
    private record PartPose(Model model,long revision,int generation,String name,double x,double y,double z,List<ModelBox> boxes) {}
    private static final Map<TransformedInstance,PartPose> PARTS=new WeakHashMap<>();
    // Состав частей visual меняется только с новыми экземплярами Flywheel:
    // обход через reflection повторяется после создания любого из них
    // и не реже раза в секунду, а не в каждом кадре.
    private record Walk(long created,long time,List<TransformedInstance> instances,List<String> paths) {}
    private static final Map<Object,Walk> WALKS=new WeakHashMap<>();
    private static final long WALK_REFRESH=1_000_000_000L;
    private static final java.util.concurrent.atomic.AtomicLong CREATED=new java.util.concurrent.atomic.AtomicLong();
    public static void created() { CREATED.incrementAndGet(); }
    private static int partGeneration=-1;
    private static final Set<Class<?>> AVAILABLE=ConcurrentHashMap.newKeySet();
    private static final ClassValue<List<Field>> FIELDS=new ClassValue<>() {
        protected List<Field> computeValue(Class<?> type) {
            var out=new ArrayList<Field>();
            for(Class<?> c=type;c!=null;c=c.getSuperclass()) for(var f:c.getDeclaredFields()) {
                if(Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                Class<?> t=f.getType();
                if(t.isArray()) t=t.getComponentType();
                String n=t.getName();
                if(TransformedInstance.class.isAssignableFrom(t) || InstanceTree.class.isAssignableFrom(t)
                        || n.startsWith("dev.engine_room.vanillin.visuals.Instanced")
                        || n.startsWith("com.hbm.client.render.flywheel.")
                        || n.startsWith("dev.engine_room.vanillin.compose.") || Collection.class.isAssignableFrom(t)) {
                    f.setAccessible(true); out.add(f);
                }
            }
            return List.copyOf(out);
        }
    };
    private static final ClassValue<Method> ORIGIN=new ClassValue<>() {
        protected Method computeValue(Class<?> type) {
            for(Class<?> c=type;c!=null;c=c.getSuperclass()) try {
                var m=c.getDeclaredMethod("renderOrigin"); m.setAccessible(true); return m;
            } catch(NoSuchMethodException ignored) {}
            throw new IllegalStateException("Нет начала координат visual "+type);
        }
    };
    private static final int POSE_OFFSET=poseOffset();
    static { NtmMeshHits.gpuFlush=GpuModelHits::flush; }
    private GpuModelHits() {}
    private static int poseOffset() {
        try { var f=TransformedInstance.class.getDeclaredField("OFF_POSE"); f.setAccessible(true); return f.getInt(null); }
        catch(ReflectiveOperationException ex) { throw new IllegalStateException(ex); }
    }
    public static boolean available(Object visual) { return AVAILABLE.contains(visual.getClass()); }
    public static void offer(Object visual,LivingEntity entity,float yaw,Vec3 position) {
        if(!ModelHitClient.enabled() || !ModelHitClient.eligible(entity)) return;
        try {
            var origin=(net.minecraft.core.Vec3i)ORIGIN.get(visual.getClass()).invoke(visual);
            PENDING.put(entity.getUUID(),new Pending(visual,entity,yaw,position,origin.getX(),origin.getY(),origin.getZ()));
        } catch(ReflectiveOperationException ex) { ModelHitLog.event("client","gpu_origin_failed",entity,ex::toString); }
    }
    private static void flush() {
        int generation=ModelTextureMask.generation();
        if(partGeneration!=generation) { PARTS.clear(); WALKS.clear(); partGeneration=generation; }
        long created=CREATED.get(),now=GunClock.nanos();
        for(var entry:PENDING.entrySet()) if(PENDING.remove(entry.getKey(),entry.getValue())) {
            var p=entry.getValue(); if(!ModelHitClient.eligible(p.entity())) continue;
            try {
                var walk=MobBenchMetrics.LEGACY?null:WALKS.get(p.visual());
                if(walk==null || walk.created()!=created || now-walk.time()>WALK_REFRESH) {
                    Map<TransformedInstance,String> found=new LinkedHashMap<>();
                    collect(p.visual(),found,Collections.newSetFromMap(new IdentityHashMap<>()),0,"root");
                    walk=new Walk(created,now,List.copyOf(found.keySet()),List.copyOf(found.values()));
                    if(!MobBenchMetrics.LEGACY) WALKS.put(p.visual(),walk);
                }
                var instances=walk.instances();
                var out=new ArrayList<ModelBox>();
                if(!AVAILABLE.contains(p.visual().getClass())) ModelHitLog.event("client","gpu_instances",p.entity(),
                        () -> "visual="+p.visual().getClass().getName()+" instances="+instances.size()+" states="+
                        instances.stream().limit(3).map(i -> i.handle().getClass().getName()+":"+
                        (i.handle() instanceof InstanceHandleImpl<?> h?h.state.getClass().getName()+":"+(h.state instanceof GpuInstancerModel):"?")).toList());
                for(int index=0;index<instances.size();index++) {
                    var instance=instances.get(index);
                    if(!instance.handle().isVisible() || !(instance.handle() instanceof InstanceHandleImpl<?> handle)) continue;
                    Object instancer=handle.state;
                    if(!(instancer instanceof GpuInstancerModel)) instancer=dev.engine_room.flywheel.backend.engine.indirect.IndirectInstancer.fromState(handle.state);
                    if(!(instancer instanceof GpuInstancerModel bound) || !(bound.gunfeel$model() instanceof Model model)) continue;
                    var shadow=(GpuPoseAccess)instance;
                    org.joml.Matrix4fc matrix=MobBenchMetrics.LEGACY?null:shadow.gunfeel$pose();
                    if(matrix==null) {
                        long pointer=handle.slabPtr(); if(pointer==0) continue;
                        matrix=new Matrix4f().set(MemoryUtil.memFloatBuffer(pointer+POSE_OFFSET,16));
                        if(!MobBenchMetrics.LEGACY) shadow.gunfeel$seed(matrix);
                    }
                    if(MobSmoke.enabled()) {
                        var actual=new Matrix4f().set(MemoryUtil.memFloatBuffer(handle.slabPtr()+POSE_OFFSET,16));
                        MobSmoke.gpuPose(p.entity(),actual.equals(matrix));
                    }
                    double ox=p.x()-p.position().x,oy=p.y()-p.position().y,oz=p.z()-p.position().z;
                    String path=walk.paths().get(index);
                    long revision=shadow.gunfeel$revision();
                    var cached=MobBenchMetrics.LEGACY?null:PARTS.get(instance);
                    if(cached!=null && cached.model()==model && cached.revision()==revision && cached.generation()==generation
                            && cached.name().equals(path) && cached.x()==ox && cached.y()==oy && cached.z()==oz) {
                        if(MobSmoke.enabled()) MobSmoke.gpuGeometry(p.entity(),cached.boxes().equals(boxes(model,path,matrix,ox,oy,oz,p.entity() instanceof net.minecraft.world.entity.Avatar)));
                        out.addAll(cached.boxes()); continue;
                    }
                    var partBoxes=boxes(model,path,matrix,ox,oy,oz,p.entity() instanceof net.minecraft.world.entity.Avatar);
                    out.addAll(partBoxes);
                    if(!MobBenchMetrics.LEGACY) PARTS.put(instance,new PartPose(model,revision,generation,path,ox,oy,oz,List.copyOf(partBoxes)));
                }
                if(!out.isEmpty()) {
                    AVAILABLE.add(p.visual().getClass());
                    ModelHitClient.publishAt(p.entity(),p.yaw(),List.copyOf(out),"gpu_rendered_geometry",p.position());
                    if(MobSmoke.enabled()) ModelHitClient.applied(p.entity());
                }
            } catch(RuntimeException|ReflectiveOperationException ex) {
                ModelHitLog.event("client","gpu_geometry_failed",p.entity(),ex::toString);
            }
        }
    }
    private static List<ModelBox> boxes(Model model,String path,org.joml.Matrix4fc matrix,double x,double y,double z,boolean avatar) {
        var out=new ArrayList<ModelBox>();
        for(var layer:geometry(model,avatar)) {
            String name=path+layer.suffix();
            if(name.length()>ModelBox.MAX_PART_NAME-5) name=name.substring(name.length()-ModelBox.MAX_PART_NAME+5);
            layer.geometry().append(out,name,matrix,x,y,z);
        }
        return out;
    }
    private static void collect(Object value,Map<TransformedInstance,String> out,Set<Object> seen,int depth,String path) throws IllegalAccessException {
        if(value==null || depth>64 || !seen.add(value)) return;
        if(value instanceof TransformedInstance instance) { out.put(instance,path); return; }
        if(value instanceof InstanceTree tree) {
            if(tree.instance()!=null) out.put(tree.instance(),path);
            for(int i=0;i<tree.childCount();i++) collect(tree.child(i),out,seen,depth+1,path+"/"+tree.childName(i));
            return;
        }
        if(depth>8) return;
        if(value instanceof Collection<?> list) { int i=0; for(var v:list) collect(v,out,seen,depth+1,path+"/"+(i++)); return; }
        if(value.getClass().isArray()) { for(int i=0;i<Array.getLength(value);i++) collect(Array.get(value,i),out,seen,depth+1,path+"/"+i); return; }
        for(var field:FIELDS.get(value.getClass())) collect(field.get(value),out,seen,depth+1,
                field.getName().equals("instances") && depth==0?"root":path+"/"+field.getName());
    }
    private static List<Layer> geometry(Model model,boolean avatar) {
        var cache=MODELS.get(model);
        if(cache!=null && cache.generation()==ModelTextureMask.generation() && cache.avatar()==avatar) return cache.meshes();
        var out=new ArrayList<Layer>();
        for(var configured:model.meshes()) {
            var blend=configured.material().transparency();
            if(blend==dev.engine_room.flywheel.api.material.Transparency.GLINT
                    || blend==dev.engine_room.flywheel.api.material.Transparency.CRUMBLING
                    || blend==dev.engine_room.flywheel.api.material.Transparency.ADDITIVE
                    || blend==dev.engine_room.flywheel.api.material.Transparency.ORDER_INDEPENDENT_ADDITIVE
                    || blend==dev.engine_room.flywheel.api.material.Transparency.LIGHTNING) continue;
            var mesh=configured.mesh(); int count=mesh.vertexCount(),indices=mesh.indexCount();
            long vertices=MemoryUtil.nmemAlloc(count*FullVertexView.STRIDE),index=MemoryUtil.nmemAlloc(indices*4L);
            try {
                var view=new FullVertexView(); view.ptr(vertices); view.vertexCount(count); mesh.write(view);
                mesh.indexSequence().fill(index,indices);
                float[] data=new float[indices*5];
                for(int i=0;i<indices;i++) {
                    int v=MemoryUtil.memGetInt(index+i*4L);
                    data[i*5]=view.x(v); data[i*5+1]=view.y(v); data[i*5+2]=view.z(v);
                    data[i*5+3]=view.u(v); data[i*5+4]=view.v(v);
                }
                var texture=configured.material().texture();
                var material=configured.material();
                // Полупрозрачная замкнутая оболочка задаёт объём тела. Нулевые
                // пиксели её рисунка не должны пробивать этот объём насквозь.
                var unmasked=ModelSurfaceGeometry.bake(data,null);
                boolean translucent=material.transparency()==dev.engine_room.flywheel.api.material.Transparency.TRANSLUCENT
                        || material.transparency()==dev.engine_room.flywheel.api.material.Transparency.ORDER_INDEPENDENT
                        || material.transparency()==dev.engine_room.flywheel.api.material.Transparency.TRANSLUCENT_ALPHA_REPLACE;
                var geometry=!avatar && translucent && unmasked.solid()?unmasked:ModelSurfaceGeometry.bake(data,ModelTextureMask.mask(texture));
                String suffix=texture.getPath().contains("/sheep_wool")?ModelWool.SUFFIX:"";
                var b=geometry.bounds();
                if(texture.getPath().equals("textures/entity/slime/slime.png") && Math.abs(b[3]-b[0]-.5F)<1e-6
                        && Math.abs(b[4]-b[1]-.5F)<1e-6 && Math.abs(b[5]-b[2]-.5F)<1e-6) suffix=ModelBodyGeometry.SHELL_SUFFIX;
                out.add(new Layer(geometry,suffix));
            } finally { MemoryUtil.nmemFree(vertices); MemoryUtil.nmemFree(index); }
        }
        var result=List.copyOf(out); MODELS.put(model,new Geometry(ModelTextureMask.generation(),avatar,result)); return result;
    }
}
