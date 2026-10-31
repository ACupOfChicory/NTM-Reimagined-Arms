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
        var bounds=ModelMesh.get(box.mesh()).boundsData();
        return bounds[0]==bounds[3] || bounds[1]==bounds[4] || bounds[2]==bounds[5];
    }
    public static List<ModelBox> filter(LivingEntity entity,List<ModelBox> boxes) {
        var size=entity.getDimensions(Pose.STANDING);
        return filter(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()),size.width(),size.height(),boxes);
    }
    static List<ModelBox> filter(net.minecraft.resources.Identifier type,float width,float height,List<ModelBox> boxes) {
        return AvatarHitPolicy.avatar(type)?boxes:filter(width,height,boxes);
    }
    static List<ModelBox> filter(float width,float height,List<ModelBox> boxes) {
        if(!large(width,height)) return boxes;
        var solid=new java.util.ArrayList<ModelBox>();
        for(var box:boxes) {
            if(box.mesh()==0) solid.add(box);
            else volumeOf(ModelMesh.get(box.mesh())).ifPresent(mesh -> solid.add(mesh.id()==box.mesh()?box:box.withMesh(mesh.id())));
        }
        return solid.isEmpty() && !boxes.isEmpty()?List.of(Empty.BOX):solid;
    }
    private static java.util.Optional<ModelMesh> volumeOf(ModelMesh mesh) {
        var volume=mesh.volume;
        if(volume==null) mesh.volume=volume=java.util.Optional.ofNullable(volume(mesh));
        return volume;
    }
    // Плоские островки могут находиться внутри объёмной OBJ-группы и быть
    // повёрнуты под любым углом. Ограничивающая коробка этого не определяет.
    static ModelMesh volume(ModelMesh mesh) {
        float[] data=mesh.triangles(); int count=data.length/9;
        int[] parent=new int[count]; for(int i=0;i<count;i++) parent[i]=i;
        var vertices=new java.util.HashMap<String,Integer>();
        for(int i=0;i<count;i++) for(int v=0;v<9;v+=3) {
            int p=i*9+v;
            String key=Math.round(data[p]*1e6)+":"+Math.round(data[p+1]*1e6)+":"+Math.round(data[p+2]*1e6);
            Integer old=vertices.putIfAbsent(key,i);
            if(old!=null) parent[root(parent,i)]=root(parent,old);
        }
        var groups=new java.util.LinkedHashMap<Integer,java.util.List<Integer>>();
        for(int i=0;i<count;i++) groups.computeIfAbsent(root(parent,i),k -> new java.util.ArrayList<>()).add(i);
        var keep=new java.util.ArrayList<Integer>();
        for(var group:groups.values()) {
            int p=group.getFirst()*9;
            double ux=data[p+3]-data[p],uy=data[p+4]-data[p+1],uz=data[p+5]-data[p+2];
            double vx=data[p+6]-data[p],vy=data[p+7]-data[p+1],vz=data[p+8]-data[p+2];
            double nx=uy*vz-uz*vy,ny=uz*vx-ux*vz,nz=ux*vy-uy*vx;
            double tolerance=Math.sqrt(nx*nx+ny*ny+nz*nz)*1e-6;
            boolean volume=false;
            for(int t:group) for(int v=0;v<9;v+=3) {
                int a=t*9+v;
                if(Math.abs(nx*(data[a]-data[p])+ny*(data[a+1]-data[p+1])+nz*(data[a+2]-data[p+2]))>tolerance) volume=true;
            }
            if(volume) keep.addAll(group);
        }
        if(keep.size()==count) return mesh;
        if(keep.isEmpty()) return null;
        float[] result=new float[keep.size()*9];
        for(int i=0;i<keep.size();i++) System.arraycopy(data,keep.get(i)*9,result,i*9,9);
        return ModelMesh.register(new ModelMesh(result));
    }
    private static int root(int[] parents,int i) {
        while(parents[i]!=i) { parents[i]=parents[parents[i]]; i=parents[i]; } return i;
    }
    // Известная пустая геометрия не должна возвращаться к ванильной коробке.
    // Нулевая по площади грань кодирует её в существующем сетевом формате:
    // нет ни попаданий, ни рёбер, ни изменения протокола.
    private static final class Empty {
        static final ModelBox BOX=new ModelBox("empty_geometry",0,0,0,1,0,0,0,1,0,0,0,1)
                .withMesh(ModelMesh.register(new ModelMesh(new float[9])).id());
    }
}
