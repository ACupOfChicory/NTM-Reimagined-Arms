package acup.gunfeel.water;

import java.util.concurrent.ThreadLocalRandom;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.items.weapon.sedna.BulletConfig;

import acup.gunfeel.Config;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.init.SoundEvents;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraftforge.fluids.IFluidBlock;

// 26.2's WaterFx (water 3): the client's effects where a bullet meets water (waterSplash), as the owner's sandbox has
// them: the crown, the jet, the plume of a skip, rings, foam, bubbles, water dust, crumbs off a shallow bottom, the
// spray of a waterfall; the bubbles of a bullet's path under water; a shot fired under water: no flash at the muzzle
// (MixinMuzzleFlash), a gas bubble that swells and breaks into bubbles; a blast at water (WaterBlastMessage): bubbles
// and a push under it, a column of water on the surface.
// port: NTM CE moves its bullets only on the server, so every bullet effect comes from the tracer (Tracers): its
// events as its head passes them, its path's bubbles each tick, the gas bubble as it starts. A projectile without a
// tracer (a grenade, a rocket: too slow) makes no splash of its own; with an impact fuze it goes off at the surface and
// shows as a blast. NTM CE's own bubbles of a bullet in water are spawned on the server only and never seen, so there
// is nothing to turn off (26.2's ntmBubbles). Sounds are 1.12.2's: the player's splash for 26.2's high-speed one, the
// bobber's and the generic splash; no bubble pop in 1.12.2: a quiet high swim sound where water stops a round.
public final class WaterFx {
    private WaterFx() {
    }

    private static boolean started;

    public static void init() {
        if (started) return;
        started = true;
        WaterEvents.listenBlasts(WaterFx::blastAt);
        // port: an NTM CE old bullet flies on the client and hands its own events here (OldBullets.effects); NTM's
        // bullets' come from their tracers
        WaterEvents.listen((world, e) -> {
            if (old(e.bullet())) handle(world, e);
        });
        WaterEvents.listenTicks((world, bullet, r) -> {
            if (old(bullet)) tracerTick(world, r);
        });
    }

    private static boolean old(net.minecraft.entity.Entity bullet) {
        return bullet instanceof com.hbm.entity.projectile.EntityBulletBase;
    }

    public static boolean on() {
        return Config.waterSplash;
    }

    static double rnd(double a, double b) {
        return a + ThreadLocalRandom.current().nextDouble() * (b - a);
    }

    // ---- limits: the player's particle setting (Minimal: a quarter), a budget a tick and a cap on the living ----
    static final int TICK_BUDGET = 1500, LIVE_MAX = 5000;
    private static long budgetTick = Long.MIN_VALUE;
    private static int budget;
    // spawned since the stand last reset them, by kind; the surface audit's failures
    public static final int[] SPAWNED = new int[WaterParticle.Kind.values().length];
    public static int chips, below, overAir, worst;
    // events handled, by type (the stand)
    public static final int[] SPAWNED_EVENTS = new int[WaterEvents.Type.values().length];
    public static double worstBelow;
    // NTM flashes not drawn because they were under water (MixinMuzzleFlash)
    public static int flashHidden;
    // for the stand: blasts under water and columns on the surface drawn
    public static int blasts, plumes;
    public static volatile boolean audit;

    static double scale() {
        int s = Minecraft.getMinecraft().gameSettings.particleSetting;
        return s <= 0 ? 1.0 : s == 1 ? 0.5 : 0.25;
    }

    static int scaled(int n) {
        return n <= 0 ? 0 : (int) Math.round(n * scale());
    }

    private static boolean room(World world) {
        long t = world.getTotalWorldTime();
        if (t != budgetTick) {
            budgetTick = t;
            budget = (int) (TICK_BUDGET * scale());
        }
        if (budget <= 0 || WaterParticle.live >= LIVE_MAX) return false;
        budget--;
        return true;
    }

    // particles.png tiles: splash 19-22, generic 0-7, bubble 32
    private static final int[] MIST = {0, 1, 2, 3, 4, 5, 6, 7}, BUBBLE = {32}, ONE = {0};

    static WaterParticle spawn(World world, WaterParticle.Kind kind, double x, double y, double z, double vx, double vy, double vz, double life,
            double size, float alpha) {
        if (world == null || !world.isRemote || !room(world)) return null;
        int[] frames;
        switch (kind) {
            case DROP:
                frames = new int[] {19 + ThreadLocalRandom.current().nextInt(4)};
                break;
            case MIST:
                frames = MIST;
                break;
            case BUBBLE:
            case RISE:
            case GAS:
            case POP:
                frames = BUBBLE;
                break;
            case FOAM:
                frames = new int[] {ThreadLocalRandom.current().nextInt(3)};
                break;
            default:
                frames = ONE;
        }
        WaterParticle p = new WaterParticle(world, kind, x, y, z, vx, vy, vz, life, size, alpha, frames);
        if (kind == WaterParticle.Kind.MIST) p.frame(7);
        if (WaterParticle.watchKind == kind && WaterParticle.watched == null) WaterParticle.watched = p;
        SPAWNED[kind.ordinal()]++;
        Minecraft.getMinecraft().effectRenderer.addEffect(p);
        return p;
    }

