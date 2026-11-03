package acup.gunfeel;

import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;

// The server's word on its bullets to the players around, for the client's tracers and holes (Tracers): each fast
// bullet as it leaves (26.2: ShotPayload), where it stopped in a creature (26.2: BulletStopPayload) and where a heavy
// round went off on a block (26.2: ImpactHolePayload, sent by ExplosiveDamage). On Net's channel at ids first to
// first + 2.
public final class TracerNet {
    public static final int COUNT = 3;
    static final double RANGE = 256.0;

    private TracerNet() {
    }

    public static int register(int first) {
        Net.CHANNEL.registerMessage(ShotHandler.class, Shot.class, first, Side.CLIENT);
        Net.CHANNEL.registerMessage(StopHandler.class, Stop.class, first + 1, Side.CLIENT);
        Net.CHANNEL.registerMessage(ImpactHandler.class, Impact.class, first + 2, Side.CLIENT);
        return first + COUNT;
    }

    public static void send(Entity bullet, IMessage m) {
        Net.CHANNEL.sendToAllAround(m, new NetworkRegistry.TargetPoint(bullet.dimension, bullet.posX, bullet.posY, bullet.posZ, RANGE));
    }

    public static final class Shot implements IMessage {
        int id, owner, config, life;
        double x, y, z, ex, ey, ez;
        float vx, vy, vz, gravity, damage;

        public Shot() {
        }

        public Shot(int config, int owner, double x, double y, double z, float vx, float vy, float vz, float gravity, int life, float damage,
                double ex, double ey, double ez, int id) {
            this.config = config;
            this.owner = owner;
            this.x = x;
            this.y = y;
            this.z = z;
            this.vx = vx;
            this.vy = vy;
            this.vz = vz;
            this.gravity = gravity;
            this.life = life;
            this.damage = damage;
            this.ex = ex;
            this.ey = ey;
            this.ez = ez;
            this.id = id;
        }

        @Override
        public void toBytes(ByteBuf b) {
            b.writeInt(id);
            b.writeInt(owner);
            b.writeInt(config);
            b.writeDouble(x);
            b.writeDouble(y);
            b.writeDouble(z);
            b.writeFloat(vx);
            b.writeFloat(vy);
            b.writeFloat(vz);
            b.writeFloat(gravity);
            b.writeInt(life);
            b.writeFloat(damage);
            b.writeDouble(ex);
            b.writeDouble(ey);
            b.writeDouble(ez);
        }

        @Override
        public void fromBytes(ByteBuf b) {
            id = b.readInt();
            owner = b.readInt();
            config = b.readInt();
            x = b.readDouble();
            y = b.readDouble();
            z = b.readDouble();
            vx = b.readFloat();
            vy = b.readFloat();
            vz = b.readFloat();
            gravity = b.readFloat();
            life = b.readInt();
            damage = b.readFloat();
            ex = b.readDouble();
            ey = b.readDouble();
            ez = b.readDouble();
        }
    }

    public static final class Stop implements IMessage {
        int shot;
        double age, x, y, z;

        public Stop() {
        }

        public Stop(int shot, double age, double x, double y, double z) {
            this.shot = shot;
            this.age = age;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public void toBytes(ByteBuf b) {
            b.writeInt(shot);
            b.writeDouble(age);
            b.writeDouble(x);
            b.writeDouble(y);
            b.writeDouble(z);
        }

        @Override
        public void fromBytes(ByteBuf b) {
            shot = b.readInt();
            age = b.readDouble();
            x = b.readDouble();
            y = b.readDouble();
            z = b.readDouble();
        }
    }

    public static final class Impact implements IMessage {
        int shot, config, bx, by, bz, face;
        double px, py, pz, dx, dy, dz;

        public Impact() {
        }

        public Impact(int shot, int config, int bx, int by, int bz, int face, double px, double py, double pz, double dx, double dy, double dz) {
            this.shot = shot;
            this.config = config;
            this.bx = bx;
            this.by = by;
            this.bz = bz;
            this.face = face;
            this.px = px;
            this.py = py;
            this.pz = pz;
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
        }

        @Override
        public void toBytes(ByteBuf b) {
            b.writeInt(shot);
            b.writeInt(config);
            b.writeInt(bx);
            b.writeInt(by);
            b.writeInt(bz);
            b.writeByte(face);
            b.writeDouble(px);
            b.writeDouble(py);
            b.writeDouble(pz);
            b.writeDouble(dx);
            b.writeDouble(dy);
            b.writeDouble(dz);
        }

        @Override
        public void fromBytes(ByteBuf b) {
            shot = b.readInt();
            config = b.readInt();
            bx = b.readInt();
            by = b.readInt();
            bz = b.readInt();
            face = b.readByte();
            px = b.readDouble();
            py = b.readDouble();
            pz = b.readDouble();
            dx = b.readDouble();
            dy = b.readDouble();
            dz = b.readDouble();
        }
    }

    // the network thread: Tracers takes them from any thread
    public static final class ShotHandler implements IMessageHandler<Shot, IMessage> {
        @Override
        public IMessage onMessage(Shot m, MessageContext ctx) {
            if (acup.gunfeel.compat.Feature.TRACERS.on()) Tracers.shot(m.id, m.owner, m.config, m.x, m.y, m.z, m.vx, m.vy, m.vz, m.gravity, m.life, m.damage, m.ex, m.ey, m.ez);
            return null;
        }
    }

    public static final class StopHandler implements IMessageHandler<Stop, IMessage> {
        @Override
        public IMessage onMessage(Stop m, MessageContext ctx) {
            if (acup.gunfeel.compat.Feature.TRACERS.on()) Tracers.stop(m.shot, m.age, m.x, m.y, m.z);
            return null;
        }
    }

    public static final class ImpactHandler implements IMessageHandler<Impact, IMessage> {
        @Override
        public IMessage onMessage(Impact m, MessageContext ctx) {
            if (acup.gunfeel.compat.Feature.TRACERS.on()) Tracers.impact(m.shot, m.config, m.bx, m.by, m.bz, m.face, m.px, m.py, m.pz, m.dx, m.dy, m.dz);
            return null;
        }
    }
}
