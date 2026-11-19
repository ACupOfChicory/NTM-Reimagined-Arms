package acup.gunfeel;

import java.nio.file.Path;

import net.neoforged.fml.loading.FMLPaths;

/** The game's folders for core (GameDirs) on 26.2: FML's, read each time as the code before it did. */
public final class FmlGameDirs implements GameDirs {
    @Override
    public Path config() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public Path game() {
        return FMLPaths.GAMEDIR.get();
    }
}
