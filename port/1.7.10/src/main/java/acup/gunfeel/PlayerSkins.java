package acup.gunfeel;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.lwjgl.opengl.GL11;

import net.minecraft.entity.player.EntityPlayer;

// port: new for 1.7.10 (26.2: the skin's model type and PlayerModel's sleeves). Vanilla 1.7.10 skins are 64 x 32: no
// slim arms, no sleeves, the left arm the right one mirrored. Et Futurum Requiem with its enablePlayerSkinOverlay keeps
// skins 64 x 64 and knows slim ("Alex") players: PlayerModelManager.isPlayerModelAlex. Et Futurum is not needed to
// build or run: it is looked up once by name.
public final class PlayerSkins {
    private static final String EFR = "ganymedes01.etfuturum.", MODELS = EFR + "client.skins.PlayerModelManager",
            CONFIG = EFR + "configuration.configs.ConfigFunctions";
    private static Method alex;
    private static Field overlay;
    private static boolean looked;

    private PlayerSkins() {}

    // the player's skin has slim arms (Et Futurum's 1.8 skins on)
    public static boolean slim(EntityPlayer player) {
        lookUp();
        if (alex == null) return false;
        try {
            return overlay.getBoolean(null) && Boolean.TRUE.equals(alex.invoke(null, player));
        } catch (ReflectiveOperationException | RuntimeException e) {
            alex = null;
            GunFeel.LOGGER.warn("Et Futurum's player models could not be read, arms are classic", e);
            return false;
        }
    }

    // the skin bound now is 64 x 64 (its own left arm and sleeves), not 64 x 32
    public static boolean square() {
        int w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        int h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
        return w > 0 && w == h;
    }

    // the log says once what the first-person arms are drawn from, and again when it changes
    private static String said = "";

    static void say(boolean square, boolean slim) {
        String now = (square ? "64 x 64 skin, own left arm and sleeves" : "64 x 32 skin, left arm mirrored") + ", " + (slim ? "slim" : "classic")
                + " arms";
        if (now.equals(said)) return;
        said = now;
        GunFeel.LOGGER.info("First-person arms: {}", now);
    }

    private static synchronized void lookUp() {
        if (looked) return;
        looked = true;
        try {
            Method m = Class.forName(MODELS).getMethod("isPlayerModelAlex", EntityPlayer.class);
            Field f = Class.forName(CONFIG).getField("enablePlayerSkinOverlay");
            overlay = f;
            alex = m;
            GunFeel.LOGGER.info("Et Futurum's 1.8 skins: slim arms as its player models say (when its skin overlay is on)");
        } catch (ClassNotFoundException e) {
            // no Et Futurum
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            GunFeel.LOGGER.warn("Et Futurum is there but its player models could not be found, arms are classic", e);
        }
    }
}
