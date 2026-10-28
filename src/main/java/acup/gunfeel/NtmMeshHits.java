package acup.gunfeel;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import com.hbm.render.loader.GroupObject;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.SubmitNodeCollector.CustomGeometryRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

// Контекст переносится вместе с настоящим отложенным draw callback. Повторного рендера нет.
public final class NtmMeshHits {
    public static Runnable gpuFlush=() -> {};
    private record Frame(LivingEntity entity, EntityRenderState state, Vec3 offset, float yaw,
            LinkedHashSet<ModelBox> boxes,boolean[] incomplete) {}
    private static final ThreadLocal<Frame> SUBMIT = new ThreadLocal<>(), DRAW = new ThreadLocal<>();
    private static final ThreadLocal<net.minecraft.resources.Identifier> DRAW_TEXTURE=new ThreadLocal<>();
    private static final java.util.Map<Frame, Boolean> PENDING = new java.util.IdentityHashMap<>();
    private record ModelKey(Object model,Object state,org.joml.Matrix4f pose) {
        public boolean equals(Object other) { return other instanceof ModelKey k && model==k.model && state==k.state && pose.equals(k.pose); }
        public int hashCode() { return 31*(31*System.identityHashCode(model)+System.identityHashCode(state))+pose.hashCode(); }
    }
    private static final java.util.Map<ModelKey,Frame> MODELS=new java.util.HashMap<>();
    private static final java.util.Map<Object,ModelSurfaceGeometry> BLOCKS=new java.util.WeakHashMap<>();
    private record Prepared(LivingEntity entity, float yaw, List<ModelBox> boxes,Vec3 origin) {}
    private static final java.util.Map<UUID, Prepared> PREPARED = new java.util.concurrent.ConcurrentHashMap<>();

    private NtmMeshHits() {}

    public static void begin(EntityRenderState state, CameraRenderState camera) {
        SUBMIT.remove();
        if (!ModelHitClient.enabled() || !acup.gunfeel.compat.Feature.MODEL_HITBOXES.on() || !camera.initialized
                || !(state instanceof ModelHitState info) || info.gunfeel$target() == null
                || !ModelHitClient.eligible(info.gunfeel$target())) return;
        LivingEntity entity = info.gunfeel$target();
        Frame frame = new Frame(entity, state, camera.pos.subtract(state.x, state.y, state.z),
                Mth.rotLerp(state.partialTick, entity.yBodyRotO, entity.yBodyRot), new LinkedHashSet<>(),new boolean[1]);
        SUBMIT.set(frame);
    }

    public static void end() { SUBMIT.remove(); }