    private static WaterParticle drop(World world, double x, double y, double z, double vx, double vy, double vz, double life, double size, float a) {
        WaterParticle p = spawn(world, WaterParticle.Kind.DROP, x, y, z, vx, vy, vz, life, size * 1.4, a);
        if (p != null) {
            p.grav = 20F;
            p.drag = 0.4F;
        }
        return p;
    }

    private static void mist(World world, double x, double y, double z, double vx, double vy, double vz, double life, double size, float a) {
        WaterParticle p = spawn(world, WaterParticle.Kind.MIST, x, y, z, vx, vy, vz, life, size, a);
        if (p != null) p.drag = 1.8F;
    }

    private static void foam(World world, double x, double y, double z, double vx, double vz, double life, int mode, double vy) {
        WaterParticle p = spawn(world, WaterParticle.Kind.FOAM, x, y, z, vx, vy, vz, life, rnd(0.24, 0.38), 0.95F);
        if (p != null) p.mode = mode;
    }

    // ---- the strength of a splash by the bullet: the sandbox's weapons (9 mm 0.55, 7.62 1, .50 1.8, a pellet 0.4) ----
    static double power(WaterClass cls) {
        switch (cls.name()) {
            case "PISTOL":
                return 0.55;
            case "RIFLE":
            case "AP":
            case "SLUG":
                return 1.0;
            case "HEAVY":
                return 1.8;
            case "SHOT":
                return 0.4;
            case "DART":
                return 0.45;
            case "GRENADE":
            case "ROCKET":
                return 1.4;
            default:
                return 0.6;
        }
    }

    private static boolean pellet(WaterEvents.Event e) {
        if (e.cls().name().equals("SHOT") || e.cls().name().equals("MINI")) return true;
        BulletConfig c = tracerConfig;
        return c != null && c.projectilesMax > 1;
    }

    // ---- from the tracers (Tracers): each event as the tracer's head passes it ----
    static BulletConfig tracerConfig;

    public static int tracedCount;

    public static void traced(int id) {
        tracedCount++;
    }

    public static void tracerEvent(World world, WaterEvents.Event e, BulletConfig config) {
        tracerConfig = config;
        try {
            handle(world, e);
        } finally {
            tracerConfig = null;
        }
    }

    public static void tracerTick(World world, WaterPath.Result r) {
        if (!on() || world == null || !world.isRemote) return;
        trail(world, r);
    }

    // ---- the events ----
    private static void handle(World world, WaterEvents.Event e) {
        if (!on() || world == null || !world.isRemote) return;
        switch (e.type()) {
            case ENTER:
                enter(world, e);
                break;
            case SKIP:
                skip(world, e);
                break;
            case EXIT:
                exit(world, e);
                break;
            case STOP:
                stop(world, e);
                break;
            case BOTTOM:
                bottom(world, e);
                break;
        }
        SPAWNED_EVENTS[e.type().ordinal()]++;
    }

    // a splash's strength: the class's, a pellet's never under 0.8 (the owner asked for more and bigger drops), less
    // as the bullet slowed
    private static double strength(WaterEvents.Event e) {
        double p = power(e.cls()) * Math.max(0.35, Math.min(1, e.f()));
        return pellet(e) ? Math.max(p, 0.8) : p;
    }

    private static void enter(World world, WaterEvents.Event e) {
        if (e.liquid() == WaterEvents.Liquid.LAVA) return;
        double p = strength(e);
        if (e.kind() == WaterEvents.Kind.FALLING) {
            Vec3d n = e.normal();
            if (e.face().getAxis() == EnumFacing.Axis.Y) n = new Vec3d(-e.dir().x, 0, -e.dir().z);
            double l = Math.hypot(n.x, n.z);
            if (l < 1e-6) n = new Vec3d(1, 0, 0);
            else n = new Vec3d(n.x / l, 0, n.z / l);
            splashFall(world, e.at(), e.dir(), n, p, false);
            sound(world, e.at(), e.cls(), p, pellet(e));
            return;
        }
        WaterSurface.Info s = WaterSurface.at(world, e.at().x, e.at().y + 0.05, e.at().z);
        if (s == null) s = new WaterSurface.Info(e.at().y, e.flow().x, e.flow().z, 0, 0, e.flow().lengthSquared() < 1e-9, e.depth(), e.pos().getX(),
                e.pos().getY(), e.pos().getZ());
        splashSurface(world, e.at(), e.dir(), s, p, false, false);
        sound(world, e.at(), e.cls(), p, pellet(e));
    }

    private static void skip(World world, WaterEvents.Event e) {
        double p = strength(e);
        WaterSurface.Info s = WaterSurface.at(world, e.at().x, e.at().y + 0.05, e.at().z);
        if (s == null) return;
        // the plume goes on the way the bullet came in: the event's dir is the new one, its vertical mirrored back
        Vec3d in = new Vec3d(e.dir().x, -Math.abs(e.dir().y), e.dir().z);
        splashSurface(world, e.at(), in, s, p, false, true);
        sound(world, e.at(), e.cls(), p * 0.7, pellet(e));
    }

