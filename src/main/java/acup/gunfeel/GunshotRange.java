package acup.gunfeel;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.sounds.SoundEvent;

// How far a gunshot carries, blocks, by the gun's fire sound. NTM plays every shot at volume 1: 16 blocks for a player
// and for an AI mob alike (SoundEvent.getRange), a pistol as far as a sniper rifle, no shot past AiBots.LOUD. A sound
// over volume 1 is no louder close by, only heard further (16 blocks a unit, linearly down to nothing), and the server
// sends it to the players that far: the shot's volume made range / 16 (Receiver.getFireVolume, MixinGunshotRange) is
// heard that far by players and AI mobs both. The server's gunshotRange times every range over 16 (0: NTM's 16 for all).
public final class GunshotRange {
    private GunshotRange() {
    }

    /** NTM's range of a shot at volume 1, blocks */
    public static final float BASE = 16F;

    private static final Map<SoundEvent, Float> BY_SOUND = new ConcurrentHashMap<>();

    /**
     * The range of a fire sound by its name (hbm's weapon.fire.* and the older weapon.*_shoot), blocks; BASE for one not
     * a shot's or not listed (melee, loops, an extinguisher, the joke shot NTM plays at volume 100). The AI stand lists
     * every NTM gun's (AiCheck: "gunshots heard"): a new gun's sound not here shows at 16.
     */
    public static float of(String path) {
        return switch (path) {
            // a silencer's point is not to be heard
            case "weapon.fire.silenced", "weapon.silencer_shoot" -> 16F;
            // energy weapons hum, they do not bang: just loud (AiBots.LOUD)
            case "weapon.fire.laser", "weapon.fire.laser_pistol", "weapon.fire.tesla", "weapon.fire.tau", "weapon.fire.tau_release",
                    "weapon.fire.disintegration", "weapon.coilgun_shoot" -> 24F;
            // pistols and submachine guns: the next house, the next street
            case "weapon.fire.pistol", "weapon.fire.pistol_light", "weapon.fire.uzi", "weapon.fire.grease_gun", "weapon.fire.aberrator" -> 48F;
            // shotguns, black powder and heavy revolvers; launchers (their blast is a sound of its own)
            case "weapon.fire.shotgun", "weapon.fire.shotgun_alt", "weapon.fire.shotgun_auto", "weapon.fire.black_powder",
                    "weapon.shotgun_shoot", "weapon.44_shoot", "weapon.fire.gl", "weapon.fire.gl_charge", "weapon.fire.fatman",
                    "weapon.fire.lockon", "weapon.rpg_shoot" -> 64F;
            // rifles: the AI's fights at 20 to 60 blocks heard whole, not across half the map
            case "weapon.fire.rifle", "weapon.fire.assault", "weapon.fire.underbarrel" -> 80F;
            // machine guns and heavy rifles: as far as an AI mob sees (96), they give themselves away as far as they show
            case "weapon.fire.rifle_heavy", "weapon.fire.mk108", "weapon.fire.laser_gatling", "weapon.fire.vstar", "weapon.cal_shoot",
                    "turret.chekhov_fire" -> 96F;
            // anti-materiel rifles: the loudest
            case "weapon.fire.amat" -> 128F;
            default -> BASE;
        };
    }

    /** the range of this fire sound, blocks, cached (a shot asks every time) */
    public static float of(SoundEvent sound) {
        return BY_SOUND.computeIfAbsent(sound, s -> of(s.location().getPath()));
    }

    /**
     * A shot's volume for its range: NTM's volume times range / 16, the part over 16 times the server's gunshotRange.
     * A volume over 1 from NTM itself (its joke shot at 100) is left as it is; so is a sound not listed.
     */
    public static float volume(SoundEvent sound, float volume, float scale) {
        float range = of(sound);
        if (volume > 1F || range <= BASE) return volume;
        float r = BASE + (range - BASE) * Math.max(0F, scale);
        return volume * r / BASE;
    }

    /** the server's gunshotRange: 1 the table, 0 NTM's 16 for all */
    public static float scale() {
        return Math.max(0F, ReaimTimes.number("gunshotRange", 1F));
    }
}
