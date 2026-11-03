package acup.gunfeel;

// port: new for 1.7.10 (26.2: AmmoLockKey.register's listeners and GunFeel's frame call of InstantSwitch). The client
// events of the HUD part: the ammo key's tick and clicks, the radial menu's drawing, the instant switch's frame.

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
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
        MinecraftForge.EVENT_BUS.register(e);
    }

    // 26.2: ClientTickEvent.Post
    @SubscribeEvent
    public void clientTick(TickEvent.ClientTickEvent e) {
        if (e.phase == TickEvent.Phase.END && acup.gunfeel.compat.Feature.AMMO_CHOICE.on()) AmmoLockKey.tick();
    }

    // before NTM's own handlers of the same events (HbmKeybinds, priority LOW)
    @SubscribeEvent(priority = EventPriority.HIGH)
    public void mouse(InputEvent.MouseInputEvent e) {
        int button = Mouse.getEventButton();
        if (button >= 0 && acup.gunfeel.compat.Feature.AMMO_CHOICE.on()) AmmoLockKey.input(button - 100, Mouse.getEventButtonState());
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void key(InputEvent.KeyInputEvent e) {
        if (acup.gunfeel.compat.Feature.AMMO_CHOICE.on()) AmmoLockKey.input(Keyboard.getEventKey(), Keyboard.getEventKeyState());
    }

    @SubscribeEvent
    public void renderTick(TickEvent.RenderTickEvent e) {
        if (e.phase == TickEvent.Phase.START && acup.gunfeel.compat.Feature.INSTANT_SWITCH.on()) InstantSwitch.frame(Minecraft.getMinecraft().player);
    }

    // 26.2: RenderGuiEvent.Post, over the rest of the HUD
    @SubscribeEvent
    public void overlay(RenderGameOverlayEvent.Post e) {
        if (e.getType() == RenderGameOverlayEvent.ElementType.ALL && acup.gunfeel.compat.Feature.AMMO_CHOICE.on()) RadialMenu.render(e.getResolution());
    }
}
