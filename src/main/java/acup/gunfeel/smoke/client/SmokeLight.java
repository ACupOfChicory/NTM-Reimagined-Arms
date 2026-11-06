package acup.gunfeel.smoke.client;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** Client: how lit smoke in a cell is, 0..1 (the brighter of block and sky light, the sky darkened at night). */
public final class SmokeLight {
    private SmokeLight() {
    }

    public static float brightness(Level level, BlockPos pos) {
        int v = level.getMaxLocalRawBrightness(pos);
        float b = Math.clamp(v / 15F, 0F, 1F);
        // the vanilla curve of light level to brightness, with no dimension ambient light
        return b / (4F - 3F * b);
    }
}