    // out of the water into the air: under the surface going up (fired from under water) a crown and rings; through
    // a stream's far side a fan of drops, if air is there
    private static void exit(World world, WaterEvents.Event e) {
        if (e.liquid() == WaterEvents.Liquid.LAVA) return;
        double p = strength(e), sq = Math.sqrt(p);
        Vec3d at = e.at(), d = e.dir();
        if (e.face() == EnumFacing.UP && e.kind() != WaterEvents.Kind.FALLING) {
            WaterSurface.Info s = WaterSurface.at(world, at.x, at.y + 0.05, at.z);
            double y0 = (s != null ? s.y() : at.y) + 0.02;
            int n = scaled((int) Math.round(18 * p));
            for (int i = 0; i < n; i++) {
                double a = rnd(0, Math.PI * 2), out = rnd(0.3, 1.2) * sq, up = rnd(1.5, 4.0) * sq;
                drop(world, at.x + Math.cos(a) * 0.05, y0, at.z + Math.sin(a) * 0.05, Math.cos(a) * out + d.x * 2 * sq, up, Math.sin(a) * out + d.z * 2 * sq,
                        rnd(0.9, 1.5), rnd(0.12, 0.22) * Math.min(1.3, sq), 1F);
            }
            int m = scaled((int) Math.round(3 + 3 * p));
            for (int i = 0; i < m; i++)
                mist(world, at.x + rnd(-.15, .15), y0 + rnd(0.05, 0.3), at.z + rnd(-.15, .15), rnd(-.4, .4) * sq, rnd(0.3, 1.0) * sq, rnd(-.4, .4) * sq,
                        rnd(1.0, 1.8), rnd(0.4, 0.7) * sq, 0.35F);
            if (s != null) WaterRings.add(world, at.x, s.y(), at.z, 0, 1.1, 0.2 * sq, 1.2 * sq, 0.6F, !s.still(), false);
            sound(world, at, e.cls(), p * 0.6, pellet(e));
            return;
        }
        // a stream's far side, or the side of water against air: the fan
        BlockPos past = new BlockPos(at.add(d.scale(0.05)));
        IBlockState ps = world.getBlockState(past);
        if (ps.getMaterial().blocksMovement() || WaterSurface.wet(ps)) return;
        int nb = scaled((int) Math.round(12 * p));
        for (int i = 0; i < nb; i++)
            drop(world, at.x + d.x * 0.02, at.y, at.z + d.z * 0.02, d.x * rnd(2, 5) + rnd(-1, 1), d.y * rnd(2, 5) + rnd(-.5, 1) - 2,
                    d.z * rnd(2, 5) + rnd(-1, 1), rnd(0.5, 0.9), 0.14, 0.85F);
    }

    // the water stopped it: a puff of bubbles where it stopped, a quiet bubbling; lava: smoke and a hiss
    private static void stop(World world, WaterEvents.Event e) {
        Vec3d at = e.at();
        if (e.liquid() == WaterEvents.Liquid.LAVA) {
            for (int i = 0; i < 4; i++) world.spawnParticle(EnumParticleTypes.SMOKE_NORMAL, at.x + rnd(-.1, .1), at.y + 0.1, at.z + rnd(-.1, .1), 0, 0.05, 0);
            world.playSound(at.x, at.y, at.z, SoundEvents.BLOCK_LAVA_EXTINGUISH, SoundCategory.NEUTRAL, 0.25F, 1.6F + (float) rnd(0, 0.4), false);
            return;
        }
        int n = scaled(6);
        for (int j = 0; j < n; j++)
            spawn(world, WaterParticle.Kind.RISE, at.x + rnd(-.05, .05), at.y + rnd(-.05, .05), at.z + rnd(-.05, .05), rnd(-.2, .2), rnd(0.2, 0.6),
                    rnd(-.2, .2), 10, rnd(0.05, 0.1), 1F);
        if (quietSounds(world)) world.playSound(at.x, at.y, at.z, SoundEvents.ENTITY_GENERIC_SWIM, SoundCategory.NEUTRAL, 0.15F, 1.6F + (float) rnd(0, 0.4),
                false);
        SunkBullets.stopped(world, e, tracerConfig);
    }

    // through shallow water into the bottom: crumbs of the block and a few bubbles
    private static void bottom(World world, WaterEvents.Event e) {
        // e.pos() is the water cell; the block hit is behind its face
        EnumFacing back = e.face().getOpposite();
        Vec3d behind = e.at().add(back.getXOffset() * 0.01, back.getYOffset() * 0.01, back.getZOffset() * 0.01);
        IBlockState state = world.getBlockState(new BlockPos(behind));
        Block b = state.getBlock();
        if (state.getMaterial() == Material.AIR || b instanceof BlockLiquid || b instanceof IFluidBlock) return;
        double p = strength(e), sq = Math.sqrt(p);
        Vec3d at = e.at().add(e.normal().scale(0.05));
        int n = scaled((int) Math.round(6 + 6 * p));
        int id = Block.getStateId(state);
        for (int i = 0; i < n; i++) {
            double sp = Math.min(1.4, sq);
            world.spawnParticle(EnumParticleTypes.BLOCK_CRACK, at.x, at.y, at.z, rnd(-1, 1) * sp * 0.05, rnd(1.5, 3.5) * sp * 0.05, rnd(-1, 1) * sp * 0.05, id);
        }
        chips += n;
        int k = scaled(4);
        for (int j = 0; j < k; j++)
            spawn(world, WaterParticle.Kind.RISE, at.x + rnd(-.05, .05), at.y + 0.05, at.z + rnd(-.05, .05), rnd(-.2, .2), rnd(0.3, 0.7), rnd(-.2, .2), 6,
                    rnd(0.05, 0.1), 1F);
    }

