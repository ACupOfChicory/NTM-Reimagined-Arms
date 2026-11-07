package acup.gunfeel.gl54;

import com.hbm.client.ClientRegistry;
import com.hbm.items.weapon.sedna.factory.LegoClient;
import com.hbm.render.item.weapon.sedna.ItemRenderWeaponBase;

import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;

// The GL-54's client side: its renderer and HUD parts are put in after every resource reload, as NTM does it for its own
// guns (ClientRegistry.setupGunClient), because the model is read again then.
public final class Gl54Client {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("ntmgunfeel", "gl54_setup");

    private Gl54Client() {
    }

    public static void init(IEventBus modBus) {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent.class, Gl54LaserWorld::submit);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.entity.player.ItemTooltipEvent.class, Gl54Client::tooltip);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.InputEvent.MouseScrollingEvent.class, Gl54Client::wheel);
        modBus.addListener(net.neoforged.neoforge.client.event.RegisterMenuScreensEvent.class, e -> e.register(Gl54Items.MAG_MENU.get(), MagScreen::new));
        modBus.addListener(RegisterColorHandlersEvent.ItemTintSources.class, e -> {
            e.register(Identifier.fromNamespaceAndPath("ntmgunfeel", "mag_tint"), MagTint.MAP_CODEC);
            e.register(Identifier.fromNamespaceAndPath("ntmgunfeel", "grenade_tint"), GrenadeTint.MAP_CODEC);
        });
        modBus.addListener(AddClientReloadListenersEvent.class, e -> {
            e.addListener(ID, (ResourceManagerReloadListener) manager -> setup());
            e.addDependency(ClientRegistry.GUN_CLIENT_SETUP_ID, ID);
        });
    }

    // the gun's tooltip tells how to measure, with the key as the player has it: with the right button for the aim (the mod's
    // default) it is the key NTM binds to its aim, the middle button; with the aim on NTM's own key it is NTM's second one
    static void tooltip(net.neoforged.neoforge.event.entity.player.ItemTooltipEvent e) {
        if (Gl54Items.GUN == null || e.getItemStack().getItem() != Gl54Items.GUN.get()) return;
        boolean swapped = acup.gunfeel.Config.enabled && acup.gunfeel.Config.aimOnRightButton;
        var key = swapped ? com.hbm.client.Keybinds.GUN_TERTIARY : com.hbm.client.Keybinds.GUN_SECONDARY;
        e.getToolTip().add(net.minecraft.network.chat.Component.translatable("ntmgunfeel.gl54.range_tooltip", key.getTranslatedKeyMessage())
                .withStyle(net.minecraft.ChatFormatting.GRAY));
        e.getToolTip().add(net.minecraft.network.chat.Component.translatable("ntmgunfeel.gl54.offset_tooltip", wheelName())
                .withStyle(net.minecraft.ChatFormatting.GRAY));
    }

    // the wheel's name: the game has no key for it, so the word, as the player's language has it
    static net.minecraft.network.chat.Component wheelName() {
        return net.minecraft.network.chat.Component.translatable("ntmgunfeel.gl54.wheel");
    }

    // The mouse wheel while the held GL-54 is aimed shifts the burst instead of the hotbar: the scroll is taken (cancelled) and the
    // change goes to the server. Outside the sight, or with a screen open, it is the game's.
    static void wheel(net.neoforged.neoforge.client.event.InputEvent.MouseScrollingEvent e) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (Gl54Items.GUN == null || mc.player == null || mc.gui.screen() != null) return;
        var stack = mc.player.getMainHandItem();
        if (stack.getItem() != Gl54Items.GUN.get() || !com.hbm.items.weapon.sedna.ItemGunBaseNT.getIsAiming(stack)) return;
        double dy = e.getScrollDeltaY();
        if (dy == 0.0) return;
        e.setCanceled(true);
        try {
            net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(new Gl54OffsetPayload(dy > 0 ? 1 : -1));
        } catch (RuntimeException ex) {
            // not connected to a server
        }
    }

    static void setup() {
        Gl54Anim.reset();
        ItemRenderWeaponBase.register(Gl54Items.GUN::get, new ItemRenderGl54());
        Gl54Items.GUN.get().getConfig(null, 0).hud(LegoClient.HUD_COMPONENT_DURABILITY, LegoClient.HUD_COMPONENT_AMMO);
    }
}
