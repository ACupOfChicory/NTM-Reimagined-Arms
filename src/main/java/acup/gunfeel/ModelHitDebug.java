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
    public record BoxContext(Entity owner,float partial,boolean[] queued) {}
    private static final ThreadLocal<BoxContext> BOX_OWNER=new ThreadLocal<>();
    static int suppressedBoxes;
    static final java.util.Map<Integer,Integer> retainedBoxColors=new java.util.HashMap<>();
    public static BoxContext beginBoxes(Entity owner,float partial) {
        var previous=BOX_OWNER.get(); BOX_OWNER.set(new BoxContext(owner,partial,new boolean[1])); return previous;
    }
    public static void endBoxes(BoxContext previous) {
        if(previous==null) BOX_OWNER.remove(); else BOX_OWNER.set(previous);
    }
    public static boolean replaceLegacyBox(AABB box,net.minecraft.gizmos.GizmoStyle style) {
        var context=BOX_OWNER.get();
        if(context==null) return false;
        var owner=context.owner();
        var parent=ModelPartEntities.parent(owner);
        if(!context.queued()[0] || !ModelHitClient.enabled() || !ModelHits.hasPose(parent==null?owner:parent)) {
            if(MobSmoke.enabled()) retainedBoxColors.merge(style.stroke(),1,Integer::sum);
            return false;
        }
        boolean matches=legacyBox(box,owner,context.partial());
        if(!matches) for(var part:ModelPartEntities.parts(owner))
            if(part!=null && legacyBox(box,part,context.partial())) { matches=true; break; }
        if(!matches) return false;
        if(MobSmoke.enabled()) suppressedBoxes++;
        return true;
    }
    private static boolean legacyBox(AABB drawn,Entity entity,float partial) {
        var raw=entity.getBoundingBox();
        var shifted=raw.move(entity.getPosition(partial).subtract(entity.position()));
        return sameBox(drawn,raw) || sameBox(drawn,shifted);
    }
    private static boolean sameBox(AABB a,AABB b) {
        return Math.abs(a.minX-b.minX)<1e-6 && Math.abs(a.minY-b.minY)<1e-6 && Math.abs(a.minZ-b.minZ)<1e-6
                && Math.abs(a.maxX-b.maxX)<1e-6 && Math.abs(a.maxY-b.maxY)<1e-6 && Math.abs(a.maxZ-b.maxZ)<1e-6;
    }
    public static final GizmoProperties DEFERRED=new GizmoProperties() {
        public GizmoProperties setAlwaysOnTop() { return this; }
        public GizmoProperties persistForMillis(int ms) { return this; }
        public GizmoProperties fadeOut() { return this; }
    };
    private ModelHitDebug() {}
    public static void beginFrame() { TARGETS.clear(); ModelDebugRender.clear(); }
    public static boolean defer(Entity entity,AABB fallback) {
        var parent=ModelPartEntities.parent(entity);
        var context=BOX_OWNER.get();
        if(parent!=null && ModelHitClient.enabled() && ModelHits.hasPose(parent) && targeted(parent)) {
            if(context!=null && context.owner()==entity) context.queued()[0]=true;
            return true;
        }
        if(!(entity instanceof LivingEntity) || !ModelHitClient.enabled()
                || !acup.gunfeel.compat.Feature.MODEL_HITBOXES.on()) return false;
        TARGETS.add(new Target(entity,fallback));
        if(context!=null && context.owner()==entity && ModelHits.hasPose(entity)) context.queued()[0]=true;
        return true;
    }
    private static boolean targeted(Entity entity) {
        for(var target:TARGETS) if(target.entity()==entity) return true;
        return false;
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
        List<List<ModelBox>> all=new ArrayList<>(TARGETS.size());
        var customs=new boolean[TARGETS.size()];
        List<Object> owners=new ArrayList<>(TARGETS.size());
        for(int i=0;i<customs.length;i++) {
            var target=TARGETS.get(i);
            var boxes=ModelHits.worldBoxes(target.entity());
            if(HeadLabels.ENABLED) boxes=HeadLabels.boxes(target.entity(),boxes);
            customs[i]=!boxes.isEmpty();
            MobSmoke.debug(target.entity(),customs[i]);
            if(!customs[i]) {
                var b=target.fallback();
                boxes=List.of(new ModelBox("fallback",b.minX,b.minY,b.minZ,b.getXsize(),0,0,0,b.getYsize(),0,0,0,b.getZsize()));
            }
            all.add(boxes); owners.add(target.entity());
        }
        // Все контуры кадра одним вызовом: недостающие строятся параллельно,
        // а у уже показанного моба — в фоне; кадр ждёт её, только если прежний
        // контур старше правила (ModelContours.LAG).
        // Группы NTM (броня, его OBJ-модели) — каждая своим контуром в своей коробке: он не зависит от позы,
        // строится один раз, на кадр движется только его матрица; остальное (кубы, меши Flywheel) — одним
        // контуром, как прежде (ModelContours.split).
        List<ModelContours.Outline> outlines=null;
        int[] cubes=new int[customs.length],groups=new int[customs.length+1];
        if(!MobBenchMetrics.LEGACY) {
            long contour=MobBenchMetrics.start();
            List<List<ModelBox>> lists=new ArrayList<>(customs.length*2);
            List<Object> listOwners=new ArrayList<>(customs.length*2);
            List<List<ModelBox>> parts=new ArrayList<>();
            for(int i=0;i<customs.length;i++) {
                var split=ModelContours.split(all.get(i));
                cubes[i]=split.together().isEmpty()?-1:lists.size();
                if(cubes[i]>=0) { lists.add(split.together()); listOwners.add(owners.get(i)); }
                groups[i]=parts.size(); parts.addAll(split.groups());
            }
            groups[customs.length]=parts.size();
            // группы мешей — без владельца: их форма не меняется, и прежний контур им не нужен
            int first=lists.size();
            lists.addAll(parts); for(int g=0;g<parts.size();g++) listOwners.add(null);
            outlines=ModelContours.outlines(lists,listOwners);
            for(int i=0;i<=customs.length;i++) groups[i]+=first;
            MobBenchMetrics.add(MobBenchMetrics.CONTOUR,contour);
            // возраст общего контура — по тем же частям: контур лежит в системе первой из них
            if(ContourAge.ENABLED)
                for(int i=0;i<customs.length;i++) if(customs[i] && cubes[i]>=0) ContourAge.check(TARGETS.get(i).entity(),lists.get(cubes[i]),outlines.get(cubes[i]));
        }
        for(int i=0;i<customs.length;i++) {
            var target=TARGETS.get(i); var boxes=all.get(i);
            if(MobBenchMetrics.LEGACY) {
                long contour=MobBenchMetrics.start();
                shapes.add(new ModelDebugRender.Shape(++id,customs[i],boxes,ModelContours.edges(boxes)));
                MobBenchMetrics.add(MobBenchMetrics.CONTOUR,contour);
            } else {
                shapes.add(new ModelDebugRender.Shape(++id,customs[i],boxes,List.of(),cubes[i]<0?null:outlines.get(cubes[i])));
                // маска уже берёт все части моба из первой формы: у групп только линии
                for(int g=groups[i];g<groups[i+1];g++) shapes.add(new ModelDebugRender.Shape(id,customs[i],List.of(),List.of(),outlines.get(g)));
            }
            if(HeadLabels.ENABLED) HeadLabels.overlay(shapes,id,target.entity());
            else HeadDebug.add(shapes,id,target.entity());
        }
        TARGETS.clear();
        if(MobBenchMetrics.ENABLED) MobBenchMetrics.shapes+=shapes.size();
        long draw=MobBenchMetrics.start();
        ModelDebugRender.draw(shapes);
        MobBenchMetrics.add(MobBenchMetrics.DRAW,draw);
        ModelDebugRender.probe(); MobSmoke.contourShot();
    }
}
