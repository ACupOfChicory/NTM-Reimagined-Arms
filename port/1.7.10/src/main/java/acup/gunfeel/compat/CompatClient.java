package acup.gunfeel.compat;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.common.MinecraftForge;

import acup.gunfeel.GunFeel;
import acup.gunfeel.Net;
import acup.gunfeel.PortPaths;
import acup.gunfeel.ServerSettingsClient;

// port: 26.2's CompatClient (e284339). The warning on the title screen when NTM is not a tested version (once a launch,
// unless hidden for that NTM version in config/ntm-gunfeel-compat.properties) and /gunfeel status (a client command).
// The server's versions in chat are ServerSettingsClient's (Net's hello). 1.7.10 has no confirm screen with a long
// message, so the warning is a screen of its own: the lines wrapped, "continue" and "don't show for this version";
// Esc is "continue".
public final class CompatClient {
    private static final String FILE = "ntm-gunfeel-compat.properties", HIDE = "hideWarningFor";
    private static boolean shown;

    private CompatClient() {
    }

    public static void init() {
        MinecraftForge.EVENT_BUS.register(new CompatClient());
        ClientCommandHandler.instance.registerCommand(new Status());
    }

    @SubscribeEvent
    public void open(GuiOpenEvent e) {
        if (shown || !(e.gui instanceof GuiMainMenu) || Compat.inRange()) return;
        shown = true;
        if (Compat.hbmVersion().equals(hidden())) return;
        e.gui = new Warning(e.gui);
    }

    // ---------------------------------------------------------------- the warning

    static final class Warning extends GuiScreen {
        private final GuiScreen next;
        private List<String> lines = new ArrayList<String>(), titleLines = new ArrayList<String>();

        Warning(GuiScreen next) {
            this.next = next;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void initGui() {
            buttonList.clear();
            buttonList.add(new GuiButton(0, width / 2 - 155, height / 6 + 120, 150, 20, I18n.format("ntmgunfeel.compat.continue")));
            buttonList.add(new GuiButton(1, width / 2 + 5, height / 6 + 120, 150, 20, I18n.format("ntmgunfeel.compat.hide")));
            String title = I18n.format("ntmgunfeel.compat.title", Compat.hbmVersion(), Compat.lastSupported(Compat.tested()));
            titleLines = new ArrayList<String>();
            // the font measures the bold letters as drawn and carries the colour on to the next line
            for (Object l : fontRendererObj.listFormattedStringToWidth(EnumChatFormatting.RED + "" + EnumChatFormatting.BOLD + title, width - 50))
                titleLines.add((String) l);
            lines = new ArrayList<String>();
            for (String l : message()) lines.addAll(fontRendererObj.listFormattedStringToWidth(l, width - 50));
        }

        @Override
        protected void actionPerformed(GuiButton b) {
            if (b.id == 1) hide(Compat.hbmVersion());
            mc.displayGuiScreen(next);
        }

        // a dev run that closes by itself (a file "autostop", a scenario): a picture of the warning, then on
        private int ticks;

        @Override
        public void updateScreen() {
            if (!new java.io.File("autostop").exists() && System.getProperty("ntmgunfeel.scenario") == null || ++ticks != 40) return;
            net.minecraft.util.ScreenShotHelper.saveScreenshot(mc.mcDataDir, "compat-warning.png", mc.displayWidth, mc.displayHeight,
                    mc.getFramebuffer());
            GunFeel.LOGGER.info("Untested NTM warning: screenshot compat-warning.png, going on");
            mc.displayGuiScreen(next);
        }

        @Override
        protected void keyTyped(char c, int key) {
            if (key == 1) mc.displayGuiScreen(next);
        }

        @Override
        public void drawScreen(int mouseX, int mouseY, float partial) {
            drawDefaultBackground();
            int y = 30;
            for (String l : titleLines) {
                drawCenteredString(fontRendererObj, l, width / 2, y, 0xFFFFFF);
                y += fontRendererObj.FONT_HEIGHT + 1;
            }
            y += 8;
            for (String l : lines) {
                drawCenteredString(fontRendererObj, l, width / 2, y, 0xFFFFFF);
                y += fontRendererObj.FONT_HEIGHT;
            }
            super.drawScreen(mouseX, mouseY, partial);
        }
    }

