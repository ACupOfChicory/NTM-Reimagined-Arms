package acup.gunfeel;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

// The mod's network on 1.12.2 (26.2: GunFeelMod's PayloadRegistrar and the *Payload records): one Forge channel
// "ntmgunfeel" (SimpleNetworkWrapper), a message per payload, each with its own discriminator byte:
//   0 hello     both ways: the build and the versions of this mod and NTM (26.2: compat.HelloPayload)
//   1 settings  server -> client: ServerSettings, packed (26.2: ServerSettingsPayload)
//   2-4 tracers   server -> client (TracerNet)
//   5-6 ammo      client -> server (AmmoNet)
//   7-8 hitboxes  client -> server (ModelHitNet)
// Further messages take the next free numbers (GunFeelMod.preInit). Handlers run on the network thread.
//
// The handshake. 26.2 refuses a client of another build at connection (NeoForge's registrar, the channel's version is
// the mod's build). Forge checks only mod lists: GunFeelMod's @NetworkCheckHandler turns away a side without the mod
// (FML's default would ask for the very same version, with a bare "Mod rejections" for a text), and then the server
// says hello with its build (again every two seconds until the answer comes); the client answers with its own, and
// the server kicks a player of another build with a text that names both. Builds made without git (X0-dev) play
// only with each other. The client says in chat when the server's NTM differs from its own (26.2: CompatClient.hello).
public final class Net {
    public static final SimpleNetworkWrapper CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel(GunFeelMod.MODID);
    public static final int HELLO = 0, SETTINGS = 1;

    private Net() {
    }

