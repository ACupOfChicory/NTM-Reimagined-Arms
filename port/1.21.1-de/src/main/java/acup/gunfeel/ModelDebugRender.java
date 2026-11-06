package acup.gunfeel;

import net.neoforged.bus.api.IEventBus;

// Stub until the model hitbox part is ported (notes/ports/de-1.21.1-progress.md): the root's ModelDebugRender draws
// the model hitboxes for debugging. CUBE is the root's: a unit cube's twelve triangles (HeadSmoke, the tests).
public final class ModelDebugRender {
    static final float[] CUBE=cube();
    private ModelDebugRender() {
    }
    private static float[] cube() {
        float[] data=new float[108]; int at=0;
        for(int axis=0;axis<3;axis++) for(int side=0;side<2;side++) for(int corner:new int[]{0,1,3,0,3,2}) {
            float[] p=new float[3]; p[axis]=side; p[(axis+1)%3]=corner&1; p[(axis+2)%3]=(corner>>1)&1;
            for(float value:p) data[at++]=value;
        }
        return data;
    }

    static void register(IEventBus modBus) {
    }
}
