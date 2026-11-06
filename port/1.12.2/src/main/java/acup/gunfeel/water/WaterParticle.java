package acup.gunfeel.water;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

// 26.2's WaterParticle (water 3): the particles of the water effects (WaterFx): drops, water dust, bubbles that rise
// and pop at the surface, foam that rides the flow, the muzzle's and a blast's gas bubble, a blast's flash. Speeds are
// in blocks per second, worked out per client tick (0.05 s), as the sandbox has them.
// port: 1.12.2's Particle on vanilla's particles.png: drops splash (19-22), water dust and foam generic (0-7), bubbles
// bubble (32), the flash the firework's overlay. 1.12.2 has no bubble_pop frames: a pop is the bubble growing to
// twice its size and fading out over its life.
public final class WaterParticle extends Particle {
    public enum Kind {
        DROP, MIST, BUBBLE, RISE, POP, FOAM, GAS, FLASH, DEBRIS
    }

    private static final double DT = 0.05;
    // the flow's speed for foam and drifting rings, blocks per second per unit of the flow
    static final double FLOW_SPEED = 1.6;
    static final double FALL_SPEED = 8;
    // particles.png: 16 × 16 tiles of 8 pixels on 128
    private static final float TILE = 1F / 16F, TEXEL = 1F / 128F;

    // ours alive now (WaterFx's limit)
    public static int live;
    // bumped by forget(): a particle of an older world does not count down when the engine drops it
    private static int epoch;
    private final int born = epoch;
    private boolean counted = true;

    static void forget() {
        epoch++;
        live = 0;
    }

    final Kind kind;
    // velocity, blocks per second
    double vx, vy, vz;
    float size0, alpha0, grav, drag, quadSize;
    // foam: 0 rides the surface, 1 falls off an edge or along a stream; a drop: 1 makes a little ring where it lands
    int mode;
    double phase;
    // the gas bubble: its full radius, the bubbles it breaks into, where it started, forward
    double gasR;
    int gasN;
    boolean still;
    Vec3d origin = Vec3d.ZERO, forward = Vec3d.ZERO;
    private final int[] frames;
    private int frame;

    // the stand's frame log: the first particle of this kind spawned, each frame drawn: the time in ticks, its size,
    // alpha, the drawn centre and the gas bubble's origin
    public static volatile Kind watchKind;
    public static WaterParticle watched;
    public static final java.util.List<double[]> WATCH = new java.util.ArrayList<double[]>();

    WaterParticle(World world, Kind kind, double x, double y, double z, double vx, double vy, double vz, double life, double size, float alpha,
            int... frames) {
        super(world, x, y, z);
        this.kind = kind;
        this.frames = frames;
        this.frame = frames.length > 0 ? frames[0] : 0;
        this.vx = vx;
        this.vy = vy;
        this.vz = vz;
        this.motionX = this.motionY = this.motionZ = 0;
        this.particleMaxAge = Math.max(1, (int) Math.round(life / DT));
        this.size0 = (float) size;
        this.quadSize = (float) size * 0.5F;
        this.alpha0 = alpha;
        this.particleAlpha = alpha;
        this.particleRed = this.particleGreen = this.particleBlue = 1F;
        this.canCollide = false;
        this.particleGravity = 0;
        this.phase = rand.nextDouble() * 6.283;
        live++;
    }

    World level() {
        return world;
    }

    int age() {
        return particleAge;
    }

    double x() {
        return posX;
    }

    double y() {
        return posY;
    }

    double z() {
        return posZ;
    }

    boolean removed() {
        return !isAlive();
    }

    void frame(int i) {
        frame = frames[Math.max(0, Math.min(frames.length - 1, i))];
    }

    void setColor(float r, float g, float b) {
        setRBGColorF(r, g, b);
    }

    void remove() {
        if (counted && born == epoch) live--;
        counted = false;
        setExpired();
    }

    @Override
    public int getBrightnessForRender(float a) {
        return kind == Kind.FLASH ? 0xF000F0 : super.getBrightnessForRender(a);
    }

