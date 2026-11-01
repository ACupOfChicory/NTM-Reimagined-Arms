package acup.gunfeel;

import java.util.Map;
import java.util.function.BiFunction;

import com.hbm.items.weapon.sedna.GunAnimation;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;

import acup.gunfeel.compat.Feature;
import acup.gunfeel.next.BusAnimation;
import acup.gunfeel.next.BusAnimationKeyframe.IType;
import acup.gunfeel.next.BusAnimationSequence;
import acup.gunfeel.next.NextGunAnimations;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

// port/continuum: NTM NEXT's gun animations (next/NextGunAnimations) in place of NTM Continuum's own (GunAnimations) for
// every gun both have (MixinGunAnimations). The owner's decision: the arms and the gun's parts move as on 26.2, where
// the grips and arm paths of ntmgunfeel-guns.properties are made on NEXT's buses; where a Continuum model has no part a
// NEXT bus moves, the arms follow the bus all the same. On top, what main does to NEXT's animations on 26.2:
// MixinGunConfigAnims (MARESLEG: Sexy's inspect, the sawn-off Maresleg aimed) and MixinG3Inspect (ARMS: the G3's
// inspect with the owner's magazine release; its length is this node's MixinG3Inspect).
public final class ContinuumAnimations {
    private ContinuumAnimations() {
    }

