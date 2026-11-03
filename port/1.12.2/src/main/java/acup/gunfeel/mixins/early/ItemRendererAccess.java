package acup.gunfeel.mixins.early;

// port: new for 1.7.10 (26.2: MixinItemInHandRenderer's @Shadow fields). The item ItemRenderer draws in first person
// and its height, for InstantSwitch.take. port: 1.12.2 keeps the main hand's item and height (itemStackMainHand,
// equippedProgressMainHand) and no slot: a new item in the same hand is told by the stack.

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.item.ItemStack;

@Mixin(ItemRenderer.class)
public interface ItemRendererAccess {
    @Accessor("itemStackMainHand")
    ItemStack gunfeel$getItemToRender();

    @Accessor("itemStackMainHand")
    void gunfeel$setItemToRender(ItemStack stack);

    @Accessor("equippedProgressMainHand")
    void gunfeel$setEquippedProgress(float v);

    @Accessor("prevEquippedProgressMainHand")
    void gunfeel$setPrevEquippedProgress(float v);
}
