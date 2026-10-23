package acup.gunfeel;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

// client -> server: /gunfeel testlog switched the player's test log; an op (or the single player world's owner)
// switches the server's side with it (TestLog)
public record TestLogPayload(boolean on) implements CustomPacketPayload {
    public static final Type<TestLogPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("ntmgunfeel", "test_log"));
    public static final StreamCodec<ByteBuf, TestLogPayload> CODEC = ByteBufCodecs.BOOL.map(TestLogPayload::new, TestLogPayload::on);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ServerPlayer sp, TestLogPayload p) {
        boolean owner = sp.level().getServer().isSingleplayerOwner(sp.nameAndId());
        if (!owner && !sp.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
            sp.sendSystemMessage(Component.literal("[testlog] Лог сервера включает только оп: пишется только лог клиента"));
            return;
        }
        java.nio.file.Path f = TestLog.set(false, p.on());
        if (p.on()) TestLog.s(-1, "start", "player", sp.getPlainTextName());
        boolean same = sp.level().getServer().isSingleplayer();
        sp.sendSystemMessage(Component.literal(p.on()
                ? "[testlog] Сервер пишет лог" + (same ? " в тот же файл" : ": " + (f == null ? "не открылся" : f.getFileName()))
                : "[testlog] Сервер больше не пишет"));
    }
}