    float getQuadSize(float a) {
        if (kind == Kind.GAS) return (float) Math.max(0, gasRadius((particleAge + a) * DT, gasR));
        // water dust swells as it goes
        if (kind == Kind.MIST) return quadSize + size0 * 0.35F * (float) DT * 0.5F * (particleAge + a);
        // port: the pop grows to twice its size as it fades
        if (kind == Kind.POP) return quadSize * (1F + (particleAge + a) / particleMaxAge);
        return quadSize;
    }

    // fading in and out by the frame's time, not once a tick
    private void fade(float a) {
        float t = particleAge + a, f = 1F - t / particleMaxAge;
        float al;
        switch (kind) {
            case DROP:
                al = alpha0 * Math.min(1F, f * 5F);
                break;
            case MIST:
                al = alpha0 * Math.min(1F, (1 - f) * 6F) * f;
                break;
            case FOAM:
                al = alpha0 * Math.min(1F, f * 3F);
                break;
            case DEBRIS:
                al = Math.min(1F, (particleMaxAge - t) * (float) DT * 2F) * alpha0;
                break;
            case FLASH:
                al = (float) (alpha0 * (1 - t * DT / 0.05));
                break;
            case POP:
                al = alpha0 * f;
                break;
            default:
                al = particleAlpha;
        }
        particleAlpha = Math.max(0F, al);
    }

    @Override
    public void renderParticle(BufferBuilder buffer, Entity viewer, float a, float rx, float rz, float ryz, float rxy, float rxz) {
        fade(a);
        if (kind == Kind.GAS && gasRadius((particleAge + a) * DT, gasR) <= 0) return;
        if (particleAlpha <= 0.004F) return;
        double cx = prevPosX + (posX - prevPosX) * a, cy = prevPosY + (posY - prevPosY) * a, cz = prevPosZ + (posZ - prevPosZ) * a;
        float h = getQuadSize(a);
        if (this == watched) WATCH.add(new double[] {particleAge + a, h * 2, particleAlpha, cx, cy, cz, origin.x, origin.y, origin.z});
        float u0, u1, v0, v1;
        if (kind == Kind.FLASH) {
            u0 = 0.25F;
            u1 = 0.5F;
            v0 = 0.125F;
            v1 = 0.375F;
        } else {
            u0 = (frame % 16) * TILE;
            v0 = (frame / 16) * TILE;
            u1 = u0 + TILE;
            v1 = v0 + TILE;
            // the gas bubble is sampled half a texel inside its tile: at a big scale the neighbours would show along
            // its edges; the others as vanilla's 0.0624375
            float in = kind == Kind.GAS ? TEXEL * 0.5F : TILE - 0.0624375F;
            u0 += kind == Kind.GAS ? in : 0;
            v0 += kind == Kind.GAS ? in : 0;
            u1 -= in;
            v1 -= in;
        }
        float x = (float) (cx - interpPosX), y = (float) (cy - interpPosY), z = (float) (cz - interpPosZ);
        int light = getBrightnessForRender(a);
        int j = light >> 16 & 65535, k = light & 65535;
        float r = particleRed, g = particleGreen, b = particleBlue, al = particleAlpha;
        buffer.pos(x - rx * h - rxy * h, y - rz * h, z - ryz * h - rxz * h).tex(u1, v1).color(r, g, b, al).lightmap(j, k).endVertex();
        buffer.pos(x - rx * h + rxy * h, y + rz * h, z - ryz * h + rxz * h).tex(u1, v0).color(r, g, b, al).lightmap(j, k).endVertex();
        buffer.pos(x + rx * h + rxy * h, y + rz * h, z + ryz * h + rxz * h).tex(u0, v0).color(r, g, b, al).lightmap(j, k).endVertex();
        buffer.pos(x + rx * h - rxy * h, y - rz * h, z + ryz * h - rxz * h).tex(u0, v1).color(r, g, b, al).lightmap(j, k).endVertex();
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
        posX += dx;
        posY += dy;
        posZ += dz;
    }

    private boolean solidAt(double px, double py, double pz) {
        return world.getBlockState(new BlockPos(px, py, pz)).getMaterial().blocksMovement();
    }

