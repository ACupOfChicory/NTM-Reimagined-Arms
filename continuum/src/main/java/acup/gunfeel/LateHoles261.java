package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;

import acup.gunfeel.compat.Feature;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

// port/continuum: the late pass of bullet holes on glass with improved transparency on 26.1. 26.2 draws the submits
// MixinSubmitNodeCollection puts in SubmitNodeCollection.afterTerrain right after the translucent terrain; 26.1 has no
// such list, its translucent features draw before the translucent terrain, whose glass would cover the holes. Here the
// mixin keeps them, and they draw where 26.2 draws afterTerrain: after the translucent terrain, in the same pass
// (RenderLevelStageEvent.AfterTranslucentBlocks), with the same pose and render type, into the target the translucent
// terrain went to. Render thread only.
@EventBusSubscriber(modid = "ntmgunfeel", value = Dist.CLIENT)
public final class LateHoles261 {
    private record Late(PoseStack.Pose pose, RenderType type, SubmitNodeCollector.CustomGeometryRenderer renderer) {}

    private static final List<Late> LATE = new ArrayList<>();

    private LateHoles261() {
    }

    public static void submit(PoseStack.Pose pose, RenderType type, SubmitNodeCollector.CustomGeometryRenderer renderer) {
        LATE.add(new Late(pose, type, renderer));
    }

    @SubscribeEvent
    static void afterTranslucentBlocks(RenderLevelStageEvent.AfterTranslucentBlocks event) {
        if (LATE.isEmpty()) return;
        try {
            if (!Feature.TRACERS.on()) return;
            var mc = Minecraft.getInstance();
            var source = mc.renderBuffers().bufferSource();
            // improved transparency: the glass went to the translucent target, laid over the main one afterwards by
            // depth. Drawn into the main target the holes would end up under the glass; they go where the glass is
            var translucent = mc.levelRenderer.getTranslucentTarget();
            var color = RenderSystem.outputColorTextureOverride;
            var depth = RenderSystem.outputDepthTextureOverride;
            if (translucent != null) {
                RenderSystem.outputColorTextureOverride = translucent.getColorTextureView();
                RenderSystem.outputDepthTextureOverride = translucent.getDepthTextureView();
            }
            try {
                for (Late late : LATE) {
                    late.renderer().render(late.pose(), source.getBuffer(late.type()));
                    source.endBatch(late.type());
                }
            } finally {
                RenderSystem.outputColorTextureOverride = color;
                RenderSystem.outputDepthTextureOverride = depth;
            }
        } finally {
            LATE.clear();
        }
    }
}
