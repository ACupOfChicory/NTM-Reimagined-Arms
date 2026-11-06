package acup.gunfeel.water;

import java.util.concurrent.ThreadLocalRandom;

import org.jspecify.annotations.Nullable;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.items.weapon.sedna.BulletConfig;

import acup.gunfeel.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

// The client's effects where a bullet meets water (waterSplash), from the water events (WaterEvents), as the owner's
// sandbox has them (notes/water/water-splash.tpl.html, numbers and layers from there): the crown, the jet, the plume
// of a skip, rings, foam, bubbles, water dust, crumbs off a shallow bottom, the spray of a waterfall; the bubbles of a
// bullet's path under water; a shot fired under water (notes/water/bubble-demo.tpl.html): no flash at the muzzle, a
// gas bubble that swells and breaks into bubbles. Only bullets make them.
//
// For the explosives (part 3): blast() under water and blastPlume() at the surface, both on the client.
public final class WaterFx {
    private WaterFx() {
    }

    private static boolean started;

    public static void init() {
        if (started) return;
        started = true;
        WaterEvents.listen(WaterFx::on);
        WaterEvents.listenTicks(WaterFx::tick);
        WaterEvents.ntmBubbles = !Config.waterSplash;
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
    // spawned since the stand last reset them, by kind; the surface audit's failures (WaterStand)
    public static final int[] SPAWNED = new int[WaterParticle.Kind.values().length];
    public static int chips, below, overAir, worst;
    // events handled, by type (the stand)
    public static final int[] SPAWNED_EVENTS = new int[WaterEvents.Type.values().length];
    public static double worstBelow;
    // NTM flashes not drawn because they were under water (MixinMuzzleFlash)
    public static int flashHidden;
    public static volatile boolean audit;

    static double scale() {
        ParticleStatus s = Minecraft.getInstance().options.particles().get();
        return switch (s) {
            case ALL -> 1.0;
            case DECREASED -> 0.5;
            case MINIMAL -> 0.25;
        };
    }

    static int scaled(int n) {
        return n <= 0 ? 0 : (int) Math.round(n * scale());
    }

    private static boolean room(Level level) {
        long t = level.getGameTime();
        if (t != budgetTick) {
            budgetTick = t;
            budget = (int) (TICK_BUDGET * scale());
        }
        if (budget <= 0 || WaterParticle.live >= LIVE_MAX) return false;
        budget--;
        return true;
    }

    static @Nullable WaterParticle spawn(Level level, WaterParticle.Kind kind, double x, double y, double z, double vx, double vy, double vz,
            double life, double size, float alpha) {
        if (!(level instanceof ClientLevel cl) || !room(level)) return null;
        String[] frames = switch (kind) {
            case DROP -> new String[] {"splash_" + ThreadLocalRandom.current().nextInt(4)};
            case MIST -> new String[] {"generic_0", "generic_1", "generic_2", "generic_3", "generic_4", "generic_5", "generic_6", "generic_7"};
            case BUBBLE, RISE, GAS -> new String[] {"bubble"};
            case POP -> new String[] {"bubble_pop_0", "bubble_pop_1", "bubble_pop_2", "bubble_pop_3", "bubble_pop_4"};
            case FOAM -> new String[] {"generic_" + ThreadLocalRandom.current().nextInt(3)};
            case FLASH -> new String[] {"flash"};
            case DEBRIS -> new String[] {"generic_0"};
        };
        WaterParticle p = new WaterParticle(cl, kind, x, y, z, vx, vy, vz, life, size, alpha, frames);
        if (kind == WaterParticle.Kind.MIST) p.frame(7);
        SPAWNED[kind.ordinal()]++;
        Minecraft.getInstance().particleEngine.add(p);
        return p;
    }

    private static @Nullable WaterParticle drop(Level level, double x, double y, double z, double vx, double vy, double vz, double life,
            double size, float a) {
        WaterParticle p = spawn(level, WaterParticle.Kind.DROP, x, y, z, vx, vy, vz, life, size * 1.4, a);
        if (p != null) {
            p.grav = 20F;
            p.drag = 0.4F;
        }
        return p;
    }

    private static void mist(Level level, double x, double y, double z, double vx, double vy, double vz, double life, double size, float a) {
        WaterParticle p = spawn(level, WaterParticle.Kind.MIST, x, y, z, vx, vy, vz, life, size, a);
        if (p != null) p.drag = 1.8F;
    }

    private static void foam(Level level, double x, double y, double z, double vx, double vz, double life, int mode, double vy) {
        WaterParticle p = spawn(level, WaterParticle.Kind.FOAM, x, y, z, vx, vy, vz, life, rnd(0.24, 0.38), 0.95F);
        if (p != null) p.mode = mode;
    }

    // ---- the strength of a splash by the bullet: the sandbox's weapons (9 mm 0.55, 7.62 1, .50 1.8, a pellet 0.4) ----
    static double power(WaterClass cls) {
        return switch (cls.name()) {
            case "PISTOL" -> 0.55;
            case "RIFLE", "AP", "SLUG" -> 1.0;
            case "HEAVY" -> 1.8;
            case "SHOT" -> 0.4;
            case "DART" -> 0.45;
            case "GRENADE", "ROCKET" -> 1.4;
            default -> 0.6;
        };
    }

    private static boolean pellet(WaterEvents.Event e) {
        if (e.cls().name().equals("SHOT") || e.cls().name().equals("MINI")) return true;
        BulletConfig c = config(e);
        return c != null && c.projectilesMax > 1;
    }

    static @Nullable BulletConfig config(@Nullable Entity bullet) {
        return bullet instanceof EntityBulletBaseMK4 b ? b.config : null;
    }

    // the round of an event: its bullet's, or the tracer's that handed it over
    static @Nullable BulletConfig config(WaterEvents.Event e) {
        return e.bullet() != null ? config(e.bullet()) : tracerConfig;
    }

    // ---- from the tracers (Tracers): the server sends the client its bullet only after the bullet's first tick, at
    // its place then (a pistol round is 16 blocks on by then, and gone if water stopped it). The tracer starts at the
    // muzzle and works out the same path in water, so the effects come from it, each event as the tracer's head
    // passes it; the events of a bullet a tracer has are left alone. ----
    private static @Nullable BulletConfig tracerConfig;
    private static final java.util.Map<Integer, Boolean> TRACED = new java.util.LinkedHashMap<>(256, 0.75F, false) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<Integer, Boolean> eldest) {
            return size() > 2048;
        }
    };

    public static int tracedCount;

    public static void traced(int id) {
        tracedCount++;
        if (id >= 0) TRACED.put(id, Boolean.TRUE);
    }

    private static boolean tracedBullet(@Nullable Entity bullet) {
        return bullet != null && TRACED.containsKey(bullet.getId());
    }

    public static void tracerEvent(Level level, WaterEvents.Event e, @Nullable BulletConfig config) {
        tracerConfig = config;
        try {
            handle(level, e);
        } finally {
            tracerConfig = null;
        }
    }

    public static void tracerTick(Level level, WaterPath.Result r) {
        if (!on() || !(level instanceof ClientLevel)) return;
        WaterEvents.ntmBubbles = false;
        trail(level, r);
    }

    // ---- the events ----
    private static void on(Level level, WaterEvents.Event e) {
        if (tracedBullet(e.bullet())) return;
        handle(level, e);
    }

    private static void handle(Level level, WaterEvents.Event e) {
        if (!on() || !(level instanceof ClientLevel)) return;
        WaterEvents.ntmBubbles = false;
        switch (e.type()) {
            case ENTER -> enter(level, e);
            case SKIP -> skip(level, e);
            case EXIT -> exit(level, e);
            case STOP -> stop(level, e);
            case BOTTOM -> bottom(level, e);
        }
        SPAWNED_EVENTS[e.type().ordinal()]++;
    }

    // a splash's strength: the class's, a pellet's never under 0.8 (the owner asked for more and bigger drops), less
    // as the bullet slowed
    private static double strength(WaterEvents.Event e) {
        double p = power(e.cls()) * Math.max(0.35, Math.min(1, e.f()));
        return pellet(e) ? Math.max(p, 0.8) : p;
    }

    private static void enter(Level level, WaterEvents.Event e) {
        if (e.liquid() == WaterEvents.Liquid.LAVA) return;
        double p = strength(e);
        if (e.kind() == WaterEvents.Kind.FALLING) {
            Vec3 n = e.normal();
            if (e.face().getAxis() == Direction.Axis.Y) n = new Vec3(-e.dir().x, 0, -e.dir().z);
            double l = Math.hypot(n.x, n.z);
            if (l < 1e-6) n = new Vec3(1, 0, 0);
            else n = new Vec3(n.x / l, 0, n.z / l);
            splashFall(level, e.at(), e.dir(), n, p, false);
            sound(level, e.at(), e.cls(), p, pellet(e));
            return;
        }
        WaterSurface.Info s = WaterSurface.at(level, e.at().x, e.at().y + 0.05, e.at().z);
        if (s == null) s = new WaterSurface.Info(e.at().y, e.flow().x, e.flow().z, 0, 0, e.flow().lengthSqr() < 1e-9, e.depth(), e.pos().getX(),
                e.pos().getY(), e.pos().getZ());
        splashSurface(level, e.at(), e.dir(), s, p, false, false);
        sound(level, e.at(), e.cls(), p, pellet(e));
    }

    private static void skip(Level level, WaterEvents.Event e) {
        double p = strength(e);
        WaterSurface.Info s = WaterSurface.at(level, e.at().x, e.at().y + 0.05, e.at().z);
        if (s == null) return;
        // the plume goes on the way the bullet came in: the event's dir is the new one, its vertical mirrored back
        Vec3 in = new Vec3(e.dir().x, -Math.abs(e.dir().y), e.dir().z);
        splashSurface(level, e.at(), in, s, p, false, true);
        sound(level, e.at(), e.cls(), p * 0.7, pellet(e));
    }

    // out of the water into the air: under the surface going up (fired from under water) a crown and rings; through
    // a stream's far side a fan of drops, if air is there
    private static void exit(Level level, WaterEvents.Event e) {
        if (e.liquid() == WaterEvents.Liquid.LAVA) return;
        double p = strength(e), sq = Math.sqrt(p);
        Vec3 at = e.at(), d = e.dir();
        if (e.face() == Direction.UP && e.kind() != WaterEvents.Kind.FALLING) {
            WaterSurface.Info s = WaterSurface.at(level, at.x, at.y + 0.05, at.z);
            double y0 = (s != null ? s.y() : at.y) + 0.02;
            int n = scaled((int) Math.round(18 * p));
            for (int i = 0; i < n; i++) {
                double a = rnd(0, Math.PI * 2), out = rnd(0.3, 1.2) * sq, up = rnd(1.5, 4.0) * sq;
                drop(level, at.x + Math.cos(a) * 0.05, y0, at.z + Math.sin(a) * 0.05, Math.cos(a) * out + d.x * 2 * sq, up, Math.sin(a) * out + d.z * 2 * sq,
                        rnd(0.9, 1.5), rnd(0.12, 0.22) * Math.min(1.3, sq), 1F);
            }
            int m = scaled((int) Math.round(3 + 3 * p));
            for (int i = 0; i < m; i++)
                mist(level, at.x + rnd(-.15, .15), y0 + rnd(0.05, 0.3), at.z + rnd(-.15, .15), rnd(-.4, .4) * sq, rnd(0.3, 1.0) * sq, rnd(-.4, .4) * sq,
                        rnd(1.0, 1.8), rnd(0.4, 0.7) * sq, 0.35F);
            if (s != null) WaterRings.add(level, at.x, s.y(), at.z, 0, 1.1, 0.2 * sq, 1.2 * sq, 0.6F, !s.still(), false);
            sound(level, at, e.cls(), p * 0.6, pellet(e));
            return;
        }
        // a stream's far side, or the side of water against air: the fan
        BlockPos past = BlockPos.containing(at.add(d.scale(0.05)));
        if (level.getBlockState(past).blocksMotion() || WaterSurface.wet(level.getFluidState(past))) return;
        int nb = scaled((int) Math.round(12 * p));
        for (int i = 0; i < nb; i++)
            drop(level, at.x + d.x * 0.02, at.y, at.z + d.z * 0.02, d.x * rnd(2, 5) + rnd(-1, 1), d.y * rnd(2, 5) + rnd(-.5, 1) - 2,
                    d.z * rnd(2, 5) + rnd(-1, 1), rnd(0.5, 0.9), 0.14, 0.85F);
    }

    // the water stopped it: a puff of bubbles where it stopped, a quiet bubbling; lava: smoke and a hiss
    private static void stop(Level level, WaterEvents.Event e) {
        Vec3 at = e.at();
        if (e.liquid() == WaterEvents.Liquid.LAVA) {
            for (int i = 0; i < 4; i++) level.addParticle(ParticleTypes.SMOKE, at.x + rnd(-.1, .1), at.y + 0.1, at.z + rnd(-.1, .1), 0, 0.05, 0);
            level.playLocalSound(at.x, at.y, at.z, SoundEvents.LAVA_EXTINGUISH, SoundSource.NEUTRAL, 0.25F, 1.6F + (float) rnd(0, 0.4), false);
            return;
        }
        int n = scaled(6);
        for (int j = 0; j < n; j++)
            spawn(level, WaterParticle.Kind.RISE, at.x + rnd(-.05, .05), at.y + rnd(-.05, .05), at.z + rnd(-.05, .05), rnd(-.2, .2), rnd(0.2, 0.6),
                    rnd(-.2, .2), 10, rnd(0.05, 0.1), 1F);
        if (quietSounds(level)) level.playLocalSound(at.x, at.y, at.z, SoundEvents.BUBBLE_COLUMN_BUBBLE_POP, SoundSource.NEUTRAL, 0.25F,
                1.2F + (float) rnd(0, 0.3), false);
        SunkBullets.stopped(level, e);
    }

    // through shallow water into the bottom: crumbs of the block (BlockParticleOption) and a few bubbles
    private static void bottom(Level level, WaterEvents.Event e) {
        // e.pos() is the water cell; the block hit is behind its face
        BlockState state = level.getBlockState(BlockPos.containing(e.at().relative(e.face().getOpposite(), 0.01)));
        if (state.isAir() || state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) return;
        double p = strength(e), sq = Math.sqrt(p);
        Vec3 at = e.at().add(e.normal().scale(0.05));
        int n = scaled((int) Math.round(6 + 6 * p));
        BlockParticleOption crumbs = new BlockParticleOption(ParticleTypes.BLOCK, state);
        for (int i = 0; i < n; i++) {
            double sp = Math.min(1.4, sq);
            level.addParticle(crumbs, at.x, at.y, at.z, rnd(-1, 1) * sp * 0.05, rnd(1.5, 3.5) * sp * 0.05, rnd(-1, 1) * sp * 0.05);
        }
        chips += n;
        int b = scaled(4);
        for (int j = 0; j < b; j++)
            spawn(level, WaterParticle.Kind.RISE, at.x + rnd(-.05, .05), at.y + 0.05, at.z + rnd(-.05, .05), rnd(-.2, .2), rnd(0.3, 0.7), rnd(-.2, .2), 6,
                    rnd(0.05, 0.1), 1F);
    }

    // every tick of a bullet in water: bubbles along its path (instead of NTM's 4 a tick wherever it is), one each
    // 0.12 blocks, as the demo's trail
    private static void tick(Level level, Entity bullet, WaterPath.Result r) {
        if (!on() || !(level instanceof ClientLevel) || tracedBullet(bullet)) return;
        WaterEvents.ntmBubbles = false;
        trail(level, r);
    }

    private static void trail(Level level, WaterPath.Result r) {
        int made = 0;
        for (Vec3[] span : r.wet()) {
            Vec3 a = span[0], b = span[1];
            double len = a.distanceTo(b);
            int n = Math.min(40 - made, scaled((int) Math.floor(len / 0.12)));
            for (int i = 0; i < n; i++) {
                double t = ThreadLocalRandom.current().nextDouble();
                double x = a.x + (b.x - a.x) * t + rnd(-.03, .03), y = a.y + (b.y - a.y) * t + rnd(-.03, .03), z = a.z + (b.z - a.z) * t;
                if (!WaterSurface.wet(level.getFluidState(BlockPos.containing(x, y, z)))) continue;
                spawn(level, WaterParticle.Kind.RISE, x, y, z, rnd(-.1, .1), rnd(0.2, 0.5), rnd(-.1, .1), 10, rnd(0.05, 0.09), 1F);
            }
            made += n;
            if (made >= 40) break;
        }
    }

    // ---- the splash on a surface (still, sloped, shallow), the sandbox's splashSurface ----
    static final java.util.Map<String, Integer> LAST = new java.util.LinkedHashMap<>();

    static void splashSurface(Level level, Vec3 p, Vec3 d, WaterSurface.Info s, double power, boolean boom, boolean skipped) {
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
            WaterParticle k = drop(level, p.x + cx * 0.12 * sq, y0, p.z + cz * 0.12 * sq, cx * out + fdx * fm * fs * 0.8, up, cz * out + fdz * fm * fs * 0.8,
                    rnd(1.2, 1.8), rnd(0.14, 0.26) * Math.min(1.3, sq), 1F);
            if (k != null) k.mode = 1;
        }
        LAST.put("crown", n);
        // the jet: 2-4 ticks late, only steep and over deep enough water
        int jet = 0;
        if (!thin && !rico && (steep > 0.35 || boom)) {
            jet = scaled((int) Math.round((boom ? 40 : 8 + 10 * power) * (boom ? 1 : steep)));
            double delay = boom ? 0.05 : 0.11 + 0.06 * sq;
            int jn = jet;
            later(level, delay, () -> {
                for (int i = 0; i < jn; i++) {
                    double f = (double) i / jn, spd = (boom ? 7 : 3.5) * sq * (0.45 + f * 0.9) + rnd(-.3, .3);
                    WaterParticle k = drop(level, p.x + rnd(-.04, .04) * sq, y0, p.z + rnd(-.04, .04) * sq, rnd(-.25, .25) + fdx * fm * 0.9, spd,
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
                WaterParticle k = drop(level, p.x, y0, p.z, (rx / rl + rnd(-.12, .12)) * sp, (ry / rl + rnd(-.05, .12)) * sp, (rz / rl + rnd(-.12, .12)) * sp,
                        rnd(0.7, 1.3), rnd(0.1, 0.18) * Math.min(1.3, sq), 0.95F);
                if (k != null) k.mode = 1;
            }
        }
        LAST.put("plume", plume);
        // rings: flat on the surface, drifting at half the flow, growing only downstream
        int rings = boom ? 3 : (rico ? 1 : 2);
        for (int i = 0; i < rings; i++)
            WaterRings.add(level, p.x + (rico ? hx * 0.4 : 0), s.y(), p.z + (rico ? hz * 0.4 : 0), i * 0.22, 1.3 + i * 0.2, 0.25 * sq,
                    (1.6 + i * 0.6) * sq * (boom ? 1.6 : 1), 0.75F - i * 0.15F, fm > 0.01, false);
        LAST.put("rings", rings);
        int nf = scaled((int) Math.round((boom ? 40 : 8) + 6 * power));
        for (int i = 0; i < nf; i++) {
            double a = rnd(0, Math.PI * 2), r = rnd(0, 0.25) * sq, v = rnd(0.2, 0.9) * sq;
            foam(level, p.x + Math.cos(a) * r, y0, p.z + Math.sin(a) * r, Math.cos(a) * v, Math.sin(a) * v, rnd(2.5, 5), 0, 0);
        }
        LAST.put("foam", nf);
        int nb = 0;
        if (!thin) {
            nb = scaled((int) Math.round((boom ? 60 : 12) * power * (rico ? 0.3 : 1)));
            double depth = Math.min(s.depth() - 0.2, (boom ? 2 : 1.6) * sq * (rico ? 0.2 : 1));
            for (int i = 0; i < nb; i++) {
                double t = rnd(0, 1) * depth / Math.max(0.2, -d.y);
                spawn(level, WaterParticle.Kind.BUBBLE, p.x + d.x * t + rnd(-.08, .08), Math.max(s.y() - s.depth() + 0.1, p.y + d.y * t), p.z + d.z * t + rnd(-.08, .08),
                        fdx * fm * fs, rnd(0.9, 1.8), fdz * fm * fs, 6, rnd(0.1, 0.16) * (boom ? 1.4 : 1), 0.9F);
            }
        }
        LAST.put("bubbles", nb);
        int nm = scaled((int) Math.round((boom ? 28 : 4) + 5 * power));
        for (int i = 0; i < nm; i++)
            mist(level, p.x + rnd(-.2, .2) * sq, y0 + rnd(0.1, 0.5) * sq, p.z + rnd(-.2, .2) * sq, rnd(-.6, .6) * sq + (rico ? hx * 2 : 0) + fdx * fm * fs,
                    rnd(0.3, 1.4) * sq, rnd(-.6, .6) * sq + (rico ? hz * 2 : 0) + fdz * fm * fs, rnd(1.2, 2.2), rnd(0.5, 0.9) * sq, 0.35F);
        LAST.put("mist", nm);
    }

    // ---- a waterfall: no rings and no jet; spray out along the face's normal and down with the stream, foam that
    // sticks to it and falls with it ----
    static void splashFall(Level level, Vec3 p, Vec3 d, Vec3 n, double power, boolean boom) {
        double sq = Math.sqrt(power), nx = n.x, nz = n.z, fall = WaterParticle.FALL_SPEED;
        LAST.clear();
        int k = scaled((int) Math.round((boom ? 120 : 26) * power));
        for (int i = 0; i < k; i++) {
            double out = rnd(1, 3.6) * sq, side = rnd(-2.4, 2.4) * sq;
            WaterParticle q = drop(level, p.x + nx * 0.05 + rnd(-.08, .08) * Math.abs(nz), p.y + rnd(-.08, .08), p.z + nz * 0.05 + rnd(-.08, .08) * Math.abs(nx),
                    nx * out + side * Math.abs(nz) - d.x * 0.4 * sq, rnd(-1.2, 2.2) * sq - fall * 0.35, nz * out + side * Math.abs(nx) - d.z * 0.4 * sq,
                    rnd(0.9, 1.5), rnd(0.12, 0.22) * Math.min(1.3, sq), 1F);
            if (q != null) q.mode = 1;
        }
        LAST.put("spray", k);
        int nf = scaled((int) Math.round((boom ? 40 : 10) + 6 * power));
        for (int i = 0; i < nf; i++)
            foam(level, p.x + nx * 0.04 + rnd(-.25, .25) * sq * Math.abs(nz), p.y + rnd(-.2, .3) * sq, p.z + nz * 0.04 + rnd(-.25, .25) * sq * Math.abs(nx), 0, 0,
                    rnd(3, 5), 1, -rnd(1, 4));
        LAST.put("foam", nf);
        int nm = scaled((int) Math.round((boom ? 24 : 4) + 4 * power));
        for (int i = 0; i < nm; i++)
            mist(level, p.x + nx * 0.3, p.y + rnd(-.2, .2), p.z + nz * 0.3, nx * rnd(0.4, 1.4) * sq + rnd(-.5, .5) * Math.abs(nz), -fall * 0.25 + rnd(-.3, .3),
                    nz * rnd(0.4, 1.4) * sq + rnd(-.5, .5) * Math.abs(nx), rnd(1.2, 2), rnd(0.5, 0.8) * sq, 0.35F);
        LAST.put("mist", nm);
    }

    // ---- sounds: vanilla splashes by strength; one shot's pellets do not each play one ----
    private static long soundTick = Long.MIN_VALUE;
    private static int sounds;

    private static boolean quietSounds(Level level) {
        long t = level.getGameTime();
        if (t != soundTick) {
            soundTick = t;
            sounds = 0;
        }
        return sounds++ < 3;
    }

    private static void sound(Level level, Vec3 at, WaterClass cls, double power, boolean pellet) {
        if (!quietSounds(level)) return;
        SoundEvent ev;
        float vol;
        if (cls.explosive || power >= 1.5) {
            ev = SoundEvents.PLAYER_SPLASH_HIGH_SPEED;
            vol = 0.6F;
        } else if (pellet || power < 0.8) {
            ev = SoundEvents.FISHING_BOBBER_SPLASH;
            vol = 0.5F;
        } else {
            ev = SoundEvents.GENERIC_SPLASH;
            vol = 0.5F;
        }
        level.playLocalSound(at.x, at.y, at.z, ev, SoundSource.NEUTRAL, vol, 0.9F + (float) rnd(0, 0.3), false);
    }

    // ---- delayed effects (the jet), run on the client tick ----
    private record Later(long at, Runnable run) {
    }

    private static final java.util.ArrayDeque<Later> LATER = new java.util.ArrayDeque<>();

    private static long ticks;
    private static @Nullable Level lastLevel;

    private static void later(Level level, double seconds, Runnable r) {
        LATER.add(new Later(ticks + Math.max(1, Math.round(seconds * 20)), r));
    }

    // the client tick: delayed effects, the bodies on the bottom
    public static void clientTick() {
        WaterEvents.ntmBubbles = !Config.waterSplash;
        Minecraft mc = Minecraft.getInstance();
        // another world or dimension: what is left belongs to the old one
        if (mc.level != lastLevel) {
            clear();
            lastLevel = mc.level;
        }
        if (mc.level == null || mc.isPaused()) return;
        long now = ++ticks;
        int n = LATER.size();
        for (int i = 0; i < n; i++) {
            Later l = LATER.poll();
            if (l == null) break;
            if (l.at() <= now) l.run().run();
            else LATER.add(l);
        }
    }

    public static void clear() {
        LATER.clear();
        WaterRings.clear();
        SunkBullets.clear();
        WaterParticle.forget();
        TRACED.clear();
        gasTick = Long.MIN_VALUE;
        gasAt = Vec3.ZERO;
    }

    // ---- a shot under water (Tracers, when the shot starts): no NTM flash (MixinMuzzleFlash), a gas bubble at the
    // muzzle, of the calibre's size, that swells for 2 ticks, shrinks, bounces and breaks into 8 + 44R bubbles ----
    private static long gasTick = Long.MIN_VALUE;
    private static Vec3 gasAt = Vec3.ZERO;

    // a point in water for the effects (any liquid but lava)
    public static boolean wetAt(Level level, Vec3 p) {
        BlockPos pos = BlockPos.containing(p);
        net.minecraft.world.level.material.FluidState fs = level.getFluidState(pos);
        return WaterSurface.wet(fs) && p.y < pos.getY() + fs.getHeight(level, pos);
    }

    public static void fired(Level level, Vec3 muzzle, Vec3 dir, @Nullable BulletConfig config) {
        if (!on() || !(level instanceof ClientLevel)) return;
        if (!wetAt(level, muzzle)) return;
        // the pellets of one shot come as several tracers: one bubble for them
        long tick = level.getGameTime();
        if (tick == gasTick && muzzle.distanceToSqr(gasAt) < 0.25) return;
        gasTick = tick;
        gasAt = muzzle;
        double r = gasRadius(config);
        WaterParticle g = spawn(level, WaterParticle.Kind.GAS, muzzle.x, muzzle.y, muzzle.z, 0, 0, 0, 0.3, 0.001, 1F);
        if (g == null) return;
        g.gasR = r;
        g.gasN = (int) Math.round(8 + 44 * r);
        g.origin = muzzle;
        g.forward = dir.lengthSqr() > 1e-9 ? dir.normalize() : Vec3.ZERO;
    }

    // the gas bubble's radius by calibre (the owner's numbers): p22 .15, p9 .25, m357 .3, m44 .32, r556 .3, r762 .35,
    // bmg50 .5, b75 .3, g12 .4, g10 .45, stone .35
    private static final java.util.Map<String, Double> GAS = java.util.Map.ofEntries(java.util.Map.entry("p22", .15), java.util.Map.entry("p9", .25),
            java.util.Map.entry("m357", .3), java.util.Map.entry("m44", .32), java.util.Map.entry("r556", .3), java.util.Map.entry("r762", .35),
            java.util.Map.entry("bmg50", .5), java.util.Map.entry("b75", .3), java.util.Map.entry("g12", .4), java.util.Map.entry("g10", .45),
            java.util.Map.entry("stone", .35));

    static double gasRadius(@Nullable BulletConfig config) {
        String cal = calibre(config);
        Double r = cal == null ? null : GAS.get(cal);
        return r != null ? r : 0.3;
    }

    // "m44" of hbm:ammo_standard_m44_jhp
    static @Nullable String calibre(@Nullable BulletConfig config) {
        String id = ammo(config);
        if (id == null) return null;
        int cut = id.indexOf('_');
        return cut < 0 ? id : id.substring(0, cut);
    }

    // "m44_jhp" of hbm:ammo_standard_m44_jhp (ammo_secret_ the same)
    public static @Nullable String ammo(@Nullable BulletConfig config) {
        if (config == null || config.ammoItem == null) return null;
        try {
            var key = BuiltInRegistries.ITEM.getKey(config.ammoItem.get());
            String p = key.getPath();
            if (p.startsWith("ammo_standard_")) return p.substring("ammo_standard_".length());
            if (p.startsWith("ammo_secret_")) return p.substring("ammo_secret_".length());
            return p;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- explosions under water and at the surface (part 3 calls these on the client) ----

    // A blast whose centre is under water: a flash (orange and weaker for an incendiary), a big pulsing gas bubble that
    // breaks into a cloud of bubbles, bits flying out, the bodies on the bottom pushed away. size = NTM's explosion
    // size in blocks.
    public static void blast(Level level, Vec3 at, float size, boolean incendiary) {
        blastPower(level, at, Math.clamp(size * 0.25, 0.2, 2.0), incendiary);
    }

    // the same in the demo's units (a round's charge: r762_he .35, bmg50_he .55, b75_exp .7, g12_explosive .6,
    // g10_explosive .25, b75_inc .25)
    static void blastPower(Level level, Vec3 at, double p, boolean inc) {
        if (!on() || !(level instanceof ClientLevel)) return;
        WaterParticle g = spawn(level, WaterParticle.Kind.GAS, at.x, at.y, at.z, 0, 0, 0, 0.3, 0.001, 1F);
        if (g != null) {
            g.gasR = p;
            g.gasN = (int) Math.round(20 + 80 * p);
            g.origin = at;
            g.still = true;
        }
        WaterParticle f = spawn(level, WaterParticle.Kind.FLASH, at.x, at.y, at.z, 0, 0, 0, 0.1, p * (inc ? 2.4 : 1.6), 0.9F);
        if (f != null) {
            if (inc) f.setColor(1F, 0.63F, 0.25F);
            else f.setColor(1F, 0.96F, 0.82F);
        }
        int n = scaled((int) Math.round(30 * p));
        for (int j = 0; j < n; j++) {
            double dx = rnd(-1, 1), dy = rnd(-.2, 1), dz = rnd(-1, 1), l = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (l < 1e-6) continue;
            WaterParticle q = spawn(level, WaterParticle.Kind.DEBRIS, at.x, at.y + 0.05, at.z, dx / l * rnd(.5, 2), dy / l * rnd(.5, 2), dz / l * rnd(.5, 2), 0.8,
                    0.05, 1F);
            if (q != null) q.setColor(0.91F, 0.95F, 1F);
        }
        SunkBullets.blast(at, p, inc);
        level.playLocalSound(at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.NEUTRAL, (float) Math.min(1.0, 0.3 + p), 0.6F, false);
    }

    // A blast at the surface or within a block under it: a column of water instead of a fireball, a tall fountain of
    // drops and water dust, rings and foam. size = NTM's explosion size in blocks.
    public static void blastPlume(Level level, Vec3 at, float size) {
        if (!on() || !(level instanceof ClientLevel)) return;
        WaterSurface.Info s = WaterSurface.at(level, at.x, at.y + 1.2, at.z);
        if (s == null) return;
        double p = Math.clamp(size * 0.85, 0.5, 6.0);
        splashSurface(level, new Vec3(at.x, s.y(), at.z), new Vec3(0, -1, 0), s, p, true, false);
        level.playLocalSound(at.x, s.y(), at.z, SoundEvents.PLAYER_SPLASH_HIGH_SPEED, SoundSource.NEUTRAL, 1F, 0.7F, false);
    }

    // ---- the stand's surface audit: a drop, foam or a pop more than 0.15 under the surface, foam or a pop over air ----
    static void audit(WaterParticle p) {
        switch (p.kind) {
            case FOAM, POP -> {
                if (p.kind == WaterParticle.Kind.FOAM && p.mode == 1) return;
                WaterSurface.Info s = WaterSurface.at(p.level(), p.x(), p.y() + 0.4, p.z());
                if (s == null) {
                    overAir++;
                    fault(p, "over air", Double.NaN);
                } else under(p, s.y() - p.y());
            }
            case DROP -> {
                if (!WaterSurface.wet(p.level(), (int) Math.floor(p.x()), (int) Math.floor(p.y()), (int) Math.floor(p.z()))) return;
                WaterSurface.Info s = WaterSurface.at(p.level(), p.x(), p.y() + 0.4, p.z());
                if (s != null) under(p, s.y() - p.y());
            }
            default -> {
            }
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
        acup.gunfeel.GunFeel.LOGGER.info("[waterfx] audit {} {} mode {} age {} at {} {} {} by {} fluid here {} below {}", what, p.kind, p.mode, p.age(),
                String.format("%.3f", p.x()), String.format("%.3f", p.y()), String.format("%.3f", p.z()), String.format("%.3f", d),
                p.level().getFluidState(BlockPos.containing(p.x(), p.y(), p.z())), p.level().getFluidState(BlockPos.containing(p.x(), p.y() - 1, p.z())));
    }

    public static void resetCounts() {
        java.util.Arrays.fill(SPAWNED, 0);
        chips = below = overAir = 0;
        worstBelow = 0;
    }
}
