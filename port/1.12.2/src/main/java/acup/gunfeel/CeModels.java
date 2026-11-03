package acup.gunfeel;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import com.hbm.render.loader.GroupHandle;
import com.hbm.render.loader.GroupObject;
import com.hbm.render.loader.HFRWavefrontObject;
import com.hbm.render.loader.WaveFrontObjectVAO;

import net.minecraft.util.ResourceLocation;

// port: new for 1.12.2. NTM CE uploads a model's groups to the GPU (WaveFrontObjectVAO) and its renderers draw a part
// through a GroupHandle, often one resolved once into a static field (ItemRenderG3: ResourceManager.g3.resolve("Body")),
// so the part's name and faces are gone at the draw. When a VAO loads (MixinVaoModel) its file and the name of each of
// its groups' handles are kept here; a handle's draw (MixinGroupHandle) is a part drawn as on 1.7.10 (renderPart on
// the source model).
// NTM CE loads all its ~400 models at start and lets their parsed faces go once they are on the GPU (it keeps only the
// file, to load it again on a resource reload). Keeping every parsed model took 100-150 MB of heap, so a model is
// parsed again from its file only when something here needs its faces (the gun in hand, a mob's hitboxes, a machine or
// door taken), and kept from then on, until the VAO loads again.
public final class CeModels {
    private CeModels() {}

    private static final class Vao {
        final ResourceLocation file;
        final boolean mixed;
        // a model made from a stream has no file: its faces are kept as they were given
        HFRWavefrontObject parsed;
        boolean failed;

        Vao(ResourceLocation file, boolean mixed, HFRWavefrontObject given) {
            this.file = file;
            this.mixed = mixed;
            this.parsed = file == null ? given : null;
        }
    }

    private static final Map<WaveFrontObjectVAO, Vao> VAOS = Collections.synchronizedMap(new WeakHashMap<WaveFrontObjectVAO, Vao>());
    private static final Map<GroupHandle, WaveFrontObjectVAO> OWNER = Collections.synchronizedMap(new WeakHashMap<GroupHandle, WaveFrontObjectVAO>());
    private static final Map<GroupHandle, String> GROUP = Collections.synchronizedMap(new WeakHashMap<GroupHandle, String>());

    // the VAO loaded its groups from obj (MixinVaoModel)
    public static void loaded(WaveFrontObjectVAO vao, HFRWavefrontObject obj) {
        if (vao == null || obj == null) return;
        VAOS.put(vao, new Vao(obj.resource, mixed(obj), obj));
        for (GroupObject g : obj.groupObjects) {
            if (g.name == null) continue;
            GroupHandle h = vao.resolve(g.name);
            if (h == null) continue;
            OWNER.put(h, vao);
            GROUP.put(h, g.name);
        }
    }

    // how the model was read (HFRWavefrontObject(file, mixedMode)), to read it again the same way
    private static java.lang.reflect.Field mixedField;
    private static boolean mixedLooked;

    private static boolean mixed(HFRWavefrontObject obj) {
        if (!mixedLooked) {
            mixedLooked = true;
            try {
                mixedField = HFRWavefrontObject.class.getDeclaredField("allowMixedMode");
                mixedField.setAccessible(true);
            } catch (Exception e) {
                GunFeel.LOGGER.warn("NTM's models: no allowMixedMode, read again without it");
            }
        }
        try {
            return mixedField != null && mixedField.getBoolean(obj);
        } catch (Exception e) {
            return false;
        }
    }

    // the model a ResourceManager field holds (a VAO's source, parsed now if it was not, or the model itself), or null
    public static HFRWavefrontObject source(Object model) {
        if (model instanceof HFRWavefrontObject) return (HFRWavefrontObject) model;
        return model instanceof WaveFrontObjectVAO ? parsed((WaveFrontObjectVAO) model) : null;
    }

    private static HFRWavefrontObject parsed(WaveFrontObjectVAO vao) {
        Vao v = VAOS.get(vao);
        if (v == null) return null;
        synchronized (v) {
            if (v.parsed == null && !v.failed) {
                try {
                    v.parsed = new HFRWavefrontObject(v.file, v.mixed);
                } catch (Exception e) {
                    v.failed = true;
                    GunFeel.LOGGER.warn("Could not read the model {} again: {}", v.file, e.toString());
                }
            }
            return v.parsed;
        }
    }

    public static int loadedModels() {
        return VAOS.size();
    }

    // models whose faces are held (parsed again, or given by a stream)
    public static int parsedModels() {
        int n = 0;
        synchronized (VAOS) {
            for (Vao v : VAOS.values()) if (v.parsed != null) n++;
        }
        return n;
    }

    // a handle draws its group (MixinGroupHandle): the same as renderPart of that group on the source model. Nothing
    // is looked up while nothing takes the faces: NTM's machines draw their parts every frame
    public static void drawn(GroupHandle h) {
        boolean arms = Arms.capturing(), doors = DoorCapture.active(), machines = MachineMesh.active(), mobs = ModelHitClient.capturing();
        if (!arms && !doors && !machines && !mobs) return;
        WaveFrontObjectVAO vao = OWNER.get(h);
        if (vao == null) return;
        HFRWavefrontObject model = parsed(vao);
        if (model == null) return;
        String name = GROUP.get(h);
        MachineMesh.objBegin();
        if (arms) Arms.parts(model, 1, name);
        if (doors) DoorCapture.parts(model, 1, name);
        if (machines) MachineMesh.parts(model, 1, name);
        if (mobs) ModelObjParts.hfr(model, 1, name);
        MachineMesh.objEnd();
    }
}
