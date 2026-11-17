package acup.gunfeel.ammo;

import java.util.List;
import java.util.function.Predicate;

import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.Receiver;
import com.hbm.items.weapon.sedna.mags.IMagazine;
import com.hbm.items.weapon.sedna.mags.MagazineBelt;
import com.hbm.items.weapon.sedna.mags.MagazineSingleTypeBase;
import com.hbm.registration.NeoForgeRegistrar;
import com.hbm.registration.RegistryHandle;

import acup.gunfeel.ModRegistrar;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

/**
 * What the mod's rounds for NTM's guns share (the shock shell's way, ShockAmmo): an item of the mod's own in NTM's
 * weapons tab, and its config put into the magazines of the guns that take a given NTM round, at the common setup (on
 * both sides in the same order, so the configs' ids agree).
 */
public final class NewAmmo {
    private NewAmmo() {
    }

    /** an item of the mod's, {@code ntmgunfeel:<name>}, a stack of 64, in NTM's weapons tab */
    static RegistryHandle<Item> item(IEventBus modBus, String name) {
        ModRegistrar.subscribe(modBus);
        RegistryHandle<Item> h = NeoForgeRegistrar.registerItem(ModRegistrar.NAMESPACE + ":" + name, Item::new, () -> new Item.Properties().stacksTo(64));
        ResourceKey<CreativeModeTab> weapons = ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.fromNamespaceAndPath("hbm", "weapons"));
        modBus.addListener(BuildCreativeModeTabContentsEvent.class, e -> {
            if (e.getTabKey().equals(weapons)) e.accept(new ItemStack(h.get()));
        });
        return h;
    }

    /**
     * {@code config} into every magazine (of a single type) of the guns {@code gun} picks whose ammo {@code takes}
     * accepts; how many magazines
     */
    static int addTo(BulletConfig config, Predicate<ItemGunBaseNT> gun, Predicate<List<BulletConfig>> takes) {
        int n = 0;
        for (Item item : BuiltInRegistries.ITEM) {
            if (!(item instanceof ItemGunBaseNT g) || !gun.test(g)) continue;
            // no stack: the gun's own configs (stacks cannot be made yet at the common setup)
            ItemStack stack = null;
            for (int c = 0; c < g.getConfigCount(); c++) {
                for (Receiver rec : g.getConfig(stack, c).getReceivers(stack)) {
                    IMagazine<?> mag = rec.getMagazine(stack);
                    // a single type's magazine or a belt (the miniguns'): both keep their list of rounds
                    List<BulletConfig> acc = mag instanceof MagazineSingleTypeBase s ? s.acceptedBullets
                            : mag instanceof MagazineBelt b ? b.acceptedBullets : null;
                    if (acc != null && takes.test(acc) && !acc.contains(config)) {
                        acc.add(config);
                        n++;
                    }
                }
            }
        }
        return n;
    }

    /** the gun's id path ("gun_amat"), or "" */
    static String gunId(Item item) {
        Identifier id = BuiltInRegistries.ITEM.getKey(item);
        return id == null ? "" : id.getPath();
    }
}
