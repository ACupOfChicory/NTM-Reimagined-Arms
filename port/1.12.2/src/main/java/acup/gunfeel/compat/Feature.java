package acup.gunfeel.compat;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

// port: 26.2's Feature (compat/Feature.java, e284339) with the port's mixins (LateMixins) and classes. The mod's systems
// and what each takes from NTM: its mixins into NTM and the mod classes that call NTM directly. If NTM is not a tested
// version and one of them does not fit it, the whole system is turned off (see Compat); a mixin shared by systems turns
// all of them off. A class calling NTM that is in no list belongs to CORE, and CORE off turns everything off. The tests
// check every mixin into NTM is here, and the lang keys ntmgunfeel.feature.<id>.
// Not in 26.2: TRACE, the dev trace scenario's clock (MixinClock). MACHINE_MESH has no mixins of its own:
// it reads NTM models through ARMS's MixinWavefrontObject(VBO).
// The water classes (acup.gunfeel.water) are in BULLETS and TRACERS, both of which call them (26.2 leaves them to
// CORE): an NTM without what they read turns those two off, not the whole mod.
public enum Feature {
    CORE("core", list(), list("GunFeel", "GunFeelMod", "GunFeelClient", "Config", "Net", "PortPaths", "LateMixins")),
    MODEL_HITBOXES("model_hitboxes", list("MixinHeadDamage"),
            list("ModelHits", "ModelHitHooks", "ModelHitClient", "ModelHitNet", "ModelAABB", "ModelMesh", "ModelCapture", "ModelObjParts", "HeadDamage",
                    "HeadProfiles", "ModelHeads")),
    BULLETS("bullets", list("MixinBulletServer", "MixinThrowableClip", "MixinLegacyBullet", "MixinOldBulletBase", "MixinOldBulletWater", "ThrowableAccess", "MixinWaterBullet", "MixinWaterShooter", "MixinWaterImpact", "MixinWaterFire", "MixinWaterFireFlamer", "MixinWaterBlast", "MixinWaterBlastProcessor", "MixinBeamHits",
            "MixinBeamBlocks", "MixinBeamTurretSight", "MixinBeamTauon", "MixinBeamMaxwell", "MixinBeamTesla", "TurretMaxwellAccess", "TurretAccess", "MixinExplosiveBullet", "MixinExplosionContext",
            "MixinExplosiveProcessor", "MixinExplosiveDirect"),
            list("BulletServer", "LegacyBullets", "PassableClip", "BulletFx", "BulletRules", "FenceCells", "ModelCells", "BlockRay", "PierceSteps", "ExplosiveDamage", "ExplosiveAmmo", "DoorCells",
                    "WaterClass", "WaterPath", "WaterTick", "WaterBullet", "WaterEvents", "WaterSettings", "WaterMuffle", "WaterImpact", "WaterBlast", "OldBullets", "OldBlast", "BeamServer", "BeamTurrets")),
    HOLD_AIM("hold_aim", list("MixinHoldAim", "MixinAimButton"), list("ReaimCommand", "AimButton")),
    SPREAD("spread", list("MixinLegoSpread"), list("Spread")),
    RICOCHET_SOUND("ricochet_sound", list("MixinRicochetSound"), list()),
    AMMO_CHOICE("ammo_choice", list("MixinAmmoLock", "MixinAmmoLockBelt", "MixinGunKeysRadial"),
            list("AmmoLock", "AmmoLockKey", "AmmoLogic", "AmmoNet", "RadialMenu", "AmmoStats", "AmmoSelectPayload", "AmmoLockPayload")),
    RECOIL("recoil", list("MixinModEventHandlerClient"), list("CameraRecoil", "ModelRecoil", "RecoilPresets", "ReaimTimes")),
    ARMS("arms", list("MixinItemRenderWeaponBase", "MixinHbmAnimations", "MixinWavefrontObject", "MixinVaoModel", "MixinGroupHandle", "MixinG3Inspect",
            "MixinHbmAnimationPacket"), list("Arms", "ArmAnims", "Grips", "GunGeometry", "WeaponSway", "WalkSprint", "PistolHold")),
    HUD("hud", list("MixinGunHud"), list("Hud", "HudEvents", "HudFormat", "Silhouette", "GunGeometry")),
    INSTANT_SWITCH("instant_switch", list("MixinHbmAnimationPacket"), list("InstantSwitch", "SwitchPrediction")),
    CLICK_AIM("click_aim", list("MixinClickAim"), list("ClickAim")),
    MACHINE_MESH("machine_mesh", list(), list("MachineMesh")),
    TRACERS("tracers", list("MixinLegoClient", "MixinRenderBulletMK4", "MixinSmokeNodes", "MixinMuzzleFlash"), list("Tracers", "TracerNet", "TracerPath", "WaterClass", "WaterPath", "WaterTick", "WaterBullet", "WaterEvents", "WaterSettings", "WaterMuffle", "WaterFx", "SunkBullets", "OldBullets")),
    MARESLEG("maresleg", list("MixinGunConfigAnims"), list()),
    SPRINT_FIRE("sprint_fire", list("MixinSprintFireClick", "MixinSprintFireDecider", "MixinSprintFireGun"), list("SprintFire")),
    SPECTATOR("spectator", list("MixinSpectatorGun", "MixinSpectatorOldGun", "MixinSpectatorKeys", "MixinSpectatorHud"), list("SpectatorGuns", "SpectatorCheck", "SpectatorScenario")),
    // port only: NTM CE's keys pressed on the server's thread (an NTM CE bug, see ServerKeys)
    SERVER_KEYS("server_keys", list("MixinKeysOnServerThread"), list("ServerKeys")),
    TRACE("trace", list("MixinClock"), list("TraceScenario", "TraceClient", "TraceHooks", "ShootScenario", "LookScenario", "ShapesScenario", "WalkSprintScenario"));

    public final String id;
    // simple names: mixins in acup.gunfeel.mixins, classes in acup.gunfeel
    public final List<String> mixins, classes;
    private volatile boolean on = true;

    Feature(String id, List<String> mixins, List<String> classes) {
        this.id = id;
        this.mixins = mixins;
        this.classes = classes;
    }

    private static List<String> list(String... names) {
        return Collections.unmodifiableList(Arrays.asList(names));
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
