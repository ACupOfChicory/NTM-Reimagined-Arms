package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import acup.gunfeel.mixin.mc.ModelPartAccess;

// Общий обход для игры и проверки настоящих моделей Minecraft без окна.
public final class ModelPoseCapture {
    public interface Parts {
        List<ModelPart.Cube> cubes(ModelPart part);
        Map<String, ModelPart> children(ModelPart part);
        default ModelCubeGeometry geometry(ModelPart.Cube cube) { return ModelTextureMask.geometry(cube,null); }
    }
    public record Result(boolean complete, int nodes, List<ModelBox> boxes) {}
    public static final Parts ACCESS = new Parts() {
        public List<ModelPart.Cube> cubes(ModelPart p) { return ((ModelPartAccess) (Object) p).gunfeel$cubes(); }
        public Map<String, ModelPart> children(ModelPart p) { return ((ModelPartAccess) (Object) p).gunfeel$children(); }
    };

    private ModelPoseCapture() {}

    public static Result collect(ModelPart root, Matrix4f matrix, Vector3f renderPosition, Parts parts) {
        PoseStack stack = new PoseStack();
        stack.last().pose().set(matrix);
        return collect(root, stack, -renderPosition.x, -renderPosition.y, -renderPosition.z, parts);
    }

    public static Result collect(ModelPart root, PoseStack stack, double x, double y, double z, Parts parts) {
        List<ModelBox> boxes = new ArrayList<>();
        int[] nodes = {0};
        boolean complete = traverse("root", root, stack, x, y, z, boxes, nodes, 0, parts);
        return new Result(complete, nodes[0], List.copyOf(boxes));
    }

    private static boolean traverse(String name, ModelPart part, PoseStack stack, double x, double y, double z,
            List<ModelBox> boxes, int[] nodes, int depth, Parts parts) {
        if (++nodes[0] > 2048 || depth > 32 || name.length() > ModelBox.MAX_PART_NAME) return false;
        if (!part.visible) return true;
        stack.pushPose();
        try {
            part.translateAndRotate(stack);
            if (!part.skipDraw) for (ModelPart.Cube cube : parts.cubes(part)) {
                parts.geometry(cube).append(boxes,name,stack.last().pose(),x,y,z);
                if (boxes.size() > ModelPosePayload.MAX_BOXES) return false;
            }
            for (var entry : parts.children(part).entrySet())
                if (!traverse(name + "/" + entry.getKey(), entry.getValue(), stack, x, y, z, boxes, nodes, depth + 1, parts)) return false;
            return true;
        } finally { stack.popPose(); }
    }
}
