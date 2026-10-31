package acup.gunfeel;

// port: copy of 26.2 src/main/java/acup/gunfeel/MeshModelPose.java at 0762086; changes marked "port:"
// port: the matrix is 16 floats column by column as GL keeps it (Mat4), not JOML's Matrix4fc; the point and the three
// directions are computed in float as JOML's transformPosition/transformDirection do (m00*x + m10*y + m20*z + m30).

// Одна коробка группы меша в её собственной системе координат, затем настоящая матрица отрисовки.
public final class MeshModelPose {
    private MeshModelPose() {}

    public static ModelBox box(String name, float[] bounds, float[] matrix, double x, double y, double z) {
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
            float[] matrix,double x,double y,double z) {
        if(!hasSurface(dx,dy,dz)) return null;
        float[] p=position(matrix,px,py,pz);
        float[] u=direction(matrix,dx==0?1/16F:dx,0,0);
        float[] v=direction(matrix,0,dy==0?1/16F:dy,0);
        float[] w=direction(matrix,0,0,dz==0?1/16F:dz);
        var box=new ModelBox(part,p[0]+x,p[1]+y,p[2]+z,u[0],u[1],u[2],v[0],v[1],v[2],w[0],w[1],w[2]);
        int axis=dx==0?0:dy==0?1:dz==0?2:-1;
        return axis<0?box:box.withMesh(plane(axis).id());
    }

    // port: JOML transformPosition / transformDirection on a column-major float[16]
    static float[] position(float[] m,float x,float y,float z) {
        return new float[]{m[0]*x+m[4]*y+m[8]*z+m[12],m[1]*x+m[5]*y+m[9]*z+m[13],m[2]*x+m[6]*y+m[10]*z+m[14]};
    }
    static float[] direction(float[] m,float x,float y,float z) {
        return new float[]{m[0]*x+m[4]*y+m[8]*z,m[1]*x+m[5]*y+m[9]*z,m[2]*x+m[6]*y+m[10]*z};
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
