package acup.gunfeel.smoke.client;

import acup.gunfeel.smoke.SmokePayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Client: the smoke's listeners and packet handlers. */
public final class SmokeClientSetup {
    private static long lastFrame;

    private SmokeClientSetup() {
    }

    public static void init(IEventBus modBus) {
        SmokeRender.register(modBus);
        modBus.addListener(RegisterGuiLayersEvent.class,
                e -> e.registerAbove(VanillaGuiLayers.CAMERA_OVERLAYS, Identifier.fromNamespaceAndPath("ntmgunfeel", "smoke"), SmokeFog::layer));
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.ClientTickEvent.Post.class, e -> {
            SmokeClient.tick();
            SmokeAtlas.update();
        });
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut.class, e -> {
            SmokeClient.leave();
            SmokeAtlas.update();
        });
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.RenderFrameEvent.Pre.class, e -> {
            long now = System.nanoTime();
            float seconds = lastFrame == 0L ? 0.016F : Math.min(0.25F, (now - lastFrame) / 1e9F);
            lastFrame = now;
            SmokeFog.frame(Minecraft.getInstance().gameRenderer.mainCamera(), seconds);
        });
        NeoForge.EVENT_BUS.addListener(ViewportEvent.RenderFog.class, SmokeFog::fog);
        NeoForge.EVENT_BUS.addListener(ViewportEvent.ComputeFogColor.class, SmokeFog::color);
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.AfterOpaqueFeatures.class, SmokeRender::afterOpaque);
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.AfterTranslucentParticles.class, SmokeRender::afterTranslucent);
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.AfterLevel.class, e -> SmokeRender.frame());
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent.class, SmokeSlices::submit);
    }

    public static void payloads(PayloadRegistrar r) {
        r.playToClient(SmokePayloads.Spawn.TYPE, SmokePayloads.Spawn.CODEC, (p, ctx) -> ctx.enqueueWork(() -> SmokeClient.spawn(p)));
        r.playToClient(SmokePayloads.End.TYPE, SmokePayloads.End.CODEC, (p, ctx) -> ctx.enqueueWork(() -> SmokeClient.end(p)));
        r.playToClient(SmokePayloads.Disturb.TYPE, SmokePayloads.Disturb.CODEC, (p, ctx) -> ctx.enqueueWork(() -> SmokeClient.disturb(p)));
        r.playToClient(SmokePayloads.Check.TYPE, SmokePayloads.Check.CODEC, (p, ctx) -> ctx.enqueueWork(() -> SmokeClient.check(p)));
        r.playToClient(SmokePayloads.Grid.TYPE, SmokePayloads.Grid.CODEC, (p, ctx) -> ctx.enqueueWork(() -> SmokeClient.grid(p)));
    }
}
