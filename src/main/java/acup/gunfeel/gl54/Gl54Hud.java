package acup.gunfeel.gl54;

import com.hbm.items.weapon.sedna.ItemGunBaseNT.GunState;
import com.hbm.render.anim.AnimationEnums.GunAnimation;

// What the corner HUD shows for the GL-54 (Hud draws it in its own layout, the same block as the other guns'), worked out
// from the gun's data, without drawing: the numbers, the colour of the number, the order dots, LOW AMMO, the reload bar.
// Decided by the owner (2026-11-07, hud-live): the number is the barrel's grenade plus the magazine (009), in the colour of
// the tip of the grenade that fires next; the icon is the magazine in the gun; the reserve is the grenades of the kind R
// would load (0021); a mixed magazine has a strip of coloured dots of its order; reloading shows as a bar instead of the
// durability; at 2 or fewer the number does not go red but LOW AMMO blinks under the silhouette (this gun only).
public final class Gl54Hud {
    public static final int BAR_COLOR = 0xFFFFB03E, LOW_COLOR = 0xFFFF5555;
    // LOW AMMO blinks: shown for 400 ms, hidden for 400 ms
    public static final int BLINK_MS = 400;

    private Gl54Hud() {
    }

    // everything the HUD draws that depends on the gun: big = "009", small = "0021", number colour, whether the number is
    // dimmed (a reload is on), the bar's fill (< 0 for the durability bar), the dots' colours (empty = none), LOW AMMO
    public record Model(String big, String small, int color, boolean dim, float bar, int[] dots, boolean lowAmmo) {
    }

    public static Model model(GunMag gun, int reserve, float reloadFrac) {
        boolean reloading = reloadFrac >= 0F;
        int count = gun.count();
        String big = String.format("%03d", Math.min(count, 999));
        String small = String.format("%04d", Math.min(Math.max(reserve, 0), 9999));
        return new Model(big, small, MagLogic.numberColor(gun), reloading, reloadFrac, dots(gun), MagLogic.low(count, reloading));
    }

    // the order of the grenades behind the barrel's, when the gun holds more than one colour (a mixed magazine, or a
    // barrel left from another kind after a change); none for a single kind
    public static int[] dots(GunMag gun) {
        boolean differs = false;
        String first = gun.hasChamber() ? gun.chamber() : gun.mag().isEmpty() ? "" : gun.mag().get(0);
        for (String r : gun.mag()) if (!r.equals(first)) differs = true;
        if (!differs) return new int[0];
        int[] out = new int[gun.mag().size()];
        for (int i = 0; i < out.length; i++) out[i] = GrenadeKind.argbOf(gun.mag().get(i));
        return out;
    }

    // how far a reload is, 0..1, from the animation the server last started (HbmAnimations cue and its age in ticks) and the
    // gun's state; -1 for none. A reload is begin + end (+ post when the gun was empty); the cue RELOAD runs begin, RELOAD_END
    // the end and the post.
    public static float reloadFrac(GunAnimation cue, int ticks, GunState state, boolean wasEmpty) {
        int total = Gl54Gun.RELOAD_BEGIN + Gl54Gun.RELOAD_END + (wasEmpty ? Gl54Gun.RELOAD_EMPTY_POST : 0);
        float t;
        if (cue == GunAnimation.RELOAD && state == GunState.RELOADING) t = ticks;
        else if (cue == GunAnimation.RELOAD_END && state == GunState.DRAWING) t = Gl54Gun.RELOAD_BEGIN + ticks;
        else return -1F;
        return Math.min(Math.max(t / total, 0F), 1F) >= 1F ? -1F : Math.max(t / total, 0F);
    }

    // is the blinking LOW AMMO in its shown half at that time
    public static boolean blinkOn(long millis) {
        return Math.floorDiv(millis, BLINK_MS) % 2 == 0;
    }
}
