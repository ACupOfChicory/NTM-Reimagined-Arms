package acup.gunfeel.crosshair;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.Grips;
import acup.gunfeel.GunWeight;

// Which style a gun's crosshair has, by the class the mod already gives it, as TaCZ picks one by the gun's type:
// pistols and revolvers a corner, SMGs brackets, shotguns a square, launchers and the heaviest guns a ruler, the rest
// (rifles, snipers, machine guns) a cross.
//  - The class: the gun's kickPreset (ntmgunfeel-guns.properties, the player's own file over it), else its kind in
//    ntmgunfeel-gun-weight.txt. A gun of neither (there "none": the fire extinguisher, the power armour's hands; or one
//    the file does not know yet) keeps NTM's crosshair: null.
//  - Kinds with no TaCZ type of their own: energy guns and specials go by weight (the super-heavy ones a ruler: the
//    Tesla cannon, the Folly), the light energy guns are pistols; flamethrowers fire a stream, as machine guns: a cross.
public final class CrosshairClass {
    private CrosshairClass() {
    }

    /** the style of the gun with this item id ({@code hbm:gun_g3}), or null for NTM's own crosshair */
    public static @Nullable CrosshairStyle of(String id) {
        String preset = Grips.gunValue(id, "kickPreset");
        GunWeight.Gun kind = GunWeight.gun(id);
        return of(preset, kind);
    }

    static @Nullable CrosshairStyle of(@Nullable String preset, GunWeight.@Nullable Gun kind) {
        CrosshairStyle s = preset == null ? null : byPreset(preset.strip());
        if (s != null) return s;
        if (kind == null) return null;
        return switch (kind.kind()) {
            case "none" -> null;
            case "pistol", "revolver" -> CrosshairStyle.CORNER;
            case "smg" -> CrosshairStyle.BRACKETS;
            case "shotgun" -> CrosshairStyle.SQUARE;
            case "grenade_launcher", "rocket_launcher" -> CrosshairStyle.RULER;
            case "energy", "special" -> kind.weight() == GunWeight.Weight.SUPER_HEAVY ? CrosshairStyle.RULER
                    : kind.weight() == GunWeight.Weight.LIGHT && kind.kind().equals("energy") ? CrosshairStyle.CORNER : CrosshairStyle.CROSS;
            default -> CrosshairStyle.CROSS;
        };
    }

    // the kick presets (RecoilPresets): pistol, revolver, smg, assault, rifle, lever, levershot, shotgun, sniper, mg,
    // launcher; another one (a player's own) says nothing, the kind decides
    private static @Nullable CrosshairStyle byPreset(String preset) {
        return switch (preset) {
            case "pistol", "revolver" -> CrosshairStyle.CORNER;
            case "smg" -> CrosshairStyle.BRACKETS;
            case "shotgun", "levershot" -> CrosshairStyle.SQUARE;
            case "launcher" -> CrosshairStyle.RULER;
            case "assault", "rifle", "lever", "sniper", "mg" -> CrosshairStyle.CROSS;
            default -> null;
        };
    }
}
