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
        // the GL-54 grenade launcher, the mod's first item of its own
        if (Feature.GL54.on()) {
            acup.gunfeel.gl54.Gl54Items.register(modBus);
            if (dist.isClient()) acup.gunfeel.gl54.Gl54Client.init(modBus);
        }
        acup.gunfeel.gas.GasSetup.init(modBus, dist);
        // the 12 gauge electroshock shell
        if (Feature.SHOCK.on()) {
            acup.gunfeel.shock.ShockAmmo.register(modBus);
            if (dist.isClient()) acup.gunfeel.shock.client.ShockClient.init(modBus);
        }
        GunWeight.register();
        acup.gunfeel.ai.AiPose.register(modBus);
        BalanceRecipes.register(modBus);
        GunMechanismRecipes.register(modBus);
        if (dist.isClient()) {
            modBus.addListener(net.neoforged.neoforge.client.event.AddClientReloadListenersEvent.class,
                    e -> e.addListener(net.minecraft.resources.Identifier.fromNamespaceAndPath("ntmgunfeel", "model_masks"),
                            (net.minecraft.server.packs.resources.ResourceManagerReloadListener) manager -> ModelHitClient.clear()));
            CompatClient.init();
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.entity.player.ItemTooltipEvent.class, GunWeight::tooltip);
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.ComputeFovModifierEvent.class, GunWeight::fov);
            ServerSettingsClient.init();
            // the balance's gun lines on this side's receivers too, by the server's setting while connected to one
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.ClientTickEvent.Post.class,
                    e -> {
                        if (Feature.BALANCE.on()) BalanceGuns.sync();
                    });
            GunFeel.init();
            HoleRender.register(modBus);
            ModelDebugRender.register(modBus);
            if (Feature.AMMO_CHOICE.on()) AmmoLockKey.register(modBus);
            if (Feature.ARMS.on()) GunEditor.register(modBus);
            if (Feature.PRONE.on()) ProneClient.registerClient(modBus);
            acup.gunfeel.smoke.client.SmokeClientSetup.init(modBus);
            acup.gunfeel.ai.client.AiDebugClient.init();
            if (Feature.MOB_AI.on()) acup.gunfeel.ai.client.AiVoiceClient.init();
            if (Feature.SUPPRESSION.on()) Suppression.init(modBus);
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                    net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut.class, e -> GunHitClient.clear());
        }
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.RegisterCommandsEvent.class,
                e -> {
                    acup.gunfeel.smoke.SmokeCommand.register(e.getDispatcher());
                    if (Feature.GL54.on()) acup.gunfeel.gl54.MagCommand.register(e.getDispatcher());
                    acup.gunfeel.ai.AiCommand.register(e.getDispatcher());
                    acup.gunfeel.ai.imitation.ImitationCommand.register(e.getDispatcher());
                });
        // a vanilla blast (TNT, a creeper, a bed) near players: their suppression scale (NTM's: MixinSuppressionBlast)
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.level.ExplosionEvent.Detonate.class,
                e -> {
                    if (Feature.SUPPRESSION.on()) SuppressionServer.blast(e.getExplosion());
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.tick.ServerTickEvent.Post.class,
                e -> {
                    if (Feature.BULLETS.on()) BulletServer.tick();
                    ServerSettingsPayload.tick(e.getServer());
                    if (Feature.BALANCE.on()) BalanceGuns.sync();
                    acup.gunfeel.ai.imitation.Imitation.tick(e.getServer());
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.tick.LevelTickEvent.Post.class,
                e -> {
                    ModelHits.tick(e.getLevel());
                    if (e.getLevel() instanceof net.minecraft.server.level.ServerLevel level) {
                        acup.gunfeel.smoke.SmokeClouds.serverTick(level);
                        acup.gunfeel.ai.TacticalMap.tickLevel(level);
                        acup.gunfeel.ai.AiBots.tick(level);
                        acup.gunfeel.ai.AiDebug.tick(level);
                        acup.gunfeel.ai.imitation.FightRecorder.tick(level);
                    }
                });
        acup.gunfeel.ai.AiEvents.register();
        acup.gunfeel.ai.AiUnderFire.init();
        HeadDamage.register();
        DamageCap.register();
        BalanceLog.register();
        BalanceKit.register();
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.server.ServerStartedEvent.class,
                e -> acup.gunfeel.ai.imitation.Imitation.started(e.getServer()));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.server.ServerStoppingEvent.class,
                e -> acup.gunfeel.ai.imitation.Imitation.stopping());
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
                    if (e.getLevel() instanceof net.minecraft.server.level.ServerLevel level) {
                        acup.gunfeel.ai.TacticalMap.forget(level);
                        acup.gunfeel.ai.AiBots.forget(level);
                        acup.gunfeel.ai.AiSquads.forget(level);
                        acup.gunfeel.ai.AiGrenades.forget(level);
                        acup.gunfeel.ai.AiEntry.forget(level);
                        acup.gunfeel.ai.AiClearing.forget(level);
                    }
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
        if (Feature.PRONE.on()) Prone.registerServer();
        // the machines' bullet shapes in the jar: their index read meanwhile
        ShapePack.preload();
        BlockDump.registerIfAsked();
        SettingsCheck.registerIfAsked();
        BulletCheck.registerIfAsked();
        SpreadCheck.registerIfAsked();
        DamageCapCheck.registerIfAsked();
        BalanceCheck.registerIfAsked();
        MechanismCheck.registerIfAsked();
        WaterCheck.registerIfAsked();
        ExplosiveCheck.registerIfAsked();
        acup.gunfeel.ai.AiCheck.registerIfAsked();
        acup.gunfeel.ai.bench.AiBench.registerIfAsked();
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
                    else HitReports.untracked(p);
                });
                r.playToClient(BulletStopPayload.TYPE,BulletStopPayload.CODEC,(p,ctx)->ctx.enqueueWork(()->Tracers.stop(p)));
                r.playToClient(HelloPayload.TYPE, HelloPayload.CODEC, (p, ctx) -> CompatClient.hello(p));
                r.playToClient(ImpactHolePayload.TYPE,ImpactHolePayload.CODEC,(p,ctx)->{
                    if(Feature.TRACERS.on())ctx.enqueueWork(()->Tracers.impact(p));
                });
                r.playToClient(ServerSettingsPayload.TYPE, ServerSettingsPayload.CODEC, (p, ctx) -> ServerSettingsClient.receive(p));
                r.playToClient(ProneStandPayload.TYPE, ProneStandPayload.CODEC, (p, ctx) -> {
                    if (Feature.PRONE.on()) ctx.enqueueWork(ProneClient::serverStood);
                });
                r.playToClient(acup.gunfeel.water.WaterBlastPayload.TYPE, acup.gunfeel.water.WaterBlastPayload.CODEC,
                        (p, ctx) -> ctx.enqueueWork(() -> p.handle(ctx.player().level())));
                r.playToClient(SuppressionBlastPayload.TYPE, SuppressionBlastPayload.CODEC, (p, ctx) -> {
                    if (Feature.SUPPRESSION.on()) ctx.enqueueWork(() -> Suppression.blast(p));
                });
                r.playToClient(acup.gunfeel.shock.ShockPayloads.Shock.TYPE, acup.gunfeel.shock.ShockPayloads.Shock.CODEC, (p, ctx) -> {
                    if (Feature.SHOCK.on()) ctx.enqueueWork(() -> acup.gunfeel.shock.client.ShockClient.shock(p));
                });
                r.playToClient(acup.gunfeel.shock.ShockPayloads.Mark.TYPE, acup.gunfeel.shock.ShockPayloads.Mark.CODEC, (p, ctx) -> {
                    if (Feature.SHOCK.on()) ctx.enqueueWork(() -> acup.gunfeel.shock.client.ShockClient.mark(p));
                });
                r.playToClient(GunHitPayload.TYPE, GunHitPayload.CODEC, (p, ctx) -> ctx.enqueueWork(GunHitClient::receive));
                r.playToClient(acup.gunfeel.gl54.PressLayoutsPayload.TYPE, acup.gunfeel.gl54.PressLayoutsPayload.CODEC,
                        (p, ctx) -> ctx.enqueueWork(() -> {
                            if (Feature.GL54.on()) acup.gunfeel.gl54.PressSync.receive(p);
                        }));
                acup.gunfeel.smoke.client.SmokeClientSetup.payloads(r);
                acup.gunfeel.gas.GasSetup.payloads(r, true);
                acup.gunfeel.gl54.frag.FragNet.payloads(r, true);
                r.playToClient(acup.gunfeel.ai.AiCellsPayload.TYPE, acup.gunfeel.ai.AiCellsPayload.CODEC,
                        (p, ctx) -> ctx.enqueueWork(() -> acup.gunfeel.ai.client.AiDebugClient.receive(p)));
                r.playToClient(acup.gunfeel.ai.AiBotsPayload.TYPE, acup.gunfeel.ai.AiBotsPayload.CODEC,
                        (p, ctx) -> ctx.enqueueWork(() -> acup.gunfeel.ai.client.AiBotsClient.receive(p)));
                r.playToClient(acup.gunfeel.ai.AiRoomsPayload.TYPE, acup.gunfeel.ai.AiRoomsPayload.CODEC,
                        (p, ctx) -> ctx.enqueueWork(() -> acup.gunfeel.ai.client.AiRoomsClient.receive(p)));
            } else {
                r.playToClient(ShotPayload.TYPE, ShotPayload.CODEC);
                r.playToClient(BulletStopPayload.TYPE,BulletStopPayload.CODEC);
                r.playToClient(ImpactHolePayload.TYPE,ImpactHolePayload.CODEC);
                r.playToClient(HelloPayload.TYPE, HelloPayload.CODEC);
                r.playToClient(ServerSettingsPayload.TYPE, ServerSettingsPayload.CODEC);
                r.playToClient(ProneStandPayload.TYPE, ProneStandPayload.CODEC);
                acup.gunfeel.gl54.frag.FragNet.payloads(r, false);
                r.playToClient(acup.gunfeel.gl54.PressLayoutsPayload.TYPE, acup.gunfeel.gl54.PressLayoutsPayload.CODEC);
                r.playToClient(acup.gunfeel.water.WaterBlastPayload.TYPE, acup.gunfeel.water.WaterBlastPayload.CODEC);
                r.playToClient(SuppressionBlastPayload.TYPE, SuppressionBlastPayload.CODEC);
                r.playToClient(acup.gunfeel.shock.ShockPayloads.Shock.TYPE, acup.gunfeel.shock.ShockPayloads.Shock.CODEC);
                r.playToClient(acup.gunfeel.shock.ShockPayloads.Mark.TYPE, acup.gunfeel.shock.ShockPayloads.Mark.CODEC);
                r.playToClient(GunHitPayload.TYPE, GunHitPayload.CODEC);
                acup.gunfeel.smoke.SmokeNet.payloads(r);
                acup.gunfeel.gas.GasSetup.payloads(r, false);
                r.playToClient(acup.gunfeel.ai.AiCellsPayload.TYPE, acup.gunfeel.ai.AiCellsPayload.CODEC);
                r.playToClient(acup.gunfeel.ai.AiBotsPayload.TYPE, acup.gunfeel.ai.AiBotsPayload.CODEC);
                r.playToClient(acup.gunfeel.ai.AiRoomsPayload.TYPE, acup.gunfeel.ai.AiRoomsPayload.CODEC);
            }
            r.playToServer(acup.gunfeel.smoke.SmokePayloads.Resync.TYPE, acup.gunfeel.smoke.SmokePayloads.Resync.CODEC, (p, ctx) -> {
                if (ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp) ctx.enqueueWork(() -> acup.gunfeel.smoke.SmokeNet.resync(sp, p));
            });
            r.playToServer(AmmoSelectPayload.TYPE, AmmoSelectPayload.CODEC, (p, ctx) -> {
                if (Feature.AMMO_CHOICE.on() && ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp && !acup.gunfeel.SpectatorGuns.blocks(sp)) AmmoLock.select(sp, p.config());
            });
            r.playToServer(acup.gunfeel.gl54.Gl54SelectPayload.TYPE, acup.gunfeel.gl54.Gl54SelectPayload.CODEC, (p, ctx) -> {
                if (Feature.GL54.on() && ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp && !acup.gunfeel.SpectatorGuns.blocks(sp))
                    ctx.enqueueWork(() -> acup.gunfeel.gl54.GrenadeMagazine.select(sp, p.contents()));
            });
            r.playToServer(acup.gunfeel.gl54.Gl54OffsetPayload.TYPE, acup.gunfeel.gl54.Gl54OffsetPayload.CODEC, (p, ctx) -> {
                if (Feature.GL54.on() && ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp && !acup.gunfeel.SpectatorGuns.blocks(sp))
                    ctx.enqueueWork(() -> acup.gunfeel.gl54.Gl54Range.shift(sp, p.delta()));
            });
            r.playToServer(HitReportPayload.TYPE, HitReportPayload.CODEC, (p, ctx) -> {
                if (ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp) ctx.enqueueWork(() -> ClientHits.receive(sp, p));
            });
            r.playToServer(ModelPosePayload.TYPE, ModelPosePayload.CODEC, (p, ctx) -> {
                if (ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp) ModelHits.receive(sp, p);
            });
            r.playToServer(ModelMeshPayload.TYPE, ModelMeshPayload.CODEC, (p, ctx) -> {
                if (ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp) p.receive(sp.getUUID());
            });
            r.playToServer(TestLogPayload.TYPE, TestLogPayload.CODEC, (p, ctx) -> {
                if (ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp) TestLogPayload.handle(sp, p);
            });
            r.playToServer(ProneCrawlPayload.TYPE, ProneCrawlPayload.CODEC, (p, ctx) -> {
                if (Feature.PRONE.on() && ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp) ctx.enqueueWork(() -> Prone.receive(sp, p.crawl()));
            });
            r.playToServer(AmmoLockPayload.TYPE, AmmoLockPayload.CODEC, (p, ctx) -> {
                if (Feature.AMMO_CHOICE.on() && ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp && !acup.gunfeel.SpectatorGuns.blocks(sp)) AmmoLock.toggle(sp);
            });
        });
    }
}
