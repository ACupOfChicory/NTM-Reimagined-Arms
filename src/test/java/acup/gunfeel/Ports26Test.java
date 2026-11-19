package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.EnumSet;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

import acup.gunfeel.host.NtmFeature;
import acup.gunfeel.host.Ports;
import acup.gunfeel.host.PortsContract;

// 26.2's root of ports (Wiring) does what every version's must (PortsContract), and gives 26.2's classes: a port
// missing here would leave core on its fallback in the game (no config folder, the game's values printed by toString)
class Ports26Test extends PortsContract {
    @Override
    protected Ports ports() {
        return new Wiring();
    }

    @Test
    void theRootIsTheWiring() {
        assertInstanceOf(Wiring.class, CoreServices.root());
    }

    @Test
    void the262Classes() {
        Ports p = ports();
        assertInstanceOf(FmlGameDirs.class, p.port(GameDirs.class));
        assertInstanceOf(TestLogPrint.class, p.port(TestLog.Printer.class));
        assertInstanceOf(PistolHoldReread.class, p.port(Config.Reread.class));
        assertInstanceOf(ServerTablesReload.class, p.port(ReaimTimes.Reload.class));
        assertInstanceOf(RemoteShapes.class, p.port(ServerSettings.Follow.class));
        assertInstanceOf(GameMainHand.class, p.port(MainHand.class));
        assertInstanceOf(NtmAnimState.class, p.port(GunAnimState.class));
        assertInstanceOf(NtmAnimState.class, ArmTimeline.ANIM);
        assertInstanceOf(acup.gunfeel.compat.CompatVersions.class, p.port(acup.gunfeel.host.HostVersions.class));
        assertInstanceOf(acup.gunfeel.compat.CompatVersions.class, acup.gunfeel.host.HostVersions.get());
        assertInstanceOf(FmlGameDirs.class, GameDirs.get());
        assertInstanceOf(BlockStates.class, p.port(acup.gunfeel.host.BlockTable.class));
    }

    @Test
    void ntmNextCanDoAll() {
        assertEquals(EnumSet.allOf(NtmFeature.class), ports().ntm());
        assertEquals(EnumSet.allOf(NtmFeature.class), CoreServices.ntm());
    }

    @Test
    void theTestLogPrintsTheGamesValuesAsBefore() {
        assertEquals("1,2,3", TestLog.fmt(new BlockPos(1, 2, 3)));
        assertEquals("0.500,1.000,-2.250", TestLog.fmt(new Vec3(0.5, 1, -2.25)));
        assertEquals("0.125", TestLog.fmt(0.125F));
        assertEquals("a b", TestLog.fmt("a\tb"));
    }
}
