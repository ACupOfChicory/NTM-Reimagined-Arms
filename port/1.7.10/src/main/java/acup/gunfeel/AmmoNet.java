package acup.gunfeel;

// port: new for 1.7.10 (26.2: the two playToServer lines in GunFeelMod's RegisterPayloadHandlersEvent). The ammo lock's
// and the ammo menu's messages, client -> server. register() puts them on a channel at given ids: the point where the
// mod's common channel (with the handshake) takes them over. Until that channel exists, init() makes one of their own.

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;

public final class AmmoNet {
    // the channel of their own (FML: at most 20 characters)
    static final String CHANNEL = "ntmgunfeel_ammo";
    // ids taken on a channel: AmmoLockPayload, AmmoSelectPayload
    public static final int COUNT = 2;
    private static SimpleNetworkWrapper channel;

    private AmmoNet() {
    }

    // both sides, in preInit
    static void init() {
        if (channel == null) register(NetworkRegistry.INSTANCE.newSimpleChannel(CHANNEL), 0);
    }

    // the messages on that channel at ids first and first + 1; returns the next free id
    public static int register(SimpleNetworkWrapper ch, int first) {
        ch.registerMessage(AmmoLockPayload.Handler.class, AmmoLockPayload.class, first, Side.SERVER);
        ch.registerMessage(AmmoSelectPayload.Handler.class, AmmoSelectPayload.class, first + 1, Side.SERVER);
        channel = ch;
        return first + COUNT;
    }

    // the client: to the server, when connected to one
    static void send(IMessage m) {
        if (channel == null) return;
        try {
            channel.sendToServer(m);
        } catch (RuntimeException e) {
            // not connected to a server
        }
    }
}
