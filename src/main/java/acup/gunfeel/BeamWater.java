package acup.gunfeel;

import java.util.List;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.water.WaterPath;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

// A beam in water (beamWater, server; with beamRules, the same beams and arcs): its damage falls with the path it goes
// through water, x exp(-rho·s / L) with L = beamWaterLength blocks and rho 1 (a falling stream 0.15, as for bullets:
// WaterPath.density), and where less than beamWaterFade of it is left the beam is gone: it ends there and hits
// nothing. With L 2 and 0.1: 1 block of water leaves 0.61 of the damage, 3 blocks 0.22, the beam is gone after
// L·ln(1/0.1) = 4.6 blocks. A bullet's damage goes as f² = exp(-2s / L) with its own L (a pistol's 1.25: 0.20 after a
// block, gone after 2.4; a rifle's 0.65: 0.05, gone after 1.2). Only water: NTM's beams go through every liquid
// (Fluid.NONE), lava and the rest stay so.
public final class BeamWater {
    public static final String KEY = "beamWater";

    // on = beamWater, length = beamWaterLength (blocks), fade = beamWaterFade (the share at which the beam is gone)
    public record Settings(boolean on, double length, double fade) {
        public static final Settings DEFAULT = new Settings(true, 2.0, 0.1);

        public static Settings current() {
            return new Settings(ReaimTimes.flag(KEY, true), Math.max(0.05F, ReaimTimes.number("beamWaterLength", 2F)),
                    Math.clamp(ReaimTimes.number("beamWaterFade", 0.1F), 0.001F, 0.99F));
        }
    }

    // no water on the way (or beamWater off): the whole damage everywhere, never gone
    public static final BeamWater NONE = new BeamWater(new double[0], 1.0, Double.POSITIVE_INFINITY);

    // start, end, rho per span, in blocks from the beam's start, in order
    private final double[] spans;
    private final double length;
    private final double gone;

    private BeamWater(double[] spans, double length, double gone) {
        this.spans = spans;
        this.length = length;
        this.gone = gone;
    }

    // the water on the way from `from` to `to` in the level (the server's settings). Only loaded chunks are looked at:
    // a gun's beam is 250 blocks long, and this look must not load the chunks behind a wall it stops at
    public static BeamWater along(Level level, Vec3 from, Vec3 to) {
        Settings s = Settings.current();
        if (!s.on()) return NONE;
        WaterPath.Fluids all = WaterPath.of(level);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        WaterPath.Fluids loaded = new WaterPath.Fluids() {
            @Override
            public WaterPath.@Nullable Cell at(int x, int y, int z) {
                return level.isLoaded(pos.set(x, y, z)) ? all.at(x, y, z) : null;
            }

            @Override
            public Vec3 flow(int x, int y, int z) {
                return all.flow(x, y, z);
            }
        };
        return of(WaterPath.water(loaded, from, to), s);
    }

    // the same on spans {start, end, rho} in order (WaterPath.water; the tests give their own)
    public static BeamWater of(List<double[]> spans, Settings s) {
        if (!s.on() || spans.isEmpty()) return NONE;
        double[] flat = new double[spans.size() * 3];
        // the depth (sum of rho·s / L) at which it is gone, and where along the way it gets there
        double depth = Math.log(1 / s.fade()) * s.length(), sum = 0, gone = Double.POSITIVE_INFINITY;
        for (int i = 0; i < spans.size(); i++) {
            double[] sp = spans.get(i);
            flat[i * 3] = sp[0];
            flat[i * 3 + 1] = sp[1];
            flat[i * 3 + 2] = sp[2];
            if (gone < Double.POSITIVE_INFINITY || sp[2] <= 0) continue;
            double add = sp[2] * (sp[1] - sp[0]);
            if (sum + add >= depth) gone = sp[0] + (depth - sum) / sp[2];
            sum += add;
        }
        return new BeamWater(flat, s.length(), gone);
    }

    // the share of the beam's damage left `t` blocks along its way
    public double k(double t) {
        double sum = 0;
        for (int i = 0; i < spans.length; i += 3) {
            if (spans[i] >= t) break;
            sum += spans[i + 2] * (Math.min(spans[i + 1], t) - spans[i]);
        }
        return sum <= 0 ? 1.0 : Math.exp(-sum / length);
    }

    // how far along its way the beam is gone in the water, else infinity
    public double gone() {
        return gone;
    }
}