    @Override
    public void onUpdate() {
        prevPosX = posX;
        prevPosY = posY;
        prevPosZ = posZ;
        if (particleAge++ >= particleMaxAge) {
            remove();
            return;
        }
        float f = 1F - (float) particleAge / particleMaxAge;
        switch (kind) {
            case DROP:
                drop();
                break;
            case MIST:
                mist(f);
                break;
            case BUBBLE:
            case RISE:
                bubble();
                break;
            case POP:
                // its water gone (drained, flowed away): gone with it
                if (WaterSurface.top(world, (int) Math.floor(posX), (int) Math.floor(posZ), posY + 0.4, 3) == null) {
                    remove();
                    return;
                }
                break;
            case FOAM:
                foam();
                break;
            case GAS:
                gas();
                break;
            case FLASH:
                break;
            case DEBRIS:
                vy -= 1.2 * DT;
                vx *= 1 - DT;
                vz *= 1 - DT;
                moveBy(vx * DT, vy * DT, vz * DT);
                if (solidAt(posX, posY, posZ)) remove();
                break;
        }
        if (WaterFx.audit && isAlive()) WaterFx.audit(this);
    }

    private void drop() {
        double df = Math.max(0, 1 - drag * DT);
        vx *= df;
        vy *= df;
        vz *= df;
        vy -= grav * DT;
        moveBy(vx * DT, vy * DT, vz * DT);
        if (posY < -1 || solidAt(posX, posY, posZ)) {
            remove();
            return;
        }
        if (vy < 0 && nearWater()) {
            WaterSurface.Info s = WaterSurface.at(world, posX, posY + 0.3, posZ, false);
            if (s != null && posY <= s.y()) {
                if (mode == 1 && rand.nextFloat() < 0.25F) WaterRings.add(world, posX, s.y(), posZ, 0, 0.9, 0.1, 0.5, 0.5F, !s.still(), false);
                remove();
            }
        }
    }

    // the cell or the one under it has water: only then a surface can be near
    private boolean nearWater() {
        int bx = (int) Math.floor(posX), by = (int) Math.floor(posY), bz = (int) Math.floor(posZ);
        return WaterSurface.wet(world, bx, by, bz) || WaterSurface.wet(world, bx, by - 1, bz);
    }

    private void mist(float f) {
        double df = Math.max(0, 1 - drag * DT);
        vx *= df;
        vy *= df;
        vz *= df;
        vy += 0.25 * DT;
        moveBy(vx * DT, vy * DT, vz * DT);
        frame(Math.max(0, Math.min(7, 7 - (int) Math.floor(8 * (1 - f)))));
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
        BlockPos p = new BlockPos(posX, posY, posZ);
        IBlockState fs = world.getBlockState(p);
        if (!WaterSurface.wet(fs)) {
            // came up through the top of its cell: pops where the water ends
            BlockPos below = p.down();
            IBlockState was = world.getBlockState(below);
            if (WaterSurface.wet(was) && !WaterSurface.falling(was)) pop(below.getY() + WaterSurface.cellHeight(world, below, was));
            else remove();
            return;
        }
        if (WaterSurface.falling(fs)) return;
        if (WaterSurface.wet(world.getBlockState(p.up()))) return;
        double top = p.getY() + WaterSurface.cellHeight(world, p, fs);
        if (posY >= top - 0.05) pop(top);
    }

    private void pop(double top) {
        WaterFx.spawn(world, Kind.POP, posX, top + 0.03, posZ, 0, 0, 0, 0.25, kind == Kind.RISE ? size0 * 1.6 : 0.22, 0.95F);
        remove();
    }

