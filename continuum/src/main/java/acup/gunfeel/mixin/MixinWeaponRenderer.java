package acup.gunfeel.mixin;

import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.client.render.weapon.WeaponRenderer;
import com.mojang.blaze3d.vertex.PoseStack;

import acup.gunfeel.Config;
import acup.gunfeel.GunExtent;
import acup.gunfeel.GunFeel;
import acup.gunfeel.ModelRecoil;
import acup.gunfeel.WeaponSway;
import dev.golem.core.render.SubmitSink;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

// port/continuum: the recoil and sway part of MixinItemRenderWeaponBase on NTM Continuum's WeaponRenderer, whose
// setupTransformsAndSubmit is NTM NEXT's: turn lag, walk sway, the gun's own offset, then renderFirstPerson.
@Mixin(value = WeaponRenderer.class, remap = false)
public abstract class MixinWeaponRenderer {
    private static final String RENDER = "Lcom/hbm/client/render/weapon/WeaponRenderer;renderFirstPerson(Lnet/minecraft/world/item/ItemStack;"
            + "Lcom/mojang/blaze3d/vertex/PoseStack;Ldev/golem/core/render/SubmitSink;IILnet/minecraft/world/InteractionHand;)V";

    @Inject(method = "setupTransformsAndSubmit", at = @At("HEAD"))
    private void gunfeel$begin(ItemStack stack, LocalPlayer player, PoseStack pose, SubmitSink collector, int light, int overlay,
            boolean otherArm, CallbackInfo ci) {
        // the gun drawn may not be the one in hand (switching slots): its own settings, not the defaults
        Config.select(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        GunFeel.current = (WeaponRenderer) (Object) this;
        GunFeel.currentAkimbo = otherArm;
        GunFeel.lastDrawn = System.nanoTime();
        if (!Config.enabled) return;
        GunFeel.checkShot(stack);
        WeaponSway.apply(pose, player);
    }

    // NTM's two turn-lag rotations are replaced by WeaponSway, not added to it
    @ModifyArg(method = "setupTransformsAndSubmit", at = @At(value = "INVOKE",
            target = "Lcom/mojang/math/Axis;rotationDegrees(F)Lorg/joml/Quaternionf;", ordinal = 0), index = 0)
    private float gunfeel$replacePitchLag(float original) {
        return Config.enabled ? 0F : original;
    }

    @ModifyArg(method = "setupTransformsAndSubmit", at = @At(value = "INVOKE",
            target = "Lcom/mojang/math/Axis;rotationDegrees(F)Lorg/joml/Quaternionf;", ordinal = 1), index = 0)
    private float gunfeel$replaceYawLag(float original) {
        return Config.enabled ? 0F : original;
    }

    // spring kick in the gun's frame, after NTM placed the gun and before it draws the model
    @Inject(method = "setupTransformsAndSubmit", at = @At(value = "INVOKE", target = RENDER))
    private void gunfeel$kick(ItemStack stack, LocalPlayer player, PoseStack pose, SubmitSink collector, int light, int overlay,
            boolean otherArm, CallbackInfo ci) {
        if (!Config.enabled || ModelRecoil.idle()) return;
        Vector3f pivot = new Vector3f();
        float size = GunExtent.size((WeaponRenderer) (Object) this, stack, pivot);
        if (size > 0F) ModelRecoil.apply(pose, size, pivot);
    }

    @Inject(method = "setupTransformsAndSubmit", at = @At("RETURN"))
    private void gunfeel$end(ItemStack stack, LocalPlayer player, PoseStack pose, SubmitSink collector, int light, int overlay,
            boolean otherArm, CallbackInfo ci) {
        GunFeel.current = null;
    }
}
