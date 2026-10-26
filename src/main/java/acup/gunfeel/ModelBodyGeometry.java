package acup.gunfeel;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.monster.breeze.BreezeModel;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;

// Breeze рисует ветер отдельной моделью, но анимирует те же узлы в основной.
// Берём только геометрию слоя; setupAnim повторно не вызывается.
public final class ModelBodyGeometry {
    private static final Identifier WIND=Identifier.withDefaultNamespace("textures/entity/breeze/breeze_wind.png");
    private static final Map<ModelPart,Map<ModelPart,List<ModelPart.Cube>>> CACHE=new IdentityHashMap<>();
    private ModelBodyGeometry() {}

    static Map<String,List<ModelPart.Cube>> wind(ModelPoseCapture.Parts access) {
        Map<String,List<ModelPart.Cube>> out=new LinkedHashMap<>();
        collect(BreezeModel.createWindLayer().bakeRoot(),"root",access,out);
        return out;
    }
    private static void collect(ModelPart root,String path,ModelPoseCapture.Parts access,Map<String,List<ModelPart.Cube>> out) {
        out.put(path,access.cubes(root));
        access.children(root).forEach((name,part) -> collect(part,path+"/"+name,access,out));
    }
    public static void supplement(LivingEntity entity,ModelPoseLayout layout) {
        if(entity.getType()==EntityTypes.BREEZE) layout.supplement(wind(ModelPoseCapture.ACCESS),WIND);
    }
    public static ModelPoseCapture.Parts parts(EntityModel<?> model,Identifier texture) {
        var base=ModelTextureMask.parts(texture);
        if(!(model instanceof BreezeModel)) return base;
        var extra=CACHE.computeIfAbsent(model.root(),root -> {
            var byName=wind(ModelPoseCapture.ACCESS);
            Map<ModelPart,List<ModelPart.Cube>> byPart=new IdentityHashMap<>();
            attach(root,"root",byName,byPart);
            return byPart;
        });
        return new ModelPoseCapture.Parts() {
            public List<ModelPart.Cube> cubes(ModelPart part) {
                var out=new java.util.ArrayList<>(base.cubes(part));
                for(var cube:extra.getOrDefault(part,List.of())) if(ModelTextureMask.visible(cube,WIND)) out.add(cube);
                return out;
            }
            public Map<String,ModelPart> children(ModelPart part) { return base.children(part); }
            public ModelCubeGeometry geometry(ModelPart.Cube cube) {
                boolean wind=extra.values().stream().anyMatch(list -> list.contains(cube));
                return ModelTextureMask.geometry(cube,wind?WIND:texture);
            }
        };
    }
    private static void attach(ModelPart root,String name,Map<String,List<ModelPart.Cube>> byName,Map<ModelPart,List<ModelPart.Cube>> out) {
        out.put(root,byName.getOrDefault(name,List.of()));
        ModelPoseCapture.ACCESS.children(root).forEach((key,child) -> attach(child,name+"/"+key,byName,out));
    }
    public static void clear() { CACHE.clear(); }
}
