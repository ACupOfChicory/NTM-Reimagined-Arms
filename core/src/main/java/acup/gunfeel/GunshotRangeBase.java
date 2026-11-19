package acup.gunfeel;

// A shot's range by the name of its sound (GunshotRange in 26.2: by the game's sound event, cached, and the volume)
public class GunshotRangeBase {
    GunshotRangeBase() {
    }

    /** NTM's range of a shot at volume 1, blocks */
    public static final float BASE = 16F;

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
}
