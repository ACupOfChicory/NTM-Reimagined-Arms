// SPDX-License-Identifier: GPL-3.0-only
// Copied from TaCZ (Timeless and Classics Zero), MCModderAnchor/TACZ and contributors, GPL-3.0.
// https://github.com/MCModderAnchor/TACZ, revision 19f14c8, src/main/java/com/tacz/guns/mixin/client/MouseHandlerMixin.java:
// getCrawlPitch. Changes: the package and class; it holds the view only while the player lies down on the prone key
// (TaCZ: in any crawling pose, a trapdoor's too), within the mod's server keys proneMaxUp and proneMaxDown (TaCZ's
// CrawlMaxUpAngle and CrawlMaxDownAngle); the mouse handler's mixin is the mod's MixinMouseTurn.
package acup.gunfeel.tacz;

import acup.gunfeel.Prone;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

public final class CrawlPitch {
    private CrawlPitch() {
    }

    public static double getCrawlPitch(LocalPlayer player, double pitch) {
        boolean crawling = Prone.localLying() && Prone.lying(player);
        if (!crawling) {
            return pitch;
        }
        double minPitch = -Prone.maxUp();
        double maxPitch = Prone.maxDown();
        double currentPitch = player.getXRot();
        double minInput = Math.min(0, (minPitch - currentPitch) / 0.15F);
        double maxInput = Math.max(0, (maxPitch - currentPitch) / 0.15F);
        return Mth.clamp(pitch, minInput, maxInput);
    }
}
