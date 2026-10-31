package acup.gunfeel;

// port: 26.2's AmmoLockPayload.java (0762086) as a Forge 1.7.10 message (SimpleNetworkWrapper, AmmoNet): no fields

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

// client -> server: the ammo lock key was pressed with a gun in hand (AmmoLock.toggle)
public final class AmmoLockPayload implements IMessage {
    @Override
    public void fromBytes(ByteBuf buf) {
    }

    @Override
    public void toBytes(ByteBuf buf) {
    }

    // on the server's main thread: 1.7.10 hands a custom payload on where the game handles its packets
    public static final class Handler implements IMessageHandler<AmmoLockPayload, IMessage> {
        @Override
        public IMessage onMessage(AmmoLockPayload m, MessageContext ctx) {
            AmmoLock.toggle(ctx.getServerHandler().playerEntity);
            return null;
        }
    }
}
