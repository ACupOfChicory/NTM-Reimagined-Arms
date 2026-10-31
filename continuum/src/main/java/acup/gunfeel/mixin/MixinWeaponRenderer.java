package acup.gunfeel.mixin;

import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.client.anim.HbmAnimations;
import com.hbm.client.render.weapon.WeaponPose;
import com.hbm.client.render.weapon.WeaponRenderer;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.mojang.blaze3d.vertex.PoseStack;

import acup.gunfeel.Arms;
import acup.gunfeel.ArmsGem;
import acup.gunfeel.Config;
import acup.gunfeel.GunExtent;
import acup.gunfeel.GunFeel;
import acup.gunfeel.ModelRecoil;
import acup.gunfeel.WeaponSway;
import acup.gunfeel.compat.Feature;
import dev.golem.core.render.SubmitSink;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

// port/continuum: src/main/java MixinItemRenderWeaponBase on NTM Continuum's WeaponRenderer, whose setupTransformsAndSubmit
// is NTM NEXT's: turn lag, walk sway, the gun's own offset (setupFirstPerson), then renderFirstPerson. Recoil, sway, the
// hip-fire position and the arms (Arms; the parts the gun drew come from its Gem declaration, ArmsGem).
@Mixin(value = WeaponRenderer.class, remap = false)
public abstract class MixinWeaponRenderer {
    private static final String RENDER = "Lcom/hbm/client/render/weapon/WeaponRenderer;renderFirstPerson(Lnet/minecraft/world/item/ItemStack;"
            + "Lcom/mojang/blaze3d/vertex/PoseStack;Ldev/golem/core/render/SubmitSink;IILnet/minecraft/world/InteractionHand;)V";
    private static final String SETUP = "Lcom/hbm/client/render/weapon/WeaponRenderer;setupFirstPerson"
            + "(Lnet/minecraft/world/item/ItemStack;Lcom/mojang/blaze3d/vertex/PoseStack;)V";

    @Shadow @Final private WeaponPose pose;
    @Shadow protected static float interp;

    @Shadow
    protected abstract void setupTransformsAndSubmit(ItemStack stack, LocalPlayer player, PoseStack pose, SubmitSink collector, int light,
            int overlay, boolean otherArm);

    @Shadow
    protected abstract boolean scopedIn(ItemStack stack);