    // every tick of a tracer in water: bubbles along its path, one each 0.12 blocks, as the demo's trail
    private static void trail(World world, WaterPath.Result r) {
        int made = 0;
        for (Vec3d[] span : r.wet()) {
            Vec3d a = span[0], b = span[1];
            double len = a.distanceTo(b);
            int n = Math.min(40 - made, scaled((int) Math.floor(len / 0.12)));
            for (int i = 0; i < n; i++) {
                double t = ThreadLocalRandom.current().nextDouble();
                double x = a.x + (b.x - a.x) * t + rnd(-.03, .03), y = a.y + (b.y - a.y) * t + rnd(-.03, .03), z = a.z + (b.z - a.z) * t;
                if (!WaterSurface.wet(world, new BlockPos(x, y, z))) continue;
                spawn(world, WaterParticle.Kind.RISE, x, y, z, rnd(-.1, .1), rnd(0.2, 0.5), rnd(-.1, .1), 10, rnd(0.05, 0.09), 1F);
            }
            made += n;
            if (made >= 40) break;
        }
    }

    // ---- the splash on a surface (still, sloped, shallow), the sandbox's splashSurface ----
    static final java.util.Map<String, Integer> LAST = new java.util.LinkedHashMap<String, Integer>();

    static void splashSurface(World world, Vec3d p, Vec3d d, WaterSurface.Info s, double power, boolean boom, boolean skipped) {
        double sq = Math.sqrt(power);
        double fm = Math.hypot(s.fx(), s.fz()), fdx = fm > 0 ? s.fx() / fm : 0, fdz = fm > 0 ? s.fz() / fm : 0;
        double elev = Math.toDegrees(Math.asin(Math.min(1, -d.y))), steep = Math.clamp((elev - 10) / 50, 0, 1);
        double hl = Math.hypot(d.x, d.z);
        double hx = hl > 1e-9 ? d.x / hl : 0, hz = hl > 1e-9 ? d.z / hl : 0;
        boolean rico = (skipped || elev < 20) && !boom;
        boolean thin = s.depth() < 0.55;
        double y0 = s.y() + 0.02;
        double fs = WaterParticle.FLOW_SPEED;
        LAST.clear();
        // the crown: leans the way the bullet went, and downstream on a flow
        int n = scaled((int) Math.round((boom ? 90 : 28) * power * (rico ? 0.35 : 0.5 + steep * 0.6) * (thin ? 0.6 : 1)));
        for (int i = 0; i < n; i++) {
            double a = rnd(0, Math.PI * 2), cx = Math.cos(a), cz = Math.sin(a), fw = Math.max(0, cx * hx + cz * hz), dw = (cx * fdx + cz * fdz) * fm;
            double out = rnd(0.8, 2.4) * sq * (1 + (1 - steep) * 0.9 * fw) * (1 + 0.5 * dw);
            double up = rnd(1.6, 4.2) * sq * (0.45 + 0.55 * steep) * (1 + fw * (1 - steep) * 0.5) * (1 + 0.25 * dw) * (thin ? 0.7 : 1);
            WaterParticle k = drop(world, p.x + cx * 0.12 * sq, y0, p.z + cz * 0.12 * sq, cx * out + fdx * fm * fs * 0.8, up, cz * out + fdz * fm * fs * 0.8,
                    rnd(1.2, 1.8), rnd(0.14, 0.26) * Math.min(1.3, sq), 1F);
            if (k != null) k.mode = 1;
        }
        LAST.put("crown", n);
        // the jet: 2-4 ticks late, only steep and over deep enough water
        int jet = 0;
        if (!thin && !rico && (steep > 0.35 || boom)) {
            jet = scaled((int) Math.round((boom ? 40 : 8 + 10 * power) * (boom ? 1 : steep)));
            double delay = boom ? 0.05 : 0.11 + 0.06 * sq;
            final int jn = jet;
            later(delay, () -> {
                for (int i = 0; i < jn; i++) {
                    double f = (double) i / jn, spd = (boom ? 7 : 3.5) * sq * (0.45 + f * 0.9) + rnd(-.3, .3);
                    WaterParticle k = drop(world, p.x + rnd(-.04, .04) * sq, y0, p.z + rnd(-.04, .04) * sq, rnd(-.25, .25) + fdx * fm * 0.9, spd,
                            rnd(-.25, .25) + fdz * fm * 0.9, rnd(1.3, 2.2), (0.14 + f * 0.12) * Math.min(1.3, sq), 1F);
                    if (k != null) k.mode = 1;
                }
            });
        }
        LAST.put("jet", jet);
        // a skip: the plume goes on along the bullet
        int plume = 0;
        if (rico) {
            plume = scaled((int) Math.round(26 * power * (1 - Math.min(elev, 20) / 20) + 6));
            double rx = d.x, ry = Math.abs(d.y) * 0.7 + 0.12, rz = d.z, rl = Math.sqrt(rx * rx + ry * ry + rz * rz);
            for (int i = 0; i < plume; i++) {
                double sp = rnd(4, 11) * sq;
                WaterParticle k = drop(world, p.x, y0, p.z, (rx / rl + rnd(-.12, .12)) * sp, (ry / rl + rnd(-.05, .12)) * sp, (rz / rl + rnd(-.12, .12)) * sp,
                        rnd(0.7, 1.3), rnd(0.1, 0.18) * Math.min(1.3, sq), 0.95F);
                if (k != null) k.mode = 1;
            }
        }
        LAST.put("plume", plume);
        // rings: flat on the surface, drifting at half the flow, growing only downstream
        int rings = boom ? 3 : (rico ? 1 : 2);
        for (int i = 0; i < rings; i++)
            WaterRings.add(world, p.x + (rico ? hx * 0.4 : 0), s.y(), p.z + (rico ? hz * 0.4 : 0), i * 0.22, 1.3 + i * 0.2, 0.25 * sq,
                    (1.6 + i * 0.6) * sq * (boom ? 1.6 : 1), 0.75F - i * 0.15F, fm > 0.01, false);
        LAST.put("rings", rings);
        int nf = scaled((int) Math.round((boom ? 40 : 8) + 6 * power));
        for (int i = 0; i < nf; i++) {
            double a = rnd(0, Math.PI * 2), r = rnd(0, 0.25) * sq, v = rnd(0.2, 0.9) * sq;
            foam(world, p.x + Math.cos(a) * r, y0, p.z + Math.sin(a) * r, Math.cos(a) * v, Math.sin(a) * v, rnd(2.5, 5), 0, 0);
        }
        LAST.put("foam", nf);
        int nb = 0;
        if (!thin) {
            nb = scaled((int) Math.round((boom ? 60 : 12) * power * (rico ? 0.3 : 1)));
            double depth = Math.min(s.depth() - 0.2, (boom ? 2 : 1.6) * sq * (rico ? 0.2 : 1));
            for (int i = 0; i < nb; i++) {
                double t = rnd(0, 1) * depth / Math.max(0.2, -d.y);
                spawn(world, WaterParticle.Kind.BUBBLE, p.x + d.x * t + rnd(-.08, .08), Math.max(s.y() - s.depth() + 0.1, p.y + d.y * t), p.z + d.z * t + rnd(-.08, .08),
                        fdx * fm * fs, rnd(0.9, 1.8), fdz * fm * fs, 6, rnd(0.1, 0.16) * (boom ? 1.4 : 1), 0.9F);
            }
        }
        LAST.put("bubbles", nb);
        int nm = scaled((int) Math.round((boom ? 28 : 4) + 5 * power));
        for (int i = 0; i < nm; i++)
            mist(world, p.x + rnd(-.2, .2) * sq, y0 + rnd(0.1, 0.5) * sq, p.z + rnd(-.2, .2) * sq, rnd(-.6, .6) * sq + (rico ? hx * 2 : 0) + fdx * fm * fs,
                    rnd(0.3, 1.4) * sq, rnd(-.6, .6) * sq + (rico ? hz * 2 : 0) + fdz * fm * fs, rnd(1.2, 2.2), rnd(0.5, 0.9) * sq, 0.35F);
        LAST.put("mist", nm);
    }

