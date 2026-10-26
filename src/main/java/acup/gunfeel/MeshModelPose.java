package acup.gunfeel;

import org.joml.Matrix4fc;
import org.joml.Vector3f;

// Одна коробка группы меша в её собственной системе координат, затем настоящая матрица отрисовки.
public final class MeshModelPose {
    private MeshModelPose() {}

    public static ModelBox box(String name, float[] bounds, Matrix4fc matrix, double x, double y, double z) {
        if (bounds.length != 6) return null;
        String part = "mesh/" + name;
        if (part.length() > ModelBox.MAX_PART_NAME) part = part.substring(0, ModelBox.MAX_PART_NAME);
        return surface(part,bounds[0],bounds[1],bounds[2],bounds[3]-bounds[0],bounds[4]-bounds[1],bounds[5]-bounds[2],matrix,x,y,z);
    }

    public static boolean hasSurface(float dx,float dy,float dz) {
        return dx>=0 && dy>=0 && dz>=0 && (dx>0?1:0)+(dy>0?1:0)+(dz>0?1:0)>=2;
    }

    // Плоскость хранится сеткой из двух треугольников. Ненулевой служебный базис
    // нужен только для обратной матрицы; столкновение и обводка берутся из сетки.
    public static ModelBox surface(String part,float px,float py,float pz,float dx,float dy,float dz,
            Matrix4fc matrix,double x,double y,double z) {
        if(!hasSurface(dx,dy,dz)) return null;
        var p=matrix.transformPosition(px,py,pz,new Vector3f());
        var u=matrix.transformDirection(dx==0?1/16F:dx,0,0,new Vector3f());
        var v=matrix.transformDirection(0,dy==0?1/16F:dy,0,new Vector3f());
        var w=matrix.transformDirection(0,0,dz==0?1/16F:dz,new Vector3f());
        var box=new ModelBox(part,p.x+x,p.y+y,p.z+z,u.x,u.y,u.z,v.x,v.y,v.z,w.x,w.y,w.z);
        int axis=dx==0?0:dy==0?1:dz==0?2:-1;
        return axis<0?box:box.withMesh(plane(axis).id());
    }

    private static final ModelMesh[] PLANES=new ModelMesh[3];
    private static synchronized ModelMesh plane(int axis) {
        if(PLANES[axis]==null) {
            float[] data=new float[18];
            int[] corners={0,1,3,0,3,2};
            for(int i=0;i<6;i++) {
                data[i*3+(axis+1)%3]=corners[i]&1;
                data[i*3+(axis+2)%3]=(corners[i]>>1)&1;
            }
            PLANES[axis]=ModelMesh.register(new ModelMesh(data));
        }
        return PLANES[axis];
    }
}