    @Inject(method = "setupTransformsAndSubmit", at = @At("HEAD"))
    private void gunfeel$begin(ItemStack stack, LocalPlayer player, PoseStack poseStack, SubmitSink collector, int light, int overlay,
            boolean otherArm, CallbackInfo ci) {
        WeaponRenderer self = (WeaponRenderer) (Object) this;
        // the gun drawn may not be the one in hand (switching slots): its own settings, not the defaults
        Config.select(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        boolean arms = Feature.ARMS.on();
        // first time in hand, during its draw animation: read its rest pose first (Arms.measuring)
        if (arms && !Arms.measuring && Config.enabled && Config.arms && !otherArm && ArmsGem.fits(stack)
                && HbmAnimations.getRelevantAnim(0) != null && Arms.needsMeasure(stack.getItem())) {
            PoseStack copy = new PoseStack();
            copy.last().set(poseStack.last());
            Arms.measuring = true;
            try {
                setupTransformsAndSubmit(stack, player, copy, Arms.NOWHERE, light, overlay, false);
            } catch (Throwable t) {
                GunFeel.LOGGER.warn("Could not read the rest pose of {}", stack.getItem(), t);
            } finally {
                Arms.measuring = false;
            }
        }
        GunFeel.current = self;
        GunFeel.currentAkimbo = otherArm;
        if (!Arms.measuring) GunFeel.lastDrawn = System.nanoTime();
        if (arms) Arms.begin(stack);
        if (!Config.enabled || Arms.measuring) return;
        GunFeel.checkShot(stack);
        WeaponSway.apply(poseStack, player);
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

    // the camera-fixed frame the arms are built in
    @Inject(method = "setupTransformsAndSubmit", at = @At(value = "INVOKE", target = SETUP))
    private void gunfeel$camera(ItemStack stack, LocalPlayer player, PoseStack poseStack, SubmitSink collector, int light, int overlay,
            boolean otherArm, CallbackInfo ci) {
        if (Feature.ARMS.on()) Arms.cameraFrame(poseStack);
    }

    // Spring kick in the gun's frame, after NTM placed the gun and before it draws the model. It turns around the right
    // hand's grip (Arms.kickFrame); a gun whose shape the arms have not read turns around the middle of its box (GunExtent).
    @Inject(method = "setupTransformsAndSubmit", at = @At(value = "INVOKE", target = RENDER))
    private void gunfeel$kick(ItemStack stack, LocalPlayer player, PoseStack poseStack, SubmitSink collector, int light, int overlay,
            boolean otherArm, CallbackInfo ci) {
        boolean arms = Feature.ARMS.on();
        if (arms) Arms.aimFrame(poseStack);
        if (Config.enabled && !Arms.measuring && !ModelRecoil.idle()) {
            Vector3f pivot = new Vector3f();
            float size = arms ? Arms.kickFrame(stack.getItem(), pivot) : 0F;
            if (size <= 0F) size = GunExtent.size((WeaponRenderer) (Object) this, stack, pivot);
            if (size > 0F) ModelRecoil.apply(poseStack, size, pivot);
        }
        if (arms) {
            Arms.gunFrame(poseStack);
            ArmsGem.handed(poseStack);
        }
    }

    // the parts the gun drew, then the arms holding it
    @Inject(method = "setupTransformsAndSubmit", at = @At(value = "INVOKE", target = RENDER, shift = At.Shift.AFTER))
    private void gunfeel$arms(ItemStack stack, LocalPlayer player, PoseStack poseStack, SubmitSink collector, int light, int overlay,
            boolean otherArm, CallbackInfo ci) {
        if (!Feature.ARMS.on()) return;
        // scoped in, the gun drew nothing (its pose was not begun this frame)
        if (!scopedIn(stack)) ArmsGem.parts((WeaponRenderer) (Object) this, pose);
        Arms.end(stack, collector, light, Config.enabled && !Arms.measuring && !otherArm && ArmsGem.fits(stack));
    }

    @Inject(method = "setupTransformsAndSubmit", at = @At("RETURN"))
    private void gunfeel$end(ItemStack stack, LocalPlayer player, PoseStack poseStack, SubmitSink collector, int light, int overlay,
            boolean otherArm, CallbackInfo ci) {
        GunFeel.current = null;
        if (Arms.measuring) return;
        var held = player.getMainHandItem().getItem();
        Config.select(held instanceof ItemGunBaseNT ? BuiltInRegistries.ITEM.getKey(held).toString() : null);
    }

    // Hip-fire position, moved toward the centre of the screen; the aimed position is unchanged (main's
    // MixinItemRenderWeaponBase.gunfeel$hip: the arms' grips are set with the gun there).
    @Inject(method = "standardAimingTransform", at = @At("HEAD"), cancellable = true)
    private static void gunfeel$hip(ItemStack stack, PoseStack pose, double sX, double sY, double sZ, double aX, double aY, double aZ,
            CallbackInfo ci) {
        if (!Config.enabled || GunFeel.current == null || GunFeel.currentAkimbo) return;
        float[] tilt = gunfeel$tilt();
        if (Config.hipX == 1F && Config.hipY == 1F && Config.hipZ == 1F && tilt == null) return;
        float p = ItemGunBaseNT.prevAimingProgress + (ItemGunBaseNT.aimingProgress - ItemGunBaseNT.prevAimingProgress) * interp;
        double hx = sX * Config.hipX, hy = sY * Config.hipY;
        double hz = sZ > 0 ? sZ * Config.hipZ : sZ;
        pose.translate(hx + (aX - hx) * p, hy + (aY - hy) * p, hz + (aZ - hz) * p);
        // modelTilt (per gun): turned around the hip-fire position, fading out as the gun is aimed
        if (tilt != null) {
            float w = 1F - p;
            pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(tilt[1] * w));
            pose.mulPose(com.mojang.math.Axis.XP.rotationDegrees(tilt[0] * w));
            pose.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(tilt[2] * w));
        }
        ci.cancel();
    }

    private static String gunfeel$tiltSource;
    private static float[] gunfeel$tiltValue;

    // modelTilt "pitch,yaw,roll" or null
    private static float[] gunfeel$tilt() {
        String src = Config.modelTilt;
        if (!src.equals(gunfeel$tiltSource)) {
            gunfeel$tiltSource = src;
            gunfeel$tiltValue = null;
            String[] t = src.split(",");
            if (t.length == 3) {
                try {
                    gunfeel$tiltValue = new float[] {Float.parseFloat(t[0].trim()), Float.parseFloat(t[1].trim()), Float.parseFloat(t[2].trim())};
                } catch (NumberFormatException e) {
                    // malformed: no tilt
                }
            }
        }
        return gunfeel$tiltValue;
    }
}