    private static final Map<String, BiFunction<ItemStack, GunAnimation, BusAnimation>> NEXT = Map.ofEntries(
            Map.entry("hbm:gun_debug", NextGunAnimations.LAMBDA_DEBUG_ANIMS),
            Map.entry("hbm:gun_double_barrel", NextGunAnimations.LAMBDA_DOUBLE_BARREL_ANIMS),
            Map.entry("hbm:gun_double_barrel_sacred_dragon", NextGunAnimations.LAMBDA_DOUBLE_BARREL_ANIMS),
            Map.entry("hbm:gun_autoshotgun_heretic", NextGunAnimations.LAMBDA_SEXY_ANIMS),
            Map.entry("hbm:gun_maresleg", NextGunAnimations.LAMBDA_MARESLEG_ANIMS),
            Map.entry("hbm:gun_maresleg_akimbo", NextGunAnimations.LAMBDA_MARESLEG_SHORT_ANIMS),
            Map.entry("hbm:gun_maresleg_broken", NextGunAnimations.LAMBDA_MARESLEG_SHORT_ANIMS),
            Map.entry("hbm:gun_liberator", NextGunAnimations.LAMBDA_LIBERATOR_ANIMS),
            Map.entry("hbm:gun_spas12", NextGunAnimations.LAMBDA_SPAS_ANIMS),
            Map.entry("hbm:gun_autoshotgun", NextGunAnimations.LAMBDA_SHREDDER_ANIMS),
            Map.entry("hbm:gun_autoshotgun_shredder", NextGunAnimations.LAMBDA_SHREDDER_ANIMS),
            Map.entry("hbm:gun_autoshotgun_sexy", NextGunAnimations.LAMBDA_SEXY_ANIMS),
            Map.entry("hbm:gun_am180", NextGunAnimations.LAMBDA_AM180_ANIMS),
            Map.entry("hbm:gun_star_f", NextGunAnimations.LAMBDA_STAR_F_ANIMS),
            Map.entry("hbm:gun_star_f_akimbo", NextGunAnimations.LAMBDA_STAR_F_ANIMS),
            Map.entry("hbm:gun_light_revolver", NextGunAnimations.LAMBDA_ATLAS_ANIMS),
            Map.entry("hbm:gun_light_revolver_atlas", NextGunAnimations.LAMBDA_ATLAS_ANIMS),
            Map.entry("hbm:gun_light_revolver_dani", NextGunAnimations.LAMBDA_DANI_ANIMS),
            Map.entry("hbm:gun_aberrator", NextGunAnimations.LAMBDA_ABERRATOR),
            Map.entry("hbm:gun_aberrator_eott", NextGunAnimations.LAMBDA_ABERRATOR),
            Map.entry("hbm:gun_flaregun", NextGunAnimations.LAMBDA_FLAREGUN_ANIMS),
            Map.entry("hbm:gun_congolake", NextGunAnimations.LAMBDA_CONGOLAKE_ANIMS),
            Map.entry("hbm:gun_mk108", NextGunAnimations.LAMBDA_MK108_ANIMS),
            Map.entry("hbm:gun_henry", NextGunAnimations.LAMBDA_HENRY_ANIMS),
            Map.entry("hbm:gun_henry_lincoln", NextGunAnimations.LAMBDA_HENRY_ANIMS),
            Map.entry("hbm:gun_heavy_revolver", NextGunAnimations.LAMBDA_NOPIP_ANIMS),
            Map.entry("hbm:gun_heavy_revolver_lilmac", NextGunAnimations.LAMBDA_LILMAC_ANIMS),
            Map.entry("hbm:gun_heavy_revolver_protege", NextGunAnimations.LAMBDA_LILMAC_ANIMS),
            Map.entry("hbm:gun_hangman", NextGunAnimations.LAMBDA_HANGMAN_ANIMS),
            Map.entry("hbm:gun_amat", NextGunAnimations.LAMBDA_AMAT_ANIMS),
            Map.entry("hbm:gun_amat_subtlety", NextGunAnimations.LAMBDA_AMAT_ANIMS),
            Map.entry("hbm:gun_amat_penance", NextGunAnimations.LAMBDA_AMAT_ANIMS),
            Map.entry("hbm:gun_m2", NextGunAnimations.LAMBDA_M2_ANIMS),
            Map.entry("hbm:gun_g3", NextGunAnimations.LAMBDA_G3_ANIMS),
            Map.entry("hbm:gun_g3_zebra", NextGunAnimations.LAMBDA_G3_ANIMS),
            Map.entry("hbm:gun_stg77", NextGunAnimations.LAMBDA_STG77_ANIMS),
            Map.entry("hbm:gun_bolter", NextGunAnimations.LAMBDA_BOLTER_ANIMS),
            Map.entry("hbm:gun_carbine", NextGunAnimations.LAMBDA_CARBINE_ANIMS),
            Map.entry("hbm:gun_minigun", NextGunAnimations.LAMBDA_MINIGUN_ANIMS),
            Map.entry("hbm:gun_minigun_lacunae", NextGunAnimations.LAMBDA_MINIGUN_ANIMS),
            Map.entry("hbm:gun_minigun_dual", NextGunAnimations.LAMBDA_MINIGUN_ANIMS),
            Map.entry("hbm:gun_mas36", NextGunAnimations.LAMBDA_MAS36_ANIMS),
            Map.entry("hbm:gun_greasegun", NextGunAnimations.LAMBDA_GREASEGUN_ANIMS),
            Map.entry("hbm:gun_lag", NextGunAnimations.LAMBDA_LAG_ANIMS),
            Map.entry("hbm:gun_uzi", NextGunAnimations.LAMBDA_UZI_ANIMS),
            Map.entry("hbm:gun_uzi_akimbo", NextGunAnimations.LAMBDA_UZI_ANIMS),
            Map.entry("hbm:gun_tau", NextGunAnimations.LAMBDA_TAU_ANIMS),
            Map.entry("hbm:gun_coilgun", NextGunAnimations.LAMBDA_COILGUN_ANIMS),
            Map.entry("hbm:gun_n_i_4_n_i", NextGunAnimations.LAMBDA_NI4NI_ANIMS),
            Map.entry("hbm:gun_pepperbox", NextGunAnimations.LAMBDA_PEPPERBOX_ANIMS),
            Map.entry("hbm:gun_fatman", NextGunAnimations.LAMBDA_FATMAN_ANIMS),
            Map.entry("hbm:gun_drill", NextGunAnimations.LAMBDA_DRILL_ANIMS),
            Map.entry("hbm:gun_tesla_cannon", NextGunAnimations.LAMBDA_TESLA_ANIMS),
            Map.entry("hbm:gun_laser_pistol", NextGunAnimations.LAMBDA_LASER_PISTOL),
            Map.entry("hbm:gun_laser_pistol_pew_pew", NextGunAnimations.LAMBDA_LASER_PISTOL),
            Map.entry("hbm:gun_laser_pistol_morning_glory", NextGunAnimations.LAMBDA_LASER_PISTOL),
            Map.entry("hbm:gun_lasrifle", NextGunAnimations.LAMBDA_LASRIFLE),
            Map.entry("hbm:gun_flamer", NextGunAnimations.LAMBDA_FLAMER_ANIMS),
            Map.entry("hbm:gun_flamer_topaz", NextGunAnimations.LAMBDA_FLAMER_ANIMS),
            Map.entry("hbm:gun_flamer_daybreaker", NextGunAnimations.LAMBDA_FLAMER_ANIMS),
            Map.entry("hbm:gun_chemthrower", NextGunAnimations.LAMBDA_CHEMTHROWER_ANIMS),
            Map.entry("hbm:gun_folly", NextGunAnimations.LAMBDA_FOLLY_ANIMS),
            Map.entry("hbm:gun_panzerschreck", NextGunAnimations.LAMBDA_PANZERSCHRECK_ANIMS),
            Map.entry("hbm:gun_stinger", NextGunAnimations.LAMBDA_PANZERSCHRECK_ANIMS),
            Map.entry("hbm:gun_quadro", NextGunAnimations.LAMBDA_QUADRO_ANIMS),
            Map.entry("hbm:gun_missile_launcher", NextGunAnimations.LAMBDA_MISSILE_LAUNCHER_ANIMS),
            Map.entry("hbm:gun_charge_thrower", NextGunAnimations.LAMBDA_CT_ANIMS));

