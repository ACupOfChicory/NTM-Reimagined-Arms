package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.GunConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.factory.Lego;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import acup.gunfeel.ammo.NuclearBmg;
import net.minecraft.world.item.ItemStack;

// The nuclear .50 (NuclearBmg): NTM's wear a shot is the round's, the same on every gun; this round's is a share of the
// gun's own durability instead (the AMAT worn out in 3 shots, the Subtlety in 6, the Penance in 10). Its shot gets a
// quarter of NTM's wear spread (NuclearRule.WEAR_SPREAD; the rest of the spread as it is): the AMAT's third shot is not
// thrown metres off
@Mixin(value = Lego.class, remap = false)
public abstract class MixinNuclearWear {
    @WrapOperation(method = "doStandardFire", at = @At(value = "INVOKE",
            target = "Lcom/hbm/items/weapon/sedna/ItemGunBaseNT;setWear(Lnet/minecraft/world/item/ItemStack;IF)V"), require = 1)
    private static void gunfeel$nuclearWear(ItemStack stack, int index, float wear, Operation<Void> op, @Local BulletConfig config,
            @Local(argsOnly = true) ItemGunBaseNT.LambdaContext ctx) {
        if (NuclearBmg.is(config) && ctx != null && ctx.config() != null) wear = NuclearBmg.wearAfter(stack, ctx.config(), index);
        op.call(stack, index, wear);
    }

    @WrapOperation(method = "calcSpread", at = @At(value = "INVOKE",
            target = "Lcom/hbm/items/weapon/sedna/factory/Lego;getStandardWearSpread(Lnet/minecraft/world/item/ItemStack;Lcom/hbm/items/weapon/sedna/GunConfig;I)F"),
            require = 1)
    private static float gunfeel$nuclearWearSpread(ItemStack stack, GunConfig gun, int index, Operation<Float> op,
            @Local(argsOnly = true) BulletConfig config) {
        float s = op.call(stack, gun, index);
        return NuclearBmg.is(config) ? s * acup.gunfeel.ammo.NuclearRule.WEAR_SPREAD : s;
    }
}
