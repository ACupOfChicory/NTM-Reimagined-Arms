package acup.gunfeel.compat;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import com.mojang.brigadier.CommandDispatcher;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

// Client side of Compat: the warning on the title screen when NTM is not a tested version (once a launch, unless
// hidden for that NTM version in config/ntm-gunfeel-compat.properties), the server's versions in chat when they differ
// (HelloPayload), and /gunfeel status.
public final class CompatClient {
    private static final String FILE = "ntm-gunfeel-compat.properties", HIDE = "hideWarningFor";
    private static boolean shown;
    private static volatile HelloPayload server;

    private CompatClient() {
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Opening.class, e -> {
            if (shown || !(e.getNewScreen() instanceof TitleScreen title) || Compat.inRange()) return;
            shown = true;
            if (Compat.hbmVersion().equals(hidden())) return;
            e.setNewScreen(new Warning(title));
        });
    }

    // ---------------------------------------------------------------- the warning

    static final class Warning extends ConfirmScreen {
        Warning(Screen next) {
            super(yes -> {
                if (!yes) hide(Compat.hbmVersion());
                Minecraft.getInstance().gui.setScreen(next);
            }, Component.translatable("ntmgunfeel.compat.title", Compat.hbmVersion(), Compat.lastSupported(Compat.tested()))
                    .withStyle(ChatFormatting.RED, ChatFormatting.BOLD), message(),
                    Component.translatable("ntmgunfeel.compat.continue"), Component.translatable("ntmgunfeel.compat.hide"));
        }

        // Esc is "continue", not "do not show again"
        @Override
        public boolean keyPressed(KeyEvent event) {
            if (event.isEscape()) {
                callback.accept(true);
                return true;
            }
            return super.keyPressed(event);
        }
    }

    static Component message() {
        Map<Feature, String> off = Compat.off();
        if (off.isEmpty()) return Component.translatable("ntmgunfeel.compat.all_on");
        MutableComponent m = Component.translatable("ntmgunfeel.compat.off");
        for (Feature f : off.keySet()) m.append("\n• ").append(Component.translatable(f.langKey()));
        return m;
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve(FILE);
    }

    static String hidden() {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file())) {
            p.load(r);
        } catch (IOException e) {
            return "";
        }
        return p.getProperty(HIDE, "").trim();
    }

    static void hide(String version) {
        Properties p = new Properties();
        p.setProperty(HIDE, version);
        try (Writer w = Files.newBufferedWriter(file())) {
            p.store(w, "NTM Reimagined Arms: the untested NTM version the warning is not shown for");
        } catch (IOException e) {
            HbmMixinPlugin.LOG.warn("Could not save {}", FILE, e);
        }
    }

    // ---------------------------------------------------------------- the server's versions

    public static void hello(HelloPayload p) {
        server = p;
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        if (!p.mod().equals(Compat.modVersion())) {
            player.sendSystemMessage(Component.translatable("ntmgunfeel.compat.server_mod", p.mod(), Compat.modVersion())
                    .withStyle(ChatFormatting.GOLD));
        }
        if (!p.hbm().equals(Compat.hbmVersion())) {
            player.sendSystemMessage(Component.translatable("ntmgunfeel.compat.server_hbm", p.hbm(), Compat.hbmVersion())
                    .withStyle(ChatFormatting.GOLD));
        }
    }

    public static void leave() {
        server = null;
    }

    // ---------------------------------------------------------------- /gunfeel status

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("gunfeel").then(Commands.literal("status").executes(c -> {
            CommandSourceStack src = c.getSource();
            java.util.function.Consumer<Component> s = m -> src.sendSuccess(() -> m, false);
            s.accept(Component.translatable("ntmgunfeel.status.versions", Compat.modVersion(), Compat.hbmVersion()));
            s.accept(Component.translatable(Compat.inRange() ? "ntmgunfeel.status.tested" : "ntmgunfeel.status.untested",
                    Compat.tested()).withStyle(Compat.inRange() ? ChatFormatting.GREEN : ChatFormatting.RED));
            HelloPayload srv = server;
            if (srv != null) s.accept(Component.translatable("ntmgunfeel.status.server", srv.mod(), srv.hbm()));
            Map<String, String> skipped = Compat.skipped();
            s.accept(Component.translatable("ntmgunfeel.status.skipped", skipped.size()));
            skipped.forEach((m, why) -> s.accept(Component.literal("  " + m + ": " + why).withStyle(ChatFormatting.GRAY)));
            Map<Feature, String> off = Compat.off();
            s.accept(Component.translatable("ntmgunfeel.status.off", off.size()));
            off.forEach((f, why) -> s.accept(Component.literal("  ").append(Component.translatable(f.langKey()))
                    .append(": " + why).withStyle(ChatFormatting.GRAY)));
            return 1;
        })));
    }
}
