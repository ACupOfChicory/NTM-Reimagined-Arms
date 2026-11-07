package acup.gunfeel;

import acup.gunfeel.compat.Compat;
import acup.gunfeel.compat.CompatClient;
import acup.gunfeel.compat.Feature;
import acup.gunfeel.compat.HelloPayload;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

// Client: recoil, gun position, HUD, arms, tracers. Server (and single player): hold-to-aim, bullet speed and hits.
@Mod("ntmgunfeel")
public final class GunFeelMod {
    // Systems that do not fit an untested NTM are off (acup.gunfeel.compat): their listeners check Feature.on()

    public GunFeelMod(IEventBus modBus, Dist dist, ModContainer container) {
        Compat.setModVersion(container.getModInfo().getVersion().toString());
        DebugLog.setup(!net.neoforged.fml.loading.FMLEnvironment.isProduction());
        // the one line of the mod's in the game's log at start (a logger of its own, past DebugLog's)
        org.slf4j.LoggerFactory.getLogger("NTM Reimagined Arms").info("NTM Reimagined Arms {}: its own log in {}", Compat.modVersion(), DebugLog.FILE);
        if (!Compat.inRange()) {
            DebugLog.LOGGER.warn("NTM {} is not tested with NTM Reimagined Arms {} (tested: {}); systems off: {}",
                    Compat.hbmVersion(), Compat.modVersion(), Compat.tested(), Compat.offList());
        }
        if (dist.isClient()) {
            modBus.addListener(net.neoforged.neoforge.client.event.AddClientReloadListenersEvent.class,
                    e -> e.addListener(net.minecraft.resources.Identifier.fromNamespaceAndPath("ntmgunfeel", "model_masks"),
                            (net.minecraft.server.packs.resources.ResourceManagerReloadListener) manager -> ModelHitClient.clear()));
            CompatClient.init();
            ServerSettingsClient.init();
            GunFeel.init();
            HoleRender.register(modBus);
            ModelDebugRender.register(modBus);
            if (Feature.AMMO_CHOICE.on()) AmmoLockKey.register(modBus);
            if (Feature.ARMS.on()) GunEditor.register(modBus);
            acup.gunfeel.smoke.client.SmokeClientSetup.init(modBus);
            acup.gunfeel.ai.client.AiDebugClient.init();
        }
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.RegisterCommandsEvent.class,
                e -> {
                    if (Feature.HOLD_AIM.on()) ReaimCommand.register(e.getDispatcher());
                    acup.gunfeel.smoke.SmokeCommand.register(e.getDispatcher());
                    acup.gunfeel.ai.AiCommand.register(e.getDispatcher());
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.tick.ServerTickEvent.Post.class,
                e -> {
                    if (Feature.BULLETS.on()) BulletServer.tick();
                    ServerSettingsPayload.tick(e.getServer());
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.tick.LevelTickEvent.Post.class,
                e -> {
                    ModelHits.tick(e.getLevel());
                    if (e.getLevel() instanceof net.minecraft.server.level.ServerLevel level) {
                        acup.gunfeel.smoke.SmokeClouds.serverTick(level);
                        acup.gunfeel.ai.TacticalMap.tickLevel(level);
                        acup.gunfeel.ai.AiDebug.tick(level);
                    }
                });
        // the AI's tactical map: what it copied of a chunk goes with the chunk, comes back with it
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.level.ChunkEvent.Load.class,
                e -> acup.gunfeel.ai.AiEvents.chunk(e.getLevel(), e.getChunk(), true));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.level.ChunkEvent.Unload.class,
                e -> acup.gunfeel.ai.AiEvents.chunk(e.getLevel(), e.getChunk(), false));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.server.ServerStoppedEvent.class,
                e -> acup.gunfeel.ai.TacticalMap.forgetAll());
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.level.ChunkWatchEvent.Sent.class,
                acup.gunfeel.smoke.SmokeNet::watched);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.level.ChunkWatchEvent.UnWatch.class,
                acup.gunfeel.smoke.SmokeNet::unwatched);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.level.LevelEvent.Unload.class,
                e -> {
                    if (e.getLevel() instanceof net.minecraft.world.level.Level level) acup.gunfeel.smoke.SmokeClouds.forget(level);
                    if (e.getLevel() instanceof net.minecraft.server.level.ServerLevel level) acup.gunfeel.ai.TacticalMap.forget(level);
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent.class,
                e -> {
                    acup.gunfeel.smoke.SmokeNet.forget(e.getEntity().getUUID());
                    acup.gunfeel.ai.AiDebug.forget(e.getEntity().getUUID());
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent.class,
                e -> {
                    if (e.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp) {
                        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp, HelloPayload.ours());
                        ServerSettingsPayload.join(sp);
                    }
                });
        // the machines' bullet shapes in the jar: their index read meanwhile
        ShapePack.preload();
        BlockDump.registerIfAsked();
        SettingsCheck.registerIfAsked();
        BulletCheck.registerIfAsked();
        WaterCheck.registerIfAsked();
        ExplosiveCheck.registerIfAsked();
        acup.gunfeel.ai.AiMapCheck.registerIfAsked();
        TraceHooks.register(dist);
        modBus.addListener(RegisterPayloadHandlersEvent.class, e -> {
            // not optional: the mod is needed on both sides, so NeoForge turns away a client without it from a server
            // with it and the other way round. The channel's version is the mod's build (515+mc26.2, from git), so a
            // client and a server of different builds do not connect either; NeoForge's screen names the mod (with the
            // server's build) and both versions. Builds made without git (0+mc26.2) connect only to each other.
            PayloadRegistrar r = e.registrar(container.getModInfo().getVersion().toString());
            if (dist.isClient()) {
                r.playToClient(ShotPayload.TYPE, ShotPayload.CODEC, (p, ctx) -> {
                    if (Feature.TRACERS.on()) Tracers.handle(p, ctx);
                });
                r.playToClient(BulletStopPayload.TYPE,BulletStopPayload.CODEC,(p,ctx)->ctx.enqueueWork(()->Tracers.stop(p)));
                r.playToClient(HelloPayload.TYPE, HelloPayload.CODEC, (p, ctx) -> CompatClient.hello(p));
                r.playToClient(ImpactHolePayload.TYPE,ImpactHolePayload.CODEC,(p,ctx)->{
                    if(Feature.TRACERS.on())ctx.enqueueWork(()->Tracers.impact(p));
                });
                r.playToClient(ServerSettingsPayload.TYPE, ServerSettingsPayload.CODEC, (p, ctx) -> ServerSettingsClient.receive(p));
                r.playToClient(acup.gunfeel.water.WaterBlastPayload.TYPE, acup.gunfeel.water.WaterBlastPayload.CODEC,
                        (p, ctx) -> ctx.enqueueWork(() -> p.handle(ctx.player().level())));
                acup.gunfeel.smoke.client.SmokeClientSetup.payloads(r);
                r.playToClient(acup.gunfeel.ai.AiCellsPayload.TYPE, acup.gunfeel.ai.AiCellsPayload.CODEC,
                        (p, ctx) -> ctx.enqueueWork(() -> acup.gunfeel.ai.client.AiDebugClient.receive(p)));
            } else {
                r.playToClient(ShotPayload.TYPE, ShotPayload.CODEC);
                r.playToClient(BulletStopPayload.TYPE,BulletStopPayload.CODEC);
                r.playToClient(ImpactHolePayload.TYPE,ImpactHolePayload.CODEC);
                r.playToClient(HelloPayload.TYPE, HelloPayload.CODEC);
                r.playToClient(ServerSettingsPayload.TYPE, ServerSettingsPayload.CODEC);
                r.playToClient(acup.gunfeel.water.WaterBlastPayload.TYPE, acup.gunfeel.water.WaterBlastPayload.CODEC);
                acup.gunfeel.smoke.SmokeNet.payloads(r);
                r.playToClient(acup.gunfeel.ai.AiCellsPayload.TYPE, acup.gunfeel.ai.AiCellsPayload.CODEC);
            }
            r.playToServer(acup.gunfeel.smoke.SmokePayloads.Resync.TYPE, acup.gunfeel.smoke.SmokePayloads.Resync.CODEC, (p, ctx) -> {
                if (ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp) ctx.enqueueWork(() -> acup.gunfeel.smoke.SmokeNet.resync(sp, p));
            });
            r.playToServer(AmmoSelectPayload.TYPE, AmmoSelectPayload.CODEC, (p, ctx) -> {
                if (Feature.AMMO_CHOICE.on() && ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp && !acup.gunfeel.SpectatorGuns.blocks(sp)) AmmoLock.select(sp, p.config());
            });
            r.playToServer(ModelPosePayload.TYPE, ModelPosePayload.CODEC, (p, ctx) -> {
                if (ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp) ModelHits.receive(sp, p);
            });
            r.playToServer(ModelMeshPayload.TYPE, ModelMeshPayload.CODEC, (p, ctx) -> p.receive());
            r.playToServer(TestLogPayload.TYPE, TestLogPayload.CODEC, (p, ctx) -> {
                if (ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp) TestLogPayload.handle(sp, p);
            });
            r.playToServer(AmmoLockPayload.TYPE, AmmoLockPayload.CODEC, (p, ctx) -> {
                if (Feature.AMMO_CHOICE.on() && ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp && !acup.gunfeel.SpectatorGuns.blocks(sp)) AmmoLock.toggle(sp);
            });
        });
    }
}
