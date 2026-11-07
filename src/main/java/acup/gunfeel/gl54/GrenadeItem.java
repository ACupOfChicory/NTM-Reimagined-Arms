package acup.gunfeel.gl54;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

// A 20 mm grenade of one kind (stack 64). It cannot be fired on its own: only a magazine goes into the GL-54. Grenades are
// loaded into a magazine in the ammo press (PressLoading), in any order.
public final class GrenadeItem extends Item {
    public final String kind;

    public GrenadeItem(Properties props, String kind) {
        super(props);
        this.kind = kind;
    }

    @Override
    public Component getName(ItemStack stack) {
        return Component.translatable("item.ntmgunfeel.gl54_grenade", MagItem.kindName(kind));
    }
}
