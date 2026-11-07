package acup.gunfeel.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.hbm.blockentity.machine.BlockEntityMachineAmmoPress;

import acup.gunfeel.gl54.GrenadeItem;
import acup.gunfeel.gl54.Gl54Items;
import acup.gunfeel.gl54.PressLayouts;
import acup.gunfeel.gl54.PressLoading;
import acup.gunfeel.gl54.PressMemory;
import acup.gunfeel.gl54.PressSync;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

// The GL-54's magazines in NTM's ammo press (PressLoading): with no recipe chosen the press loads our grenades, in any order,
// into an empty magazine, and remembers a layout that is not a preset (PressLayouts, saved with the press and gone with it);
// the recipe selector lists the remembered ones after NTM's recipes (MixinAmmoPressScreen), and selecting one works like a
// preset. With one of NTM's recipes chosen nothing here acts.
@Mixin(value = BlockEntityMachineAmmoPress.class, remap = false)
public abstract class MixinAmmoPress implements PressMemory {
    private static final String SAVED = "gunfeel_layouts";

    @Shadow
    public int selectedRecipe;

    @Shadow
    public abstract void hbm$syncSet$BlockEntityMachineAmmoPress$playAnimation(int next);

    @Shadow
    public abstract void hbm$syncSet$BlockEntityMachineAmmoPress$selectedRecipe(int next);

    @Unique
    private List<List<String>> gunfeel$layouts = List.of();

    @Override
    public List<List<String>> gunfeel$layouts() {
        return gunfeel$layouts;
    }

    @Override
    public void gunfeel$layouts(List<List<String>> layouts) {
        gunfeel$layouts = layouts;
    }

    // the stored layout the selection means, null when it means none
    @Unique
    private List<String> gunfeel$selected() {
        int k = PressLayouts.stored(selectedRecipe, PressSync.recipes(), gunfeel$layouts.size());
        return k < 0 ? null : gunfeel$layouts.get(k);
    }

    // the layouts replaced: a selected one is followed to its new place (or deselected when gone), saved, sent to the windows
    @Unique
    private void gunfeel$store(List<List<String>> next) {
        if (next.equals(gunfeel$layouts)) return;
        List<String> sel = gunfeel$selected();
        gunfeel$layouts = next;
        if (sel != null) {
            int at = PressLayouts.indexOf(next, sel);
            hbm$syncSet$BlockEntityMachineAmmoPress$selectedRecipe(at < 0 ? -1 : PressSync.recipes() + at);
        }
        ((net.minecraft.world.level.block.entity.BlockEntity) (Object) this).setChanged();
        PressSync.broadcast((BlockEntityMachineAmmoPress) (Object) this);
    }

    @Inject(method = "saveAdditional", at = @At("TAIL"))
    private void gunfeel$save(ValueOutput output, CallbackInfo ci) {
        if (!gunfeel$layouts.isEmpty()) output.store(SAVED, PressLayouts.CODEC, gunfeel$layouts);
    }

    @Inject(method = "loadAdditional", at = @At("TAIL"))
    private void gunfeel$load(ValueInput input, CallbackInfo ci) {
        gunfeel$layouts = List.copyOf(input.read(SAVED, PressLayouts.CODEC).orElse(List.of()));
    }

    @Inject(method = "performRecipe", at = @At("HEAD"), cancellable = true)
    private void gunfeel$perform(CallbackInfo ci) {
        List<String> layout = gunfeel$selected();
        if (layout == null && !PressLoading.ours(selectedRecipe)) return;
        List<String> made = PressLoading.perform(selectedRecipe, layout, (Container) (Object) this);
        if (made != null) {
            hbm$syncSet$BlockEntityMachineAmmoPress$playAnimation(40);
            gunfeel$store(PressLayouts.remember(gunfeel$layouts, made, PressSync.presets()));
            ((net.minecraft.world.level.block.entity.BlockEntity) (Object) this).setChanged();
        }
        ci.cancel();
    }

    // with no recipe NTM takes nothing; ours takes our grenades in slots 0..7 and an empty magazine in slot 8, and with a
    // stored layout selected each slot takes only the grenade of its place in the layout
    @Inject(method = "canPlaceItem", at = @At("HEAD"), cancellable = true)
    private void gunfeel$place(int slot, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        List<String> layout = gunfeel$selected();
        boolean empty = stack.getItem() == Gl54Items.MAG_EMPTY.get();
        if (layout != null) {
            cir.setReturnValue(PressLoading.acceptsLayout(layout, slot, stack.getItem() instanceof GrenadeItem g ? g.kind : "", empty));
        } else if (PressLoading.accepts(selectedRecipe, slot, stack.getItem() instanceof GrenadeItem, empty)) {
            cir.setReturnValue(true);
        }
    }

    // a window opening gets the press's layouts
    @Inject(method = "createMenu", at = @At("RETURN"))
    private void gunfeel$open(int id, net.minecraft.world.entity.player.Inventory inv, Player player, CallbackInfoReturnable<net.minecraft.world.inventory.AbstractContainerMenu> cir) {
        if (player instanceof ServerPlayer sp) PressSync.send((BlockEntityMachineAmmoPress) (Object) this, sp);
    }

    // the screen's controls: "gunfeelForget" deletes a stored layout (Shift+click); a selection past the stored ones is ignored
    @Inject(method = "receiveControl", at = @At("HEAD"), cancellable = true)
    private void gunfeel$control(CompoundTag data, CallbackInfo ci) {
        if (data.contains("gunfeelForget")) {
            gunfeel$store(PressLayouts.forget(gunfeel$layouts, data.getIntOr("gunfeelForget", -1)));
            ci.cancel();
        } else if (data.getIntOr("selection", -1) >= PressSync.recipes() + gunfeel$layouts.size()) {
            ci.cancel();
        }
    }
}
