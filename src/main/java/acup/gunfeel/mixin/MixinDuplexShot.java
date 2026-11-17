package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.factory.Lego;
import com.hbm.render.anim.AnimationEnums.GunAnimation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import acup.gunfeel.ammo.Duplex;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

// The Duplex rounds (Duplex): NTM's standard fire makes their two bullets as two of a shot, each spread on its own; the
// second is set behind the first, off its heading by DuplexRule's angle, as it is added to the world (after every
// change made to it as it was made: an AI bot's lean, the click's aim)
@Mixin(value = Lego.class, remap = false)
public abstract class MixinDuplexShot {
    @Inject(method = "doStandardFire", at = @At("HEAD"), require = 1)
    private static void gunfeel$duplexStart(ItemStack stack, ItemGunBaseNT.LambdaContext ctx, GunAnimation anim, int receiver, boolean calcWear,
            CallbackInfo ci) {
        Duplex.shotStart();
    }

    @WrapOperation(method = "doStandardFire", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"),
            require = 1)
    private static boolean gunfeel$duplexRear(Level level, Entity e, Operation<Boolean> op) {
        Duplex.place(e);
        return op.call(level, e);
    }
}
