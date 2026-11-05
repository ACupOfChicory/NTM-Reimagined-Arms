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

// The port's copy of the root's GunFeelMod (notes/ports/de-1.21.1.md, "Копии"): NeoForge 21.1's reload
// listeners, handlers on both sides for the payloads to the client, no gun editor, the systems not ported yet off.
// Client: recoil, gun position, HUD, arms, tracers. Server (and single player): hold-to-aim, bullet speed and hits.
@Mod("ntmgunfeel")
public final class GunFeelMod {
    // Systems that do not fit an untested NTM are off (acup.gunfeel.compat): their listeners check Feature.on()
    // the network protocol: raise it when a payload changes, and clients and servers on different ones will not connect
    public static final String PROTOCOL = "8";

    public GunFeelMod(IEventBus modBus, Dist dist, ModContainer container) {
        Compat.setModVersion(container.getModInfo().getVersion().toString());
        // what is not ported to 1.21.1 yet stays off
        acup.gunfeel.compat.Ported.apply();
        DebugLog.setup(!net.neoforged.fml.loading.FMLEnvironment.production);
        // the one line of the mod's in the game's log at start (a logger of its own, past DebugLog's)
        org.slf4j.LoggerFactory.getLogger("NTM Reimagined Arms").info("NTM Reimagined Arms {}: its own log in {}", Compat.modVersion(), DebugLog.FILE);
        if (!Compat.inRange()) {
            GunFeel.LOGGER.warn("NTM {} is not tested with NTM Reimagined Arms {} (tested: {}); systems off: {}",
                    Compat.hbmVersion(), Compat.modVersion(), Compat.tested(), Compat.offList());
        }
        if (dist.isClient()) {
            modBus.addListener(net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent.class,
                    e -> e.registerReloadListener(
                            (net.minecraft.server.packs.resources.ResourceManagerReloadListener) manager -> ModelHitClient.clear()));
            CompatClient.init();
            ServerSettingsClient.init();
            GunFeel.init();
            HoleRender.register(modBus);
            ModelDebugRender.register(modBus);
            if (Feature.AMMO_CHOICE.on()) AmmoLockKey.register(modBus);
        }
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.RegisterCommandsEvent.class,
                e -> {
                    if (Feature.HOLD_AIM.on()) ReaimCommand.register(e.getDispatcher());
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.tick.ServerTickEvent.Post.class,
                e -> {
                    if (Feature.BULLETS.on()) BulletServer.tick();
                    ServerSettingsPayload.tick(e.getServer());
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.tick.LevelTickEvent.Post.class,
                e -> ModelHits.tick(e.getLevel()));
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
        TraceHooks.register(dist);
        modBus.addListener(RegisterPayloadHandlersEvent.class, e -> {
            // not optional: the mod is needed on both sides, so NeoForge turns away a client without it from a server
            // with it and the other way round, and a client and server with different PROTOCOLs
            PayloadRegistrar r = e.registrar(PROTOCOL);
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
            } else {
                // 1.21.1 wants a handler on the server too: it never runs there
                r.playToClient(ShotPayload.TYPE, ShotPayload.CODEC, (p, ctx) -> {});
                r.playToClient(BulletStopPayload.TYPE, BulletStopPayload.CODEC, (p, ctx) -> {});
                r.playToClient(ImpactHolePayload.TYPE, ImpactHolePayload.CODEC, (p, ctx) -> {});
                r.playToClient(HelloPayload.TYPE, HelloPayload.CODEC, (p, ctx) -> {});
                r.playToClient(ServerSettingsPayload.TYPE, ServerSettingsPayload.CODEC, (p, ctx) -> {});
            }
            r.playToServer(AmmoSelectPayload.TYPE, AmmoSelectPayload.CODEC, (p, ctx) -> {
                if (Feature.AMMO_CHOICE.on() && ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp) AmmoLock.select(sp, p.config());
            });
            r.playToServer(ModelPosePayload.TYPE, ModelPosePayload.CODEC, (p, ctx) -> {
                if (ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp) ModelHits.receive(sp, p);
            });
            r.playToServer(ModelMeshPayload.TYPE, ModelMeshPayload.CODEC, (p, ctx) -> p.receive());
            r.playToServer(TestLogPayload.TYPE, TestLogPayload.CODEC, (p, ctx) -> {
                if (ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp) TestLogPayload.handle(sp, p);
            });
            r.playToServer(AmmoLockPayload.TYPE, AmmoLockPayload.CODEC, (p, ctx) -> {
                if (Feature.AMMO_CHOICE.on() && ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp) AmmoLock.toggle(sp);
            });
        });
    }
}