    // ---- a waterfall: no rings and no jet; spray out along the face's normal and down with the stream, foam that
    // sticks to it and falls with it ----
    static void splashFall(World world, Vec3d p, Vec3d d, Vec3d n, double power, boolean boom) {
        double sq = Math.sqrt(power), nx = n.x, nz = n.z, fall = WaterParticle.FALL_SPEED;
        LAST.clear();
        int k = scaled((int) Math.round((boom ? 120 : 26) * power));
        for (int i = 0; i < k; i++) {
            double out = rnd(1, 3.6) * sq, side = rnd(-2.4, 2.4) * sq;
            WaterParticle q = drop(world, p.x + nx * 0.05 + rnd(-.08, .08) * Math.abs(nz), p.y + rnd(-.08, .08), p.z + nz * 0.05 + rnd(-.08, .08) * Math.abs(nx),
                    nx * out + side * Math.abs(nz) - d.x * 0.4 * sq, rnd(-1.2, 2.2) * sq - fall * 0.35, nz * out + side * Math.abs(nx) - d.z * 0.4 * sq,
                    rnd(0.9, 1.5), rnd(0.12, 0.22) * Math.min(1.3, sq), 1F);
            if (q != null) q.mode = 1;
        }
        LAST.put("spray", k);
        int nf = scaled((int) Math.round((boom ? 40 : 10) + 6 * power));
        for (int i = 0; i < nf; i++)
            foam(world, p.x + nx * 0.04 + rnd(-.25, .25) * sq * Math.abs(nz), p.y + rnd(-.2, .3) * sq, p.z + nz * 0.04 + rnd(-.25, .25) * sq * Math.abs(nx), 0, 0,
                    rnd(3, 5), 1, -rnd(1, 4));
        LAST.put("foam", nf);
        int nm = scaled((int) Math.round((boom ? 24 : 4) + 4 * power));
        for (int i = 0; i < nm; i++)
            mist(world, p.x + nx * 0.3, p.y + rnd(-.2, .2), p.z + nz * 0.3, nx * rnd(0.4, 1.4) * sq + rnd(-.5, .5) * Math.abs(nz), -fall * 0.25 + rnd(-.3, .3),
                    nz * rnd(0.4, 1.4) * sq + rnd(-.5, .5) * Math.abs(nx), rnd(1.2, 2), rnd(0.5, 0.8) * sq, 0.35F);
        LAST.put("mist", nm);
    }

