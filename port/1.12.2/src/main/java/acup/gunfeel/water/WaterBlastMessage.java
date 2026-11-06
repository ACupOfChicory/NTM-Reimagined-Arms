package acup.gunfeel.water;

import io.netty.buffer.ByteBuf;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

// 26.2's WaterBlastPayload: the server to the players near, an NTM explosion at water (WaterBlast.announce), for
// WaterEvents.blast. port: a Forge message on Net's channel; the client hands it on in its own thread.
public final class WaterBlastMessage implements IMessage {
    Vec3d at, surface;
    float size;
    boolean incendiary, inWater;
    WaterEvents.Liquid liquid;

    public WaterBlastMessage() {
    }

    WaterBlastMessage(Vec3d at, float size, boolean incendiary, boolean inWater, Vec3d surface, WaterEvents.Liquid liquid) {
        this.at = at;
        this.size = size;
        this.incendiary = incendiary;
        this.inWater = inWater;
        this.surface = surface;
        this.liquid = liquid;
    }

    @Override
    public void toBytes(ByteBuf b) {
        b.writeDouble(at.x);
        b.writeDouble(at.y);
        b.writeDouble(at.z);
        b.writeFloat(size);
        b.writeBoolean(incendiary);
        b.writeBoolean(inWater);
        b.writeBoolean(surface != null);
        if (surface != null) b.writeDouble(surface.y);
        b.writeByte(liquid.ordinal());
    }

    @Override
    public void fromBytes(ByteBuf b) {
        at = new Vec3d(b.readDouble(), b.readDouble(), b.readDouble());
        size = b.readFloat();
        incendiary = b.readBoolean();
        inWater = b.readBoolean();
        surface = b.readBoolean() ? new Vec3d(at.x, b.readDouble(), at.z) : null;
        WaterEvents.Liquid[] all = WaterEvents.Liquid.values();
        int k = b.readByte();
        liquid = k >= 0 && k < all.length ? all[k] : WaterEvents.Liquid.OTHER;
    }

    public static final class Handler implements IMessageHandler<WaterBlastMessage, IMessage> {
        @Override
        public IMessage onMessage(WaterBlastMessage m, MessageContext ctx) {
            if (acup.gunfeel.compat.Feature.TRACERS.on()) Client.handle(m);
            return null;
        }
    }

    // the client's classes only here: the server never loads it
    static final class Client {
        static void handle(WaterBlastMessage m) {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
            mc.addScheduledTask(() -> {
                if (mc.world != null) WaterEvents.blast(mc.world, new WaterEvents.Blast(m.at, m.size, m.incendiary, m.inWater, m.surface, m.liquid));
            });
        }
    }
}
