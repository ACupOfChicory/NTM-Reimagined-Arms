package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

// core's code logs through ModLog, the adapter's through DebugLog: one logger, the one DebugLog.setup sends to its file
class ModLogTest {
    @Test
    void coreAndTheAdapterLogToTheSameLogger() {
        assertSame(ModLog.LOGGER, DebugLog.LOGGER);
        assertEquals(DebugLog.NAME, ModLog.LOGGER.getName());
    }
}
