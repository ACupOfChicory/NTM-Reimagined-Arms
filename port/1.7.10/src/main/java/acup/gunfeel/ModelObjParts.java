package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;

import com.hbm.render.loader.HFRWavefrontObject;
import com.hbm.render.loader.S_Face;
import com.hbm.render.loader.S_GroupObject;

import net.minecraftforge.client.model.obj.Face;
import net.minecraftforge.client.model.obj.GroupObject;
import net.minecraftforge.client.model.obj.Vertex;
import net.minecraftforge.client.model.obj.WavefrontObject;

// port: new for 1.7.10. The OBJ groups an NTM mob's model draws while its model is being taken (ModelHitClient): from
// NTM's own loader (HFRWavefrontObject, the glyphids) and Forge's (WavefrontObject: the crabs, the block spider, the
// mask man, which NTM loads with AdvancedModelLoader). mode as the render methods: 0 renderAll, 1 renderOnly /
// renderPart (names), 2 renderAllExcept (names).
public final class ModelObjParts {
    private ModelObjParts() {}

    public static void hfr(HFRWavefrontObject model, int mode, String... names) {
        if (model == null || !ModelHitClient.capturing()) return;
        for (S_GroupObject g : model.groupObjects) if (drawn(g.name, mode, names)) ModelHitClient.group(g, g.name, () -> {
            List<float[]> out = new ArrayList<>();
            for (S_Face f : g.faces) out.add(corners(f.vertices));
            return out;
        });
    }

    public static void forge(WavefrontObject model, int mode, String... names) {
        if (model == null || !ModelHitClient.capturing()) return;
        for (GroupObject g : model.groupObjects) if (drawn(g.name, mode, names)) ModelHitClient.group(g, g.name, () -> {
            List<float[]> out = new ArrayList<>();
            for (Face f : g.faces) out.add(corners(f.vertices));
            return out;
        });
    }

    private static boolean drawn(String group, int mode, String[] names) {
        if (mode == 0) return true;
        boolean named = false;
        for (String n : names) named |= n.equalsIgnoreCase(group);
        return mode == 1 ? named : !named;
    }

    private static float[] corners(Vertex[] v) {
        if (v == null) return new float[0];
        float[] out = new float[v.length * 3];
        for (int i = 0; i < v.length; i++) {
            out[i * 3] = v[i].x;
            out[i * 3 + 1] = v[i].y;
            out[i * 3 + 2] = v[i].z;
        }
        return out;
    }
}
