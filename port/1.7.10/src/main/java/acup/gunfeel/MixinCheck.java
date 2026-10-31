package acup.gunfeel;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

// Which of the mod's mixins took: each target class and how many of our handlers (gunfeel$...) Mixin merged into it.
// Mixin says nothing of it outside its debug mode, and a target never loaded takes nothing without an error.
public final class MixinCheck {
    // target -> handlers it should have
    private static final String[][] TARGETS = {
            {"com.hbm.main.ModEventHandlerClient", "1"},
            {"com.hbm.render.item.weapon.sedna.ItemRenderWeaponBase", "9"}, // 5 + MixinSmokeNodes 1 + arms 3
            {"com.hbm.render.anim.HbmAnimations", "2"},
            {"com.hbm.render.loader.HFRWavefrontObject", "4"},
            {"com.hbm.render.loader.HFRWavefrontObjectVBO", "4"},
            {"net.minecraft.client.renderer.EntityRenderer", "2"},
            {"net.minecraft.util.Timer", "1"},
            {"com.hbm.util.Clock", "1"},
            {"com.hbm.render.entity.projectile.RenderBulletMK4", "1"},
            {"com.hbm.items.weapon.sedna.factory.LegoClient", "1"},
            {"com.hbm.items.weapon.sedna.mags.MagazineSingleTypeBase", "3"},
            {"com.hbm.items.weapon.sedna.mags.MagazineBelt", "2"},
            {"com.hbm.main.ClientProxy", "1"},
            {"com.hbm.items.weapon.sedna.ItemGunBaseNT", "2"},
            {"com.hbm.packet.toclient.HbmAnimationPacket$Handler", "4"},
            {"net.minecraft.client.renderer.ItemRenderer", "5"},
            {"net.minecraft.client.renderer.entity.RendererLivingEntity", "5"},
            {"net.minecraft.client.model.ModelRenderer", "2"},
            {"net.minecraft.client.renderer.entity.RenderManager", "1"},
            {"com.hbm.items.weapon.sedna.GunConfig", "3"},
    };

    private MixinCheck() {
    }

    // class -> handlers found; logs a warning for a short one
    public static Map<String, Integer> report() {
        Map<String, Integer> out = new LinkedHashMap<String, Integer>();
        for (String[] t : TARGETS) {
            int n = 0;
            try {
                for (Method m : Class.forName(t[0]).getDeclaredMethods()) if (m.getName().contains("gunfeel$")) n++;
            } catch (ClassNotFoundException | LinkageError e) {
                n = -1;
            }
            out.put(t[0].substring(t[0].lastIndexOf('.') + 1), n);
            if (n != Integer.parseInt(t[1])) GunFeel.LOGGER.warn("Mixins: {} has {} of our handlers, wants {}", t[0], n, t[1]);
        }
        GunFeel.LOGGER.info("Mixins applied (handlers per class): {}", out);
        return out;
    }
}
