package acup.gunfeel.mixin;

import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.client.render.OtherHandCollector;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.render.loader.HFRWavefrontObject;
import com.hbm.render.item.weapon.sedna.ItemRenderWeaponBase;
import com.mojang.blaze3d.vertex.PoseStack;

import acup.gunfeel.Arms;
import acup.gunfeel.Config;
import acup.gunfeel.GunFeel;
import acup.gunfeel.ModelRecoil;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.item.ItemStack;

@Mixin(value = ItemRenderWeaponBase.class, remap = false)
public abstract class MixinItemRenderWeaponBase {
    private static final String RENDER = "Lcom/hbm/render/item/weapon/sedna/ItemRenderWeaponBase;renderFirstPerson"
            + "(Lnet/minecraft/world/item/ItemStack;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V";

    @org.spongepowered.asm.mixin.Shadow
    protected abstract void setupTransformsAndSubmit(ItemStack stack, LocalPlayer player, PoseStack pose, SubmitNodeCollector collector, int light);

    @Inject(method = "setupTransformsAndSubmit", at = @At("HEAD"))
    private void gunfeel$begin(ItemStack stack, LocalPlayer player, PoseStack pose, SubmitNodeCollector collector, int light, CallbackInfo ci) {
        ItemRenderWeaponBase self = (ItemRenderWeaponBase) (Object) this;
        // the gun drawn may not be the one in hand (switching slots: the old one is lowered, the new one raised):
        // its own settings, not the defaults
        acup.gunfeel.Config.select(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        // first time in hand, during its draw animation: read its rest pose first (Arms.measuring)
        if (!Arms.measuring && Config.enabled && Config.arms && !(collector instanceof OtherHandCollector) && !self.isAkimbo(player)
                && com.hbm.render.anim.HbmAnimations.getRelevantAnim(0) != null && Arms.needsMeasure(stack.getItem())) {
            PoseStack copy = new PoseStack();
            copy.last().set(pose.last());
            Arms.measuring = true;
            try {
                setupTransformsAndSubmit(stack, player, copy, Arms.NOWHERE, light);
            } catch (Throwable t) {
                GunFeel.LOGGER.warn("Could not read the rest pose of {}", stack.getItem(), t);
            } finally {
                Arms.measuring = false;
            }
        }
        GunFeel.current = self;
        GunFeel.currentAkimbo = self.isAkimbo(player);
        if (Config.enabled && !Arms.measuring) GunFeel.checkShot(stack);
        Arms.begin(stack);
        if(Config.enabled && !Arms.measuring) acup.gunfeel.WeaponSway.apply(pose,player);
        if (Config.enabled && !Arms.measuring && !GunFeel.currentAkimbo && !(collector instanceof OtherHandCollector))
            acup.gunfeel.WalkSprint.apply(pose, player, stack.getItem());
    }

    // NTM's own walking bob (a sway by the vanilla bob) is left out for a gun with walkSway on: WalkSprint does it.
    @org.spongepowered.asm.mixin.injection.Redirect(method = "setupTransformsAndSubmit", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/entity/ClientAvatarState;getInterpolatedBob(F)F"))
    private float gunfeel$bob(net.minecraft.client.entity.ClientAvatarState state, float partial) {
        if (acup.gunfeel.WalkSprint.bobOff()) return 0F;
        return state.getInterpolatedBob(partial);
    }

    // Два штатных поворота отставания NTM заменены целиком, а не сложены с новым движением.
    @org.spongepowered.asm.mixin.injection.ModifyArg(method = "setupTransformsAndSubmit", at = @At(value = "INVOKE",
            target = "Lcom/mojang/math/Axis;rotationDegrees(F)Lorg/joml/Quaternionf;", ordinal = 0), index = 0)
    private float gunfeel$replacePitchLag(float original) {
        if(Config.enabled)acup.gunfeel.ClientSmoke.weaponLag(0);
        return Config.enabled ? 0F : original;
    }

    @org.spongepowered.asm.mixin.injection.ModifyArg(method = "setupTransformsAndSubmit", at = @At(value = "INVOKE",
            target = "Lcom/mojang/math/Axis;rotationDegrees(F)Lorg/joml/Quaternionf;", ordinal = 1), index = 0)
    private float gunfeel$replaceYawLag(float original) {
        if(Config.enabled)acup.gunfeel.ClientSmoke.weaponLag(0);
        return Config.enabled ? 0F : original;
    }

    private static final String SETUP = "Lcom/hbm/render/item/weapon/sedna/ItemRenderWeaponBase;setupFirstPerson"
            + "(Lnet/minecraft/world/item/ItemStack;Lcom/mojang/blaze3d/vertex/PoseStack;)V";

    @Inject(method = "setupTransformsAndSubmit", at = @At(value = "INVOKE", target = SETUP))
    private void gunfeel$camera(ItemStack stack, LocalPlayer player, PoseStack pose, SubmitNodeCollector collector, int light, CallbackInfo ci) {
        Arms.cameraFrame(pose);
        acup.gunfeel.PistolHold.setup();
    }

    @Inject(method = "setupTransformsAndSubmit", at = @At(value = "INVOKE", target = RENDER, shift = At.Shift.AFTER))
    private void gunfeel$arms(ItemStack stack, LocalPlayer player, PoseStack pose, SubmitNodeCollector collector, int light, CallbackInfo ci) {
        Arms.end(stack, collector, light, Config.enabled && !Arms.measuring && !GunFeel.currentAkimbo && !(collector instanceof OtherHandCollector));
    }

    @Inject(method = "submitPart(Lnet/minecraft/client/renderer/SubmitNodeCollector;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;Lcom/hbm/render/loader/HFRWavefrontObject;III)V",
            at = @At("HEAD"))
    private static void gunfeel$part(SubmitNodeCollector collector, PoseStack pose, RenderType type, HFRWavefrontObject model, int part, int light, int color, CallbackInfo ci) {
        if (GunFeel.current != null) Arms.part(pose, model, part);
    }

    // Spring kick in the gun's frame, after NTM placed the gun and before it draws the model.
    @Inject(method = "setupTransformsAndSubmit", at = @At(value = "INVOKE", target = RENDER))
    private void gunfeel$kick(ItemStack stack, LocalPlayer player, PoseStack pose, SubmitNodeCollector collector, int light, CallbackInfo ci) {
        Arms.aimFrame(pose);
        if (Config.enabled && !Arms.measuring) {
            Vector3f pivot = new Vector3f();
            float size = Arms.kickFrame(stack.getItem(), pivot);
            if (size > 0F) ModelRecoil.apply(pose, size, pivot);
        }
        Arms.gunFrame(pose);
    }

    // Muzzle smoke: NTM fades each smoke node by a fixed step until it is no longer above zero, so the alpha ends
    // slightly negative, and a negative alpha turns into a nearly opaque colour (the widest, oldest smoke at the top
    // flashes white, spreading downward). Clamp it at zero.
    @Inject(method = "renderSmokeNodes", at = @At("HEAD"))
    private static void gunfeel$smoke(SubmitNodeCollector collector, PoseStack pose, java.util.List<ItemGunBaseNT.SmokeNode> nodes, double scale, int light, CallbackInfo ci) {
        // the muzzle of the gun in the player's hands: the player's own tracers start there
        if (GunFeel.current != null && !Arms.measuring) acup.gunfeel.Tracers.muzzle(pose);
        if (!Config.enabled) return;
        try {
            for (ItemGunBaseNT.SmokeNode node : nodes) if (node.alpha < 0.0) node.alpha = 0.0;
        } catch (java.util.ConcurrentModificationException e) {
            // the list changed under us (game tick); next frame clamps the rest
        }
    }

    // Muzzle smoke width (smokeWidth): the scale argument is the smoke's width.
    @org.spongepowered.asm.mixin.injection.ModifyVariable(method = "renderSmokeNodes", at = @At("HEAD"), argsOnly = true)
    private static double gunfeel$smokeWidth(double scale) {
        return Config.enabled && GunFeel.current != null ? scale * Config.smokeWidth : scale;
    }

    @Inject(method = "setupTransformsAndSubmit", at = @At("RETURN"))
    private void gunfeel$end(ItemStack stack, LocalPlayer player, PoseStack pose, SubmitNodeCollector collector, int light, CallbackInfo ci) {
        GunFeel.current = null;
        if (Arms.measuring) return;
        acup.gunfeel.WalkSprint.gunPlaced(stack.getItem());
        acup.gunfeel.PistolHold.placed(stack.getItem());
        var held = player.getMainHandItem().getItem();
        Config.select(held instanceof ItemGunBaseNT ? net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(held).toString() : null);
    }

    // Hip-fire position, moved toward the centre of the screen; the aimed position is unchanged.
    @Inject(method = "standardAimingTransform", at = @At("HEAD"), cancellable = true)
    private static void gunfeel$hip(ItemStack stack, PoseStack pose, double sX, double sY, double sZ, double aX, double aY, double aZ, CallbackInfo ci) {
        boolean placing = acup.gunfeel.PistolHold.placing();
        if (!Config.enabled || GunFeel.current == null || GunFeel.currentAkimbo) return;
        float[] tilt = tilt();
        // pistolHold: a pistol's hip place and aiming curve as TaCZ's; hipX/Y/Z are not used for it then
        // (a pistol not measured yet keeps the old hold until it is measured); only NTM's placing call in
        // setupFirstPerson, not a later one (the heavy revolver's kick in renderFirstPerson calls it again)
        Vector3f held = new Vector3f();
        boolean hold = placing && acup.gunfeel.PistolHold.on() && !acup.gunfeel.PistolHold.scoped(stack);
        boolean pistol = hold && acup.gunfeel.PistolHold.hip(pose, stack.getItem(), aX, aY, aZ, held);
        if (!hold && Config.hipX == 1F && Config.hipY == 1F && Config.hipZ == 1F && tilt == null) return;
        float p = ItemGunBaseNT.prevAimingProgress + (ItemGunBaseNT.aimingProgress - ItemGunBaseNT.prevAimingProgress) * ItemRenderWeaponBase.interp;
        double hx = sX * Config.hipX, hy = sY * Config.hipY;
        double hz = sZ > 0 ? sZ * Config.hipZ : sZ;
        if (pistol) {
            float w = Math.min(1F, Config.pistolHold);
            hx = sX + (held.x - sX) * w;
            hy = sY + (held.y - sY) * w;
            hz = sZ + (held.z - sZ) * w;
            float raw = p;
            p = raw + (acup.gunfeel.PistolHold.curve(raw) - raw) * w;
            if (acup.gunfeel.TestLog.frames()) acup.gunfeel.TestLog.c(-1, "pistolHold", "aiming", raw, "curve", p, "hip", held,
                    "ntmHip", new Vector3f((float) sX, (float) sY, (float) sZ), "aim", new Vector3f((float) aX, (float) aY, (float) aZ));
        }
        double tx = hx + (aX - hx) * p, ty = hy + (aY - hy) * p, tz = hz + (aZ - hz) * p;
        pose.translate(tx, ty, tz);
        // modelTilt (per gun): turned around the hip-fire position, fading out as the gun is aimed
        if (tilt != null) {
            float w = 1F - p;
            pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(tilt[1] * w));
            pose.mulPose(com.mojang.math.Axis.XP.rotationDegrees(tilt[0] * w));
            pose.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(tilt[2] * w));
        }
        // the gun's ends are measured in the frame NTM draws it in from here, past the hip shift and the tilt
        if (hold) acup.gunfeel.PistolHold.framed(pose, stack.getItem());
        ci.cancel();
    }

    private static String tiltSource;
    private static float[] tiltValue;

    // modelTilt "pitch,yaw,roll" or null
    private static float[] tilt() {
        String src = Config.modelTilt;
        if (!src.equals(tiltSource)) {
            tiltSource = src;
            tiltValue = null;
            String[] t = src.split(",");
            if (t.length == 3) {
                try {
                    tiltValue = new float[] {Float.parseFloat(t[0].trim()), Float.parseFloat(t[1].trim()), Float.parseFloat(t[2].trim())};
                } catch (NumberFormatException e) {
                    // malformed: no tilt
                }
            }
        }
        return tiltValue;
    }
}
