package acup.gunfeel.smoke.client;

import java.util.Locale;

import acup.gunfeel.AmmoLockKey;
import acup.gunfeel.compat.Feature;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Temporary, until the owner picks the looks to keep (then this goes, with the looks not picked): a key (F7 by
 * default) steps the smoke through the ways it can be drawn, now, a, b, c, a + b + c ({@link SmokeRender#look}), there
 * and then, and once pressed the look's name and the card's time on the smoke stay in the top left corner.
 */
public final class SmokeLooks {
    public static final KeyMapping KEY = new KeyMapping("key.ntmgunfeel.smoke_look", 296 /* GLFW_KEY_F7 */, AmmoLockKey.CATEGORY);
    // SmokeRender.look's bits per step, and the steps' names in the lang files
    private static final int[] LOOKS = {0, 1, 2, 4, 7};
    private static final String[] NAMES = {"now", "a", "b", "c", "abc"};
    // the card's time is taken over this many ticks (half a second)
    private static final int WINDOW = 10;

    private static int at;
    private static boolean shown;
    // the first window since the last press is not over yet
    private static boolean pending;
    private static int ticks;
    private static long nanosAt, framesAt;
    // the card's time on the smoke a frame, ms, over the last window; NaN: no smoke drawn in it
    private static double ms = Double.NaN;

    private SmokeLooks() {
    }

    /** Whether the card's time on the smoke is wanted (SmokeRender polls its timestamps only then). */
    static boolean measuring() {
        return shown;
    }

    static void register(IEventBus modBus) {
        modBus.addListener(RegisterKeyMappingsEvent.class, e -> {
            // the ammo key, the editor or the prone key register the category when one of them is on
            if (!Feature.AMMO_CHOICE.on() && !Feature.ARMS.on() && !Feature.PRONE.on()) e.registerCategory(AmmoLockKey.CATEGORY);
            e.register(KEY);
        });
        modBus.addListener(RegisterGuiLayersEvent.class,
                e -> e.registerAboveAll(Identifier.fromNamespaceAndPath("ntmgunfeel", "smoke_look"), SmokeLooks::layer));
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, e -> tick());
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, e -> {
            at = 0;
            shown = false;
            pending = false;
            SmokeRender.look = 0;
        });
    }

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        while (KEY.consumeClick()) {
            if (mc.gui.screen() != null || mc.player == null) continue;
            at = (at + 1) % LOOKS.length;
            SmokeRender.look = LOOKS[at];
            shown = true;
            // a new window, counted from a little later: the card's times come back some frames late
            ticks = -WINDOW / 2;
            ms = Double.NaN;
            pending = true;
        }
        if (!shown) return;
        if (++ticks == 0) {
            nanosAt = SmokeStats.cardNanos;
            framesAt = SmokeStats.cardFrames;
            return;
        }
        if (ticks < WINDOW) return;
        ticks = 0;
        pending = false;
        long frames = SmokeStats.cardFrames - framesAt;
        ms = frames > 0 ? (SmokeStats.cardNanos - nanosAt) / 1e6 / frames : Double.NaN;
        nanosAt = SmokeStats.cardNanos;
        framesAt = SmokeStats.cardFrames;
    }

    private static void layer(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (!shown) return;
        Component name = Component.translatable("hud.ntmgunfeel.smoke_look." + NAMES[at]);
        Component line = pending ? Component.translatable("hud.ntmgunfeel.smoke_look.wait", name)
                : Double.isNaN(ms) ? Component.translatable("hud.ntmgunfeel.smoke_look.none", name)
                : Component.translatable("hud.ntmgunfeel.smoke_look", name, String.format(Locale.ROOT, "%.2f", ms));
        g.text(mc.font, line, 4, 4, 0xFFFFFFFF, true);
    }
}
