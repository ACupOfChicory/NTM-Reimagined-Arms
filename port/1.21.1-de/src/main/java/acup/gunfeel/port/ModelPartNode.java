package acup.gunfeel.port;

import net.minecraft.client.model.geom.ModelPart;

// A model part's place in its model, which 1.21.1's ModelPart does not keep: the part that holds it and the name it
// holds it by, set when the holder is built (mixin/mc/MixinModelPartDraws). 26.2's NtmMeshHits names a part by its path
// as ModelPoseCapture walks the model from its root; here the path is walked back up from the part drawn.
public interface ModelPartNode {
    ModelPart gunfeel$parent();

    String gunfeel$name();

    void gunfeel$hold(ModelPart parent, String name);

    // "root/head/hat": the top part is "root", as ModelPoseCapture names a model's root
    static String path(ModelPart part) {
        var names = new java.util.ArrayDeque<String>();
        ModelPart p = part;
        for (int depth = 0; depth < 64; depth++) {
            var node = (ModelPartNode) (Object) p;
            ModelPart parent = node.gunfeel$parent();
            if (parent == null) break;
            names.push(node.gunfeel$name());
            p = parent;
        }
        var out = new StringBuilder("root");
        for (String n : names) out.append('/').append(n);
        return out.toString();
    }
}
