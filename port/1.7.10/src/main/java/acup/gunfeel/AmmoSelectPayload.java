package acup.gunfeel;

// port: 26.2's AmmoSelectPayload.java (0762086) as a Forge 1.7.10 message (SimpleNetworkWrapper, AmmoNet). 26.2 writes
// the id as a VarInt; here ByteBufUtils.writeVarInt, the same bytes.

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

// client -> server: the radial ammo menu chose this bullet config for the held gun (AmmoLock.select)
public final class AmmoSelectPayload implements IMessage {
    public int config;

    public AmmoSelectPayload() {
    }

    public AmmoSelectPayload(int config) {
        this.config = config;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        config = ByteBufUtils.readVarInt(buf, 5);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeVarInt(buf, config, 5);
    }

    public static final class Handler implements IMessageHandler<AmmoSelectPayload, IMessage> {
        @Override
        public IMessage onMessage(AmmoSelectPayload m, MessageContext ctx) {
            AmmoLock.select(ctx.getServerHandler().playerEntity, m.config);
            return null;
        }
    }
}
