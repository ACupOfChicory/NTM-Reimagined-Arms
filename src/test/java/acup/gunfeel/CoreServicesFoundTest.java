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

    // every interface core loads (CoreServices.load(X.class, ...) in core/src/main/java) has one provider here: a new
    // one without a service file would run on its fallback in the game, with every test still green
    @Test
    void everyServiceCoreLoadsHasOneProviderOn262() throws java.io.IOException, ClassNotFoundException {
        var load = java.util.regex.Pattern.compile("CoreServices\\.load\\(([\\w.]+)\\.class");
        var pkg = java.util.regex.Pattern.compile("(?m)^package ([\\w.]+);");
        var missing = new java.util.ArrayList<String>();
        int n = 0;
        java.nio.file.Path core = java.nio.file.Path.of("core/src/main/java");
        try (var files = java.nio.file.Files.walk(core)) {
            for (var f : files.filter(x -> x.toString().endsWith(".java")).toList()) {
                String src = java.nio.file.Files.readString(f);
                var m = load.matcher(src);
                while (m.find()) {
                    if (f.getFileName().toString().equals("CoreServices.java")) continue;
                    var pm = pkg.matcher(src);
                    String p = pm.find() ? pm.group(1) + "." : "";
                    String outer = f.getFileName().toString().replace(".java", ""), name = m.group(1);
                    Class<?> type = null;
                    for (String c : java.util.List.of(p + outer + "$" + name.replace('.', '$'), p + name.replace('.', '$'))) {
                        try {
                            type = Class.forName(c, false, getClass().getClassLoader());
                            break;
                        } catch (ClassNotFoundException e) {
                            // the other reading of the name
                        }
                    }
                    if (type == null) throw new ClassNotFoundException(name + " in " + f);
                    n++;
                    long providers = java.util.ServiceLoader.load(type).stream().count();
                    if (providers != 1) missing.add(type.getName() + ": " + providers);
                }
            }
        }
        assertEquals(java.util.List.of(), missing, "META-INF/services/<interface> on 26.2, one class each");
        assertEquals(5, n, "the services core loads; a new one: add its 26.2 class and count it here");
    }

    @Test
    void theTestLogPrintsTheGamesValuesAsBefore() {
        assertEquals("1,2,3", TestLog.fmt(new BlockPos(1, 2, 3)));
        assertEquals("0.500,1.000,-2.250", TestLog.fmt(new Vec3(0.5, 1, -2.25)));
        assertEquals("0.125", TestLog.fmt(0.125F));
        assertEquals("a b", TestLog.fmt("a\tb"));
    }
}
