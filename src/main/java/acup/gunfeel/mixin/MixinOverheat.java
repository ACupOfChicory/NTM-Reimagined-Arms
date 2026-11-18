package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.Receiver;
import com.hbm.items.weapon.sedna.factory.Lego;
import com.hbm.render.anim.AnimationEnums.GunAnimation;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import acup.gunfeel.heat.OverheatServer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

// The barrel's heat (acup.gunfeel.heat.Overheat) on NTM's standard fire: a shot adds its heat, a hot barrel wears faster
// and spreads more (added on top of the rest, after MixinLegoSpread as the shock's is), and the smoke after a shot hangs
// on longer at the muzzle. Additions to NTM's own steps, none replaced
@Mixin(value = Lego.class, remap = false, priority = 1100)
public abstract class MixinOverheat {
    @Inject(method = "doStandardFire", at = @At("RETURN"), require = 1)
    private static void gunfeel$heatShot(ItemStack stack, ItemGunBaseNT.LambdaContext ctx, GunAnimation anim, int receiver, boolean calcWear,
            CallbackInfo ci) {
        OverheatServer.shot(stack, ctx);
    }

    @WrapOperation(method = "doStandardFire", at = @At(value = "INVOKE",
            target = "Lcom/hbm/items/weapon/sedna/ItemGunBaseNT;setWear(Lnet/minecraft/world/item/ItemStack;IF)V"), require = 1)
    private static void gunfeel$heatWear(ItemStack stack, int index, float wear, Operation<Void> op,
            @Local(argsOnly = true) ItemGunBaseNT.LambdaContext ctx) {
        if (ctx != null && ctx.config() != null) wear = Math.min(OverheatServer.wear(stack, index, wear, ctx.entity()), ctx.config().getDurability(stack));
        op.call(stack, index, wear);
    }

    @ModifyReturnValue(method = "calcSpread", at = @At("RETURN"), require = 1)
    private static float gunfeel$heatSpread(float spread, ItemGunBaseNT.LambdaContext ctx, ItemStack stack, Receiver primary, BulletConfig config,
            boolean calcWear, int index, boolean aim) {
        if (ctx == null) return spread;
        return spread + OverheatServer.spread(stack, primary, ctx.entity());
    }

    // client: NTM's smoke after a shot (milliseconds it lasts), longer as the barrel is hot
    @ModifyVariable(method = "handleStandardSmoke", at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 1)
    private static int gunfeel$heatSmoke(int duration, LivingEntity entity, ItemStack stack) {
        return duration + acup.gunfeel.heat.OverheatSmoke.extraMillis(stack, entity);
    }
}
