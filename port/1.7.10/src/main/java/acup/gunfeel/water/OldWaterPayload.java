package acup.gunfeel.water;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import acup.gunfeel.Net;
import acup.gunfeel.phys.BlockPos;
import acup.gunfeel.phys.Direction;
import acup.gunfeel.phys.Vec3;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;

// port only: the server to the players near, a tick of an old EntityBulletBaseNT in water (WaterTick.covers): its
// water events and the stretches of its path in water. 26.2 fires these rounds as NTM's own bullets, whose tracer
// works the events out on the client; NTM 1.7.10's old bullet has no tracer and does not tick on the client, and the
// UFO's rocket homes, so the server's own path is sent. The client hands them to WaterFx as a tracer's (the splash,
// the stop, the trail of bubbles); an impact fuze that goes off on the surface shows nothing of its own that tick, as
// 26.2's (its blast's column comes with WaterBlastPayload).
public final class OldWaterPayload implements IMessage {
    public static final int COUNT = 1;
    // at most this many events and stretches a tick (a skip and its entry, a stop: a few)
    static final int MAX = 16;
    static final double RANGE = 128;

    int bullet;
    boolean fuzed;
    final List<WaterEvents.Event> events = new ArrayList<>();
    final List<Vec3[]> wet = new ArrayList<>();

    public OldWaterPayload() {
    }

    OldWaterPayload(Entity bullet, boolean fuzed, List<WaterEvents.Event> events, List<Vec3[]> wet) {
        this.bullet = bullet.getEntityId();
        this.fuzed = fuzed;
        for (WaterEvents.Event e : events) if (this.events.size() < MAX) this.events.add(e);
        for (Vec3[] s : wet) if (this.wet.size() < MAX) this.wet.add(s);
    }

    // GunFeelMod.preInit: the message at id first; returns the next free id
    public static int register(int first) {
        Net.CHANNEL.registerMessage(Handler.class, OldWaterPayload.class, first, Side.CLIENT);
        return first + COUNT;
    }

    // WaterTick.move, the server: a tick with events or a path in water
    static void send(Entity bullet, boolean fuzed, WaterPath.Result r) {
        List<Vec3[]> wet = r.wet();
        if (r.events.isEmpty() && wet.isEmpty()) return;
        Net.CHANNEL.sendToAllAround(new OldWaterPayload(bullet, fuzed, r.events, wet),
                new NetworkRegistry.TargetPoint(bullet.dimension, bullet.posX, bullet.posY, bullet.posZ, RANGE));
    }

    @Override
    public void toBytes(ByteBuf b) {
        b.writeInt(bullet);
        b.writeBoolean(fuzed);
        b.writeByte(events.size());
        for (WaterEvents.Event e : events) {
            b.writeByte(e.type().ordinal());
            vec(b, e.at());
            b.writeByte(e.face() == null ? -1 : e.face().ordinal());
            vec(b, e.normal());
            b.writeDouble(e.f());
            b.writeByte(e.cls() == null ? -1 : e.cls().ordinal());
            b.writeByte(e.kind() == null ? -1 : e.kind().ordinal());
            b.writeByte(e.liquid() == null ? -1 : e.liquid().ordinal());
            vec(b, e.flow());
            b.writeDouble(e.depth());
            b.writeDouble(e.under());
            vec(b, e.dir());
            BlockPos p = e.pos();
            b.writeBoolean(p != null);
            if (p != null) {
                b.writeInt(p.getX());
                b.writeInt(p.getY());
                b.writeInt(p.getZ());
            }
        }
        b.writeByte(wet.size());
        for (Vec3[] s : wet) {
            vec(b, s[0]);
            vec(b, s[1]);
        }
    }

    @Override
    public void fromBytes(ByteBuf b) {
        bullet = b.readInt();
        fuzed = b.readBoolean();
        int n = Math.min(b.readUnsignedByte(), MAX);
        for (int i = 0; i < n; i++) {
            WaterEvents.Type type = of(WaterEvents.Type.values(), b.readByte());
            Vec3 at = vec(b);
            Direction face = of(Direction.values(), b.readByte());
            Vec3 normal = vec(b);
            double f = b.readDouble();
            WaterClass cls = of(WaterClass.values(), b.readByte());
            WaterEvents.Kind kind = of(WaterEvents.Kind.values(), b.readByte());
            WaterEvents.Liquid liquid = of(WaterEvents.Liquid.values(), b.readByte());
            Vec3 flow = vec(b);
            double depth = b.readDouble(), under = b.readDouble();
            Vec3 dir = vec(b);
            BlockPos pos = b.readBoolean() ? new BlockPos(b.readInt(), b.readInt(), b.readInt()) : null;
            if (type == null || at == null) continue;
            events.add(new WaterEvents.Event(type, at, face, normal, f, cls == null ? WaterClass.PISTOL : cls, kind == null ? WaterEvents.Kind.STILL : kind,
                    liquid == null ? WaterEvents.Liquid.OTHER : liquid, flow, depth, under, dir, pos, null));
        }
        int m = Math.min(b.readUnsignedByte(), MAX);
        for (int i = 0; i < m; i++) {
            Vec3 a = vec(b), c = vec(b);
            if (a != null && c != null) wet.add(new Vec3[] {a, c});
        }
    }

    private static void vec(ByteBuf b, Vec3 v) {
        b.writeBoolean(v != null);
        if (v == null) return;
        b.writeDouble(v.x);
        b.writeDouble(v.y);
        b.writeDouble(v.z);
    }

    private static Vec3 vec(ByteBuf b) {
        return b.readBoolean() ? new Vec3(b.readDouble(), b.readDouble(), b.readDouble()) : null;
    }

    private static <T> T of(T[] all, int i) {
        return i >= 0 && i < all.length ? all[i] : null;
    }

    // ---- the client: the handler runs on the network thread, the ticks wait for the next client tick (WaterFx)
    private static final ConcurrentLinkedQueue<OldWaterPayload> RECEIVED = new ConcurrentLinkedQueue<>();

    // the stand's (WaterFxScenario): the ticks handed to the effects
    public static volatile int drained;

    public static void drain(World level) {
        OldWaterPayload m;
        while ((m = RECEIVED.poll()) != null) {
            drained++;
            // an impact fuze goes off on the surface it comes in through: its blast and nothing of its own that tick, no
            // splash and no trail (the MK4 tracer's waterFuzed, Tracers.water)
            if (m.fuzed && WaterSettings.fuze() && entered(m.events)) continue;
            Entity bullet = level.getEntityByID(m.bullet);
            for (WaterEvents.Event e : m.events) {
                WaterFx.oldEvent(level, new WaterEvents.Event(e.type(), e.at(), e.face(), e.normal(), e.f(), e.cls(), e.kind(), e.liquid(), e.flow(),
                        e.depth(), e.under(), e.dir(), e.pos(), bullet));
            }
            WaterFx.oldTrail(level, m.wet);
        }
    }

    private static boolean entered(List<WaterEvents.Event> events) {
        for (WaterEvents.Event e : events) if (e.type() == WaterEvents.Type.ENTER) return true;
        return false;
    }

    public static void forget() {
        RECEIVED.clear();
    }

    public static final class Handler implements IMessageHandler<OldWaterPayload, IMessage> {
        @Override
        public IMessage onMessage(OldWaterPayload m, MessageContext ctx) {
            if (RECEIVED.size() < 256) RECEIVED.add(m);
            return null;
        }
    }
}