    // ---- sounds: vanilla splashes by strength; one shot's pellets do not each play one ----
    private static long soundTick = Long.MIN_VALUE;
    private static int sounds;

    private static boolean quietSounds(World world) {
        long t = world.getTotalWorldTime();
        if (t != soundTick) {
            soundTick = t;
            sounds = 0;
        }
        return sounds++ < 3;
    }

    private static void sound(World world, Vec3d at, WaterClass cls, double power, boolean pellet) {
        if (!quietSounds(world)) return;
        SoundEvent ev;
        float vol;
        if (cls.explosive || power >= 1.5) {
            ev = SoundEvents.ENTITY_PLAYER_SPLASH;
            vol = 0.6F;
        } else if (pellet || power < 0.8) {
            ev = SoundEvents.ENTITY_BOBBER_SPLASH;
            vol = 0.5F;
        } else {
            ev = SoundEvents.ENTITY_GENERIC_SPLASH;
            vol = 0.5F;
        }
        world.playSound(at.x, at.y, at.z, ev, SoundCategory.NEUTRAL, vol, 0.9F + (float) rnd(0, 0.3), false);
    }

    // ---- delayed effects (the jet), run on the client tick ----
    private static final class Later {
        final long at;
        final Runnable run;

        Later(long at, Runnable run) {
            this.at = at;
            this.run = run;
        }
    }

    private static final java.util.ArrayDeque<Later> LATER = new java.util.ArrayDeque<Later>();

    private static long ticks;
    private static World lastWorld;

    private static void later(double seconds, Runnable r) {
        LATER.add(new Later(ticks + Math.max(1, Math.round(seconds * 20)), r));
    }

    // the client tick: delayed effects
    public static void clientTick() {
        Minecraft mc = Minecraft.getMinecraft();
        // another world or dimension: what is left belongs to the old one
        if (mc.world != lastWorld) {
            clear();
            lastWorld = mc.world;
        }
        if (mc.world == null || mc.isGamePaused()) return;
        long now = ++ticks;
        int n = LATER.size();
        for (int i = 0; i < n; i++) {
            Later l = LATER.poll();
            if (l == null) break;
            if (l.at <= now) l.run.run();
            else LATER.add(l);
        }
    }

    public static void clear() {
        LATER.clear();
        WaterRings.clear();
        SunkBullets.clear();
        WaterParticle.forget();
        gasTick = Long.MIN_VALUE;
        gasAt = Vec3d.ZERO;
    }

    // ---- a shot under water (Tracers, when the shot starts): no NTM flash (MixinMuzzleFlash), a gas bubble at the
    // muzzle, of the calibre's size, that swells for 2 ticks, shrinks, bounces and breaks into 8 + 44R bubbles ----
    private static long gasTick = Long.MIN_VALUE;
    private static Vec3d gasAt = Vec3d.ZERO;

    // a point in water for the effects (any liquid but lava)
    public static boolean wetAt(World world, Vec3d p) {
        return WaterSurface.wetAt(world, p.x, p.y, p.z);
    }

    // MixinMuzzleFlash: the flash NTM is about to draw is under water (counted in flashHidden)
    public static boolean flashWet() {
        if (!on() || !acup.gunfeel.compat.Feature.TRACERS.on() || acup.gunfeel.Arms.measuring) return false;
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        if (mc.world == null) return false;
        double[] p = acup.gunfeel.Tracers.here(acup.gunfeel.GunFeelClient.current != null);
        if (p == null || !WaterSurface.wetAt(mc.world, p[0], p[1], p[2])) return false;
        flashHidden++;
        return true;
    }

    public static void fired(World world, Vec3d muzzle, Vec3d dir, BulletConfig config) {
        if (!on() || world == null || !world.isRemote) return;
        if (!wetAt(world, muzzle)) return;
        // the pellets of one shot come as several tracers: one bubble for them
        long tick = world.getTotalWorldTime();
        if (tick == gasTick && muzzle.squareDistanceTo(gasAt) < 0.25) return;
        gasTick = tick;
        gasAt = muzzle;
        double r = gasRadius(config);
        WaterParticle g = spawn(world, WaterParticle.Kind.GAS, muzzle.x, muzzle.y, muzzle.z, 0, 0, 0, 0.3, 0.001, 1F);
        if (g == null) return;
        g.gasR = r;
        g.gasN = (int) Math.round(8 + 44 * r);
        g.origin = muzzle;
        g.forward = dir.lengthSquared() > 1e-9 ? dir.normalize() : Vec3d.ZERO;
        g.placeGas();
    }

