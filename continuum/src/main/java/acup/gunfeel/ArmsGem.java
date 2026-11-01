package acup.gunfeel;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

import com.hbm.client.render.weapon.WeaponPose;
import com.hbm.client.render.weapon.WeaponRenderer;
import com.mojang.blaze3d.vertex.PoseStack;

import acup.gunfeel.obj.HFRWavefrontObject;
import dev.golem.core.render.gem.Gem;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

// port/continuum: what NTM NEXT's ItemRenderWeaponBase.submitPart tells Arms (src/main/java MixinItemRenderWeaponBase),
// on NTM Continuum's WeaponRenderer. Continuum declares a gun once as a Golem Core Gem model and replays it; the parts
// and their poses are read back with Gem.walk, the same walk and pose-stack calls the draw made, on the renderer's own
// WeaponPose (its buses as this frame's draw read them).
public final class ArmsGem {
    // The arms are drawn on every gun that animates with NTM NEXT's buses here (ContinuumAnimations): the arm settings
    // are made on them. -Dntmgunfeel.armsAll (gradle -Pntmgunfeel.armsAll=true) draws them on the rest too.
    private static final boolean ALL = Boolean.getBoolean("ntmgunfeel.armsAll");
    private static final Map<WeaponRenderer, HFRWavefrontObject> GUN_MODEL = new IdentityHashMap<>();

    private ArmsGem() {
    }

    // NTM NEXT's paired guns (their renderers' isAkimbo): 26.2 draws no arms on them (MixinItemRenderWeaponBase)
    private static final java.util.Set<String> AKIMBO = java.util.Set.of("hbm:gun_maresleg_akimbo", "hbm:gun_star_f_akimbo",
            "hbm:gun_light_revolver_dani", "hbm:gun_aberrator_eott", "hbm:gun_minigun_dual", "hbm:gun_uzi_akimbo");

    // the arms are drawn on this gun
    public static boolean fits(ItemStack stack) {
        return ALL || ContinuumAnimations.covers(stack)
                && !AKIMBO.contains(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
    }

    // the frame renderFirstPerson is handed (MixinWeaponRenderer, right before the call)
    private static final PoseStack HANDED = new PoseStack();

    public static void handed(PoseStack pose) {
        HANDED.last().set(pose.last());
    }

    // Arms.part for every OBJ group the gun's model draws this frame, in draw order, with the pose it was drawn at,
    // walked from the frame renderFirstPerson was handed (handed): the pose stack after the call is not that frame,
    // as Continuum's WeaponEffects moves it for the muzzle flash and smoke without a push (Maresleg: its 0.375 scale).
    public static void parts(WeaponRenderer renderer, WeaponPose state) {
        HFRWavefrontObject gun = GUN_MODEL.computeIfAbsent(renderer, r -> gunModel(r, state));
        if (gun == null) return;
        PoseStack copy = new PoseStack();
        copy.last().set(HANDED.last());
        boolean unloaded = emptyChargeThrower(state);
        Gem.walk(renderer.gem(), state, copy, (part, geometry, at) -> {
            HFRWavefrontObject.Selection s = HFRWavefrontObject.select(geometry);
            if (s == null || s.model() != gun) return;
            for (int group : s.groups()) if (!unloaded || !CT_AMMO.contains(gun.groups[group].name)) Arms.part(at, gun, group);
        });
    }

    // The charge thrower's ammo parts. Continuum draws the loaded type's part at rest even with the magazine empty,
    // NTM NEXT (ItemRenderChargeThrower) only with a round in it or while the AMMO bus plays. Told to Arms, the hook
    // (the model's biggest part) became the gun's body, and in the reload, where AMMO moves it, the arms went with it.
    private static final java.util.Set<String> CT_AMMO = java.util.Set.of("Hook", "Mortar", "Oomph", "Rocket");

    private static boolean emptyChargeThrower(WeaponPose state) {
        ItemStack stack = state.stack;
        if (stack == null || !(stack.getItem() instanceof com.hbm.items.weapon.sedna.ItemGunBaseNT gun)
                || !"hbm:gun_charge_thrower".equals(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())) return false;
        var mag = gun.getPrimaryMagazine(stack, 0);
        return mag != null && mag.getAmount(stack, null) <= 0 && !state.has("AMMO");
    }

    // The gun's own model: of the OBJ models the renderer draws (Sexy also draws its whiskey bottle, first) the one
    // with the most parts drawn. Arms takes the first model it is told about, as NTM NEXT's renderers draw the gun
    // with submitPart and the rest otherwise.
    private static HFRWavefrontObject gunModel(WeaponRenderer renderer, WeaponPose state) {
        Map<HFRWavefrontObject, Integer> count = new HashMap<>();
        HFRWavefrontObject[] best = {null};
        Gem.walk(renderer.gem(), state, new PoseStack(), (part, geometry, at) -> {
            HFRWavefrontObject.Selection s = HFRWavefrontObject.select(geometry);
            if (s == null) return;
            int n = count.merge(s.model(), s.groups().length, Integer::sum);
            if (best[0] == null || n > count.get(best[0])) best[0] = s.model();
        });
        if (best[0] != null) GunFeel.LOGGER.info("[port] arms: {} draws {} ({} groups)", renderer.getClass().getSimpleName(),
                best[0].source, best[0].groups.length);
        return best[0];
    }

    public static void clear() {
        GUN_MODEL.clear();
    }
}
