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
    private record Cache(Alpha alpha, Map<ModelPart.Cube, ModelCubeGeometry> cubes) {}
    // Слой поверх той же модели: только пиксели, прозрачные в основной текстуре.
    private record Overlay(Identifier texture, Identifier base) {}
    private static final Map<ModelPart.Cube,ModelCubeGeometry> UNMASKED=new IdentityHashMap<>();
    private static final Map<Object, Cache> TEXTURES = new java.util.HashMap<>();
    private static final Map<Object, Map<ModelPart, List<ModelPart.Cube>>> PARTS = new java.util.HashMap<>();
    private static int generation;
    private static final Map<net.minecraft.client.renderer.rendertype.RenderType,Identifier> RENDER_TEXTURES=new java.util.WeakHashMap<>();

    public static Identifier texture(net.minecraft.client.renderer.rendertype.RenderType type) {
        synchronized(RENDER_TEXTURES) {
            if(RENDER_TEXTURES.containsKey(type)) return RENDER_TEXTURES.get(type);
            Identifier result=null;
            try {
                var stateField=type.getClass().getDeclaredField("state"); stateField.setAccessible(true);
                Object state=stateField.get(type);
                var textures=state.getClass().getDeclaredField("textures"); textures.setAccessible(true);
                Object binding=((Map<?,?>)textures.get(state)).get("Sampler0");
                if(binding!=null) {
                    var location=binding.getClass().getDeclaredMethod("location"); location.setAccessible(true);
                    result=(Identifier)location.invoke(binding);
                }
            } catch(ReflectiveOperationException ex) {
                org.slf4j.LoggerFactory.getLogger("ntm-gunfeel").debug("Нет текстуры render type {}: {}",type,ex.toString());
            }
            RENDER_TEXTURES.put(type,result); return result;
        }
    }

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
        return geometry(cube,texture).visible();
    }
    private static boolean visibleFor(ModelPart.Cube cube, Object key) {
        return geometryFor(cube,key).visible();
    }

    public static ModelCubeGeometry geometry(ModelPart.Cube cube,Identifier texture) {
        return geometryFor(cube,texture);
    }
    private static ModelCubeGeometry geometryFor(ModelPart.Cube cube,Object key) {
        if(key==null) return UNMASKED.computeIfAbsent(cube,c -> ModelCubeGeometry.bake(c,null));
        Cache cache = cache(key);
        return cache.cubes().computeIfAbsent(cube, c -> ModelCubeGeometry.bake(c, cache.alpha()));
    }
    public static Alpha mask(Identifier texture) { return texture==null?null:cache(texture).alpha(); }
    private static Cache cache(Object key) {
        var cached = TEXTURES.get(key);
        if (cached != null) return cached;
        // Части разности загружаются до записи: computeIfAbsent не допускает
        // вложенного изменения той же карты.
        var mask = key instanceof Overlay overlay ? difference(cache(overlay.texture()).alpha(), cache(overlay.base()).alpha())
                : load((Identifier) key);
        var result = new Cache(mask, new IdentityHashMap<>());
        TEXTURES.put(key, result);
        return result;
    }
    private static Alpha load(Identifier id) {
        Alpha mask = null;
        try {
            var resource=Minecraft.getInstance().getResourceManager().getResource(id);
            if(resource.isPresent()) try(var stream=resource.get().open();var image=NativeImage.read(stream)) {
                mask=alpha(image.getWidth(),image.getHeight(),image.getPixels());
            } else if(Minecraft.getInstance().getTextureManager().getTexture(id) instanceof net.minecraft.client.renderer.texture.DynamicTexture dynamic) {
                // Сетевые скины не лежат в ресурс-паке. Изображение принадлежит
                // TextureManager: копируем альфу и не закрываем NativeImage.
                var image=dynamic.getPixels();
                if(image!=null && !image.isClosed()) mask=alpha(image.getWidth(),image.getHeight(),image.getPixels());
            }
        } catch (Exception ex) {
            org.slf4j.LoggerFactory.getLogger("ntm-gunfeel").debug("Model texture mask {}: {}", id, ex.toString());
        }
        return mask;
    }

    public static ModelPoseCapture.Parts parts(Identifier texture) { return partsFor(texture); }
    // Повторная отрисовка основной модели с другой текстурой.
    public static ModelPoseCapture.Parts parts(Identifier texture, Identifier base) {
        return base == null || texture == null || base.equals(texture) ? partsFor(texture) : partsFor(new Overlay(texture, base));
    }
    private static ModelPoseCapture.Parts partsFor(Object key) {
        Map<ModelPart, List<ModelPart.Cube>> cache = PARTS.computeIfAbsent(key, k -> new IdentityHashMap<>());
        return new ModelPoseCapture.Parts() {
            public List<ModelPart.Cube> cubes(ModelPart p) {
                return cache.computeIfAbsent(p, part -> ModelPoseCapture.ACCESS.cubes(part).stream().filter(c -> visibleFor(c, key)).toList());
            }
            public Map<String, ModelPart> children(ModelPart p) { return ModelPoseCapture.ACCESS.children(p); }
            public ModelCubeGeometry geometry(ModelPart.Cube cube) { return geometryFor(cube,key); }
        };
    }

    public static int generation() { return generation; }
    public static void clear() { TEXTURES.clear(); PARTS.clear(); UNMASKED.clear(); RENDER_TEXTURES.clear(); generation++; }
}
