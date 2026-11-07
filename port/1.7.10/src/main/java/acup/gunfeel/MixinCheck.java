package acup.gunfeel;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// Which of the mod's mixins took: each target class and how many of our handlers (gunfeel$...) Mixin merged into it.
// Mixin says nothing of it outside its debug mode, and a target never loaded takes nothing without an error.
public final class MixinCheck {
    // target -> handlers it should have
    private static final String[][] TARGETS = {
            {"com.hbm.main.ModEventHandlerClient", "1"},
            {"com.hbm.render.item.weapon.sedna.ItemRenderWeaponBase", "17"}, // 5 + MixinSmokeNodes 1 + arms 3 + Shader Fixer path 2 and its helpers 2 + walkSway 1 + hip, muzzle, underWater ... (counted on the obf run, X543.177)
            {"com.hbm.render.anim.HbmAnimations", "2"},
            {"com.hbm.render.loader.HFRWavefrontObject", "8"}, // 4 + MachineMesh ends 4
            {"com.hbm.render.loader.HFRWavefrontObjectVBO", "4"},
            {"net.minecraft.client.renderer.EntityRenderer", "6"}, // 2 + MixinMousePick 2 + the world pass 2 (MixinEntityRenderer)
            {"net.minecraft.client.renderer.RenderGlobal", "1"}, // MixinRenderGlobal: the holes before the block cracks
            {"net.minecraft.entity.projectile.EntityArrow", "5"}, // MixinVanillaProjectileHits, as the three below
            {"net.minecraft.entity.projectile.EntityThrowable", "5"},
            {"net.minecraft.entity.projectile.EntityFireball", "5"},
            {"net.minecraft.entity.projectile.EntityFishHook", "5"},
            {"net.minecraft.util.Timer", "1"},
            {"com.hbm.util.Clock", "1"},
            {"com.hbm.render.entity.projectile.RenderBulletMK4", "1"},
            {"com.hbm.items.weapon.sedna.factory.LegoClient", "1"},
            {"com.hbm.items.weapon.sedna.factory.Lego", "2"}, // MixinLegoSpread, MixinSprintFireClick
            {"com.hbm.items.weapon.sedna.factory.GunStateDecider", "1"}, // MixinSprintFireDecider
            {"com.hbm.items.weapon.sedna.mags.MagazineSingleTypeBase", "3"},
            {"com.hbm.items.weapon.sedna.mags.MagazineBelt", "2"},
            {"com.hbm.main.ClientProxy", "2"}, // MixinGunKeysRadial, MixinAimButton
            {"com.hbm.items.weapon.sedna.ItemGunBaseNT", "17"}, // 2 + MixinHoldAim 3 handlers and its 3 helpers (gunfeel$standardScope ...) + MixinSprintFireGun 4 + heldClick, hud, components ... (counted on the obf run, X543.177)
            {"com.hbm.packet.toclient.HbmAnimationPacket$Handler", "4"},
            {"net.minecraft.client.renderer.ItemRenderer", "10"}, // ItemRendererAccess 5 + MixinItemRendererGunSway 2 + MixinItemRendererGunSwing 3
            {"net.minecraft.client.renderer.entity.RendererLivingEntity", "9"}, // begin, end, yaw, modelStart, modelEnd, pass + worn: draw, overlay, glint
            {"net.minecraft.client.entity.EntityPlayerSP", "1"}, // MixinSprintStop
            {"net.minecraft.client.model.ModelRenderer", "4"}, // 2 + MachineMesh compile 2
            {"net.minecraft.client.model.ModelBiped", "1"}, // MixinModelBipedPose (armArmor)
            {"net.minecraft.client.renderer.texture.TextureManager", "1"}, // MixinTextureBind (armArmor's stand)
            {"net.minecraft.client.renderer.Tessellator", "2"}, // MixinTessellator: draw, drop
            {"net.minecraftforge.client.model.obj.WavefrontObject", "8"}, // MixinForgeWavefrontObject 4 + ends 4
            {"net.minecraft.client.renderer.entity.RenderManager", "2"}, // the model box, the box at full brightness
            {"com.hbm.items.weapon.sedna.GunConfig", "6"}, // MixinG3Inspect 4 (gunfeel$release + 3), MixinGunConfigAnims 2
            {"com.hbm.entity.projectile.EntityThrowableNT", "12"}, // MixinThrowableClip: its 12 gunfeel$ methods (handlers and helpers)
            {"com.hbm.entity.projectile.EntityThrowableInterp", "1"}, // MixinThrowableMotion (client)
            {"com.hbm.entity.projectile.EntityBulletBeamBase", "4"},
            {"com.hbm.entity.projectile.EntityBullet", "9"}, // MixinLegacyBullet: 8 + gunfeel$extra
            {"com.hbm.entity.projectile.EntityBulletBaseNT", "40"}, // MixinOldBulletBase: 2 + gunfeel$ntmHead; MixinWaterOldBullet: 10 handlers + 27 of WaterBullet, Hits and helpers
            {"com.hbm.explosion.vanillant.standard.EntityProcessorCross", "5"}, // MixinWaterBlastProcessor: the blast in water
            {"com.hbm.explosion.vanillant.standard.EntityProcessorStandard", "5"}, // MixinWaterBlastStandard: an old bullet's blast in water
            {"com.hbm.items.weapon.sedna.BulletConfig", "3"}, // MixinHeadDamage, MixinRicochetSound
            {"com.hbm.render.block.RenderBlockCT", "1"}, // the cracks on NTM's glass
    };

    private MixinCheck() {
    }

    // class -> handlers found; logs a warning for a short one
    public static Map<String, Integer> report() {
        Map<String, Integer> out = new LinkedHashMap<String, Integer>();
        for (String[] t : TARGETS) {
            int n = 0;
            List<String> names = new ArrayList<String>();
            try {
                for (Method m : Class.forName(t[0]).getDeclaredMethods()) if (m.getName().contains("gunfeel$") && !m.getName().contains("lambda$") && !m.getName().contains("mixinextras$bridge$")) { // a mixin's lambdas and MixinExtras' bridges (a @Local's, named after the handler) are not handlers
                    n++;
                    names.add(m.getName());
                }
            } catch (ClassNotFoundException | LinkageError e) {
                n = -1;
            }
            out.put(t[0].substring(t[0].lastIndexOf('.') + 1), n);
            if (n != Integer.parseInt(t[1])) GunFeel.LOGGER.warn("Mixins: {} has {} of our handlers, wants {}: {}", t[0], n, t[1], names);
        }
        GunFeel.LOGGER.info("Mixins applied (handlers per class): {}", out);
        return out;
    }
}
