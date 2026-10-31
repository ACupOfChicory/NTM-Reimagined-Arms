package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import com.hbm.client.render.weapon.WeaponPose;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

// port/continuum: WeaponPose.begin is package-private; GunExtent poses a gun at rest with it
@Mixin(value = WeaponPose.class, remap = false)
public interface WeaponPoseAccess {
    @Invoker("begin")
    void gunfeel$begin(ItemStack stack, boolean animated, int overlay, ItemDisplayContext context, LivingEntity owner, InteractionHand hand);
}
