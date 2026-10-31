package acup.gunfeel;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;

// The client's frame and tick for model hitboxes (26.2: GunFeel's RenderFrameEvent.Pre -> ModelHitClient.frame, the
// level's end of frame -> finishFrame, LevelTickEvent.Post -> ModelHits.tick on the client's level). Client only.
public final class ModelHitHooks {
    private ModelHitHooks() {}

    static void register() {
        ModelHitHooks hooks = new ModelHitHooks();
        FMLCommonHandler.instance().bus().register(hooks);
        MinecraftForge.EVENT_BUS.register(hooks);
    }

    @SubscribeEvent
    public void renderTick(TickEvent.RenderTickEvent e) {
        if (e.phase == TickEvent.Phase.START) ModelHitClient.frame();
    }

    @SubscribeEvent
    public void worldLast(RenderWorldLastEvent e) {
        ModelHitClient.finishFrame();
    }

    @SubscribeEvent
    public void clientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        var world = Minecraft.getMinecraft().theWorld;
        if (world != null) ModelHits.tick(world);
    }
}
