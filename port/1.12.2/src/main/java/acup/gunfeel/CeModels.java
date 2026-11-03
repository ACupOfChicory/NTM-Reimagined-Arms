package acup.gunfeel;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import com.hbm.render.loader.GroupHandle;
import com.hbm.render.loader.GroupObject;
import com.hbm.render.loader.HFRWavefrontObject;
import com.hbm.render.loader.WaveFrontObjectVAO;

// port: new for 1.12.2. NTM CE uploads a model's groups to the GPU (WaveFrontObjectVAO) and its renderers draw a part
// through a GroupHandle, often one resolved once into a static field (ItemRenderG3: ResourceManager.g3.resolve("Body")),
// so the part's name and faces are gone at the draw. The VAO's source model and the handle of each of its groups are
// kept here when the VAO loads (MixinVaoModel), and a handle's draw (MixinGroupHandle) is a part drawn as on 1.7.10
// (renderPart on the source model, see parts).
public final class CeModels {
    private CeModels() {}

    private static final Map<WaveFrontObjectVAO, HFRWavefrontObject> SOURCE = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<GroupHandle, GroupObject> GROUP = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<GroupHandle, HFRWavefrontObject> MODEL = Collections.synchronizedMap(new WeakHashMap<>());

    // the VAO loaded its groups from obj (MixinVaoModel)
    public static void loaded(WaveFrontObjectVAO vao, HFRWavefrontObject obj) {
        if (vao == null || obj == null) return;
        SOURCE.put(vao, obj);
        for (GroupObject g : obj.groupObjects) {
            if (g.name == null) continue;
            GroupHandle h = vao.resolve(g.name);
            if (h == null) continue;
            GROUP.put(h, g);
            MODEL.put(h, obj);
        }
    }

    // the model a ResourceManager field holds (a VAO's source, or the model itself), or null
    public static HFRWavefrontObject source(Object model) {
        if (model instanceof HFRWavefrontObject) return (HFRWavefrontObject) model;
        return model instanceof WaveFrontObjectVAO ? SOURCE.get(model) : null;
    }

    public static int loadedModels() {
        return SOURCE.size();
    }

    // a handle draws its group (MixinGroupHandle): the same as renderPart of that group on the source model
    public static void drawn(GroupHandle h) {
        HFRWavefrontObject model = MODEL.get(h);
        if (model == null) return;
        String name = GROUP.get(h).name;
        MachineMesh.objBegin();
        if (Arms.capturing()) Arms.parts(model, 1, name);
        if (DoorCapture.active()) DoorCapture.parts(model, 1, name);
        if (MachineMesh.active()) MachineMesh.parts(model, 1, name);
        ModelObjParts.hfr(model, 1, name);
        MachineMesh.objEnd();
    }
}
