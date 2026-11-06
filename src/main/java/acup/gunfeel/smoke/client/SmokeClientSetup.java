package acup.gunfeel.smoke.client;

import acup.gunfeel.smoke.SmokePayloads;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Client: the smoke's listeners and packet handlers. */
public final class SmokeClientSetup {
    private SmokeClientSetup() {
    }

    public static void init(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.ClientTickEvent.Post.class, e -> SmokeClient.tick());
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut.class, e -> SmokeClient.leave());
    }

    public static void payloads(PayloadRegistrar r) {
        r.playToClient(SmokePayloads.Spawn.TYPE, SmokePayloads.Spawn.CODEC, (p, ctx) -> ctx.enqueueWork(() -> SmokeClient.spawn(p)));
        r.playToClient(SmokePayloads.End.TYPE, SmokePayloads.End.CODEC, (p, ctx) -> ctx.enqueueWork(() -> SmokeClient.end(p)));
        r.playToClient(SmokePayloads.Disturb.TYPE, SmokePayloads.Disturb.CODEC, (p, ctx) -> ctx.enqueueWork(() -> SmokeClient.disturb(p)));
        r.playToClient(SmokePayloads.Check.TYPE, SmokePayloads.Check.CODEC, (p, ctx) -> ctx.enqueueWork(() -> SmokeClient.check(p)));
        r.playToClient(SmokePayloads.Grid.TYPE, SmokePayloads.Grid.CODEC, (p, ctx) -> ctx.enqueueWork(() -> SmokeClient.grid(p)));
    }
}