    public static void init(boolean client) {
        CHANNEL.registerMessage(ServerHello.class, Hello.class, HELLO, Side.SERVER);
        CHANNEL.registerMessage(ClientHello.class, Hello.class, HELLO, Side.CLIENT);
        CHANNEL.registerMessage(ClientSettings.class, Settings.class, SETTINGS, Side.CLIENT);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new Net());
        if (client) ServerSettingsClient.init();
    }

    // ---------------------------------------------------------------- messages

    public static final class Hello implements IMessage {
        // protocol: the build too (BuildInfo.VERSION); older builds compared their hand-raised protocol here and so
        // turn a newer side away themselves
        public String protocol = "", mod = "", hbm = "";

        public Hello() {
        }

        public Hello(String protocol, String mod, String hbm) {
            this.protocol = protocol;
            this.mod = mod;
            this.hbm = hbm;
        }

        public static Hello ours() {
            return new Hello(BuildInfo.VERSION, BuildInfo.VERSION, PortPaths.hbmVersion());
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            protocol = ByteBufUtils.readUTF8String(buf);
            mod = ByteBufUtils.readUTF8String(buf);
            hbm = ByteBufUtils.readUTF8String(buf);
        }

        @Override
        public void toBytes(ByteBuf buf) {
            ByteBufUtils.writeUTF8String(buf, protocol);
            ByteBufUtils.writeUTF8String(buf, mod);
            ByteBufUtils.writeUTF8String(buf, hbm);
        }
    }

    public static final class Settings implements IMessage {
        public byte[] data = new byte[0];

        public Settings() {
        }

        public Settings(byte[] data) {
            this.data = data;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            int n = buf.readInt();
            if (n < 0 || n > ServerSettings.MAX_BYTES + 4096 || n > buf.readableBytes()) throw new IllegalArgumentException("bad settings length " + n);
            data = new byte[n];
            buf.readBytes(data);
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeInt(data.length);
            buf.writeBytes(data);
        }
    }

    // ---------------------------------------------------------------- handlers (the client's go to client classes)

    // the client's answer: its build and versions
    public static final class ServerHello implements IMessageHandler<Hello, IMessage> {
        @Override
        public IMessage onMessage(Hello m, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            boolean same = BuildInfo.VERSION.equals(m.mod);
            GunFeel.LOGGER.info("{}: NTM Reimagined Arms {}, NTM {}{}", player.getName(), m.mod, m.hbm,
                    same ? "" : ": another build than the server's " + BuildInfo.VERSION + ", turned away");
            if (TestLog.server) TestLog.s(-1, "hello", "player", player.getName(), "clientMod", m.mod, "clientNtm", m.hbm,
                    "sameMod", same);
            UNANSWERED.remove(player);
            if (!same) KICKS.add(new Object[] {player, "Server has NTM Reimagined Arms " + BuildInfo.DISPLAY + ", you have "
                    + display(m.mod) + " \u2014 install the same build."});
            return null;
        }
    }

    // a build as players see it: 516.3+mc1.12.2-ce is X516.3, 0+mc… (no git) X0-dev; at most 40 characters of
    // what a client sends
    static String display(String version) {
        int plus = version.indexOf('+');
        if (version.length() > 40) version = version.substring(0, 40);
        if (plus <= 0 || plus >= version.length()) return version;
        String n = version.substring(0, plus);
        return n.equals("0") ? "X0-dev" : "X" + n;
    }

    public static final class ClientHello implements IMessageHandler<Hello, IMessage> {
        @Override
        public IMessage onMessage(Hello m, MessageContext ctx) {
            ServerSettingsClient.hello(m);
            return ours();
        }

        private static Hello ours() {
            return Hello.ours();
        }
    }

    public static final class ClientSettings implements IMessageHandler<Settings, IMessage> {
        @Override
        public IMessage onMessage(Settings m, MessageContext ctx) {
            ServerSettingsClient.receive(m.data);
            return null;
        }
    }

    // ---------------------------------------------------------------- the server's side

    // players to turn away, on the server thread (the hello comes in on the network thread)
    private static final ConcurrentLinkedQueue<Object[]> KICKS = new ConcurrentLinkedQueue<Object[]>();

    @SubscribeEvent
    public void loggedIn(PlayerEvent.PlayerLoggedInEvent e) {
        if (!(e.player instanceof EntityPlayerMP)) return;
        EntityPlayerMP player = (EntityPlayerMP) e.player;
        UNANSWERED.put(player, new int[2]); // before the hello: its answer may come at once
        CHANNEL.sendTo(Hello.ours(), player);
        Settings s = current();
        if (s != null) CHANNEL.sendTo(s, player);
    }

    @SubscribeEvent
    public void loggedOut(PlayerEvent.PlayerLoggedOutEvent e) {
        UNANSWERED.remove(e.player);
    }

    // players whose answer to the hello has not come: ticks since the last hello, hellos sent again. A hello or its
    // answer does not always arrive (a client of another build once played on the 1.12.2 stand, its answer never came
    // in), so it goes again every two seconds, at most ten times; the answer takes the player off (ServerHello)
    private static final Map<EntityPlayerMP, int[]> UNANSWERED = new ConcurrentHashMap<EntityPlayerMP, int[]>();

    private static int ticks;

    @SubscribeEvent
    public void serverTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        if (acup.gunfeel.compat.Feature.BULLETS.on()) BulletServer.tick();
        for (Object[] k; (k = KICKS.poll()) != null;) {
            EntityPlayerMP p = (EntityPlayerMP) k[0];
            if (p.connection != null) p.connection.disconnect(new net.minecraft.util.text.TextComponentString((String) k[1]));
        }
        for (Iterator<Map.Entry<EntityPlayerMP, int[]>> i = UNANSWERED.entrySet().iterator(); i.hasNext();) {
            Map.Entry<EntityPlayerMP, int[]> w = i.next();
            int[] c = w.getValue();
            if (++c[0] < 40) continue;
            c[0] = 0;
            if (++c[1] > 10) {
                GunFeel.LOGGER.warn("{} has not answered the hello: its build is not known", w.getKey().getName());
                i.remove();
                continue;
            }
            CHANNEL.sendTo(Hello.ours(), w.getKey());
        }
        // the server's files, checked every two seconds: a few file dates, nothing read until one changes
        if (++ticks < 40) return;
        ticks = 0;
        MinecraftServer server = net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null || server.getCurrentPlayerCount() == 0 || stamp().equals(sentStamp)) return;
        Settings s = current();
        if (s != null) CHANNEL.sendToAll(s);
    }

    private static String sentStamp = "";
    private static Settings sent;

    // the message for the files as they are now, packed again only when one changed (26.2: ServerSettingsPayload.current)
    static synchronized Settings current() {
        String now = stamp();
        if (sent != null && now.equals(sentStamp)) return sent;
        try {
            Path rules = PortPaths.config().resolve(BulletRules.FILE);
            String bullets = Files.exists(rules) ? new String(Files.readAllBytes(rules), StandardCharsets.UTF_8) : "";
            // the shapes beyond the jar's (26.2: PassableClip.extraShapes): the server's config file (ModelCells)
            sent = new Settings(ServerSettings.encodeFitting(new ServerSettings.Snapshot(bullets, ReaimTimes.values(), ModelCells.reloadOwn())));
            sentStamp = now;
            return sent;
        } catch (Exception ex) {
            GunFeel.LOGGER.warn("Could not pack the server settings for the players", ex);
            return sent;
        }
    }

    private static String stamp() {
        Path dir = PortPaths.config();
        return modified(dir.resolve(BulletRules.FILE)) + "|" + modified(dir.resolve(ReaimTimes.FILE)) + "|" + modified(dir.resolve(ModelCells.CONFIG));
    }

    private static long modified(Path f) {
        try {
            return Files.exists(f) ? Files.getLastModifiedTime(f).toMillis() : 0L;
        } catch (Exception e) {
            return -1L;
        }
    }
}
