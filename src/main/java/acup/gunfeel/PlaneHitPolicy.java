package acup.gunfeel;

import java.util.List;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;

// EntityTypes.GOAT: .sized(0.9F,1.3F). Сравниваем наибольший размер,
// чтобы правило работало и у высоких, и у широких мобов. Поза стоя сохраняет
// категорию при сне/плавании, но учитывает возраст и масштаб сущности.
public final class PlaneHitPolicy {
    private PlaneHitPolicy() {}
    public static final float LARGE_SIZE=1.3F;
    static boolean large(float width,float height) { return Math.max(width,height)>=LARGE_SIZE; }
    public static boolean flat(ModelBox box) {
        if(box.mesh()==0) return false;
        var bounds=ModelMesh.get(box.mesh()).bounds();
        return bounds[0]==bounds[3] || bounds[1]==bounds[4] || bounds[2]==bounds[5];
    }
    public static List<ModelBox> filter(LivingEntity entity,List<ModelBox> boxes) {
        var size=entity.getDimensions(Pose.STANDING);
        return filter(size.width(),size.height(),boxes);
    }
    static List<ModelBox> filter(float width,float height,List<ModelBox> boxes) {
        if(!large(width,height)) return boxes;
        var solid=boxes.stream().filter(box -> !flat(box)).toList();
        return solid.isEmpty() && !boxes.isEmpty()?List.of(Empty.BOX):solid;
    }
    // Известная пустая геометрия не должна возвращаться к ванильной коробке.
    // Нулевая по площади грань кодирует её в существующем сетевом формате:
    // нет ни попаданий, ни рёбер, ни изменения протокола.
    private static final class Empty {
        static final ModelBox BOX=new ModelBox("empty_geometry",0,0,0,1,0,0,0,1,0,0,0,1)
                .withMesh(ModelMesh.register(new ModelMesh(new float[9])).id());
    }
}
