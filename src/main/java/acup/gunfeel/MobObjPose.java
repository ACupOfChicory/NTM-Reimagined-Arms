package acup.gunfeel;

import com.hbm.render.loader.HFRWavefrontObject;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

// Геометрия MobObjVisual читается один раз на visual; покадровые матрицы берёт сам mixin.
// Не импортируем MobObjVisual.Spec: его сигнатуры требуют необязательный Flywheel API.
public final class MobObjPose {
    public record Part(String name, float[] bounds) {}
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
            out.add(new Part(name, mesh.groups[id].bounds().clone()));
        }
        return List.copyOf(out);
    }

    public static List<ModelBox> boxes(List<Part> layout, org.joml.Matrix4f[] poses, boolean[] visible, org.joml.Vector3f position) {
        if (layout.size() != poses.length || visible.length != poses.length) return List.of();
        List<ModelBox> out = new ArrayList<>(poses.length);
        for (int i = 0; i < poses.length; i++) if (visible[i]) {
            Part part = layout.get(i);
            ModelBox box = MeshModelPose.box(part.name(), part.bounds(), poses[i], -position.x, -position.y, -position.z);
            if (box != null) out.add(box);
        }
        return List.copyOf(out);
    }
}
