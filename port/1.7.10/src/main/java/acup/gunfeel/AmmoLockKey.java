package acup.gunfeel;

// port: copy of 26.2's AmmoLockKey.java (0762086) for Forge 1.7.10. The key is a KeyBinding (ClientRegistry, Y, the
// category "key.category.ntmgunfeel.main"); its events come from HudEvents. The tap/hold timing moved into KeyHold.
// 26.2 takes the left click in the open menu at the start of the client tick; 1.7.10 sees the click as an input event
// (InputEvent.MouseInputEvent/KeyInputEvent), before NTM's own handler of it. NTM 1.7.10 asks its gun keys through
// ClientProxy.getIsKeyPressed: MixinGunKeysRadial puts gunKey() there (26.2: Keybinds$GameplayKey.refresh).

import java.util.EnumSet;

import org.lwjgl.input.Keyboard;

import com.hbm.handler.HbmKeybinds.EnumKeybind;
import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;

import cpw.mods.fml.client.registry.ClientRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemStack;

// Client: the ammo key (Controls, "NTM Reimagined Arms"; Y by default). A tap locks or unlocks the held gun's
// ammo type; held, it opens the radial ammo menu (on a locked gun too: the lock moves to the chosen ammo); a left click takes the
// hovered ammo, letting go of the key just closes it.
public final class AmmoLockKey {
    public static final String CATEGORY = "key.category.ntmgunfeel.main";
    public static final KeyBinding KEY = new KeyBinding("key.ntmgunfeel.ammo_lock", Keyboard.KEY_Y, CATEGORY);

    private static final KeyHold HOLD = new KeyHold();
    // NTM's gun keys held through the menu: up for NTM until they are let go
    private static final EnumSet<EnumKeybind> HELD_THROUGH = EnumSet.noneOf(EnumKeybind.class);

    private AmmoLockKey() {
    }

    static void register() {
        ClientRegistry.registerKeyBinding(KEY);
    }

    // an input event (button or key, down or up), before NTM's: a press of the attack key in the open menu takes the
    // hovered ammo and goes no further (no swing, no digging; the fire key on the same button stays up, gunKey)
    static void input(int code, boolean down) {
        if (!RadialMenu.isOpen() || !down || code == 0) return;
        KeyBinding attack = Minecraft.getMinecraft().gameSettings.keyBindAttack;
        if (code != attack.getKeyCode()) return;
        while (attack.isPressed()) {
        }
        BulletConfig c = RadialMenu.hovered();
        if (c == null) return;
        RadialMenu.close();
        AmmoNet.send(new AmmoSelectPayload(c.id));
    }

    static void tick() {
        while (KEY.isPressed()) {
        }
        Minecraft mc = Minecraft.getMinecraft();
        ItemStack stack = mc.thePlayer == null ? null : mc.thePlayer.getHeldItem();
        boolean gun = stack != null && stack.getItem() instanceof ItemGunBaseNT;
        boolean down = KEY.getIsKeyPressed() && mc.currentScreen == null;

        if (RadialMenu.isOpen() && (!gun || stack.getItem() != RadialMenu.gun().getItem() || mc.currentScreen != null)) RadialMenu.close();

        switch (HOLD.tick(down, gun, RadialMenu.isOpen(), GunClock.millis())) {
            case OPEN:
                RadialMenu.open(stack);
                break;
            case CLOSE:
                RadialMenu.close(); // let go without a click: nothing changes
                break;
            case TAP:
                AmmoNet.send(new AmmoLockPayload());
                break;
            default:
                break;
        }
    }

    // MixinGunKeysRadial: NTM asks whether a gun key (fire, aim, tertiary/inspect, reload) is down. While the menu is
    // open they count as let go (NTM sends the release), and one held through the menu stays up until it is let go.
    public static boolean gunKey(EnumKeybind key, boolean down) {
        if (key != EnumKeybind.GUN_PRIMARY && key != EnumKeybind.GUN_SECONDARY && key != EnumKeybind.GUN_TERTIARY && key != EnumKeybind.RELOAD)
            return down;
        if (RadialMenu.isOpen()) {
            if (down) HELD_THROUGH.add(key);
            return false;
        }
        if (!HELD_THROUGH.contains(key)) return down;
        if (down) return false;
        HELD_THROUGH.remove(key);
        return false;
    }
}
