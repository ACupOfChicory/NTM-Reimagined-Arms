package acup.gunfeel;

import net.minecraftforge.common.MinecraftForge;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;

// The model hitboxes' messages on the mod's channel (26.2: the model_pose and model_mesh payloads of GunFeelMod's registrar) and
// the ticks ModelHits needs (26.2: LevelTickEvent.Post -> ModelHits.tick). Both messages go client -> server. FML hands
// them in on a Netty thread; they wait in a queue for the server thread's next tick, in their order, so a mesh is
// known before the pose that uses it. One pose is at most 128 cubes of ~70 bytes, one mesh 768 triangles: well under
// 1.7.10's 32767-byte limit for a client's packet.
public final class ModelHitNet {
    // ids taken on Net.CHANNEL: PoseMessage, MeshMessage
    public static final int COUNT = 2;
    private static SimpleNetworkWrapper net;
    private record Pending(EntityPlayerMP player, Object payload) {}
    private static final ConcurrentLinkedQueue<Pending> QUEUE = new ConcurrentLinkedQueue<>();

    private ModelHitNet() {}

    // GunFeelMod.preInit: the messages at ids first and first + 1; returns the next free id
    public static int register(int first, boolean client) {
        net = Net.CHANNEL;
        net.registerMessage(PoseHandler.class, PoseMessage.class, first, Side.SERVER);
        net.registerMessage(MeshHandler.class, MeshMessage.class, first + 1, Side.SERVER);
        MinecraftForge.EVENT_BUS.register(new ModelHitNet.Ticks());
        if (client) ModelHitHooks.register();
        return first + COUNT;
    }

    static void send(ModelPosePayload p) {
        if (net != null) net.sendToServer(new PoseMessage(p));
    }

    static void send(ModelMeshPayload p) {
        if (net != null) net.sendToServer(new MeshMessage(p));
    }

    public static final class PoseMessage implements IMessage {
        ModelPosePayload payload;
        public PoseMessage() {}
        PoseMessage(ModelPosePayload payload) { this.payload = payload; }
        @Override public void fromBytes(ByteBuf b) { payload = ModelPosePayload.read(b); }
        @Override public void toBytes(ByteBuf b) { payload.write(b); }
    }

    public static final class MeshMessage implements IMessage {
        ModelMeshPayload payload;
        public MeshMessage() {}
        MeshMessage(ModelMeshPayload payload) { this.payload = payload; }
        @Override public void fromBytes(ByteBuf b) { payload = ModelMeshPayload.read(b); }
        @Override public void toBytes(ByteBuf b) { payload.write(b); }
    }

    public static final class PoseHandler implements IMessageHandler<PoseMessage, IMessage> {
        @Override public IMessage onMessage(PoseMessage m, MessageContext ctx) {
            if (QUEUE.size() < 4096) QUEUE.add(new Pending(ctx.getServerHandler().player, m.payload));
            return null;
        }
    }

    public static final class MeshHandler implements IMessageHandler<MeshMessage, IMessage> {
        @Override public IMessage onMessage(MeshMessage m, MessageContext ctx) {
            if (QUEUE.size() < 4096) QUEUE.add(new Pending(ctx.getServerHandler().player, m.payload));
            return null;
        }
    }

    // server thread: the queued messages, then each world's poses once a tick
    public static final class Ticks {
        @SubscribeEvent
        public void serverTick(TickEvent.ServerTickEvent e) {
            if (e.phase != TickEvent.Phase.START) return;
            for (Pending p; (p = QUEUE.poll()) != null; ) {
                try {
                    if (p.payload() instanceof ModelPosePayload pose) ModelHits.receive(p.player(), pose);
                    else if (p.payload() instanceof ModelMeshPayload mesh) mesh.receive();
                } catch (RuntimeException ex) {
                    ModelHitLog.event("server", "packet_failed", p.player(), ex::toString);
                }
            }
        }

        @SubscribeEvent
        public void worldTick(TickEvent.WorldTickEvent e) {
            if (e.phase == TickEvent.Phase.END && !e.world.isRemote && acup.gunfeel.compat.Feature.MODEL_HITBOXES.on()) ModelHits.tick(e.world);
        }
    }
}
