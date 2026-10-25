package acup.gunfeel.compat;

import java.util.List;

// The mod's systems and what each takes from NTM: its mixins into NTM (ntmgunfeel-hbm.mixins.json) and the mod classes
// that call NTM directly. If NTM is not a tested version and one of them does not fit it, the whole system is turned
// off (see Compat); a mixin shared by systems turns all of them off. A class calling NTM that is in no list belongs
// to CORE, and CORE off turns everything off. The tests check every NTM mixin is here, and the lang keys
// ntmgunfeel.feature.<id> in ru_ru and en_us.
public enum Feature {
    CORE("core", List.of(), List.of("GunFeel", "GunFeelMod", "Config", "GunDump", "BlockDump")),
    HOLD_AIM("hold_aim", List.of("MixinHoldAim"), List.of("ReaimCommand", "ReaimTimes")),
    BULLETS("bullets", List.of("MixinBulletServer", "MixinThrowableHit", "MixinBeamModelQuery"),
            List.of("BulletServer", "PassableClip", "Cells", "Structures", "BulletFx", "BulletRules")),
    RICOCHET_SOUND("ricochet_sound", List.of("MixinRicochetSound"), List.of()),
    SPREAD("spread", List.of("MixinLegoSpread"), List.of("Spread")),
    AMMO_CHOICE("ammo_choice", List.of("MixinAmmoLock", "MixinAmmoLockBelt", "MixinGunKeysRadial"),
            List.of("AmmoLock", "AmmoLockKey", "RadialMenu", "AmmoStats", "AmmoSelectPayload", "AmmoLockPayload")),
    RECOIL("recoil", List.of("MixinItemGunBaseNT", "MixinHbmAnimations"), List.of("CameraRecoil", "ModelRecoil")),
    ARMS("arms", List.of("MixinItemRenderWeaponBase", "MixinWholeModelGuns", "MixinSexyBottle", "MixinHbmAnimations"),
            List.of("Arms", "Grips", "GunGeometry", "GripCommand")),
    HUD("hud", List.of("MixinClientRegistry"), List.of("Hud", "Silhouette", "GunGeometry")),
    INSTANT_SWITCH("instant_switch", List.of("MixinHbmAnimationPayload"), List.of("InstantSwitch")),
    TRACERS("tracers", List.of("MixinLegoClient", "MixinRenderBulletMK4"), List.of("Tracers", "ShotPayload")),
    MACHINE_MESH("machine_mesh", List.of("MixinHbmVisual"), List.of("MeshShapes", "RenderedMesh")),
    MARESLEG("maresleg", List.of("MixinGunConfigAnims"), List.of());

    public final String id;
    // simple names: mixins in acup.gunfeel.mixin, classes in acup.gunfeel
    public final List<String> mixins, classes;
    private volatile boolean on = true;

    Feature(String id, List<String> mixins, List<String> classes) {
        this.id = id;
        this.mixins = mixins;
        this.classes = classes;
    }

    public boolean on() {
        return on && (this == CORE || CORE.on);
    }

    void set(boolean on) {
        this.on = on;
    }

    public String langKey() {
        return "ntmgunfeel.feature." + id;
    }

    public static Feature byId(String id) {
        for (Feature f : values()) if (f.id.equals(id) || f.name().equals(id)) return f;
        return null;
    }
}
