package acup.gunfeel;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.resources.Identifier;
import com.mojang.blaze3d.platform.NativeImage;

// Пустые UV-области не являются геометрией попадания. Маски и результат для каждого куба кэшируются.
public final class ModelTextureMask {
    public record Alpha(int width, int height, int[] sums) {
        public boolean any(float u0, float v0, float u1, float v1) {
            int x0 = Math.clamp((int) Math.floor(u0 * width + 1e-4), 0, width);
            int y0 = Math.clamp((int) Math.floor(v0 * height + 1e-4), 0, height);
            int x1 = Math.clamp((int) Math.ceil(u1 * width - 1e-4), 0, width);
            int y1 = Math.clamp((int) Math.ceil(v1 * height - 1e-4), 0, height);
            int stride = width + 1;
            return x1 > x0 && y1 > y0 && sums[y1 * stride + x1] - sums[y0 * stride + x1]
                    - sums[y1 * stride + x0] + sums[y0 * stride + x0] > 0;
        }
    }
    private record Cache(Alpha alpha, Map<ModelPart.Cube, Boolean> cubes) {}
    private static final Map<Identifier, Cache> TEXTURES = new java.util.HashMap<>();
    private static final Map<Identifier, Map<ModelPart, List<ModelPart.Cube>>> PARTS = new java.util.HashMap<>();
    private static int generation;

    private ModelTextureMask() {}

    public static Alpha alpha(int width, int height, int[] argb) {
        int stride = width + 1;
        int[] sums = new int[stride * (height + 1)];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++)
            sums[(y + 1) * stride + x + 1] = ((argb[y * width + x] >>> 24) != 0 ? 1 : 0)
                    + sums[y * stride + x + 1] + sums[(y + 1) * stride + x] - sums[y * stride + x];
        return new Alpha(width, height, sums);
    }

    public static boolean visible(ModelPart.Cube cube, Alpha mask) {
        if (mask == null) return true;
        for (var face : cube.polygons) {
            float u0 = Float.POSITIVE_INFINITY, v0 = u0, u1 = Float.NEGATIVE_INFINITY, v1 = u1;
            for (var vertex : face.vertices()) {
                u0 = Math.min(u0, vertex.u()); v0 = Math.min(v0, vertex.v());
                u1 = Math.max(u1, vertex.u()); v1 = Math.max(v1, vertex.v());
            }
            if (mask.any(u0, v0, u1, v1)) return true;
        }
        return false;
    }

    public static boolean visible(ModelPart.Cube cube, Identifier texture) {
        if (texture == null) return true;
        Cache cache = TEXTURES.computeIfAbsent(texture, id -> {
            Alpha mask = null;
            try (var stream = Minecraft.getInstance().getResourceManager().getResourceOrThrow(id).open();
                    var image = NativeImage.read(stream)) {
                mask = alpha(image.getWidth(), image.getHeight(), image.getPixels());
            } catch (Exception ex) {
                org.slf4j.LoggerFactory.getLogger("ntm-gunfeel").debug("Model texture mask {}: {}", id, ex.toString());
            }
            return new Cache(mask, new IdentityHashMap<>());
        });
        return cache.cubes().computeIfAbsent(cube, c -> visible(c, cache.alpha()));
    }

    public static ModelPoseCapture.Parts parts(Identifier texture) {
        Map<ModelPart, List<ModelPart.Cube>> cache = PARTS.computeIfAbsent(texture, k -> new IdentityHashMap<>());
        return new ModelPoseCapture.Parts() {
            public List<ModelPart.Cube> cubes(ModelPart p) {
                return cache.computeIfAbsent(p, part -> ModelPoseCapture.ACCESS.cubes(part).stream().filter(c -> visible(c, texture)).toList());
            }
            public Map<String, ModelPart> children(ModelPart p) { return ModelPoseCapture.ACCESS.children(p); }
        };
    }

    public static int generation() { return generation; }
    public static void clear() { TEXTURES.clear(); PARTS.clear(); generation++; }
}
