package acup.gunfeel;

// port: from 26.2's F3+B renderer (src/main/java/acup/gunfeel/ModelDebugRender.java) only the unit cube's triangles,
// which HeadLabelGeometry and the shared tests read; the port draws F3+B in ModelHitDebug
final class ModelDebugRender {
    private ModelDebugRender() {}
    static final float[] CUBE=cube();
    private static float[] cube() {
        float[] data=new float[108]; int at=0;
        for(int axis=0;axis<3;axis++) for(int side=0;side<2;side++) for(int corner:new int[]{0,1,3,0,3,2}) {
            float[] p=new float[3]; p[axis]=side; p[(axis+1)%3]=corner&1; p[(axis+2)%3]=(corner>>1)&1;
            for(float value:p) data[at++]=value;
        }
        return data;
    }
}
