package acup.gunfeel;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;

// The port's copy of the root's HoleRender (notes/ports/de-1.21.1.md, "Копии"): the render type of bullet holes on
// 1.21.1, which has no pipelines. As the root's: entity_translucent that does not write depth, with a small depth
// bias (the polygon offset layering), culled or not; its shader (ntmgunfeel:bullet_hole, the port's own) adds
// ModelOffset to the position and lights the face per side.
public final class HoleRender {
    private static ShaderInstance shader;
    private static final Map<String, RenderType> TYPES = new HashMap<>();
    private static final java.util.Set<RenderType> LATE_TYPES = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    private HoleRender() {
    }

    static void register(IEventBus modBus) {
        modBus.addListener(RegisterShadersEvent.class, e -> {
            try {
                e.registerShader(new ShaderInstance(e.getResourceProvider(), ResourceLocation.fromNamespaceAndPath("ntmgunfeel", "bullet_hole"),
                        DefaultVertexFormat.NEW_ENTITY), s -> shader = s);
            } catch (IOException x) {
                throw new UncheckedIOException(x);
            }
        });
    }

    // late: holes on glass, drawn after the translucent terrain (Tracers.afterWeather)
    public static RenderType type(ResourceLocation texture, boolean late, boolean cull) {
        return TYPES.computeIfAbsent(texture + (late ? "|late" : "") + (cull ? "|cull" : ""), k -> {
            RenderType type = Shards.type(late ? "ntmgunfeel_bullet_hole_late" : "ntmgunfeel_bullet_hole", texture, cull);
            if (late) LATE_TYPES.add(type);
            return type;
        });
    }

    public static boolean isLate(RenderType type) {
        return !LATE_TYPES.isEmpty() && LATE_TYPES.contains(type);
    }

    // RenderType's state shards are protected
    private static final class Shards extends RenderType {
        private Shards() {
            super("", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 0, false, false, () -> {}, () -> {});
        }

        static RenderType type(String name, ResourceLocation texture, boolean cull) {
            CompositeState state = CompositeState.builder()
                    .setShaderState(new ShaderStateShard(() -> shader))
                    .setTextureState(new TextureStateShard(texture, false, false))
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setCullState(cull ? CULL : NO_CULL)
                    .setLightmapState(LIGHTMAP)
                    .setOverlayState(OVERLAY)
                    .setWriteMaskState(COLOR_WRITE)
                    .setLayeringState(POLYGON_OFFSET_LAYERING)
                    .createCompositeState(false);
            return com.hbm.backport.client.rendertype.RenderTypeFactory.create(name, DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 1536,
                    false, false, state);
        }
    }
}
