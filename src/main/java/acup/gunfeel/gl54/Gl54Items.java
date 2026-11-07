package acup.gunfeel.gl54;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.registration.NeoForgeRegistrar;
import com.hbm.registration.RegistryHandle;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

// The mod's first items of its own: the GL-54 grenade launcher and, for trying it, a temporary grenade. They are
// registered the way NTM registers its own (NeoForgeRegistrar), in the mod's namespace, in the mod's constructor.
public final class Gl54Items {
    public static final String NAMESPACE = "ntmgunfeel";
    public static RegistryHandle<ItemGunBaseNT> GUN;
    // TEMPORARY: the grenade the magazine takes until the 20 mm magazine (Gl54Ammo); in no creative tab
    public static RegistryHandle<Item> GRENADE_TEST;

    private Gl54Items() {
    }

    public static void register(IEventBus modBus) {
        NeoForgeRegistrar.subscribe(NAMESPACE, modBus);
        GRENADE_TEST = NeoForgeRegistrar.registerItem(NAMESPACE + ":gl54_grenade_test", Item::new, Item.Properties::new);
        GUN = NeoForgeRegistrar.registerItem(NAMESPACE + ":gun_gl54",
                props -> new ItemGunBaseNT(ItemGunBaseNT.WeaponQuality.A_SIDE, props, Gl54Gun.config()), Item.Properties::new);
        // the gun in NTM's weapons tab
        ResourceKey<CreativeModeTab> weapons = ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.fromNamespaceAndPath("hbm", "weapons"));
        modBus.addListener(BuildCreativeModeTabContentsEvent.class, e -> {
            if (e.getTabKey().equals(weapons)) e.accept(GUN.get());
        });
    }
}
