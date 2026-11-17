package acup.gunfeel.ammo;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** The mod's rounds in NTM's ammo bags (MixinAmmoBagOurs): those of the tag, a plain stack (no components changed) */
public final class AmmoBagOurs {
    public static final TagKey<Item> TAG = TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("ntmgunfeel", "ammo_bag"));

    private AmmoBagOurs() {
    }

    public static boolean takes(ItemStack stack) {
        return !stack.isEmpty() && stack.is(TAG) && stack.getComponentsPatch().isEmpty();
    }
}
