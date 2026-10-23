package acup.gunfeel;

import acup.gunfeel.compat.Compat;
import acup.gunfeel.compat.CompatClient;
import acup.gunfeel.compat.HelloPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

// Client: /gunfeel testlog switches the test log (TestLog) on or off, here and, for an op, on the server too
public final class TestLogClient {
    private TestLogClient() {
    }

    public static int toggle(CommandSourceStack source) {
        boolean on = !TestLog.client;
        java.nio.file.Path f = TestLog.set(true, on);
        Minecraft mc = Minecraft.getInstance();
        if (on) {
            var sp = mc.getSingleplayerServer();
            HelloPayload hello = CompatClient.serverHello();
            TestLog.c(-1, "start", "player", mc.player == null ? "-" : mc.player.getPlainTextName(),
                    "game", sp == null ? "server" : sp.isPublished() ? "lan_host" : "single", "remoteSettings", ServerSettings.remote() != null,
                    "mod", Compat.modVersion(), "ntm", Compat.hbmVersion(), "ntmTested", Compat.tested(), "inRange", Compat.inRange(),
                    "off", Compat.offList(), "serverMod", hello == null ? null : hello.mod(), "serverNtm", hello == null ? null : hello.hbm(),
                    "cameraScale", Config.cameraScale, "cameraScaleMin", ReaimTimes.number("cameraScaleMin", 0F),
                    "bulletSpeed", ReaimTimes.number("bulletSpeed", 1.6F), "bulletBreakConcrete", ReaimTimes.flag(BulletRules.CONCRETE_KEY, false));
            Config.logQuality();
        }
        // the server's side: it answers in the chat (TestLogPayload.handle)
        if (mc.getConnection() != null) ClientPacketDistributor.sendToServer(new TestLogPayload(on));
        source.sendSuccess(() -> Component.literal(on
                ? "[testlog] Лог проверки пишется: " + (f == null ? "файл не открылся, см. latest.log" : "logs/" + f.getFileName()) + ". Повтор команды выключает."
                : "[testlog] Лог проверки выключен"), false);
        return 1;
    }
}
