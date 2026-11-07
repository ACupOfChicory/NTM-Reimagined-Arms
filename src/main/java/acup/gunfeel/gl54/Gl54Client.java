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
    }

    static void setup() {
        Gl54Anim.reset();
        ItemRenderWeaponBase.register(Gl54Items.GUN::get, new ItemRenderGl54());
        Gl54Items.GUN.get().getConfig(null, 0).hud(LegoClient.HUD_COMPONENT_DURABILITY, LegoClient.HUD_COMPONENT_AMMO);
    }
}