    // NTM's entity of a round with an impact fuze that starts no tracer here (grenades, rockets, mortar shells:
    // Tracers.traced) has come to this client: shot under water, it makes the gas bubble too. A shot leaves the gun
    // with a motion of length 1 (its speed is NTM's motionMult); one that comes into sight sinking or lying on the
    // bottom has next to none (WaterTick.sink) and is no shot. Its config follows the entity, so the bubble waits for
    // the next tick.
    // port: FML's spawn of NTM CE's bullet carries no motion (its spawn data is the thrower only; the config does come,
    // in its data): the server's velocity packet (sendsVelocityUpdates) comes after it, so the motion is asked in the
    // next tick, and again while it is still none (a sinking one has some: (0, at most 0.05 down, 0))
    public static void joined(World world, net.minecraft.entity.Entity e) {
        if (!on() || !(e instanceof EntityBulletBaseMK4) || !world.isRemote) return;
        EntityBulletBaseMK4 b = (EntityBulletBaseMK4) e;
        Vec3d at = new Vec3d(e.posX, e.posY, e.posZ);
        if (!wetAt(world, at)) return;
        Minecraft mc = Minecraft.getMinecraft();
        boolean own = mc.player != null && b.getThrower() == mc.player;
        later(0.05, () -> bubble(world, b, at, own, 3));
    }

    // the velocity packet may come a tick later than the spawn: asked again up to `tries` times
    private static void bubble(World world, EntityBulletBaseMK4 b, Vec3d at, boolean own, int tries) {
        if (Minecraft.getMinecraft().world != world || b.isDead) return;
        BulletConfig config = b.getBulletConfig();
        if (config == null || !WaterClass.Fuze.of(config).any()) return;
        // a gun's round only, not a flame; a turret's shell is traced (shot0 gives its bubble)
        if (config.ammo == null || WaterClass.Fuze.field(config) != null || acup.gunfeel.Tracers.traced(config)) return;
        Vec3d dir = new Vec3d(b.motionX, b.motionY, b.motionZ);
        if (dir.lengthSquared() < 0.25) {
            if (tries > 1 && dir.lengthSquared() < 1e-12) later(0.05, () -> bubble(world, b, at, own, tries - 1));
            return;
        }
        // the player's own shot: at the muzzle drawn in their hands, as a tracer's
        double[] m = own ? acup.gunfeel.Tracers.drawnMuzzle() : null;
        Vec3d muzzle = m == null ? null : new Vec3d(m[0], m[1], m[2]);
        fired(world, muzzle != null && muzzle.squareDistanceTo(at) < 9 ? muzzle : at, dir, config);
    }

    // the gas bubble's radius by calibre (the owner's numbers): p22 .15, p9 .25, m357 .3, m44 .32, r556 .3, r762 .35,
    // bmg50 .5, b75 .3, g12 .4, g10 .45, stone .35
    private static final java.util.Map<String, Double> GAS = new java.util.HashMap<String, Double>();
    static {
        GAS.put("p22", .15);
        GAS.put("p9", .25);
        GAS.put("m357", .3);
        GAS.put("m44", .32);
        GAS.put("r556", .3);
        GAS.put("r762", .35);
        GAS.put("bmg50", .5);
        GAS.put("b75", .3);
        GAS.put("g12", .4);
        GAS.put("g10", .45);
        GAS.put("stone", .35);
    }

    static double gasRadius(BulletConfig config) {
        String cal = calibre(config);
        Double r = cal == null ? null : GAS.get(cal);
        return r != null ? r : 0.3;
    }

    // "m44" of hbm:ammo_standard_m44_jhp
    static String calibre(BulletConfig config) {
        String id = ammo(config);
        if (id == null) return null;
        int cut = id.indexOf('_');
        return cut < 0 ? id : id.substring(0, cut);
    }

    // "m44_jhp" of hbm:ammo_standard_m44_jhp (ammo_secret_ the same); port: by WaterClass.ammoName
    public static String ammo(BulletConfig config) {
        if (config == null) return null;
        String p = WaterClass.ammoName(config);
        if (p.isEmpty()) return null;
        if (p.startsWith("ammo_standard_")) return p.substring("ammo_standard_".length());
        if (p.startsWith("ammo_secret_")) return p.substring("ammo_secret_".length());
        return p;
    }

    // ---- explosions under water and at the surface ----

    // a blast at water from the server (WaterEvents.listenBlasts): on the surface (its centre within half a block of
    // it) only the column; deeper bubbles and a push, and a column that shrinks with the depth, none past a block
    // plus the blast's size
    public static void blastAt(World world, WaterEvents.Blast b) {
        if (b.liquid() == WaterEvents.Liquid.LAVA) return;
        double depth = b.surface() == null ? Double.POSITIVE_INFINITY : b.surface().y - b.at().y;
        if (b.inWater() && depth > 0.5) blast(world, b.at(), b.size(), b.incendiary());
        if (b.surface() == null) return;
        double k = depth <= 1 ? 1 : 1 - (depth - 1) / Math.max(1, b.size());
        if (k > 0.1) blastPlume(world, b.surface(), (float) (b.size() * k));
    }

