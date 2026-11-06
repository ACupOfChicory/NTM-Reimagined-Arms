package acup.gunfeel.water;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

// The particles of the water effects (WaterFx), on vanilla sprites: drops (splash_0-3), water dust (generic, low
// alpha), bubbles that rise and pop at the surface (bubble, bubble_pop_0-4), foam that rides the flow, the muzzle's
// and a blast's gas bubble (one bubble sprite, only its scale changes), a blast's flash. Speeds are in blocks per
// second, worked out per client tick (0.05 s), as the sandbox (notes/water/water-splash.tpl.html) has them.
public final class WaterParticle extends SingleQuadParticle {
    public enum Kind {
        DROP, MIST, BUBBLE, RISE, POP, FOAM, GAS, FLASH, DEBRIS
    }

    private static final double DT = 0.05;
    // the flow's speed for foam and drifting rings, blocks per second per unit of getFlow
    static final double FLOW_SPEED = 1.6;
    static final double FALL_SPEED = 8;

    // ours alive now (WaterFx's limit)
    public static int live;
    // bumped by forget(): a particle of an older world does not count down when the engine drops it
    private static int epoch;
    private final int born = epoch;

    static void forget() {
        epoch++;
        live = 0;
    }

    final Kind kind;
    // velocity, blocks per second
    double vx, vy, vz;
    float size0, alpha0, grav, drag;
    // foam: 0 rides the surface, 1 falls off an edge or along a stream; a drop: 1 makes a little ring where it lands
    int mode;
    double phase;
    // the gas bubble: its full radius, the bubbles it breaks into, where it started, forward
    double gasR;
    int gasN;
    boolean still;
    Vec3 origin = Vec3.ZERO, forward = Vec3.ZERO;
    private final String[] frames;

    // the stand's frame log (WaterFxStand): the first particle of this kind spawned, each frame drawn: the time in
    // ticks, its size, alpha, the drawn centre and the gas bubble's origin
    public static volatile Kind watchKind;
    public static WaterParticle watched;
    public static final java.util.List<double[]> WATCH = new java.util.ArrayList<>();

    WaterParticle(ClientLevel level, Kind kind, double x, double y, double z, double vx, double vy, double vz, double life, double size,
            float alpha, String... frames) {
        super(level, x, y, z, sprite(frames[0]));
        this.kind = kind;
        this.frames = frames;
        this.vx = vx;
        this.vy = vy;
        this.vz = vz;
        this.xd = this.yd = this.zd = 0;
        this.lifetime = Math.max(1, (int) Math.round(life / DT));
        this.size0 = (float) size;
        this.quadSize = (float) size * 0.5F;
        this.alpha0 = alpha;
        this.alpha = alpha;
        this.hasPhysics = false;
        this.gravity = 0;
        this.friction = 1;
        this.phase = random.nextDouble() * 6.283;
        live++;
    }

    ClientLevel level() {
        return level;
    }

    int age() {
        return age;
    }

    double x() {
        return x;
    }

    double y() {
        return y;
    }

    double z() {
        return z;
    }

