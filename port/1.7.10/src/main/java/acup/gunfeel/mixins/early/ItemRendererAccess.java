package acup.gunfeel.mixins.early;

// port: new for 1.7.10 (26.2: MixinItemInHandRenderer's @Shadow fields). The item ItemRenderer draws in first person
// and its height, for InstantSwitch.take.

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.item.ItemStack;

@Mixin(ItemRenderer.class)
public interface ItemRendererAccess {
    @Accessor("itemToRender")
    ItemStack gunfeel$getItemToRender();

    @Accessor("itemToRender")
    void gunfeel$setItemToRender(ItemStack stack);

    @Accessor("equippedProgress")
    void gunfeel$setEquippedProgress(float v);

    @Accessor("prevEquippedProgress")
    void gunfeel$setPrevEquippedProgress(float v);

    @Accessor("equippedItemSlot")
    void gunfeel$setEquippedItemSlot(int slot);
}
