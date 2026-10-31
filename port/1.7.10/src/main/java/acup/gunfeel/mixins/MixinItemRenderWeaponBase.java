package acup.gunfeel.mixins;

import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.render.anim.HbmAnimations;
import com.hbm.render.item.weapon.sedna.ItemRenderWeaponBase;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import acup.gunfeel.Arms;
import acup.gunfeel.Config;
import acup.gunfeel.GunFeel;
import acup.gunfeel.GunFeelClient;
import acup.gunfeel.ModelRecoil;
import acup.gunfeel.WeaponSway;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.IItemRenderer;

// The first-person gun (NTM: setPerspectiveAndRender -> setupTransformsAndRender -> renderItem -> setupFirstPerson,
// renderFirstPerson). 26.2 has the same steps in setupTransformsAndSubmit, with setupFirstPerson and renderFirstPerson
// called from it directly; here they are in renderItem, which only the first-person gun reaches with current == this.
@Mixin(value = ItemRenderWeaponBase.class, remap = false)
public abstract class MixinItemRenderWeaponBase {
    @Shadow
    protected abstract void setupTransformsAndRender(ItemStack stack);

    @Inject(method = "setupTransformsAndRender", at = @At("HEAD"))
    private void gunfeel$begin(ItemStack stack, CallbackInfo ci) {
        ItemRenderWeaponBase self = (ItemRenderWeaponBase) (Object) this;
        // the gun drawn may not be the one in hand (switching slots): its own settings, not the defaults
        Config.select(stack == null ? null : GunFeelClient.gunId(stack.getItem()));
        boolean akimbo = self.isAkimbo(Minecraft.getMinecraft().thePlayer);
        // first time in hand, during its draw animation: read its rest pose first (Arms.measuring), the gun drawn
        // into closed colour and depth writes (26.2: into the NOWHERE collector)
        if (!Arms.measuring && stack != null && Config.enabled && Config.arms && !akimbo && HbmAnimations.getRelevantAnim(0) != null
                && Arms.needsMeasure(stack.getItem())) {
            Arms.measuring = true;
            GL11.glPushMatrix();
            GL11.glColorMask(false, false, false, false);
            GL11.glDepthMask(false);
            try {
                setupTransformsAndRender(stack);
            } catch (Throwable t) {
                GunFeel.LOGGER.warn("Could not read the rest pose of {}", stack.getItem(), t);
            } finally {
                GL11.glColorMask(true, true, true, true);
                GL11.glDepthMask(true);
                GL11.glPopMatrix();
                Arms.measuring = false;
            }
        }
        GunFeelClient.current = self;
        GunFeelClient.currentAkimbo = akimbo;
        if (Config.enabled && !Arms.measuring) GunFeelClient.checkShot(stack);
        if (stack != null) Arms.begin(stack);
    }

    // NTM's two lag turns ((pitch - arm pitch) * 0.1 * turnMagnitude, the same for yaw) are replaced by WeaponSway as a
    // whole, not added to it: with the magnitude 0 they turn by nothing.
    @ModifyExpressionValue(method = "setupTransformsAndRender", at = @At(value = "INVOKE",
            target = "Lcom/hbm/render/item/weapon/sedna/ItemRenderWeaponBase;getTurnMagnitude(Lnet/minecraft/item/ItemStack;)F"))
    private float gunfeel$noLag(float original) {
        return Config.enabled ? 0F : original;
    }

