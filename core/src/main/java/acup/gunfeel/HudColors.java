package acup.gunfeel;

// The corner HUD's colours worked out from its numbers (Hud draws them).
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

    // the heat bar: yellow up to where the shot starts to spread, to red at the top
    public static int heatColor(float heat) {
        float t = acup.gunfeel.heat.OverheatBase.ramp(heat, acup.gunfeel.heat.OverheatBase.SPREAD_FROM, 1F);
        int gr = (int) (208 - 176 * t), b = (int) (64 - 32 * t);
        return 0xFF000000 | 0xFF << 16 | gr << 8 | b;
    }
}
