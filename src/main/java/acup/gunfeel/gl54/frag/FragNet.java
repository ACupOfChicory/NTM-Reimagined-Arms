package acup.gunfeel.gl54.frag;

import net.neoforged.neoforge.network.registration.PayloadRegistrar;

// Registers the fragment burst's packet (GunFeelMod): the client draws it, the server only has the type to send
public final class FragNet {
    private FragNet() {
    }

    public static void payloads(PayloadRegistrar r, boolean client) {
        if (client) r.playToClient(FragPayload.TYPE, FragPayload.CODEC, (p, ctx) -> ctx.enqueueWork(() -> FragFx.handle(p)));
        else r.playToClient(FragPayload.TYPE, FragPayload.CODEC);
    }
}