    public static void model(Object state, Object model,PoseStack stack) {
        Frame frame = SUBMIT.get();
        if(frame!=null && model instanceof EntityModel<?>) {
            MODELS.put(new ModelKey(model,state,new org.joml.Matrix4f(stack.last().pose())),frame); PENDING.put(frame,Boolean.TRUE);
        }
        if (frame != null && frame.state() == state && !(state instanceof LivingEntityRenderState)
                && model instanceof EntityModel<?> entityModel)
            ((ModelHitState) state).gunfeel$primary(entityModel, null);
    }
    // Все реальные дополнительные EntityModel, а не список известных мобов.
    public static void failed(net.minecraft.client.renderer.feature.ModelFeatureRenderer.Submit<?> submit) {
        var frame=MODELS.get(new ModelKey(submit.model(),submit.state(),new org.joml.Matrix4f(submit.pose().pose())));
        if(frame!=null) frame.incomplete()[0]=true;
    }
    public static boolean prepared(net.minecraft.client.renderer.feature.ModelFeatureRenderer.Submit<?> submit) {
        var frame=MODELS.get(new ModelKey(submit.model(),submit.state(),new org.joml.Matrix4f(submit.pose().pose())));
        if(frame==null || !(submit.model() instanceof EntityModel<?> model)) return false;
        var stack=new PoseStack(); stack.last().set(submit.pose());
        var texture=ModelTextureMask.texture(submit.renderType());
        var base=ModelTextureMask.parts(texture);
        boolean avatar=model instanceof net.minecraft.client.model.player.PlayerModel;
        var access=new ModelPoseCapture.Parts() {
            public List<net.minecraft.client.model.geom.ModelPart.Cube> cubes(net.minecraft.client.model.geom.ModelPart part) { return base.cubes(part); }
            public java.util.Map<String,net.minecraft.client.model.geom.ModelPart> children(net.minecraft.client.model.geom.ModelPart part) { return base.children(part); }
            public ModelCubeGeometry geometry(net.minecraft.client.model.geom.ModelPart.Cube cube) {
                var raw=ModelTextureMask.geometry(cube,null); var b=raw.bounds();
                return !avatar && submit.renderType().hasBlending() && b[3]>b[0] && b[4]>b[1] && b[5]>b[2]?raw:base.geometry(cube);
            }
            public boolean custom(net.minecraft.client.model.geom.ModelPart part,String name,PoseStack pose,java.util.List<ModelBox> out,double x,double y,double z) {
                return avatar && SkinLayerGeometry.append(part,name,pose,out,x,y,z);
            }
        };
        var result=ModelPoseCapture.collect(model.root(),stack,frame.offset().x,frame.offset().y,frame.offset().z,
                access);
        if(result.complete()) {
            boolean wool=texture!=null && texture.getPath().contains("/sheep_wool");
            boolean shell=model instanceof net.minecraft.client.model.monster.slime.SlimeModel && model.root().hasChild("cube")
                    && ModelPoseCapture.ACCESS.cubes(model.root().getChild("cube")).stream().anyMatch(c -> Math.abs(c.maxX-c.minX-8)<1e-5);
            String suffix=wool?ModelWool.SUFFIX:shell?ModelBodyGeometry.SHELL_SUFFIX:"";
            for(var box:result.boxes()) frame.boxes().add(!suffix.isEmpty()?new ModelBox(box.part()+suffix,
                    box.x(),box.y(),box.z(),box.ux(),box.uy(),box.uz(),box.vx(),box.vy(),box.vz(),box.wx(),box.wy(),box.wz(),box.mesh()):box);
        } else {
            frame.incomplete()[0]=true;
            ModelHitLog.event("client","prepared_geometry_incomplete",frame.entity(),
                    () -> "model="+model.getClass().getName()+" nodes="+result.nodes()+" boxes="+result.boxes().size());
        }
        return true;
    }
    public static void blocks(PoseStack stack,List<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart> parts) {
        var frame=SUBMIT.get(); if(frame==null) return;
        var geometry=BLOCKS.computeIfAbsent(parts,key -> {
            var data=new java.util.ArrayList<Float>();
            for(var part:parts) {
                var quads=new java.util.ArrayList<>(part.getQuads(null));
                for(var direction:net.minecraft.core.Direction.values()) quads.addAll(part.getQuads(direction));
                for(var quad:quads) for(int i:new int[]{0,1,2,0,2,3}) {
                    var p=quad.position(i); data.add(p.x()); data.add(p.y()); data.add(p.z()); data.add(0F); data.add(0F);
                }
            }
            float[] raw=new float[data.size()]; for(int i=0;i<raw.length;i++) raw[i]=data.get(i);
            return ModelSurfaceGeometry.bake(raw,null);
        });
        var out=new java.util.ArrayList<ModelBox>();
        geometry.append(out,"block_layer",stack.last().pose(),frame.offset().x,frame.offset().y,frame.offset().z);
        frame.boxes().addAll(out); PENDING.put(frame,Boolean.TRUE);
    }

