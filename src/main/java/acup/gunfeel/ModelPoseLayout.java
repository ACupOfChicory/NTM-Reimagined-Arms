package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;

// Неизменная геометрия Flywheel отделена от уже рассчитанных им анимационных трансформаций.
public final class ModelPoseLayout {
    private final String[] names;
    private final int[] parents;
    private final List<List<ModelPart.Cube>> cubes;
    private final java.util.Map<Identifier, List<List<ModelPart.Cube>>> visible = new java.util.HashMap<>();
    private int maskGeneration = -1;

    public ModelPoseLayout(ModelPart[] parts, String[] names, int[] parents) {
        this.names = java.util.Arrays.stream(names).map(n -> n.isEmpty() ? "root" : "root/" + n).toArray(String[]::new);
        this.parents = parents.clone();
        cubes = new ArrayList<>(parts.length);
        for (var part : parts) cubes.add(List.copyOf(ModelPoseCapture.ACCESS.cubes(part)));
    }

    // Перегрузка для проверки без Mixin и без окна.
    public ModelPoseLayout(String[] names, int[] parents, List<List<ModelPart.Cube>> cubes) {
        this.names = java.util.Arrays.stream(names).map(n -> n.isEmpty() ? "root" : "root/" + n).toArray(String[]::new);
        this.parents = parents.clone(); this.cubes = List.copyOf(cubes);
    }

    public List<ModelBox> boxes(Matrix4f root, float[] transforms, boolean[] draw, Identifier texture) {
        int n = names.length;
        if (n > 2048 || parents.length != n || cubes.size() != n || transforms.length != n * 9 || draw.length != n) return List.of();
        if (maskGeneration != ModelTextureMask.generation()) { visible.clear(); maskGeneration = ModelTextureMask.generation(); }
        var drawnCubes = texture == null ? cubes : visible.computeIfAbsent(texture,
                t -> cubes.stream().map(part -> part.stream().filter(c -> ModelTextureMask.visible(c, t)).toList()).toList());
        Matrix4f[] matrices = new Matrix4f[n];
        List<ModelBox> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (parents[i] >= i || parents[i] < -1) return List.of();
            int k = i * 9;
            Matrix4f m = new Matrix4f(parents[i] < 0 ? root : matrices[parents[i]])
                    .translate(transforms[k] / 16, transforms[k + 1] / 16, transforms[k + 2] / 16)
                    .rotateZYX(transforms[k + 5], transforms[k + 4], transforms[k + 3])
                    .scale(transforms[k + 6], transforms[k + 7], transforms[k + 8]);
            matrices[i] = m;
            if (!draw[i]) continue;
            String name = names[i];
            if (name.length() > ModelBox.MAX_PART_NAME) return List.of();
            for (var cube : drawnCubes.get(i)) {
                if (cube.maxX <= cube.minX || cube.maxY <= cube.minY || cube.maxZ <= cube.minZ) continue;
                if (out.size() >= ModelPosePayload.MAX_BOXES) return List.of();
                Vector3f p = m.transformPosition(cube.minX / 16, cube.minY / 16, cube.minZ / 16, new Vector3f());
                Vector3f u = m.transformDirection((cube.maxX - cube.minX) / 16, 0, 0, new Vector3f());
                Vector3f v = m.transformDirection(0, (cube.maxY - cube.minY) / 16, 0, new Vector3f());
                Vector3f w = m.transformDirection(0, 0, (cube.maxZ - cube.minZ) / 16, new Vector3f());
                out.add(new ModelBox(name, p.x, p.y, p.z, u.x, u.y, u.z, v.x, v.y, v.z, w.x, w.y, w.z));
            }
        }
        return out;
    }
}
