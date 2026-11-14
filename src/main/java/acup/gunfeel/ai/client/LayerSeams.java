package acup.gunfeel.ai.client;

import java.util.HashSet;
import java.util.Set;

import org.joml.Vector3f;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.model.geom.ModelPart;

/**
 * For the stand and the tests: what of a layer's piece above the cut (a sleeve, a legging's thigh) a split limb draws on
 * its forearm or shin, out of the upper half as posed. Its opaque texels there are loose where the upper half's last
 * row in the same column is clear (the piece ends above the joint, and the texel hangs on nothing: a stray piece at
 * the elbow or the knee), at the corner where it is not (the piece goes on over the joint, and the texel fills the
 * corner the bend opens).
 */
public final class LayerSeams {
    /** a texel this far out of the upper half, px, is out of it */
    static final float OUT = 0.05F;
    /** samples across a face per pixel of the model */
    static final int STEPS = 4;

    private LayerSeams() {
    }

    /** loose and corner texels, summed over the limbs */
    public record Count(int loose, int corner) {
        Count plus(Count o) {
            return new Count(loose + o.loose, corner + o.corner);
        }
    }

    /** the texture's opacity at a pixel */
    @FunctionalInterface
    public interface Alpha {
        boolean opaque(int x, int y);
    }

    /** the four limbs of a split root ({@link BotModel#split}), each as posed now */
    public static Count measure(ModelPart root, acup.gunfeel.ModelPoseCapture.Parts cubes, Alpha alpha) {
        Count c = new Count(0, 0);
        for (String limb : new String[] { "right_arm", "left_arm", "right_leg", "left_leg" })
            if (root.hasChild(limb)) c = c.plus(limb(root.getChild(limb), cubes, alpha, limb.endsWith("arm") ? BotModel.ARM_V : BotModel.LEG_V));
        return c;
    }

    static Count limb(ModelPart limb, acup.gunfeel.ModelPoseCapture.Parts cubes, Alpha alpha, int texV) {
        ModelPart lower = BotModel.lower(limb);
        if (lower == null) return new Count(0, 0);
        // the upper half's box, as drawn (grown)
        float[] box = { Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE };
        for (ModelPart.Cube c : cubes.cubes(limb)) for (ModelPart.Polygon p : c.polygons) for (ModelPart.Vertex v : p.vertices()) {
            box[0] = Math.min(box[0], v.x());
            box[1] = Math.min(box[1], v.y());
            box[2] = Math.min(box[2], v.z());
            box[3] = Math.max(box[3], v.x());
            box[4] = Math.max(box[4], v.y());
            box[5] = Math.max(box[5], v.z());
        }
        if (box[0] > box[3]) return new Count(0, 0);
        PoseStack pose = new PoseStack();
        lower.translateAndRotate(pose);
        int sides = texV + 4, cut = sides + (int) BotModel.HALF;
        Set<Long> loose = new HashSet<>(), corner = new HashSet<>();
        int face = 0;
        for (ModelPart.Cube c : cubes.cubes(lower)) for (ModelPart.Polygon p : c.polygons) {
            face++;
            ModelPart.Vertex[] q = p.vertices();
            if (q.length != 4) continue;
            float size = Math.max(dist(q[0], q[1]), dist(q[1], q[2]));
            int n = Math.max(2, (int) Math.ceil(size * STEPS));
            for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
                float s = (i + 0.5F) / n, t = (j + 0.5F) / n;
                float u = lerp(q, s, t, 3), v = lerp(q, s, t, 4);
                int tx = (int) Math.floor(u * BotModel.TEX_W), ty = (int) Math.floor(v * BotModel.TEX_H);
                // the side rows above the cut only, opaque
                if (ty < sides || ty >= cut || !alpha.opaque(tx, ty)) continue;
                Vector3f at = pose.last().pose().transformPosition(lerp(q, s, t, 0) / 16F, lerp(q, s, t, 1) / 16F, lerp(q, s, t, 2) / 16F,
                        new Vector3f()).mul(16F);
                boolean out = at.x < box[0] - OUT || at.y < box[1] - OUT || at.z < box[2] - OUT
                        || at.x > box[3] + OUT || at.y > box[4] + OUT || at.z > box[5] + OUT;
                if (!out) continue;
                long key = ((long) face << 32) | ((long) tx << 16) | ty;
                (alpha.opaque(tx, cut - 1) ? corner : loose).add(key);
            }
        }
        return new Count(loose.size(), corner.size());
    }

    /** a quad's vertex value across it: 0..2 the position, 3 u, 4 v */
    private static float lerp(ModelPart.Vertex[] q, float s, float t, int what) {
        return (1 - s) * (1 - t) * get(q[0], what) + s * (1 - t) * get(q[1], what) + s * t * get(q[2], what) + (1 - s) * t * get(q[3], what);
    }

    private static float dist(ModelPart.Vertex a, ModelPart.Vertex b) {
        return (float) Math.sqrt((a.x() - b.x()) * (a.x() - b.x()) + (a.y() - b.y()) * (a.y() - b.y()) + (a.z() - b.z()) * (a.z() - b.z()));
    }

    private static float get(ModelPart.Vertex v, int what) {
        return switch (what) {
            case 0 -> v.x();
            case 1 -> v.y();
            case 2 -> v.z();
            case 3 -> v.u();
            default -> v.v();
        };
    }
}
