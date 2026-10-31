package acup.gunfeel;

import java.nio.file.Path;
import java.nio.file.Paths;

import cpw.mods.fml.common.Loader;

// Where the mod's files live on 1.7.10: the game's config/ folder (26.2: FMLPaths.CONFIGDIR) and the game folder
// (FMLPaths.GAMEDIR: the working directory, as Forge 1.7.10 runs the game there).
public final class PortPaths {
    // tests run without Forge: -Dntmgunfeel.config=<dir>, else the working directory's config/
    private static Path config;

    private PortPaths() {
    }

    public static synchronized Path config() {
        if (config == null) {
            String own = System.getProperty("ntmgunfeel.config");
            if (own != null) config = Paths.get(own);
            else {
                java.io.File dir = null;
                try {
                    dir = Loader.instance().getConfigDir();
                } catch (RuntimeException | LinkageError e) {
                    // no Forge (tests)
                }
                config = dir != null ? dir.toPath() : Paths.get("config");
            }
        }
        return config;
    }

    public static Path game() {
        return Paths.get("").toAbsolutePath();
    }

    // NTM's version for the test log's header (26.2: Compat.hbmVersion); "?" without Forge or NTM
    public static String hbmVersion() {
        try {
            cpw.mods.fml.common.ModContainer hbm = Loader.instance().getIndexedModList().get("hbm");
            return hbm == null ? "?" : hbm.getVersion();
        } catch (RuntimeException | LinkageError e) {
            return "?";
        }
    }
}