    private void foam() {
        if (mode == 0) {
            WaterSurface.Info s = WaterSurface.at(world, posX, posY + 0.4, posZ, false);
            if (s == null) {
                remove();
                return;
            }
            double tx = s.fx() * FLOW_SPEED, tz = s.fz() * FLOW_SPEED, kk = Math.min(1, DT * (s.still() ? 0.8 : 3));
            vx += (tx - vx) * kk;
            vz += (tz - vz) * kk;
            double nx = posX + vx * DT, nz = posZ + vz * DT;
            WaterSurface.Info s2 = WaterSurface.at(world, nx, posY + 0.4, nz, false);
            if (s2 != null && s2.y() > posY - 0.35) {
                posX = nx;
                posZ = nz;
                posY = s2.y() + 0.02;
            } else {
                // off the edge: down along the stream, onto the water below
                double ox = Math.floor(posX), oz = Math.floor(posZ);
                posX = Math.max(ox - 0.04, Math.min(ox + 1.04, nx));
                posZ = Math.max(oz - 0.04, Math.min(oz + 1.04, nz));
                vx *= 0.15;
                vz *= 0.15;
                vy = 0;
                mode = 1;
            }
        } else {
            vy = Math.max(vy - 22 * DT, -FALL_SPEED);
            moveBy(vx * DT, vy * DT, vz * DT);
            if (posY < 0 || solidAt(posX, posY, posZ)) {
                remove();
                return;
            }
            WaterSurface.Info s = WaterSurface.at(world, posX, posY + 0.3, posZ, false);
            if (s != null && posY <= s.y() + 0.02) {
                mode = 0;
                posY = s.y() + 0.02;
                double a = rand.nextDouble() * 6.283;
                vx = Math.cos(a) * 0.4;
                vz = Math.sin(a) * 0.4;
            } else if (s == null && !WaterSurface.wet(world, (int) Math.floor(posX), (int) Math.floor(posY), (int) Math.floor(posZ))
                    && !WaterSurface.wet(world, (int) Math.floor(posX), (int) Math.floor(posY) - 1, (int) Math.floor(posZ)) && !nearStream()) {
                // fell off into the air away from any stream: foam does not hang there
                remove();
            }
        }
    }

    // a falling stream next to it (foam sticks to the stream and falls with it)
    private boolean nearStream() {
        int bx = (int) Math.floor(posX), by = (int) Math.floor(posY), bz = (int) Math.floor(posZ);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (WaterSurface.wet(world, bx + dx, by, bz + dz)) return true;
        return false;
    }

    // the gas bubble's centre at tk ticks: ahead of its origin by 0.85 of its radius (over it by 0.6 for a blast),
    // rising slowly
    private Vec3d gasCentre(double tk) {
        double r = Math.max(0, gasRadius(Math.min(tk, 3.999) * DT, gasR));
        return origin.add(still ? Vec3d.ZERO : forward.scale(r * 0.85)).add(0, tk * DT * 0.4 + (still ? r * 0.6 : 0), 0);
    }

    // drawn between this tick's centre and the next one's, as its size is drawn at the frame's time
    void placeGas() {
        Vec3d c0 = gasCentre(particleAge), c1 = gasCentre(particleAge + 1);
        prevPosX = c0.x;
        prevPosY = c0.y;
        prevPosZ = c0.z;
        posX = c1.x;
        posY = c1.y;
        posZ = c1.z;
    }

    private void gas() {
        if (gasRadius(particleAge * DT, gasR) > 0) {
            placeGas();
            return;
        }
        // breaks up into bubbles that rise and pop
        double big = gasR * 0.7;
        int n = WaterFx.scaled(gasN);
        for (int j = 0; j < n; j++) {
            double dx = rand.nextDouble() * 2 - 1, dy = rand.nextDouble() * 2 - 1, dz = rand.nextDouble() * 2 - 1;
            double l = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (l < 1e-6) continue;
            dx /= l;
            dy /= l;
            dz /= l;
            double rr = rand.nextDouble() * big;
            WaterFx.spawn(world, Kind.RISE, posX + dx * rr, posY + dy * rr, posZ + dz * rr, dx * WaterFx.rnd(0.3, 1.2), dy * WaterFx.rnd(0.3, 1.2) + 0.3,
                    dz * WaterFx.rnd(0.3, 1.2), 12, WaterFx.rnd(0.07, 0.16) * (0.7 + gasR), 1F);
        }
        remove();
    }
}
