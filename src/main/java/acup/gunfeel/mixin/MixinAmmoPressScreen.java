package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.hbm.client.gui.ScreenMachineAmmoPress;

import acup.gunfeel.gl54.PressScreenExtras;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;

// Client: the ammo press's recipe selector lists the layouts the press remembers (PressLayouts) after NTM's recipes, paged
// with the same arrows (PressScreenExtras draws them, answers the click and the tooltip). The screen's own list is untouched.
@Mixin(value = ScreenMachineAmmoPress.class, remap = false)
public abstract class MixinAmmoPressScreen {
    @Shadow
    private int index;
    @Shadow
    private int size;
    @Shadow
    private int selection;
    @Shadow
    private java.util.List<com.hbm.inventory.recipes.AmmoPressRecipe> recipes;
    @Shadow
    private net.minecraft.client.gui.components.EditBox search;

    @Unique
    private final PressScreenExtras gunfeel$extras = new PressScreenExtras();

    @SuppressWarnings("unchecked")
    @Unique
    private AbstractContainerScreen<com.hbm.inventory.container.MenuMachineAmmoPress> gunfeel$self() {
        return (AbstractContainerScreen<com.hbm.inventory.container.MenuMachineAmmoPress>) (Object) this;
    }

    // the page count counts the remembered layouts too
    @Inject(method = "search", at = @At("TAIL"))
    private void gunfeel$search(String term, CallbackInfo ci) {
        var press = (com.hbm.blockentity.machine.BlockEntityMachineAmmoPress) gunfeel$self().getMenu().blockEntity();
        gunfeel$extras.search(press, term, recipes.size());
        size = Math.max(0, (int) Math.ceil((recipes.size() + gunfeel$extras.shown() - 12) / 3.0));
    }

    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void gunfeel$refresh(GuiGraphicsExtractor g, int mx, int my, float pt, CallbackInfo ci) {
        var press = (com.hbm.blockentity.machine.BlockEntityMachineAmmoPress) gunfeel$self().getMenu().blockEntity();
        if (gunfeel$extras.changed(press)) {
            // the server sent the layouts again (one stored, moved, deleted): the list and the selection as it has them
            gunfeel$extras.search(press, search == null ? "" : search.getValue(), recipes.size());
            size = Math.max(0, (int) Math.ceil((recipes.size() + gunfeel$extras.shown() - 12) / 3.0));
            index = Math.min(index, size);
            selection = press.selectedRecipe;
        }
    }

    @SuppressWarnings("removal")
    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void gunfeel$click(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        var press = (com.hbm.blockentity.machine.BlockEntityMachineAmmoPress) gunfeel$self().getMenu().blockEntity();
        int next = gunfeel$extras.click(press, gunfeel$self().getGuiLeft(), gunfeel$self().getGuiTop(), (int) event.x(), (int) event.y(), index, recipes.size(), event.hasShiftDown(), selection);
        if (next != PressScreenExtras.NOT_HIT) {
            selection = next;
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "extractLabels", at = @At("TAIL"))
    private void gunfeel$labels(GuiGraphicsExtractor g, int mx, int my, CallbackInfo ci) {
        var press = (com.hbm.blockentity.machine.BlockEntityMachineAmmoPress) gunfeel$self().getMenu().blockEntity();
        gunfeel$extras.draw(g, press, index, recipes.size(), selection);
    }

    @SuppressWarnings("removal")
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void gunfeel$tooltip(GuiGraphicsExtractor g, int mx, int my, float pt, CallbackInfo ci) {
        gunfeel$extras.tooltip(g, gunfeel$self().getGuiLeft(), gunfeel$self().getGuiTop(), mx, my, index, recipes.size());
    }
}
