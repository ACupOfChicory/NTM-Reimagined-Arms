package acup.gunfeel;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

// The mod's network on 1.7.10 (26.2: GunFeelMod's PayloadRegistrar and the *Payload records): one Forge channel
// "ntmgunfeel" (SimpleNetworkWrapper), a message per payload, each with its own discriminator byte:
//   0 hello     both ways: the protocol and the versions of this mod and NTM (26.2: compat.HelloPayload)
//   1 settings  server -> client: ServerSettings, packed (26.2: ServerSettingsPayload)
// Further messages (tracers, holes, ammo, hitboxes) take the next free numbers. Handlers run on the network thread.
//
// The handshake. 26.2 refuses a client with another PROTOCOL at connection (NeoForge's registrar does it) and lets
// other mod versions in. Forge 1.7.10 checks only mod lists: GunFeelMod's @NetworkCheckHandler turns away a side
// without the mod (FML's default would ask for the very same version), and then the server says hello with its
// PROTOCOL; the client answers with its own, and the server kicks a player whose protocol differs. The client says in
// chat when the server's versions differ from its own (26.2: CompatClient.hello).
public final class Net {
    // raise it when a message changes: a client and a server with different ones do not play together
    public static final String PROTOCOL = "1.7.10-1";
    public static final SimpleNetworkWrapper CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel(GunFeelMod.MODID);
    public static final int HELLO = 0, SETTINGS = 1;

    private Net() {
    }

    public static void init(boolean client) {
        CHANNEL.registerMessage(ServerHello.class, Hello.class, HELLO, Side.SERVER);
        CHANNEL.registerMessage(ClientHello.class, Hello.class, HELLO, Side.CLIENT);
        CHANNEL.registerMessage(ClientSettings.class, Settings.class, SETTINGS, Side.CLIENT);
        FMLCommonHandler.instance().bus().register(new Net());
        if (client) ServerSettingsClient.init();
    }

    // ---------------------------------------------------------------- messages

    public static final class Hello implements IMessage {
        public String protocol = "", mod = "", hbm = "";

        public Hello() {
        }

        public Hello(String protocol, String mod, String hbm) {
            this.protocol = protocol;
            this.mod = mod;
            this.hbm = hbm;
        }

        public static Hello ours() {
            return new Hello(PROTOCOL, BuildInfo.VERSION, PortPaths.hbmVersion());
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

    // the client's answer: its protocol and versions
    public static final class ServerHello implements IMessageHandler<Hello, IMessage> {
        @Override
        public IMessage onMessage(Hello m, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            boolean same = PROTOCOL.equals(m.protocol);
            GunFeel.LOGGER.info("{}: NTM Reimagined Arms {} (protocol {}), NTM {}{}", player.getCommandSenderName(), m.mod, m.protocol, m.hbm,
                    same ? "" : ": another protocol than the server's " + PROTOCOL + ", turned away");
            if (TestLog.server) TestLog.s(-1, "hello", "player", player.getCommandSenderName(), "clientMod", m.mod, "clientNtm", m.hbm,
                    "protocol", m.protocol, "sameProtocol", same);
            if (!same) KICKS.add(new Object[] {player, "NTM Reimagined Arms: this server runs " + BuildInfo.DISPLAY + " (protocol " + PROTOCOL
                    + "), your game " + m.mod + " (protocol " + m.protocol + "). Install the server's version."});
            return null;
        }
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
        CHANNEL.sendTo(Hello.ours(), player);
        Settings s = current();
        if (s != null) CHANNEL.sendTo(s, player);
    }

    private static int ticks;

    @SubscribeEvent
    public void serverTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        BulletServer.tick();
        for (Object[] k; (k = KICKS.poll()) != null;) {
            EntityPlayerMP p = (EntityPlayerMP) k[0];
            if (p.playerNetServerHandler != null) p.playerNetServerHandler.kickPlayerFromServer((String) k[1]);
        }
        // the server's files, checked every two seconds: a few file dates, nothing read until one changes
        if (++ticks < 40) return;
        ticks = 0;
        MinecraftServer server = MinecraftServer.getServer();
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
            // port: no bullet shapes on 1.7.10 yet (PassableClip.extraShapes)
            sent = new Settings(ServerSettings.encodeFitting(new ServerSettings.Snapshot(bullets, ReaimTimes.values(), Map.<String, String>of())));
            sentStamp = now;
            return sent;
        } catch (Exception ex) {
            GunFeel.LOGGER.warn("Could not pack the server settings for the players", ex);
            return sent;
        }
    }

    private static String stamp() {
        Path dir = PortPaths.config();
        return modified(dir.resolve(BulletRules.FILE)) + "|" + modified(dir.resolve(ReaimTimes.FILE));
    }

    private static long modified(Path f) {
        try {
            return Files.exists(f) ? Files.getLastModifiedTime(f).toMillis() : 0L;
        } catch (Exception e) {
            return -1L;
        }
    }
}
