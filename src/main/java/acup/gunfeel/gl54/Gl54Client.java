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
        modBus.addListener(RegisterColorHandlersEvent.ItemTintSources.class, e -> {
            e.register(Identifier.fromNamespaceAndPath("ntmgunfeel", "mag_tint"), MagTint.MAP_CODEC);
            e.register(Identifier.fromNamespaceAndPath("ntmgunfeel", "grenade_tint"), GrenadeTint.MAP_CODEC);
        });
        modBus.addListener(AddClientReloadListenersEvent.class, e -> {
            e.addListener(ID, (ResourceManagerReloadListener) manager -> setup());
            e.addDependency(ClientRegistry.GUN_CLIENT_SETUP_ID, ID);
        });
    }

    static void setup() {
        Gl54Anim.reset();
        ItemRenderWeaponBase.register(Gl54Items.GUN::get, new ItemRenderGl54());
        Gl54Items.GUN.get().getConfig(null, 0).hud(LegoClient.HUD_COMPONENT_DURABILITY, LegoClient.HUD_COMPONENT_AMMO);
    }
}