    // A blast whose centre is under water: a flash (orange and weaker for an incendiary), a big pulsing gas bubble that
    // breaks into a cloud of bubbles, bits flying out, the bodies on the bottom pushed away. size = NTM's explosion
    // size in blocks.
    public static void blast(World world, Vec3d at, float size, boolean incendiary) {
        blasts++;
        blastPower(world, at, Math.clamp(size * 0.25, 0.2, 2.0), incendiary);
    }

    // the same in the demo's units (a round's charge: r762_he .35, bmg50_he .55, b75_exp .7, g12_explosive .6,
    // g10_explosive .25, b75_inc .25)
    static void blastPower(World world, Vec3d at, double p, boolean inc) {
        if (!on() || world == null || !world.isRemote) return;
        WaterParticle g = spawn(world, WaterParticle.Kind.GAS, at.x, at.y, at.z, 0, 0, 0, 0.3, 0.001, 1F);
        if (g != null) {
            g.gasR = p;
            g.gasN = (int) Math.round(20 + 80 * p);
            g.origin = at;
            g.still = true;
            g.placeGas();
        }
        WaterParticle f = spawn(world, WaterParticle.Kind.FLASH, at.x, at.y, at.z, 0, 0, 0, 0.1, p * (inc ? 2.4 : 1.6), 0.9F);
        if (f != null) {
            if (inc) f.setColor(1F, 0.63F, 0.25F);
            else f.setColor(1F, 0.96F, 0.82F);
        }
        int n = scaled((int) Math.round(30 * p));
        for (int j = 0; j < n; j++) {
            double dx = rnd(-1, 1), dy = rnd(-.2, 1), dz = rnd(-1, 1), l = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (l < 1e-6) continue;
            WaterParticle q = spawn(world, WaterParticle.Kind.DEBRIS, at.x, at.y + 0.05, at.z, dx / l * rnd(.5, 2), dy / l * rnd(.5, 2), dz / l * rnd(.5, 2), 0.8,
                    0.05, 1F);
            if (q != null) q.setColor(0.91F, 0.95F, 1F);
        }
        SunkBullets.blast(at, p, inc);
        world.playSound(at.x, at.y, at.z, SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.NEUTRAL, (float) Math.min(1.0, 0.3 + p), 0.6F, false);
    }

    // A blast at the surface or within a block under it: a column of water instead of a fireball, a tall fountain of
    // drops and water dust, rings and foam. size = NTM's explosion size in blocks.
    public static void blastPlume(World world, Vec3d at, float size) {
        if (!on() || world == null || !world.isRemote) return;
        WaterSurface.Info s = WaterSurface.at(world, at.x, at.y + 1.2, at.z);
        if (s == null) return;
        plumes++;
        double p = Math.clamp(size * 0.85, 0.5, 6.0);
        splashSurface(world, new Vec3d(at.x, s.y(), at.z), new Vec3d(0, -1, 0), s, p, true, false);
        world.playSound(at.x, s.y(), at.z, SoundEvents.ENTITY_PLAYER_SPLASH, SoundCategory.NEUTRAL, 1F, 0.7F, false);
    }

    // ---- the stand's surface audit: a drop, foam or a pop more than 0.15 under the surface, foam or a pop over air ----
    static void audit(WaterParticle p) {
        switch (p.kind) {
            case FOAM:
            case POP: {
                if (p.kind == WaterParticle.Kind.FOAM && p.mode == 1) return;
                WaterSurface.Info s = WaterSurface.at(p.level(), p.x(), p.y() + 0.4, p.z());
                if (s == null) {
                    overAir++;
                    fault(p, "over air", Double.NaN);
                } else under(p, s.y() - p.y());
                break;
            }
            case DROP: {
                if (!WaterSurface.wet(p.level(), (int) Math.floor(p.x()), (int) Math.floor(p.y()), (int) Math.floor(p.z()))) return;
                WaterSurface.Info s = WaterSurface.at(p.level(), p.x(), p.y() + 0.4, p.z());
                if (s != null) under(p, s.y() - p.y());
                break;
            }
            default:
        }
    }

    private static void under(WaterParticle p, double d) {
        if (d > worstBelow) worstBelow = d;
        if (d > 0.15) {
            below++;
            fault(p, "under", d);
        }
    }

    // the first faults of the audit in the log, to find what made them
    private static int faults;

    private static void fault(WaterParticle p, String what, double d) {
        if (faults++ >= 40) return;
        acup.gunfeel.GunFeel.LOGGER.info("[waterfx] audit {} {} mode {} age {} at {} {} {} by {}", what, p.kind, p.mode, p.age(),
                String.format("%.3f", p.x()), String.format("%.3f", p.y()), String.format("%.3f", p.z()), String.format("%.3f", d));
    }

    public static void resetCounts() {
        java.util.Arrays.fill(SPAWNED, 0);
        chips = below = overAir = 0;
        worstBelow = 0;
    }
}
