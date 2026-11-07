package acup.gunfeel.gl54;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.registration.NeoForgeRegistrar;
import com.hbm.registration.RegistryHandle;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

// The mod's items of its own: the GL-54 grenade launcher and its 20 mm magazines, and the data components of them. They are
// registered the way NTM registers its own (NeoForgeRegistrar), in the mod's namespace, in the mod's constructor.
public final class Gl54Items {
    public static final String NAMESPACE = "ntmgunfeel";
    public static RegistryHandle<ItemGunBaseNT> GUN;
    // the magazine with its grenades (stack 1) and the empty one (stack 16): the ammunition of the GL-54
    public static RegistryHandle<Item> MAG, MAG_EMPTY;
    // the grenades, one item per kind (stack 64), by the kind's key
    public static final java.util.Map<String, RegistryHandle<Item>> GRENADES = new java.util.LinkedHashMap<>();
    // the grenades of a magazine; on the gun what it holds (without the swap animation: a shot does not draw it again)
    public static RegistryHandle<DataComponentType<MagContents>> MAG_CONTENTS;
    public static RegistryHandle<DataComponentType<GunMag>> GUN_MAG;
    public static RegistryHandle<net.minecraft.world.inventory.MenuType<MagMenu>> MAG_MENU;
    // the horizontal range the rangefinder last measured, blocks (on the gun; without the swap animation)
    public static RegistryHandle<DataComponentType<Float>> RANGE;
    // the burst's shift from it, blocks (-3..3)
    public static RegistryHandle<DataComponentType<Integer>> OFFSET;
    // the point the rangefinder last found, in the world (the range is its distance, kept for the digits where this is missing)
    public static RegistryHandle<DataComponentType<net.minecraft.world.phys.Vec3>> TARGET;

    private Gl54Items() {
    }

    public static void register(IEventBus modBus) {
        NeoForgeRegistrar.subscribe(NAMESPACE, modBus);
        MAG_CONTENTS = NeoForgeRegistrar.registerDataComponent(NAMESPACE + ":gl54_mag",
                DataComponentType.<MagContents>builder().persistent(MagContents.CODEC).networkSynchronized(MagContents.STREAM_CODEC).build());
        GUN_MAG = NeoForgeRegistrar.registerDataComponent(NAMESPACE + ":gl54_gun",
                DataComponentType.<GunMag>builder().persistent(GunMag.CODEC).networkSynchronized(GunMag.STREAM_CODEC).ignoreSwapAnimation().build());
        RANGE = NeoForgeRegistrar.registerDataComponent(NAMESPACE + ":gl54_range",
                DataComponentType.<Float>builder().persistent(com.mojang.serialization.Codec.FLOAT).networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.FLOAT).ignoreSwapAnimation().build());
        OFFSET = NeoForgeRegistrar.registerDataComponent(NAMESPACE + ":gl54_offset",
                DataComponentType.<Integer>builder().persistent(com.mojang.serialization.Codec.INT).networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.VAR_INT).ignoreSwapAnimation().build());
        TARGET = NeoForgeRegistrar.registerDataComponent(NAMESPACE + ":gl54_target",
                DataComponentType.<net.minecraft.world.phys.Vec3>builder().persistent(net.minecraft.world.phys.Vec3.CODEC)
                        .networkSynchronized(net.minecraft.network.codec.StreamCodec.composite(
                                net.minecraft.network.codec.ByteBufCodecs.DOUBLE, v -> v.x,
                                net.minecraft.network.codec.ByteBufCodecs.DOUBLE, v -> v.y,
                                net.minecraft.network.codec.ByteBufCodecs.DOUBLE, v -> v.z,
                                net.minecraft.world.phys.Vec3::new))
                        .ignoreSwapAnimation().build());
        MAG = NeoForgeRegistrar.registerItem(NAMESPACE + ":gl54_mag", props -> new MagItem(props, false), () -> new Item.Properties().stacksTo(4));
        MAG_EMPTY = NeoForgeRegistrar.registerItem(NAMESPACE + ":gl54_mag_empty", props -> new MagItem(props, true), () -> new Item.Properties().stacksTo(4));
        MAG_MENU = NeoForgeRegistrar.registerMenu(NAMESPACE + ":gl54_mag_window", MagMenu::new);
        for (GrenadeKind k : GrenadeKind.values()) {
            GRENADES.put(k.key, NeoForgeRegistrar.registerItem(NAMESPACE + ":gl54_grenade_" + k.key,
                    props -> new GrenadeItem(props, k.key), () -> new Item.Properties().stacksTo(64)));
        }
        GUN = NeoForgeRegistrar.registerItem(NAMESPACE + ":gun_gl54",
                props -> new ItemGunBaseNT(ItemGunBaseNT.WeaponQuality.A_SIDE, props, Gl54Gun.config()), Item.Properties::new);
        // the gun and the magazines in NTM's weapons tab: the empty one, a full one of each kind and, for trying, a mixed one
        ResourceKey<CreativeModeTab> weapons = ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.fromNamespaceAndPath("hbm", "weapons"));
        modBus.addListener(BuildCreativeModeTabContentsEvent.class, e -> {
            if (!e.getTabKey().equals(weapons)) return;
            e.accept(GUN.get());
            e.accept(new ItemStack(MAG_EMPTY.get()));
            for (var g : GRENADES.values()) e.accept(new ItemStack(g.get()));
            for (GrenadeKind k : GrenadeKind.values()) e.accept(magazine(MagContents.filled(k.key, MagContents.CAPACITY)));
            e.accept(magazine(new MagContents(GrenadeKind.SAMPLE_MIX)));
        });
    }

    // a magazine with those grenades (an empty list gives the empty magazine)
    public static ItemStack magazine(MagContents c) {
        if (c.rounds().isEmpty()) return new ItemStack(MAG_EMPTY.get());
        ItemStack s = new ItemStack(MAG.get());
        s.set(MAG_CONTENTS.get(), c);
        return s;
    }

}
