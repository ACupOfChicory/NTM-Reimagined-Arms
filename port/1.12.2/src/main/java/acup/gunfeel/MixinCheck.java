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
            {"com.hbm.render.item.weapon.sedna.ItemRenderWeaponBase", "16"}, // 5 + MixinSmokeNodes 2 + arms 3 + direct path 2 and its helpers 3 + walkSway 1
            {"com.hbm.render.anim.sedna.HbmAnimationsSedna", "2"},
            {"com.hbm.render.loader.HFRWavefrontObject", "8"}, // 4 + MachineMesh ends 4
            {"net.minecraft.client.renderer.EntityRenderer", "7"}, // 2 + MixinMousePick 2 + the world pass 2 (MixinEntityRenderer) + MixinMouseTurn 1
            {"net.minecraft.client.renderer.RenderGlobal", "1"}, // MixinRenderGlobal: the holes before the block cracks
            {"net.minecraft.entity.projectile.EntityArrow", "5"}, // MixinVanillaProjectileHits, as the three below
            {"net.minecraft.entity.projectile.EntityThrowable", "5"},
            {"net.minecraft.entity.projectile.EntityFireball", "3"}, // the hits begin and end, the owner; its path is ProjectileHelper.forwardsRaycast
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
            {"com.hbm.items.weapon.sedna.ItemGunBaseNT", "12"}, // 2 + MixinHoldAim 3 handlers and its 3 helpers (gunfeel$standardScope ...) + MixinSprintFireGun 4
            {"com.hbm.packet.toclient.GunAnimationPacketSedna$Handler", "4"},
            {"net.minecraft.client.renderer.ItemRenderer", "4"}, // ItemRendererAccess
            {"net.minecraft.client.renderer.entity.RenderLivingBase", "5"}, // begin, end, yaw, modelStart, modelEnd
            {"net.minecraft.client.entity.EntityPlayerSP", "6"}, // PlayerSPAccess 5, MixinSprintStop 1
            {"net.minecraft.client.renderer.entity.layers.LayerSheepWool", "1"}, // MixinLayerWool
            {"net.minecraft.client.renderer.entity.layers.LayerSlimeGel", "1"}, // MixinLayerGel
            {"net.minecraft.client.renderer.entity.RenderShulker$HeadLayer", "1"}, // MixinLayerShulkerHead
            {"net.minecraft.client.renderer.Tessellator", "1"}, // MixinTessellator
            {"net.minecraft.client.renderer.GlStateManager", "1"}, // MixinCallList
            {"net.minecraft.client.model.ModelRenderer", "4"}, // 2 + MachineMesh compile 2
            {"com.hbm.render.loader.WaveFrontObjectVAO", "1"}, // MixinVaoModel
            {"com.hbm.render.loader.GroupHandle", "2"}, // MixinGroupHandle
            {"net.minecraft.client.renderer.entity.RenderManager", "2"}, // the model box, the box at full brightness
            {"com.hbm.items.weapon.sedna.GunConfig", "6"}, // MixinG3Inspect 4 (gunfeel$release + 3), MixinGunConfigAnims 2
            {"com.hbm.entity.projectile.EntityThrowableNT", "10"}, // MixinThrowableClip 9 (blocks, mob, nearest, start, spread, hitboxes 4) + ThrowableAccess 1
            {"com.hbm.entity.projectile.EntityBulletBeamBase", "4"},
            {"com.hbm.items.weapon.sedna.BulletConfig", "3"}, // MixinHeadDamage, MixinRicochetSound
    };

    private MixinCheck() {
    }

    // class -> handlers found; logs a warning for a short one
    private static final boolean CLEANROOM = MixinCheck.class.getClassLoader().getResource("com/cleanroommc/client/input/InputHooks.class") != null;

    public static Map<String, Integer> report() {
        Map<String, Integer> out = new LinkedHashMap<String, Integer>();
        for (String[] t : TARGETS) {
            int n = 0;
            List<String> names = new ArrayList<String>();
            try {
                for (Method m : Class.forName(t[0]).getDeclaredMethods()) if (m.getName().contains("gunfeel$") && !m.getName().contains("lambda$") && !m.getName().startsWith("mixinextras$bridge$")) { // a mixin's lambdas and MixinExtras' bridges are not handlers
                    n++;
                    names.add(m.getName());
                }
            } catch (ClassNotFoundException | LinkageError e) {
                n = -1;
            }
            out.put(t[0].substring(t[0].lastIndexOf('.') + 1), n);
            int want = Integer.parseInt(t[1]);
            // port: on Cleanroom MixinMouseTurnCleanroom adds one (ArmsCore queues it there only)
            if (CLEANROOM && t[0].equals("net.minecraft.client.renderer.EntityRenderer")) want++;
            if (n != want) GunFeel.LOGGER.warn("Mixins: {} has {} of our handlers, wants {}: {}", t[0], n, want, names);
        }
        GunFeel.LOGGER.info("Mixins applied (handlers per class): {}", out);
        return out;
    }
}
