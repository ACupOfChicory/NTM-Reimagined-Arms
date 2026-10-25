package acup.gunfeel;

import com.hbm.render.loader.HFRWavefrontObject;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

// Геометрия MobObjVisual читается один раз на visual; покадровые матрицы берёт сам mixin.
// Не импортируем MobObjVisual.Spec: его сигнатуры требуют необязательный Flywheel API.
public final class MobObjPose {
    public record Part(String name, float[] bounds, long mesh) {}
    private MobObjPose() {}

    private static Object read(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException ex) { throw new IllegalStateException(ex); }
    }

    public static List<Part> layout(Object visual) {
        Object spec = read(visual, "spec");
        var mesh = (HFRWavefrontObject) ((Supplier<?>) read(spec, "mesh")).get();
        var parts = (List<?>) read(spec, "parts");
        List<Part> out = new ArrayList<>(parts.size());
        for (Object part : parts) {
            String name = (String) read(part, "name");
            int id = mesh.partId(name);
            if (id < 0 || id >= mesh.groups.length) throw new IllegalStateException("Unknown MobObjVisual group " + name);
            var group = mesh.groups[id];
            float[] bounds = group.bounds().clone();
            boolean volume = bounds[3] > bounds[0] && bounds[4] > bounds[1] && bounds[5] > bounds[2];
            out.add(new Part(name, bounds, volume ? ModelMeshCapture.mesh(group).id() : 0));
        }
        return List.copyOf(out);
    }

    public static List<ModelBox> boxes(List<Part> layout, org.joml.Matrix4f[] poses, boolean[] visible, org.joml.Vector3f position) {
        if (layout.size() != poses.length || visible.length != poses.length) return List.of();
        List<ModelBox> out = new ArrayList<>(poses.length);
        for (int i = 0; i < poses.length; i++) if (visible[i]) {
            Part part = layout.get(i);
            ModelBox box = MeshModelPose.box(part.name(), part.bounds(), poses[i], -position.x, -position.y, -position.z);
            if (box != null) out.add(box.withMesh(part.mesh()));
        }
        return List.copyOf(out);
    }
}