    static TextureAtlasSprite sprite(String name) {
        Identifier id = name.indexOf(':') >= 0 ? Identifier.parse(name) : Identifier.withDefaultNamespace(name);
        return Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.PARTICLES).getSprite(id);
    }

    void frame(int i) {
        setSprite(sprite(frames[Math.clamp(i, 0, frames.length - 1)]));
    }

    @Override
    public void remove() {
        if (!removed && born == epoch) live--;
        super.remove();
    }

    @Override
    public ParticleRenderType getGroup() {
        return ParticleRenderType.SINGLE_QUADS;
    }

    @Override
    protected Layer getLayer() {
        return switch (kind) {
            case BUBBLE, RISE, POP -> Layer.OPAQUE;
            default -> Layer.TRANSLUCENT;
        };
    }

    @Override
    protected int getLightCoords(float a) {
        return kind == Kind.FLASH ? 0xF000F0 : super.getLightCoords(a);
    }

    // the gas bubble's texture is sampled half a texel inside the sprite: at a big scale the atlas' neighbours would
    // show as thin stripes along its edges (the demo's repeat wrapping)
    private float inset(float a, float b) {
        int w = Math.max(1, sprite.contents().width());
        return (b - a) / (2F * w);
    }

    @Override
    protected float getU0() {
        return kind == Kind.GAS ? sprite.getU0() + inset(sprite.getU0(), sprite.getU1()) : sprite.getU0();
    }

    @Override
    protected float getU1() {
        return kind == Kind.GAS ? sprite.getU1() - inset(sprite.getU0(), sprite.getU1()) : sprite.getU1();
    }

    @Override
    protected float getV0() {
        return kind == Kind.GAS ? sprite.getV0() + inset(sprite.getV0(), sprite.getV1()) : sprite.getV0();
    }

    @Override
    protected float getV1() {
        return kind == Kind.GAS ? sprite.getV1() - inset(sprite.getV0(), sprite.getV1()) : sprite.getV1();
    }

    @Override
    public float getQuadSize(float a) {
        if (kind == Kind.GAS) return (float) Math.max(0, gasRadius((age + a) * DT, gasR));
        // water dust swells as it goes
        if (kind == Kind.MIST) return quadSize + size0 * 0.35F * (float) DT * 0.5F * (age + a);
        return quadSize;
    }

    // fading in and out by the frame's time, not once a tick: set on a tick, the water dust's alpha stood at its full
    // value until the first tick and then fell to a fifth of it (the owner's blinking at a shot, 2026-11-06)
    private void fade(float a) {
        float t = age + a, f = 1F - t / lifetime;
        alpha = Math.max(0F, switch (kind) {
            case DROP -> alpha0 * Math.min(1F, f * 5F);
            case MIST -> alpha0 * Math.min(1F, (1 - f) * 6F) * f;
            case FOAM -> alpha0 * Math.min(1F, f * 3F);
            case DEBRIS -> Math.min(1F, (lifetime - t) * (float) DT * 2F) * alpha0;
            case FLASH -> (float) (alpha0 * (1 - t * DT / 0.05));
            default -> alpha;
        });
    }

    @Override
    public void extract(QuadParticleRenderState state, Camera camera, float a) {
        fade(a);
        if (kind == Kind.GAS && gasRadius((age + a) * DT, gasR) <= 0) return;
        if (this == watched) WATCH.add(new double[] {age + a, getQuadSize(a), alpha, xo + (x - xo) * a, yo + (y - yo) * a, zo + (z - zo) * a, origin.x,
                origin.y, origin.z});
        super.extract(state, camera, a);
    }

    // the gas bubble's radius at t seconds (ticks 0-2 it grows to R, tick 3 shrinks to 0.4R, tick 4 bounces to
    // 0.7R; after that it breaks up: -1)
    static double gasRadius(double t, double r) {
        double tk = t * 20;
        if (tk < 2) return r * (1 - Math.pow(1 - tk / 2, 2));
        if (tk < 3) return r * (1 - 0.6 * (tk - 2));
        if (tk < 4) return r * (0.4 + 0.3 * (tk - 3));
        return -1;
    }

    private void moveBy(double dx, double dy, double dz) {
        x += dx;
        y += dy;
        z += dz;
    }

    private boolean solidAt(double px, double py, double pz) {
        BlockPos p = BlockPos.containing(px, py, pz);
        return level.getBlockState(p).blocksMotion();
    }

    @Override
    public void tick() {
        xo = x;
        yo = y;
        zo = z;
        if (age++ >= lifetime) {
            remove();
            return;
        }
        float f = 1F - (float) age / lifetime;
        switch (kind) {
            case DROP -> drop();
            case MIST -> mist(f);
            case BUBBLE, RISE -> bubble();
            case POP -> {
                // its water gone (drained, flowed away): gone with it
                if (WaterSurface.top(level, (int) Math.floor(x), (int) Math.floor(z), y + 0.4, 3) == null) {
                    remove();
                    return;
                }
                frame(Math.min(4, age * 5 / Math.max(1, lifetime)));
            }
            case FOAM -> foam();
            case GAS -> gas();
            case FLASH -> {
            }
            case DEBRIS -> {
                vy -= 1.2 * DT;
                vx *= 1 - DT;
                vz *= 1 - DT;
                moveBy(vx * DT, vy * DT, vz * DT);
                if (solidAt(x, y, z)) remove();
            }
        }
        if (WaterFx.audit && !removed) WaterFx.audit(this);
    }

    private void drop() {
        double df = Math.max(0, 1 - drag * DT);
        vx *= df;
        vy *= df;
        vz *= df;
        vy -= grav * DT;
        moveBy(vx * DT, vy * DT, vz * DT);
        if (y < level.getMinY() - 1 || solidAt(x, y, z)) {
            remove();
            return;
        }
        if (vy < 0 && nearWater()) {
            WaterSurface.Info s = WaterSurface.at(level, x, y + 0.3, z, false);
            if (s != null && y <= s.y()) {
                if (mode == 1 && random.nextFloat() < 0.25F) WaterRings.add(level, x, s.y(), z, 0, 0.9, 0.1, 0.5, 0.5F, !s.still(), false);
                remove();
            }
        }
    }

    // the cell or the one under it has water: only then a surface can be near
    private boolean nearWater() {
        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        return WaterSurface.wet(level, bx, by, bz) || WaterSurface.wet(level, bx, by - 1, bz);
    }

    private void mist(float f) {
        double df = Math.max(0, 1 - drag * DT);
        vx *= df;
        vy *= df;
        vz *= df;
        vy += 0.25 * DT;
        moveBy(vx * DT, vy * DT, vz * DT);
        frame(Math.clamp(7 - (int) Math.floor(8 * (1 - f)), 0, 7));
    }

    // bubbles under water: RISE (the muzzle's and a blast's, the trail) eases to 1.6 blocks per second and wobbles;
    // BUBBLE (a splash's) rises at its own speed and drifts with the flow. Both pop at the surface.
    private void bubble() {
        if (kind == Kind.RISE) {
            vx *= Math.max(0, 1 - 2.5 * DT);
            vz *= Math.max(0, 1 - 2.5 * DT);
            vy += (1.6 - vy) * Math.min(1, DT * 2.2);
            phase += DT * 9;
            moveBy(vx * DT + Math.sin(phase) * 0.18 * DT, vy * DT, vz * DT + Math.cos(phase * 0.8) * 0.12 * DT);
        } else {
            phase += DT * 9;
            moveBy(vx * DT + Math.sin(phase) * 0.004, vy * DT, vz * DT);
        }
        BlockPos p = BlockPos.containing(x, y, z);
        FluidState fs = level.getFluidState(p);
        if (!WaterSurface.wet(fs)) {
            // came up through the top of its cell: pops where the water ends
            BlockPos below = p.below();
            FluidState was = level.getFluidState(below);
            if (WaterSurface.wet(was) && !WaterSurface.falling(was)) pop(below.getY() + was.getHeight(level, below));
            else remove();
            return;
        }
        if (WaterSurface.falling(fs)) return;
        if (WaterSurface.wet(level.getFluidState(p.above()))) return;
        double top = p.getY() + fs.getHeight(level, p);
        if (y >= top - 0.05) pop(top);
    }

    private void pop(double top) {
        WaterFx.spawn(level, Kind.POP, x, top + 0.03, z, 0, 0, 0, 0.25, kind == Kind.RISE ? size0 * 1.6 : 0.22, 0.95F);
        remove();
    }

    private void foam() {
        if (mode == 0) {
            WaterSurface.Info s = WaterSurface.at(level, x, y + 0.4, z, false);
            if (s == null) {
                remove();
                return;
            }
            double tx = s.fx() * FLOW_SPEED, tz = s.fz() * FLOW_SPEED, kk = Math.min(1, DT * (s.still() ? 0.8 : 3));
            vx += (tx - vx) * kk;
            vz += (tz - vz) * kk;
            double nx = x + vx * DT, nz = z + vz * DT;
            WaterSurface.Info s2 = WaterSurface.at(level, nx, y + 0.4, nz, false);
            if (s2 != null && s2.y() > y - 0.35) {
                x = nx;
                z = nz;
                y = s2.y() + 0.02;
            } else {
                // off the edge: down along the stream, onto the water below
                double ox = Math.floor(x), oz = Math.floor(z);
                x = Math.clamp(nx, ox - 0.04, ox + 1.04);
                z = Math.clamp(nz, oz - 0.04, oz + 1.04);
                vx *= 0.15;
                vz *= 0.15;
                vy = 0;
                mode = 1;
            }
        } else {
            vy = Math.max(vy - 22 * DT, -FALL_SPEED);
            moveBy(vx * DT, vy * DT, vz * DT);
            if (y < level.getMinY() || solidAt(x, y, z)) {
                remove();
                return;
            }
            WaterSurface.Info s = WaterSurface.at(level, x, y + 0.3, z, false);
            if (s != null && y <= s.y() + 0.02) {
                mode = 0;
                y = s.y() + 0.02;
                double a = random.nextDouble() * 6.283;
                vx = Math.cos(a) * 0.4;
                vz = Math.sin(a) * 0.4;
            } else if (s == null && !WaterSurface.wet(level, (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z))
                    && !WaterSurface.wet(level, (int) Math.floor(x), (int) Math.floor(y) - 1, (int) Math.floor(z))
                    && !nearStream()) {
                // fell off into the air away from any stream: foam does not hang there
                remove();
            }
        }
    }

    // a falling stream next to it (foam sticks to the stream and falls with it)
    private boolean nearStream() {
        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            FluidState fs = level.getFluidState(new BlockPos(bx + dx, by, bz + dz));
            if (WaterSurface.wet(fs)) return true;
        }
        return false;
    }

    // the gas bubble's centre at tk ticks: ahead of its origin by 0.85 of its radius (over it by 0.6 for a blast),
    // rising slowly
    private Vec3 gasCentre(double tk) {
        double r = Math.max(0, gasRadius(Math.min(tk, 3.999) * DT, gasR));
        return origin.add(still ? Vec3.ZERO : forward.scale(r * 0.85)).add(0, tk * DT * 0.4 + (still ? r * 0.6 : 0), 0);
    }

    // drawn between this tick's centre and the next one's, as its size is drawn at the frame's time: set to this
    // tick's centre, it lagged a tick behind the size and stood still at its origin while it swelled to full
    void placeGas() {
        Vec3 c0 = gasCentre(age), c1 = gasCentre(age + 1);
        xo = c0.x;
        yo = c0.y;
        zo = c0.z;
        x = c1.x;
        y = c1.y;
        z = c1.z;
    }

    private void gas() {
        if (gasRadius(age * DT, gasR) > 0) {
            placeGas();
            return;
        }
        // breaks up into bubbles that rise and pop
        double big = gasR * 0.7;
        int n = WaterFx.scaled(gasN);
        for (int j = 0; j < n; j++) {
            double dx = random.nextDouble() * 2 - 1, dy = random.nextDouble() * 2 - 1, dz = random.nextDouble() * 2 - 1;
            double l = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (l < 1e-6) continue;
            dx /= l;
            dy /= l;
            dz /= l;
            double rr = random.nextDouble() * big;
            WaterFx.spawn(level, Kind.RISE, x + dx * rr, y + dy * rr, z + dz * rr, dx * WaterFx.rnd(0.3, 1.2), dy * WaterFx.rnd(0.3, 1.2) + 0.3,
                    dz * WaterFx.rnd(0.3, 1.2), 12, WaterFx.rnd(0.07, 0.16) * (0.7 + gasR), 1F);
        }
        remove();
    }
}