    public static CustomGeometryRenderer wrap(CustomGeometryRenderer renderer,net.minecraft.client.renderer.rendertype.RenderType type) {
        Frame frame = SUBMIT.get();
        if (frame == null) return renderer;
        PENDING.put(frame, Boolean.TRUE);
        return (pose, buffer) -> {
            Frame previous = DRAW.get();
            var previousTexture=DRAW_TEXTURE.get();
            DRAW.set(frame);
            DRAW_TEXTURE.set(ModelTextureMask.texture(type));
            int before=frame.boxes().size();
            var captured=new SubmittedModelGeometry(buffer);
            try {
                renderer.render(pose, captured);
                if(frame.boxes().size()==before) {
                    try {
                        var offset=frame.offset();
                        frame.boxes().addAll(captured.boxes(type.primitiveTopology(),DRAW_TEXTURE.get(),type.hasBlending(),
                                offset.x,offset.y,offset.z));
                    } catch(RuntimeException ex) {
                        frame.incomplete()[0]=true;
                        ModelHitLog.event("client","submitted_geometry_failed",frame.entity(),() -> ex.toString());
                    }
                }
            }
            finally {
                if (previous == null) DRAW.remove(); else DRAW.set(previous);
                if(previousTexture==null) DRAW_TEXTURE.remove(); else DRAW_TEXTURE.set(previousTexture);
            }
        };
    }

    public static void group(GroupObject group, PoseStack.Pose pose,com.mojang.blaze3d.vertex.VertexConsumer buffer,float us,float vs,float uo,float vo) {
        Frame frame = DRAW.get();
        if (frame == null || frame.boxes().size() > ModelPosePayload.MAX_BOXES) return;
        if(buffer instanceof SubmittedModelGeometry captured) captured.ignore();
        long started = System.nanoTime();
        var offset = frame.offset();
        var boxes=new java.util.ArrayList<ModelBox>();
        try {
            ModelMeshCapture.geometry(group,DRAW_TEXTURE.get(),us,vs,uo,vo).append(boxes,group.name,pose.pose(),offset.x,offset.y,offset.z);
            frame.boxes().addAll(boxes);
        } catch(RuntimeException ex) {
            frame.incomplete()[0]=true;
            ModelHitLog.event("client","group_capture_failed",frame.entity(),() -> ex.toString());
        }
        ModelHitClient.captureCost(System.nanoTime() - started);
    }

    public static void flush() {
        SUBMIT.remove(); DRAW.remove();
        for (Frame frame : PENDING.keySet()) if (ModelHitClient.eligible(frame.entity()) && !frame.incomplete()[0] && !frame.boxes().isEmpty())
            {
                boolean published=ModelHitClient.publishAt(frame.entity(), frame.yaw(), List.copyOf(frame.boxes()), "rendered_layers",new Vec3(frame.state().x,frame.state().y,frame.state().z));
                if(published && MobSmoke.enabled()) ModelHitClient.applied(frame.entity());
            }
        PENDING.clear(); MODELS.clear();
        for (var entry : PREPARED.entrySet()) if (PREPARED.remove(entry.getKey(), entry.getValue())) {
            Prepared pose = entry.getValue();
            if (ModelHitClient.eligible(pose.entity()))
                ModelHitClient.publishAt(pose.entity(), pose.yaw(), pose.boxes(), "ntm_flywheel_mesh",pose.origin());
        }
        gpuFlush.run();
    }

    public static void offer(LivingEntity entity, float yaw, List<ModelBox> boxes,org.joml.Vector3f origin) {
        if (PREPARED.size() >= 512 && !PREPARED.containsKey(entity.getUUID())) return;
        PREPARED.put(entity.getUUID(), new Prepared(entity, yaw, List.copyOf(boxes),new Vec3(origin.x,origin.y,origin.z)));
    }

    public static void clear() { SUBMIT.remove(); DRAW.remove(); PENDING.clear(); PREPARED.clear(); MODELS.clear(); BLOCKS.clear(); SubmittedModelGeometry.clear(); }
}
