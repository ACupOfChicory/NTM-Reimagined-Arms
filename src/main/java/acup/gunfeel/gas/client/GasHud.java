package acup.gunfeel.gas.client;

import acup.gunfeel.gas.GasKind;
import acup.gunfeel.gas.GasRules;
import acup.gunfeel.gas.GasState;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Client: a small mark for each gas that is working on the player, in the corner HUD's manner (white text with a shadow
 * and a two-pixel bar under it): CL for chlorine, MU for mustard (the colour of the gas, the bar is the dose against the
 * dose that hurts) and K for the acid's corrosion of the armour (the bar is K against its cap). Phosgene has no mark: its
 * smell is the only warning. Bottom left, above the hotbar's edge, so it does not meet the gun's block on the right.
 */
public final class GasHud {
    private GasHud() {
    }

    /** One mark: its label, its colour, how full its bar is (0 to 1; no mark at 0). */
    public record Mark(String label, int argb, float fill) {
    }

    /** The marks for the doses, in a fixed order: chlorine, mustard, acid's K. */
    public static java.util.List<Mark> marks(float chlorine, float mustard, float k) {
        java.util.List<Mark> out = new java.util.ArrayList<>();
        if (chlorine >= GasRules.CHLORINE_COUGH_DOSE) out.add(new Mark("CL", 0xFF000000 | GasKind.CHLORINE.rgb, Math.min(1F, chlorine / (GasRules.CHLORINE_DAMAGE_DOSE * 3F))));
        if (mustard >= 0.2F) out.add(new Mark("MU", 0xFF000000 | GasKind.MUSTARD.rgb, Math.min(1F, mustard / GasRules.MUSTARD_L3)));
        if (k >= 0.01F) out.add(new Mark("K " + Math.round(k * 100F) + "%", 0xFF000000 | GasKind.ACID.rgb, Math.min(1F, k / GasRules.ACID_K_MAX)));
        return out;
    }

    public static void layer(GuiGraphicsExtractor g, DeltaTracker delta) {
        var marks = marks(GasClient.dose(GasState.CHLORINE), GasClient.dose(GasState.MUSTARD), GasClient.k());
        if (marks.isEmpty() || Minecraft.getInstance().gui.hud.isHidden()) return;
        Font font = Minecraft.getInstance().font;
        int x = 8, y = g.guiHeight() - 30;
        for (Mark m : marks) {
            int w = Math.max(22, font.width(m.label));
            g.text(font, m.label, x, y, m.argb, true);
            g.fill(x, y + 11, x + w, y + 13, 0x80000000);
            g.fill(x, y + 11, x + Math.round(w * m.fill), y + 13, m.argb);
            x += w + 6;
        }
    }
}
