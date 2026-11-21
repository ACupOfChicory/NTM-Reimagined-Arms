package acup.gunfeel;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

// Count whole rendered frames without initializing the render port or its pipelines.
@EventBusSubscriber(modid = "ntmgunfeel", value = Dist.CLIENT)
public final class RenderFrames {
    static long frame;

    private RenderFrames() {}

    /** Once before GameRenderer.render, including all views and world passes in that frame. */
    @SubscribeEvent
    public static void beginFrame(RenderFrameEvent.Pre event) {
        frame++;
    }
}
