package acup.gunfeel;

import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EnumPlayerModelParts;

// port: 26.2 takes the arms' width from the skin's model type (PlayerSkin.model). Vanilla 1.12.2 has it too:
// AbstractClientPlayer.getSkinType() is "slim" (Alex) or "default" (Steve), and RenderManager draws the player with
// ModelPlayer(0, smallArms) by it. Its skins are always 64 x 64 (ImageBufferDownload makes old 64 x 32 ones square):
// every arm has its own texture and sleeve, as ModelPlayer.
public final class PlayerSkins {
    private PlayerSkins() {}

    // the player's skin has slim arms; a player that is not a client one (no skin) has classic arms
    public static boolean slim(EntityPlayer player) {
        return player instanceof AbstractClientPlayer && slim(((AbstractClientPlayer) player).getSkinType());
    }

    // getSkinType()'s word: "slim" for slim arms, anything else ("default") classic ones, as RenderManager's skinMap
    static boolean slim(String skinType) {
        return "slim".equals(skinType);
    }

    // port: 26.2 draws a first-person sleeve only when the player shows it in Skin Customization (isModelPartShown);
    // 1.12.2 has the same setting as isWearing, which RenderPlayer reads for the third-person sleeves. No player: shown
    public static boolean sleeve(EntityPlayer player, boolean right) {
        return player == null || player.isWearing(sleevePart(right));
    }

    static EnumPlayerModelParts sleevePart(boolean right) {
        return right ? EnumPlayerModelParts.RIGHT_SLEEVE : EnumPlayerModelParts.LEFT_SLEEVE;
    }

    // the log says once what the first-person arms are drawn from, and again when it changes
    private static String said = "";

    static void say(boolean slim) {
        String now = slim ? "slim" : "classic";
        if (now.equals(said)) return;
        said = now;
        GunFeel.LOGGER.info("First-person arms: {} (the skin's model type)", now);
    }
}
