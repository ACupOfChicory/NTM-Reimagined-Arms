package acup.gunfeel;

// port: new for 1.7.10 (26.2: AmmoLockKey.register's listeners and GunFeel's frame call of InstantSwitch). The client
// events of the HUD part: the ammo key's tick and clicks, the radial menu's drawing, the instant switch's frame.

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;

public final class HudEvents {
    private HudEvents() {
    }

    // GunFeelClient.init
    static void register() {
        AmmoLockKey.register();
        HudEvents e = new HudEvents();
        FMLCommonHandler.instance().bus().register(e);
        MinecraftForge.EVENT_BUS.register(e);
    }

    // 26.2: ClientTickEvent.Post
    @SubscribeEvent
    public void clientTick(TickEvent.ClientTickEvent e) {
        if (e.phase == TickEvent.Phase.END) AmmoLockKey.tick();
    }

    // before NTM's own handlers of the same events (HbmKeybinds, priority LOW)
    @SubscribeEvent(priority = EventPriority.HIGH)
    public void mouse(InputEvent.MouseInputEvent e) {
        int button = Mouse.getEventButton();
        if (button >= 0) AmmoLockKey.input(button - 100, Mouse.getEventButtonState());
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void key(InputEvent.KeyInputEvent e) {
        AmmoLockKey.input(Keyboard.getEventKey(), Keyboard.getEventKeyState());
    }

    @SubscribeEvent
    public void renderTick(TickEvent.RenderTickEvent e) {
        if (e.phase == TickEvent.Phase.START) InstantSwitch.frame(Minecraft.getMinecraft().thePlayer);
    }

    // 26.2: RenderGuiEvent.Post, over the rest of the HUD
    @SubscribeEvent
    public void overlay(RenderGameOverlayEvent.Post e) {
        if (e.type == RenderGameOverlayEvent.ElementType.ALL) RadialMenu.render(e.resolution);
    }
}
