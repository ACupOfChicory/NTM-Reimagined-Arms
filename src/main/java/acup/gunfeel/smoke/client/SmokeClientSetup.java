package acup.gunfeel.smoke.client;

import acup.gunfeel.smoke.SmokeClouds;
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
    // ticks with nothing to draw (no cloud, or smoke not drawn) after which the GPU side is freed: 10 s
    private static final int IDLE_TICKS = 200;
    private static int idle;

    private SmokeClientSetup() {
    }

    public static void init(IEventBus modBus) {
        SmokeRender.register(modBus);
        modBus.addListener(RegisterGuiLayersEvent.class,
                e -> e.registerAbove(VanillaGuiLayers.CAMERA_OVERLAYS, Identifier.fromNamespaceAndPath("ntmgunfeel", "smoke"), SmokeFog::layer));
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.ClientTickEvent.Post.class, e -> {
            SmokeClient.tick();
            SmokeAtlas.update();
            idleCheck();
        });
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut.class, e -> {
            SmokeClient.leave();
            releaseGpu(true);
        });
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.GameShuttingDownEvent.class, e -> releaseGpu(true));
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.RenderFrameEvent.Pre.class, e -> {
            long now = System.nanoTime();
            float seconds = lastFrame == 0L ? 0.016F : Math.min(0.25F, (now - lastFrame) / 1e9F);
            lastFrame = now;
            SmokeFog.frame(Minecraft.getInstance().gameRenderer.mainCamera(), seconds);
        });
        NeoForge.EVENT_BUS.addListener(ViewportEvent.RenderFog.class, SmokeFog::fog);
        NeoForge.EVENT_BUS.addListener(ViewportEvent.ComputeFogColor.class, SmokeFog::color);
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.AfterOpaqueFeatures.class, SmokeRender::afterOpaque);
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.AfterWeather.class, SmokeRender::afterWeather);
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.AfterLevel.class, e -> SmokeRender.frame());
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent.class, SmokeSlices::submit);
    }

    // The atlas, the depth copy, the target and the rest take video memory (tens of megabytes on a 4K screen) whether or not
    // a cloud is there: after ten seconds without anything to draw they are given back, and made again with the next cloud.
    private static void idleCheck() {
        SmokeClouds all = SmokeClient.current();
        if (SmokeRender.wanted() && all != null && !all.isEmpty()) {
            idle = 0;
            return;
        }
        if (idle < IDLE_TICKS && ++idle == IDLE_TICKS) releaseGpu(false);
    }

    private static void releaseGpu(boolean world) {
        try {
            SmokeAtlas.close();
            SmokeRender.release(world);
            if (world) SmokeSlices.clear();
        } catch (RuntimeException ex) {
            acup.gunfeel.GunFeel.LOGGER.debug("Smoke: freeing the GPU side failed", ex);
        }
        idle = world ? 0 : IDLE_TICKS;
    }

    public static void payloads(PayloadRegistrar r) {
        r.playToClient(SmokePayloads.Spawn.TYPE, SmokePayloads.Spawn.CODEC, (p, ctx) -> ctx.enqueueWork(() -> SmokeClient.spawn(p)));
        r.playToClient(SmokePayloads.End.TYPE, SmokePayloads.End.CODEC, (p, ctx) -> ctx.enqueueWork(() -> SmokeClient.end(p)));
        r.playToClient(SmokePayloads.Check.TYPE, SmokePayloads.Check.CODEC, (p, ctx) -> ctx.enqueueWork(() -> SmokeClient.check(p)));
        r.playToClient(SmokePayloads.Grid.TYPE, SmokePayloads.Grid.CODEC, (p, ctx) -> ctx.enqueueWork(() -> SmokeClient.grid(p)));
        r.playToClient(SmokePayloads.Stirred.TYPE, SmokePayloads.Stirred.CODEC, (p, ctx) -> ctx.enqueueWork(() -> SmokeClient.stirred(p)));
        r.playToClient(SmokePayloads.Fed.TYPE, SmokePayloads.Fed.CODEC, (p, ctx) -> ctx.enqueueWork(() -> SmokeClient.fed(p)));
    }
}