    // true: the gun has NEXT's animations here (get), whatever the cue
    public static boolean covers(ItemStack stack) {
        return NEXT.containsKey(id(stack));
    }

    // the gun's NEXT animation for the cue, null for none (as NEXT gives)
    public static BusAnimation get(ItemStack stack, GunAnimation type) {
        String id = id(stack);
        var animations = NEXT.get(id);
        if (animations == null) return null;
        if (Feature.MARESLEG.on()) {
            if (animations == NextGunAnimations.LAMBDA_SEXY_ANIMS) return sexy(stack, type);
            // the akimbo pair uses the same animations but cannot be aimed; leave it alone
            if (animations == NextGunAnimations.LAMBDA_MARESLEG_SHORT_ANIMS && !"hbm:gun_maresleg_akimbo".equals(id))
                return (type == GunAnimation.CYCLE || type == GunAnimation.CYCLE_DRY) && ItemGunBaseNT.getIsAiming(stack)
                        ? NextGunAnimations.LAMBDA_MARESLEG_ANIMS.apply(stack, type)
                        : animations.apply(stack, type);
        }
        if (Feature.ARMS.on() && animations == NextGunAnimations.LAMBDA_G3_ANIMS) return g3(stack, type);
        return animations.apply(stack, type);
    }

    private static String id(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    // main's MixinGunConfigAnims: Sexy / Heretic inspect, the bottle waiting below the screen while the left hand lets go
    public static final int SEXY_BOTTLE_WAIT_MS = 250, SEXY_HAND_BACK_MS = 250;

    private static BusAnimation sexy(ItemStack stack, GunAnimation type) {
        if (type != GunAnimation.INSPECT) return NextGunAnimations.LAMBDA_SEXY_ANIMS.apply(stack, type);
        int w = SEXY_BOTTLE_WAIT_MS;
        return new BusAnimation()
                .addBus("BOTTLE", new BusAnimationSequence()
                        .setPos(8.0, -11.0, -2.0)
                        .hold(w)
                        .addPos(6.0, -4.0, -2.0, 500, IType.SIN_DOWN)
                        .addPos(3.0, -3.0, -5.0, 500, IType.SIN_FULL)
                        .addPos(3.0, -2.0, -5.0, 1000)
                        .addPos(4.0, -6.0, -2.0, 750, IType.SIN_FULL)
                        .addPos(6.0, -11.0, -2.0, 500, IType.SIN_UP)
                        .hold(SEXY_HAND_BACK_MS))
                .addBus("SIP", new BusAnimationSequence()
                        .setPos(25.0, 0.0, 0.0)
                        .hold(500 + w)
                        .addPos(-90.0, 0.0, 0.0, 500, IType.SIN_FULL)
                        .addPos(-110.0, 0.0, 0.0, 1000)
                        .addPos(25.0, 0.0, 0.0, 750, IType.SIN_FULL));
    }

    // main's MixinG3Inspect: the owner's magazine release (armInspectReleaseMs), negative or unset for NEXT's inspect
    public static float g3Release(ItemStack stack) {
        String setting = Grips.overrides(id(stack)).get("armInspectReleaseMs");
        try {
            return Float.parseFloat(setting);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static BusAnimation g3(ItemStack stack, GunAnimation type) {
        var animation = NextGunAnimations.LAMBDA_G3_ANIMS.apply(stack, type);
        if (type != GunAnimation.INSPECT || animation == null) return animation;
        float value = g3Release(stack);
        if (!Float.isFinite(value) || value < 0) return animation;
        int r = MagazineInspect.release(value), pull = (r - MagazineInspect.PREPARE) / 2;
        return animation
                .addBus("MAG", new BusAnimationSequence().hold(MagazineInspect.PREPARE)
                        .addPos(0, -1, 0, pull)
                        .addPos(2, -1, 0, r - MagazineInspect.PREPARE - pull)
                        .addPos(2, 8, 0, 350, IType.SIN_DOWN)
                        .addPos(2, -1, 0, 350, IType.SIN_UP)
                        .addPos(2, -1, 0, 50).hold(100)
                        .addPos(0, -1, 0, 150, IType.SIN_FULL)
                        .addPos(0, 0, 0, 150, IType.SIN_UP).hold(200))
                .addBus("SPEEN", new BusAnimationSequence().hold(r).addPos(0, 360, 360, 700))
                .addBus("LIFT", new BusAnimationSequence().hold(r + 1150)
                        .addPos(-2, 0, 0, 100, IType.SIN_DOWN).addPos(0, 0, 0, 100, IType.SIN_FULL));
    }
}
