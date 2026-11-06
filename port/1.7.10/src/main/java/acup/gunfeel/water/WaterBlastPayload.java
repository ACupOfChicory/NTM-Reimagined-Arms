package acup.gunfeel.water;

import acup.gunfeel.Net;
import acup.gunfeel.phys.Vec3;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

// The server to the players near: an NTM explosion at water (WaterBlast.announce), for WaterEvents.blast.
// port: 26.2's WaterBlastPayload as a Forge 1.7.10 message on Net's channel; the handler runs on the network thread,
// so the blast waits in WaterEvents for the next client tick (26.2: enqueueWork).
public final class WaterBlastPayload implements IMessage {
    public static final int COUNT = 1;

    double x, y, z, surfaceY;
    float size;
    boolean incendiary, inWater, surface;
    int liquid;

    public WaterBlastPayload() {
    }

    WaterBlastPayload(Vec3 at, float size, boolean incendiary, boolean inWater, Vec3 surface, WaterEvents.Liquid liquid) {
        this.x = at.x;
        this.y = at.y;
        this.z = at.z;
        this.size = size;
        this.incendiary = incendiary;
        this.inWater = inWater;
        this.surface = surface != null;
        this.surfaceY = surface != null ? surface.y : 0;
        this.liquid = liquid.ordinal();
    }

    // GunFeelMod.preInit: the message at id first; returns the next free id
    public static int register(int first) {
        Net.CHANNEL.registerMessage(Handler.class, WaterBlastPayload.class, first, Side.CLIENT);
        return first + COUNT;
    }

    // to the players within 128 blocks plus twice the blast's size, as 26.2's sendToPlayersNear
    static void send(int dimension, Vec3 at, WaterBlastPayload m) {
        Net.CHANNEL.sendToAllAround(m, new NetworkRegistry.TargetPoint(dimension, at.x, at.y, at.z, 128 + m.size * 2));
    }

    @Override
    public void toBytes(ByteBuf b) {
        b.writeDouble(x);
        b.writeDouble(y);
        b.writeDouble(z);
        b.writeFloat(size);
        b.writeBoolean(incendiary);
        b.writeBoolean(inWater);
        b.writeBoolean(surface);
        if (surface) b.writeDouble(surfaceY);
        b.writeByte(liquid);
    }

    @Override
    public void fromBytes(ByteBuf b) {
        x = b.readDouble();
        y = b.readDouble();
        z = b.readDouble();
        size = b.readFloat();
        incendiary = b.readBoolean();
        inWater = b.readBoolean();
        surface = b.readBoolean();
        if (surface) surfaceY = b.readDouble();
        liquid = b.readByte();
    }

    WaterEvents.Blast blast() {
        WaterEvents.Liquid[] all = WaterEvents.Liquid.values();
        Vec3 at = new Vec3(x, y, z);
        return new WaterEvents.Blast(at, size, incendiary, inWater, surface ? new Vec3(x, surfaceY, z) : null,
                liquid >= 0 && liquid < all.length ? all[liquid] : WaterEvents.Liquid.OTHER);
    }

    public static final class Handler implements IMessageHandler<WaterBlastPayload, IMessage> {
        @Override
        public IMessage onMessage(WaterBlastPayload m, MessageContext ctx) {
            if (acup.gunfeel.compat.Feature.TRACERS.on()) WaterEvents.received(m.blast());
            return null;
        }
    }
}
