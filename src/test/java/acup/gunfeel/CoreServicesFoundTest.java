package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

// what core loads from 26.2 (META-INF/services) is found, and is 26.2's: a misspelt service file would leave core on
// its fallback in the game (no config folder, the game's values printed by toString)
class CoreServicesFoundTest {
    @Test
    void theGameFoldersAreFmls() {
        assertInstanceOf(FmlGameDirs.class, GameDirs.get());
    }

    @Test
    void theSettingsRereadDropsThePistolsHipDefaults() {
        var found = java.util.ServiceLoader.load(Config.Reread.class).stream().map(p -> p.type()).toList();
        assertEquals(java.util.List.of(PistolHoldReread.class), found);
    }

    @Test
    void theServersSettingsBringTheShapesAndTheTablesAlong() {
        assertEquals(java.util.List.of(RemoteShapes.class),
                java.util.ServiceLoader.load(ServerSettings.Follow.class).stream().map(p -> p.type()).toList());
        assertEquals(java.util.List.of(ServerTablesReload.class),
                java.util.ServiceLoader.load(ReaimTimes.Reload.class).stream().map(p -> p.type()).toList());
    }

    @Test
    void theTestLogPrintsTheGamesValuesAsBefore() {
        assertEquals("1,2,3", TestLog.fmt(new BlockPos(1, 2, 3)));
        assertEquals("0.500,1.000,-2.250", TestLog.fmt(new Vec3(0.5, 1, -2.25)));
        assertEquals("0.125", TestLog.fmt(0.125F));
        assertEquals("a b", TestLog.fmt("a\tb"));
    }
}
