package acup.gunfeel;

import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** What the test log (TestLog, core) takes from 26.2: the game's own values as a line shows them. */
public final class TestLogPrint implements TestLog.Printer {
    @Override
    public String print(Object v, boolean exact) {
        if (exact) {
            if (v instanceof Vec3 p) return Trace.vec(p.x, p.y, p.z);
            if (v instanceof BlockPos p) return p.getX() + "," + p.getY() + "," + p.getZ();
            if (v instanceof BlockState s) return net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(s);
            return null;
        }
        if (v instanceof Vec3 p) return String.format(Locale.ROOT, "%.3f,%.3f,%.3f", p.x, p.y, p.z);
        if (v instanceof BlockPos p) return p.getX() + "," + p.getY() + "," + p.getZ();
        if (v instanceof BlockState s) return BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
        return null;
    }
}
