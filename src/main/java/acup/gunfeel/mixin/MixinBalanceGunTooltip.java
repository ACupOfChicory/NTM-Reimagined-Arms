package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import acup.gunfeel.BalanceGuns;
import net.minecraft.world.item.Item;

// Client: a gun's tooltip shows the damage with its loaded ammo after the balance's mult (BalanceGuns): the line
// "damage with ammo" is NTM's base damage times the ammo's damageMult, which is read once, there
@Mixin(value = ItemGunBaseNT.class, remap = false)
public abstract class MixinBalanceGunTooltip {
    @WrapOperation(method = "appendHoverText", at = @At(value = "FIELD", target = "Lcom/hbm/items/weapon/sedna/BulletConfig;damageMult:F", opcode = org.objectweb.asm.Opcodes.GETFIELD))
    private float gunfeel$mult(BulletConfig bullet, Operation<Float> original) {
        return original.call(bullet) * BalanceGuns.tooltipMult((Item) (Object) this, bullet);
    }
}
