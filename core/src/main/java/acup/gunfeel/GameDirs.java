package acup.gunfeel;

import java.nio.file.Path;

/**
 * The game's folders, as the version's loader knows them: the game's own and its config folder. Looked up each time
 * (the loader may set them late); on 26.2 {@code FmlGameDirs} gives FML's. Without a version (core's own tests) there
 * are none, and asking throws: code that reads its file there catches it as it catches a missing file.
 */
public interface GameDirs {
    /** The config folder, where the mod's settings files are. */
    Path config();

    /** The game's folder (logs/ is in it). */
    Path game();

    /** The version's folders. */
    static GameDirs get() {
        return GameDirsFound.DIRS;
    }
}

/** Looked up once, the first time a folder is asked for. */
final class GameDirsFound {
    static final GameDirs DIRS = CoreServices.load(GameDirs.class, new GameDirs() {
        @Override
        public Path config() {
            throw new IllegalStateException("no game folders: core runs without a Minecraft version here");
        }

        @Override
        public Path game() {
            throw new IllegalStateException("no game folders: core runs without a Minecraft version here");
        }
    });

    private GameDirsFound() {
    }
}
