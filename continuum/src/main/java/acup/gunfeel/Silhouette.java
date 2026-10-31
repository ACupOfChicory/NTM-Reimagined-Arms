package acup.gunfeel;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

// port/continuum: a stand-in for Silhouette, which rasterises the gun's GunGeometry (NTM NEXT's GroupObject; on
// Continuum it comes from WavefrontObj with the arms). Until then no silhouette: the HUD draws the gun's item icon, as
// main does for a gun not yet drawn in first person.
public final class Silhouette {
    public static final int W = 192, H = 64;

    private Silhouette() {
    }

    public static Identifier get(Item item) {
        return null;
    }
}