    // the sway where NTM's lag turns were: after the lighting set-up (its own push/pop), before the lag turns
    @Inject(method = "setupTransformsAndRender", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/entity/EntityPlayerSP;prevRenderArmPitch:F", opcode = Opcodes.GETFIELD, ordinal = 0, remap = true))
    private void gunfeel$sway(ItemStack stack, CallbackInfo ci) {
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        if (Config.enabled && !Arms.measuring && player != null) WeaponSway.apply(player);
    }

    // the camera-fixed frame the arms are built in, before NTM places the gun (26.2: Arms.cameraFrame)
    @Inject(method = "renderItem", at = @At(value = "INVOKE",
            target = "Lcom/hbm/render/item/weapon/sedna/ItemRenderWeaponBase;setupFirstPerson(Lnet/minecraft/item/ItemStack;)V"))
    private void gunfeel$camera(IItemRenderer.ItemRenderType type, ItemStack stack, Object[] data, CallbackInfo ci) {
        if (GunFeelClient.current == (Object) this) Arms.cameraFrame();
    }

    // Spring kick in the gun's frame, after NTM placed the gun and before it draws the model.
    @Inject(method = "renderItem", at = @At(value = "INVOKE",
            target = "Lcom/hbm/render/item/weapon/sedna/ItemRenderWeaponBase;renderFirstPerson(Lnet/minecraft/item/ItemStack;)V"))
    private void gunfeel$kick(IItemRenderer.ItemRenderType type, ItemStack stack, Object[] data, CallbackInfo ci) {
        if (GunFeelClient.current != (Object) this) return;
        Arms.aimFrame();
        if (Config.enabled && !Arms.measuring && stack != null) {
            Vector3f pivot = new Vector3f();
            float size = Arms.kickFrame(stack.getItem(), pivot);
            if (size > 0F) ModelRecoil.apply(size, new float[] {pivot.x, pivot.y, pivot.z});
        }
        Arms.gunFrame();
    }

    // the arms, after NTM drew the gun (26.2: Arms.end after renderFirstPerson)
    @Inject(method = "renderItem", at = @At(value = "INVOKE",
            target = "Lcom/hbm/render/item/weapon/sedna/ItemRenderWeaponBase;renderFirstPerson(Lnet/minecraft/item/ItemStack;)V",
            shift = At.Shift.AFTER))
    private void gunfeel$arms(IItemRenderer.ItemRenderType type, ItemStack stack, Object[] data, CallbackInfo ci) {
        if (GunFeelClient.current != (Object) this || stack == null) return;
        Arms.end(stack, Config.enabled && !Arms.measuring && !GunFeelClient.currentAkimbo);
    }

    // Hip-fire position, moved toward the centre of the screen; the aimed position is unchanged (26.2's gunfeel$hip;
    // GL turns instead of PoseStack ones). The arms are built around the gun where this puts it.
    @Inject(method = "standardAimingTransform", at = @At("HEAD"), cancellable = true)
    private static void gunfeel$hip(ItemStack stack, double sX, double sY, double sZ, double aX, double aY, double aZ, CallbackInfo ci) {
        if (!Config.enabled || GunFeelClient.current == null || GunFeelClient.currentAkimbo) return;
        float[] tilt = tilt();
        if (Config.hipX == 1F && Config.hipY == 1F && Config.hipZ == 1F && tilt == null) return;
        float p = ItemGunBaseNT.prevAimingProgress + (ItemGunBaseNT.aimingProgress - ItemGunBaseNT.prevAimingProgress) * ItemRenderWeaponBase.interp;
        double hx = sX * Config.hipX, hy = sY * Config.hipY;
        double hz = sZ > 0 ? sZ * Config.hipZ : sZ;
        // 26.2: PoseStack.translate(double...) takes them as floats
        GL11.glTranslatef((float) (hx + (aX - hx) * p), (float) (hy + (aY - hy) * p), (float) (hz + (aZ - hz) * p));
        // modelTilt (per gun): turned around the hip-fire position, fading out as the gun is aimed
        if (tilt != null) {
            float w = 1F - p;
            GL11.glRotatef(tilt[1] * w, 0F, 1F, 0F);
            GL11.glRotatef(tilt[0] * w, 1F, 0F, 0F);
            GL11.glRotatef(tilt[2] * w, 0F, 0F, 1F);
        }
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

    @Inject(method = "setupTransformsAndRender", at = @At("RETURN"))
    private void gunfeel$end(ItemStack stack, CallbackInfo ci) {
        GunFeelClient.current = null;
        if (Arms.measuring) return;
        net.minecraft.entity.player.EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        ItemStack held = player == null ? null : player.getHeldItem();
        Config.select(held != null && held.getItem() instanceof ItemGunBaseNT ? GunFeelClient.gunId(held.getItem()) : null);
    }
}
