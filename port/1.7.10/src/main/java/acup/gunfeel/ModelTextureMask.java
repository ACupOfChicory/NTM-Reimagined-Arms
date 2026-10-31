package acup.gunfeel;

// port: copy of 26.2 src/main/java/acup/gunfeel/ModelTextureMask.java at 0762086; changes marked "port:"
// port: Alpha, alpha, difference and visible are 26.2's. The texture is read differently: 1.7.10 has no RenderType to
// name it, so the mask comes from the GL texture bound while the model draws (renderModel binds the entity's texture
// first), read back once with glGetTexImage and kept by its GL id. That covers resource textures and downloaded
// skins (26.2: DynamicTexture) alike. Overlay layers (26.2: Overlay, parts(texture, base)) are not captured in the
// port: RendererLivingEntity draws them with renderPassModel, outside the main model.

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

// Пустые UV-области не являются геометрией попадания. Маски и результат для каждого куба кэшируются.
public final class ModelTextureMask {
    public record Alpha(int width, int height, int[] sums) {
        public boolean pixel(float u,float v) {
            int x=Math.clamp((int)Math.floor(u*width),0,width-1), y=Math.clamp((int)Math.floor(v*height),0,height-1);
            int stride=width+1;
            return sums[(y+1)*stride+x+1]-sums[y*stride+x+1]-sums[(y+1)*stride+x]+sums[y*stride+x]>0;
        }
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
    // port: the cache by GL texture id and 1.7.10 ModelBox
    private record Cache(Alpha alpha, Map<net.minecraft.client.model.ModelBox, ModelCubeGeometry> cubes) {}
    private static final Map<net.minecraft.client.model.ModelBox,ModelCubeGeometry> UNMASKED=new IdentityHashMap<>();
    private static final Map<Integer, Cache> TEXTURES = new HashMap<>();
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

    // Трещины голема или ошейник лежат на уже видимой поверхности тела и формы
    // не добавляют. Поля шляпы жителя, прозрачные в основе, остаются.
    // Без маски основа закрывает весь куб, а слой без маски — непрозрачен.
    public static Alpha difference(Alpha overlay, Alpha base) {
        if (base == null) return alpha(1, 1, new int[1]);
        var size = overlay == null ? base : overlay;
        int width = size.width(), height = size.height();
        int[] argb = new int[width * height];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            float u = (x + .5F) / width, v = (y + .5F) / height;
            if ((overlay == null || overlay.pixel(u, v)) && !base.pixel(u, v)) argb[y * width + x] = 0xFF000000;
        }
        return alpha(width, height, argb);
    }

    public static boolean visible(ModelCubeGeometry.Cube cube, Alpha mask) {
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

    // port: the cube's surface under the GL texture `texture` (0: none known, the whole cube)
    public static ModelCubeGeometry geometry(net.minecraft.client.model.ModelBox cube, int texture) {
        if(texture<=0) return UNMASKED.computeIfAbsent(cube,c -> ModelCubeGeometry.bake(ModelCubeGeometry.Cube.of(c),null));
        Cache cache = TEXTURES.computeIfAbsent(texture, t -> new Cache(load(t), new IdentityHashMap<>()));
        return cache.cubes().computeIfAbsent(cube, c -> ModelCubeGeometry.bake(ModelCubeGeometry.Cube.of(c), cache.alpha()));
    }

    // port: the alpha of the texture bound to GL_TEXTURE_2D, which must be `texture`; null when it cannot be read
    private static Alpha load(int texture) {
        try {
            if (GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D) != texture) return null;
            int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            if (width <= 0 || height <= 0 || (long) width * height > 4096L * 4096L) return null;
            IntBuffer pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, pixels);
            int[] argb = new int[width * height];
            pixels.get(argb);
            return alpha(width, height, argb);
        } catch (RuntimeException ex) {
            GunFeel.LOGGER.debug("Model texture mask {}: {}", texture, ex.toString());
            return null;
        }
    }

    public static int generation() { return generation; }
    public static void clear() { TEXTURES.clear(); UNMASKED.clear(); generation++; }
}
