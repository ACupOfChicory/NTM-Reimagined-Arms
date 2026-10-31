package acup.gunfeel;

// port: new for 1.7.10 (26.2: the two playToServer lines in GunFeelMod's RegisterPayloadHandlersEvent). The ammo lock's
// and the ammo menu's messages, client -> server, on the mod's channel (Net.CHANNEL, with the handshake).

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;

public final class AmmoNet {
    // ids taken on a channel: AmmoLockPayload, AmmoSelectPayload
    public static final int COUNT = 2;
    private static SimpleNetworkWrapper channel;

    private AmmoNet() {
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
