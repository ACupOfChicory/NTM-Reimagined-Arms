package acup.gunfeel.mixin;

import java.util.function.BiConsumer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import acup.gunfeel.ProneFire;
import net.minecraft.world.item.ItemStack;

// Server: a gun's own key action that fires by itself (not through Lego.clickReceiver) is skipped while crawling; the
// key's state is still kept, so its release later finds it (proneCrawlNoFire, see ProneFire)
@Mixin(value = ItemGunBaseNT.class, remap = false)
public abstract class MixinProneFireKeys {
    @WrapOperation(method = "handleKeybind(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/Container;Lnet/minecraft/world/item/ItemStack;Lcom/hbm/handler/HbmKeybinds$EnumKeybind;Z)V",
            at = @At(value = "INVOKE", target = "Ljava/util/function/BiConsumer;accept(Ljava/lang/Object;Ljava/lang/Object;)V"))
    private void gunfeel$crawl(BiConsumer<Object, Object> action, Object stack, Object ctx, Operation<Void> original) {
        if (stack instanceof ItemStack s && ctx instanceof ItemGunBaseNT.LambdaContext c && ProneFire.blocksAction(action, s, c)) return;
        original.call(action, stack, ctx);
    }
}