    static List<String> message() {
        List<String> out = new ArrayList<String>();
        Map<Feature, String> off = Compat.off();
        if (off.isEmpty()) {
            out.add(I18n.format("ntmgunfeel.compat.all_on"));
            return out;
        }
        out.add(I18n.format("ntmgunfeel.compat.off"));
        for (Feature f : off.keySet()) out.add("• " + I18n.format(f.langKey()));
        return out;
    }

    private static Path file() {
        return PortPaths.config().resolve(FILE);
    }

    static String hidden() {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file(), StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException e) {
            return "";
        }
        return p.getProperty(HIDE, "").trim();
    }

    static void hide(String version) {
        Properties p = new Properties();
        p.setProperty(HIDE, version);
        try (Writer w = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) {
            p.store(w, "NTM Reimagined Arms: the untested NTM version the warning is not shown for");
        } catch (IOException e) {
            GunFeel.LOGGER.warn("Could not save {}", FILE, e);
        }
    }

    // ---------------------------------------------------------------- /gunfeel status

    static final class Status extends CommandBase {
        @Override
        public String getCommandName() {
            return "gunfeel";
        }

        @Override
        public String getCommandUsage(ICommandSender sender) {
            return acup.gunfeel.GunfeelCommand.usage();
        }

        @Override
        public int getRequiredPermissionLevel() {
            return 0;
        }

        @Override
        public boolean canCommandSenderUseCommand(ICommandSender sender) {
            return true;
        }

        @Override
        @SuppressWarnings("rawtypes")
        public List addTabCompletionOptions(ICommandSender sender, String[] args) {
            return acup.gunfeel.GunfeelCommand.complete(args);
        }

        @Override
        public void processCommand(ICommandSender sender, String[] args) {
            // every subcommand but status is GunfeelCommand's (26.2's GripCommand)
            if (args.length != 1 || !args[0].equals("status")) {
                if (args.length == 0 || args[0].equals("status") || !acup.gunfeel.GunfeelCommand.run(sender, args))
                    sender.addChatMessage(new ChatComponentText(getCommandUsage(sender)).setChatStyle(gray()));
                return;
            }
            sender.addChatMessage(new ChatComponentTranslation("ntmgunfeel.status.versions", Compat.modVersion(), Compat.hbmVersion()));
            boolean tested = Compat.inRange();
            sender.addChatMessage(new ChatComponentTranslation(tested ? "ntmgunfeel.status.tested" : "ntmgunfeel.status.untested", Compat.tested())
                    .setChatStyle(new ChatStyle().setColor(tested ? EnumChatFormatting.GREEN : EnumChatFormatting.RED)));
            Net.Hello srv = ServerSettingsClient.serverHello();
            if (srv != null) sender.addChatMessage(new ChatComponentTranslation("ntmgunfeel.status.server", srv.mod, srv.hbm));
            Map<String, String> skipped = Compat.skipped();
            sender.addChatMessage(new ChatComponentTranslation("ntmgunfeel.status.skipped", skipped.size()));
            for (Map.Entry<String, String> e : skipped.entrySet())
                sender.addChatMessage(new ChatComponentText("  " + e.getKey() + ": " + e.getValue()).setChatStyle(gray()));
            Map<Feature, String> off = Compat.off();
            sender.addChatMessage(new ChatComponentTranslation("ntmgunfeel.status.off", off.size()));
            for (Map.Entry<Feature, String> e : off.entrySet()) {
                IChatComponent line = new ChatComponentText("  ").appendSibling(new ChatComponentTranslation(e.getKey().langKey()))
                        .appendText(": " + e.getValue());
                sender.addChatMessage(line.setChatStyle(gray()));
            }
        }

        private static ChatStyle gray() {
            return new ChatStyle().setColor(EnumChatFormatting.GRAY);
        }
    }
}
