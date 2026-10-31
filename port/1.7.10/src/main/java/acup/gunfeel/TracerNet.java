package acup.gunfeel;

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;

// The server's word on its bullets to the players around, for the client's tracers and holes (Tracers): each fast
// bullet as it leaves (26.2: ShotPayload) and where it stopped in a creature (26.2: BulletStopPayload). On Net's
// channel at ids first and first + 1. Not here yet: the heavy rounds' scorch (26.2: ImpactHolePayload, sent by
// ExplosiveDamage, which the port has not).
public final class TracerNet {
    public static final int COUNT = 2;
    static final double RANGE = 256.0;

    private TracerNet() {
    }

    public static int register(int first) {
        Net.CHANNEL.registerMessage(ShotHandler.class, Shot.class, first, Side.CLIENT);
        Net.CHANNEL.registerMessage(StopHandler.class, Stop.class, first + 1, Side.CLIENT);
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

    // the network thread: Tracers takes them from any thread
    public static final class ShotHandler implements IMessageHandler<Shot, IMessage> {
        @Override
        public IMessage onMessage(Shot m, MessageContext ctx) {
            Tracers.shot(m.id, m.owner, m.config, m.x, m.y, m.z, m.vx, m.vy, m.vz, m.gravity, m.life, m.damage, m.ex, m.ey, m.ez);
            return null;
        }
    }

    public static final class StopHandler implements IMessageHandler<Stop, IMessage> {
        @Override
        public IMessage onMessage(Stop m, MessageContext ctx) {
            Tracers.stop(m.shot, m.age, m.x, m.y, m.z);
            return null;
        }
    }
}
