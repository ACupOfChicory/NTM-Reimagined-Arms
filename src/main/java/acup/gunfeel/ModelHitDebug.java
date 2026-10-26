package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.gizmos.GizmoProperties;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

// Видимые цели выбираются ванилью во время extract. Сетка и обводка берутся
// из текущей применённой позы после завершения отрисовки мира.
public final class ModelHitDebug {
    private record Target(Entity entity,AABB fallback) {}
    private static final List<Target> TARGETS=new ArrayList<>();
    public static final GizmoProperties DEFERRED=new GizmoProperties() {
        public GizmoProperties setAlwaysOnTop() { return this; }
        public GizmoProperties persistForMillis(int ms) { return this; }
        public GizmoProperties fadeOut() { return this; }
    };
    private ModelHitDebug() {}
    public static void beginFrame() { TARGETS.clear(); ModelDebugRender.clear(); }
    public static boolean defer(Entity entity,AABB fallback) {
        if(!(entity instanceof LivingEntity) || !ModelHitClient.enabled()
                || !acup.gunfeel.compat.Feature.MODEL_HITBOXES.on()) return false;
        TARGETS.add(new Target(entity,fallback)); return true;
    }
    public static void captureTerrain() { ModelDebugRender.captureTerrain(!TARGETS.isEmpty()); }
    public static void render(org.joml.Matrix4fc view) {
        var stack=com.mojang.blaze3d.systems.RenderSystem.getModelViewStack();
        stack.pushMatrix(); stack.set(view);
        try { render(); } finally { stack.popMatrix(); }
    }
    private static void render() {
        if(TARGETS.isEmpty()) return;
        List<ModelDebugRender.Shape> shapes=new ArrayList<>();
        int id=0;
        for(var target:TARGETS) {
            var boxes=ModelHits.worldBoxes(target.entity());
            boolean custom=!boxes.isEmpty();
            MobSmoke.debug(target.entity(),custom);
            if(!custom) {
                var b=target.fallback();
                boxes=List.of(new ModelBox("fallback",b.minX,b.minY,b.minZ,b.getXsize(),0,0,0,b.getYsize(),0,0,0,b.getZsize()));
            }
            shapes.add(new ModelDebugRender.Shape(++id,custom,boxes,ModelContours.edges(boxes)));
        }
        TARGETS.clear(); ModelDebugRender.draw(shapes); ModelDebugRender.probe();
    }
}
