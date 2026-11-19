package acup.gunfeel;

// The corner HUD's colours worked out from its numbers (Hud draws them). The heat bar's stays in Hud for now: it is
// counted on Overheat's ramp, which is the game's.
public final class HudColors {
    private HudColors() {
    }

    // an ARGB colour at 0.4 of its alpha
    public static int faint(int argb) {
        return ((int) ((argb >>> 24) * 0.4F) << 24) | (argb & 0xFFFFFF);
    }

    // the durability bar: green at full, through yellow at half, to red at none
    public static int durabilityColor(float health) {
        int r = (int) (255 * GameMath.clamp(2F - 2F * health, 0F, 1F));
        int gr = (int) (255 * GameMath.clamp(2F * health, 0F, 1F));
        return 0xFF000000 | r << 16 | gr << 8 | 0x40;
    }
}
