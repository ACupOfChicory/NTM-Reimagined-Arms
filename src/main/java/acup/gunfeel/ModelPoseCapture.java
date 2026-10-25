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
                if (cube.maxX <= cube.minX || cube.maxY <= cube.minY || cube.maxZ <= cube.minZ) continue;
                if (boxes.size() >= ModelPosePayload.MAX_BOXES) return false;
                Matrix4f m = stack.last().pose();
                Vector3f origin = m.transformPosition(cube.minX / 16F, cube.minY / 16F, cube.minZ / 16F, new Vector3f());
                Vector3f u = m.transformDirection((cube.maxX - cube.minX) / 16F, 0, 0, new Vector3f());
                Vector3f v = m.transformDirection(0, (cube.maxY - cube.minY) / 16F, 0, new Vector3f());
                Vector3f w = m.transformDirection(0, 0, (cube.maxZ - cube.minZ) / 16F, new Vector3f());
                boxes.add(new ModelBox(name, (float) (origin.x + x), (float) (origin.y + y), (float) (origin.z + z),
                        u.x, u.y, u.z, v.x, v.y, v.z, w.x, w.y, w.z));
            }
            for (var entry : parts.children(part).entrySet())
                if (!traverse(name + "/" + entry.getKey(), entry.getValue(), stack, x, y, z, boxes, nodes, depth + 1, parts)) return false;
            return true;
        } finally { stack.popPose(); }
    }
}
