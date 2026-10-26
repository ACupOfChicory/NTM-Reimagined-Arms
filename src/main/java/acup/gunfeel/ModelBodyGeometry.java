package acup.gunfeel;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.monster.breeze.BreezeModel;
import net.minecraft.client.model.animal.sheep.SheepModel;
import net.minecraft.client.model.animal.sheep.SheepFurModel;
import net.minecraft.client.model.animal.sheep.BabySheepModel;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.SheepRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;

// Breeze рисует ветер отдельной моделью, но анимирует те же узлы в основной.
// Берём только геометрию слоя; setupAnim повторно не вызывается.
public final class ModelBodyGeometry {
    private static final Identifier WIND=Identifier.withDefaultNamespace("textures/entity/breeze/breeze_wind.png");
    private static final Identifier WOOL=Identifier.withDefaultNamespace("textures/entity/sheep/sheep_wool.png");
    private static final Identifier BABY_WOOL=Identifier.withDefaultNamespace("textures/entity/sheep/sheep_wool_baby.png");
    private record Layer(Map<ModelPart,List<ModelPart.Cube>> byPart,java.util.Set<ModelPart.Cube> cubes) {}
    private static final Map<ModelPart,Layer> CACHE=new IdentityHashMap<>();
    private static final Map<Boolean,Map<String,List<ModelPart.Cube>>> WOOL_GEOMETRY=new java.util.concurrent.ConcurrentHashMap<>();
    private static Map<String,List<ModelPart.Cube>> windGeometry;
    private ModelBodyGeometry() {}

    static synchronized Map<String,List<ModelPart.Cube>> wind(ModelPoseCapture.Parts access) {
        if(windGeometry!=null) return windGeometry;
        Map<String,List<ModelPart.Cube>> out=new LinkedHashMap<>();
        collect(BreezeModel.createWindLayer().bakeRoot(),"root",access,out);
        windGeometry=out; return out;
    }
    private static void collect(ModelPart root,String path,ModelPoseCapture.Parts access,Map<String,List<ModelPart.Cube>> out) {
        out.put(path,access.cubes(root));
        access.children(root).forEach((name,part) -> collect(part,path+"/"+name,access,out));
    }
    static Map<String,List<ModelPart.Cube>> wool(boolean baby,ModelPoseCapture.Parts access) {
        return WOOL_GEOMETRY.computeIfAbsent(baby,key -> {
            Map<String,List<ModelPart.Cube>> out=new LinkedHashMap<>();
            var root=(key?BabySheepModel.createBodyLayer():SheepFurModel.createFurLayer()).bakeRoot();
            collect(root,"root",access,out); return out;
        });
    }
    public static void supplement(LivingEntity entity,ModelPoseLayout layout,int woolKind) {
        if(entity.getType()==EntityTypes.BREEZE) layout.supplement(wind(ModelPoseCapture.ACCESS),WIND);
        if(woolKind>0) layout.supplement(wool(woolKind==2,ModelPoseCapture.ACCESS),woolKind==2?BABY_WOOL:WOOL,ModelWool.SUFFIX);
    }
    public static ModelPoseCapture.Parts parts(EntityModel<?> model,Identifier texture) {
        return parts(model,texture,null);
    }
    public static ModelPoseCapture.Parts parts(EntityModel<?> model,Identifier texture,EntityRenderState state) {
        var base=ModelTextureMask.parts(texture);
        boolean sheep=model instanceof SheepModel && state instanceof SheepRenderState s && !s.isSheared;
        if(!(model instanceof BreezeModel) && !sheep) return base;
        boolean baby=sheep && ((SheepRenderState)state).isBaby;
        return layerParts(model.root(),base,sheep?wool(baby,ModelPoseCapture.ACCESS):wind(ModelPoseCapture.ACCESS),
                sheep?(baby?BABY_WOOL:WOOL):WIND,sheep?ModelWool.SUFFIX:"",ModelPoseCapture.ACCESS);
    }
    static ModelPoseCapture.Parts layerParts(ModelPart modelRoot,ModelPoseCapture.Parts base,Map<String,List<ModelPart.Cube>> byName,
            Identifier texture,String suffix,ModelPoseCapture.Parts access) {
        var extra=CACHE.computeIfAbsent(modelRoot,root -> {
            Map<ModelPart,List<ModelPart.Cube>> byPart=new IdentityHashMap<>();
            attach(root,"root",byName,byPart,access);
            var cubes=java.util.Collections.newSetFromMap(new IdentityHashMap<ModelPart.Cube,Boolean>());
            byPart.values().forEach(cubes::addAll);
            return new Layer(byPart,cubes);
        });
        return new ModelPoseCapture.Parts() {
            public List<ModelPart.Cube> cubes(ModelPart part) {
                var out=new java.util.ArrayList<>(base.cubes(part));
                for(var cube:extra.byPart().getOrDefault(part,List.of())) if(ModelTextureMask.visible(cube,texture)) out.add(cube);
                return out;
            }
            public Map<String,ModelPart> children(ModelPart part) { return base.children(part); }
            public ModelCubeGeometry geometry(ModelPart.Cube cube) {
                return extra.cubes().contains(cube)?ModelTextureMask.geometry(cube,texture):base.geometry(cube);
            }
            public String partName(String name,ModelPart.Cube cube) { return extra.cubes().contains(cube)?name+suffix:base.partName(name,cube); }
        };
    }
    private static void attach(ModelPart root,String name,Map<String,List<ModelPart.Cube>> byName,Map<ModelPart,List<ModelPart.Cube>> out,ModelPoseCapture.Parts access) {
        out.put(root,byName.getOrDefault(name,List.of()));
        access.children(root).forEach((key,child) -> attach(child,name+"/"+key,byName,out,access));
    }
    public static synchronized void clear() { CACHE.clear(); WOOL_GEOMETRY.clear(); windGeometry=null; }
}
