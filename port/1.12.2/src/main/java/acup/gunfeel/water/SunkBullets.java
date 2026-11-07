package acup.gunfeel.water;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.particle.SpentCasing;

import acup.gunfeel.Config;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

// 26.2's SunkBullets (water 3): bullets under water you can see: a low-poly model of the round (AmmoModels) while it
// flies in water, a hollow or soft point opening into a mushroom as it goes, a light streak behind it fading as it
// slows; and once the water stopped it, a body on the client only (SunkPhysics): it drifts on, tumbles and sinks at
// 1.2 blocks a second, and lies on the bottom (waterBodyLife seconds, at most waterBodyMax). A round with an impact
// fuze is no body of ours: it sinks as NTM's own entity and goes off at the first thing it touches; its model is drawn
// at that entity while it lasts.
// port: drawn by one particle of the lit layer (Drawer, FX layer 3), which 1.12.2 draws before the translucent
// terrain: drawn after it (RenderWorldLastEvent) a round under water would lie over the water's colour. Shaded on the
// CPU as vanilla's item lighting shades (two lights, ambient 0.4), times the world's light, on the white texture.
// NTM CE's client entity is not told that water stopped it: a fuzed round counts as sinking when it is in water and
// moves under 0.1 blocks a tick (water stops a round at 0.15 of a speed of 5 and more; a sinking one goes 0.05).
public final class SunkBullets {
    private SunkBullets() {
    }

    private static SunkPhysics phys;
    private static int physMax;
    private static double physLie;
    private static final ResourceLocation WHITE = new ResourceLocation("ntmgunfeel", "textures/particle/water_white.png");

    // how far a hollow / soft point has gone before it is fully open (demo EXP)
    private static final double JHP_S = 0.12, SP_S = 0.2;

    private static SunkPhysics phys() {
        int max = Math.max(0, Config.waterBodyMax);
        double lie = Math.max(1, Config.waterBodyLife);
        if (phys == null || max != physMax || lie != physLie) {
            phys = new SunkPhysics(Math.max(1, max), lie);
            physMax = max;
            physLie = lie;
        }
        return phys;
    }

    private static final class Tag {
        final AmmoModels.Model model;
        final float open;

        Tag(AmmoModels.Model model, float open) {
            this.model = model;
            this.open = open;
        }
    }

    // sized to the casing NTM CE drops for the round (its type and scale), times waterBodyScale
    public static AmmoModels.Model model(BulletConfig config) {
        String ammo = WaterFx.ammo(config);
        if (ammo == null || config == null) return null;
        SpentCasing c = config.casing;
        AmmoModels.Casing casing = c == null || c.getType() == null ? null
                : new AmmoModels.Casing(c.getType().name(), c.getScaleX(), c.getScaleY(), c.getScaleZ());
        return AmmoModels.of("ammo_standard_" + ammo, casing, Config.waterBodyScale);
    }

    // the water stopped a bullet (WaterFx on STOP): a body where it stopped, moving on at 1.5 blocks a second
    static void stopped(World world, WaterEvents.Event e, BulletConfig config) {
        if (Config.waterBodyMax <= 0 || broken) return;
        try {
            body(world, e, config);
        } catch (RuntimeException x) {
            broke(x);
        }
    }

    private static void body(World world, WaterEvents.Event e, BulletConfig config) {
        String ammo = WaterFx.ammo(config);
        // an impact fuze (grenades, rockets, HE and incendiary rounds): NTM's entity sinks on
        if (ammo == null || config == null || WaterClass.Fuze.of(config).any() || WaterClass.of(config).explosive) return;
        AmmoModels.Model m = model(config);
        if (m == null) return;
        float open = m.expands ? 1F : 0F;
        double r = m.radius * (1 + 0.75 * m.expandAmount * open) * (m.capsule ? 1 : 0.9);
        double len = m.length * (1 - 0.3 * m.expandAmount * open);
        double h = m.capsule ? Math.max(0, len / 2 - r) : 0;
        Vec3d d = e.dir().lengthSquared() > 1e-9 ? e.dir().normalize() : new Vec3d(0, -1, 0);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double spin = (5 + rnd.nextDouble() * 5) * (rnd.nextBoolean() ? 1 : -1);
        double terminal = "flechette".equals(m.shape) ? 0.9 : ("stone".equals(WaterFx.calibre(config)) && !"ball".equals(m.shape) ? 0.7 : 1.2);
        phys().add(new Tag(m, open), e.at().x, e.at().y, e.at().z, d.x, d.y, d.z, r, h, d.x * 1.5, d.y * 1.5, d.z * 1.5, spin, terminal, 0, 0F, false);
        drawer(world);
    }

    // a blast nearby: the bodies near it pushed, the farther the less
    static void blast(Vec3d at, double power, boolean inc) {
        if (phys != null) phys.blast(at.x, at.y, at.z, power, inc);
    }

    static void clear() {
        if (phys != null) phys.clear();
        FLYING.clear();
        slow.clear();
        broken = false;
    }

    // An exception in the bodies' physics or in a model (a block of another mod in boxes, say, or a model that does not
    // load) would leave the world's drawing or the water's event and take the game with it: it is written to the log
    // once, and the rounds in water are off till this world or dimension is left (WaterFx.clientTick)
    private static boolean broken;

    private static void broke(RuntimeException e) {
        if (broken) return;
        broken = true;
        acup.gunfeel.GunFeel.LOGGER.error("rounds in water failed, off till the world is left", e);
        if (phys != null) phys.clear();
        FLYING.clear();
    }

    // for the stand
    public static boolean broken() {
        return broken;
    }

    public static int bodies() {
        return phys == null ? 0 : phys.bodies().size();
    }

    public static int asleep() {
        if (phys == null) return 0;
        int n = 0;
        for (SunkPhysics.Body b : phys.bodies()) if (b.asleep) n++;
        return n;
    }

    // for the stand
    public static List<SunkPhysics.Body> list() {
        return phys == null ? Collections.<SunkPhysics.Body>emptyList() : phys.bodies();
    }

    public static double physicsMs() {
        return phys == null ? 0 : phys.lastMs();
    }

    // drawn last frame: models in flight, sunk fuzed rounds, bodies (the stand); the most in flight in any frame (a round
    // flies under water for a few frames only: a look once a tick misses them)
    public static int drawnFlying, drawnFuzed, drawnBodies, mostFlying;

    // ---- bullets in flight under water, collected from the tracers each frame ----
    private static final class Flying {
        final AmmoModels.Model model;
        final Vec3d head, dir;
        final double f, speed;
        final float open;

        Flying(AmmoModels.Model model, Vec3d head, Vec3d dir, double f, double speed, float open) {
            this.model = model;
            this.head = head;
            this.dir = dir;
            this.f = f;
            this.speed = speed;
            this.open = open;
        }
    }

    private static final List<Flying> FLYING = new ArrayList<Flying>();

    // Tracers.underWater, each frame, each shot in water: its head, its direction, its f and speed (blocks a tick)
    public static void flying(World world, BulletConfig config, Vec3d head, Vec3d dir, double f, double speed) {
        if (!WaterFx.on() || broken || dir.lengthSquared() < 1e-12 || config == null) return;
        try {
            fly(config, head, dir, f, speed);
        } catch (RuntimeException e) {
            broke(e);
        }
    }

    private static void fly(BulletConfig config, Vec3d head, Vec3d dir, double f, double speed) {
        if (roundsScale != Config.waterBodyScale) {
            ROUNDS.clear();
            roundsScale = Config.waterBodyScale;
        }
        Round r = ROUNDS.get(config);
        if (r == null) ROUNDS.put(config, r = round(config));
        AmmoModels.Model m = r.model;
        if (m == null) return;
        float open = 0F;
        if (m.expands) {
            // opens over its first centimetres in water: the path so far from its f (f = exp(-s/L))
            double s = -r.length * Math.log(Math.max(1e-6, f));
            open = (float) Math.min(1, s / (r.jhp ? JHP_S : SP_S));
        }
        FLYING.add(new Flying(m, head, dir.normalize(), f, speed, open));
    }

    // a config's model, once: the frame asks for it per shot
    private static final class Round {
        final AmmoModels.Model model;
        final boolean jhp;
        final double length;

        Round(AmmoModels.Model model, boolean jhp, double length) {
            this.model = model;
            this.jhp = jhp;
            this.length = length;
        }
    }

    private static final java.util.Map<BulletConfig, Round> ROUNDS = new java.util.IdentityHashMap<BulletConfig, Round>();
    private static float roundsScale = Float.NaN;

    private static Round round(BulletConfig config) {
        String ammo = WaterFx.ammo(config);
        AmmoModels.Model m = model(config);
        return new Round(m, ammo != null && "jhp".equals(variant(ammo)), m != null && m.expands ? WaterClass.of(config).length : 0);
    }

    private static String variant(String ammo) {
        int cut = ammo.indexOf('_');
        return cut < 0 ? "" : ammo.substring(cut + 1);
    }

    // ---- the world for the bodies ----
    private static final SunkPhysics.World WORLD = new SunkPhysics.World() {
        @Override
        public double[] boxes(int x, int y, int z) {
            World w = Minecraft.getMinecraft().world;
            if (w == null) return EMPTY;
            BlockPos p = new BlockPos(x, y, z);
            IBlockState s = w.getBlockState(p);
            List<AxisAlignedBB> boxes = new ArrayList<AxisAlignedBB>();
            try {
                s.addCollisionBoxToList(w, p, new AxisAlignedBB(p), boxes, null, false);
            } catch (RuntimeException e) {
                return EMPTY;
            }
            if (boxes.isEmpty()) return EMPTY;
            double[] out = new double[boxes.size() * 6];
            int i = 0;
            for (AxisAlignedBB b : boxes) {
                out[i++] = b.minX;
                out[i++] = b.minY;
                out[i++] = b.minZ;
                out[i++] = b.maxX;
                out[i++] = b.maxY;
                out[i++] = b.maxZ;
            }
            return out;
        }

        @Override
        public boolean water(int x, int y, int z) {
            World w = Minecraft.getMinecraft().world;
            return w != null && WaterSurface.wet(w, x, y, z);
        }

        @Override
        public int block(int x, int y, int z) {
            World w = Minecraft.getMinecraft().world;
            // block states are one object each, so the identity tells them apart without building the shape
            return w == null ? 0 : System.identityHashCode(w.getBlockState(CELL.setPos(x, y, z)));
        }

        @Override
        public boolean loaded(int x, int z) {
            World w = Minecraft.getMinecraft().world;
            return w != null && w.isBlockLoaded(CELL.setPos(x, 64, z));
        }
    };
    private static final BlockPos.MutableBlockPos CELL = new BlockPos.MutableBlockPos();
    private static final double[] EMPTY = new double[0];

    // ---- the lit layer's particle that draws them (one at a time; gone with the world's particles) ----
    private static Drawer drawer;

    private static void drawer(World world) {
        if (drawer != null && drawer.isAlive() && drawer.world() == world) return;
        drawer = new Drawer(world);
        Minecraft.getMinecraft().effectRenderer.addEffect(drawer);
    }

    // the client tick: the drawer is there while the effects are on (the tracers' rounds in water are asked for as it
    // draws; particles go when the world changes, it comes back here)
    public static void tick(World world) {
        if (world == null || !WaterFx.on()) return;
        drawer(world);
        if (Minecraft.getMinecraft().isGamePaused() || broken) return;
        try {
            countSinking(world);
        } catch (RuntimeException e) {
            broke(e);
        }
    }

    static final class Drawer extends Particle {
        Drawer(World world) {
            super(world, 0, 0, 0);
            particleMaxAge = Integer.MAX_VALUE;
        }

        World world() {
            return world;
        }

        @Override
        public int getFXLayer() {
            return 3;
        }

        @Override
        public void onUpdate() {
            particleAge = 0;
            if (drawer != this || !WaterFx.on()) {
                setExpired();
                if (drawer == this) drawer = null;
            }
        }

        @Override
        public void renderParticle(BufferBuilder buffer, Entity viewer, float partial, float rx, float rz, float ryz, float rxy, float rxz) {
            if (drawer != this || !WaterFx.on()) return;
            double cx = viewer.lastTickPosX + (viewer.posX - viewer.lastTickPosX) * partial;
            double cy = viewer.lastTickPosY + (viewer.posY - viewer.lastTickPosY) * partial;
            double cz = viewer.lastTickPosZ + (viewer.posZ - viewer.lastTickPosZ) * partial;
            if (broken) return;
            try {
                render(world, partial, cx, cy, cz);
            } catch (RuntimeException e) {
                // render has put the buffer and GL back (its finally)
                broke(e);
            }
        }
    }

    // ---- each frame: physics, then the models ----
    private static long lastNanos;

    static void render(World world, float partial, double camX, double camY, double camZ) {
        long now = System.nanoTime();
        double dt = lastNanos == 0 ? 0 : Math.max(0, Math.min(0.05, (now - lastNanos) / 1e9));
        lastNanos = now;
        boolean paused = Minecraft.getMinecraft().isGamePaused();
        SunkPhysics ph = phys;
        if (ph != null && !ph.bodies().isEmpty() && !paused) ph.step(WORLD, dt);
        List<Fuzed> fuzed = fuzed(world, partial, camX, camY, camZ);
        FLYING.clear();
        acup.gunfeel.Tracers.underWater(world);
        List<Flying> flying = new ArrayList<Flying>(FLYING);
        FLYING.clear();
        List<SunkPhysics.Body> bodies = ph == null ? Collections.<SunkPhysics.Body>emptyList() : new ArrayList<SunkPhysics.Body>(ph.bodies());
        drawnFlying = flying.size();
        mostFlying = Math.max(mostFlying, drawnFlying);
        drawnFuzed = fuzed.size();
        drawnBodies = 0;
        if (flying.isEmpty() && fuzed.isEmpty() && bodies.isEmpty()) return;
        Minecraft mc = Minecraft.getMinecraft();
        GlSaved saved = new GlSaved();
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        boolean building = false;
        try {
            GlStateManager.disableLighting();
            GlStateManager.enableDepth();
            GlStateManager.depthMask(true);
            GlStateManager.disableBlend();
            GlStateManager.enableCull();
            GlStateManager.enableTexture2D();
            GlStateManager.color(1F, 1F, 1F, 1F);
            mc.getTextureManager().bindTexture(WHITE);
            mc.entityRenderer.enableLightmap();
            buf.begin(GL11.GL_TRIANGLES, DefaultVertexFormats.BLOCK);
            building = true;
            Quaternionf q = new Quaternionf();
            for (Flying fl : flying) {
                // the model's nose is -Z: turn -Z onto the direction of flight
                q.rotationTo(0, 0, -1, (float) fl.dir.x, (float) fl.dir.y, (float) fl.dir.z);
                // the model's centre half a length behind the head
                Vec3d c = fl.head.subtract(fl.dir.scale(fl.model.length / 2));
                mesh(world, buf, fl.model, fl.open, q, c, camX, camY, camZ, 1F);
            }
            for (Fuzed z : fuzed) {
                q.rotationTo(0, 0, -1, (float) z.dir.x, (float) z.dir.y, (float) z.dir.z);
                mesh(world, buf, z.model, 0F, q, z.at, camX, camY, camZ, 1F);
            }
            for (SunkPhysics.Body b : bodies) {
                double dx = b.px - camX, dy = b.py - camY, dz = b.pz - camZ;
                if (dx * dx + dy * dy + dz * dz > 48 * 48) continue;
                Tag t = (Tag) b.tag;
                q.set(b.q[0], b.q[1], b.q[2], b.q[3]);
                mesh(world, buf, t.model, t.open, q, new Vec3d(b.px, b.py, b.pz), camX, camY, camZ, Math.max(0.05F, b.fade));
                drawnBodies++;
            }
            building = false;
            tess.draw();
            mc.entityRenderer.disableLightmap();
            if (!flying.isEmpty()) {
                building = true;
                streaks(buf, tess, flying, camX, camY, camZ);
                building = false;
            }
        } finally {
            // a throw half way: the shared buffer is not left building for every frame after
            if (building) try {
                buf.finishDrawing();
            } catch (IllegalStateException ignored) {
                // it was not building
            }
            saved.restore();
        }
    }

    // NTM's own sinking rounds with an impact fuze, stopped by the water: their model where the entity is, lying along
    // its heading
    private static final class Fuzed {
        final AmmoModels.Model model;
        final Vec3d at, dir;

        Fuzed(AmmoModels.Model model, Vec3d at, Vec3d dir) {
            this.model = model;
            this.at = at;
            this.dir = dir;
        }
    }

    // fuzed rounds seen slow in water, ticks in a row (entity id -> ticks): the client's entity moves by the server's
    // packets, and a tick without one is no sinking
    private static java.util.Map<Integer, Integer> slow = new java.util.HashMap<Integer, Integer>();
    static final int SINK_TICKS = 3;

    private static void countSinking(World world) {
        java.util.Map<Integer, Integer> next = new java.util.HashMap<Integer, Integer>();
        for (Entity en : world.loadedEntityList) {
            keepHeading(en);
            if (!sinking(world, en)) continue;
            Integer n = slow.get(en.getEntityId());
            next.put(en.getEntityId(), n == null ? 1 : Math.min(SINK_TICKS, n + 1));
        }
        slow = next;
    }

    // the level way NTM's round with an impact fuze goes while it flies: kept for its model once it sinks (headingOf).
    // Its motion here is the server's (sendsVelocityUpdates), of length 1 in flight (NTM's speed is motionMult), and
    // (0, at most 0.05 down, 0) once water stopped it (WaterTick.sink)
    private static void keepHeading(Entity en) {
        if (!(en instanceof EntityBulletBaseMK4) || !(en instanceof WaterBullet)) return;
        BulletConfig c = ((EntityBulletBaseMK4) en).getBulletConfig();
        if (c == null || !WaterClass.Fuze.of(c).any()) return;
        double h = Math.sqrt(en.motionX * en.motionX + en.motionZ * en.motionZ);
        if (h * h > 0.01) ((WaterBullet) en).gunfeel$sunkHeading(new Vec3d(en.motionX / h, 0, en.motionZ / h));
    }

    // the way a sunk round with an impact fuze lies (also for the stand)
    public static Vec3d headingOf(Entity en) {
        Vec3d h = en instanceof WaterBullet ? ((WaterBullet) en).gunfeel$sunkHeading() : null;
        return h != null ? h : heading(en.rotationYaw);
    }

    // the heading of NTM's round from its yaw, when the way it flew was not kept: NTM CE sets yaw = atan2(motionX,
    // motionZ) in flight, so the heading is (sin, cos), not a player's (-sin, cos)
    static Vec3d heading(float yawDegrees) {
        double yaw = Math.toRadians(yawDegrees);
        return new Vec3d(Math.sin(yaw), 0, Math.cos(yaw));
    }

    // a fuzed round in water moving under 0.1 blocks a tick, SINK_TICKS in a row (also for the stand)
    public static boolean sunk(World world, Entity en) {
        Integer n = slow.get(en.getEntityId());
        return n != null && n >= SINK_TICKS && sinking(world, en);
    }

    private static boolean sinking(World world, Entity en) {
        if (!(en instanceof EntityBulletBaseMK4)) return false;
        BulletConfig c = ((EntityBulletBaseMK4) en).getBulletConfig();
        if (c == null || !WaterClass.Fuze.of(c).any()) return false;
        double mx = en.posX - en.prevPosX, my = en.posY - en.prevPosY, mz = en.posZ - en.prevPosZ;
        return mx * mx + my * my + mz * mz < 0.01 && WaterFx.wetAt(world, en.getPositionVector());
    }

    private static List<Fuzed> fuzed(World world, float pt, double camX, double camY, double camZ) {
        if (!WaterFx.on()) return Collections.emptyList();
        List<Fuzed> out = null;
        for (Entity en : world.loadedEntityList) {
            Integer n = slow.get(en.getEntityId());
            if (n == null || n < SINK_TICKS || !sinking(world, en)) continue;
            double x = en.prevPosX + (en.posX - en.prevPosX) * pt, y = en.prevPosY + (en.posY - en.prevPosY) * pt, z = en.prevPosZ + (en.posZ - en.prevPosZ) * pt;
            if (sq(x - camX) + sq(y - camY) + sq(z - camZ) > 48 * 48) continue;
            AmmoModels.Model m = model(((EntityBulletBaseMK4) en).getBulletConfig());
            if (m == null) continue;
            if (out == null) out = new ArrayList<Fuzed>();
            out.add(new Fuzed(m, new Vec3d(x, y, z), headingOf(en)));
        }
        return out == null ? Collections.<Fuzed>emptyList() : out;
    }

    private static double sq(double v) {
        return v * v;
    }

    // vanilla's item lighting (RenderHelper): two lights, each 0.6 of the diffuse, and 0.4 ambient
    private static final Vector3f L0 = new Vector3f(0.2F, 1F, -0.7F).normalize(), L1 = new Vector3f(-0.2F, 1F, 0.7F).normalize();

    private static float shade(Vector3f n) {
        return Math.min(1F, 0.4F + 0.6F * (Math.max(0F, n.dot(L0)) + Math.max(0F, n.dot(L1))));
    }

    // the opened shape of each model, one buffer per model
    private static final java.util.Map<AmmoModels.Model, float[]> MORPH = new java.util.IdentityHashMap<AmmoModels.Model, float[]>();

    // one model: full detail up to 8 blocks away, farther a six-sided prism of its size (a few faces, not hundreds)
    private static void mesh(World world, BufferBuilder buf, AmmoModels.Model m, float open, Quaternionf q, Vec3d c, double camX, double camY, double camZ,
            float scale) {
        int light = world.getCombinedLight(new BlockPos(c), 0);
        int lj = light >> 16 & 65535, lk = light & 65535;
        float cx = (float) (c.x - camX), cy = (float) (c.y - camY), cz = (float) (c.z - camZ);
        double d2 = cx * cx + cy * cy + cz * cz;
        Vector3f a = new Vector3f(), n = new Vector3f();
        if (d2 > 64) {
            prism(buf, m, q, cx, cy, cz, scale, lj, lk);
            return;
        }
        float[] pos = m.positions;
        if (open > 0 && m.mushroom != null) {
            float[] out = MORPH.get(m);
            if (out == null) MORPH.put(m, out = new float[m.positions.length]);
            pos = m.morph(open, out);
        }
        float[] nor = m.normals;
        int[] col = m.colors;
        int verts = m.positions.length / 3;
        // a model short of normals or colours fails here, at once (the frame's models are dropped, render's finally)
        if (pos.length < verts * 3 || nor.length < verts * 3 || col.length < verts) throw new IllegalStateException("broken model " + m.shape);
        for (int j = 0; j < verts; j++) {
            a.set(pos[j * 3], pos[j * 3 + 1], pos[j * 3 + 2]).mul(scale);
            q.transform(a);
            n.set(nor[j * 3], nor[j * 3 + 1], nor[j * 3 + 2]);
            q.transform(n);
            vertex(buf, a.x + cx, a.y + cy, a.z + cz, col[j], shade(n), lj, lk);
        }
    }

    private static void vertex(BufferBuilder buf, float x, float y, float z, int rgb, float s, int lj, int lk) {
        int r = (int) ((rgb >> 16 & 255) * s), g = (int) ((rgb >> 8 & 255) * s), b = (int) ((rgb & 255) * s);
        buf.pos(x, y, z).color(r, g, b, 255).tex(0.5, 0.5).lightmap(lj, lk).endVertex();
    }

    private static void prism(BufferBuilder buf, AmmoModels.Model m, Quaternionf q, float cx, float cy, float cz, float scale, int lj, int lk) {
        float r = m.radius * scale, hl = m.length / 2 * scale;
        int sides = 6;
        Vector3f a = new Vector3f(), n = new Vector3f();
        for (int s = 0; s < sides; s++) {
            double t0 = s * Math.PI * 2 / sides, t1 = (s + 1) * Math.PI * 2 / sides;
            float x0 = (float) Math.cos(t0) * r, y0 = (float) Math.sin(t0) * r, x1 = (float) Math.cos(t1) * r, y1 = (float) Math.sin(t1) * r;
            float nx = (float) Math.cos((t0 + t1) / 2), ny = (float) Math.sin((t0 + t1) / 2);
            // the side as two triangles (26.2's quad 0 1 2 3)
            float[][] side = {{x0, y0, hl}, {x1, y1, hl}, {x1, y1, -hl}, {x0, y0, hl}, {x1, y1, -hl}, {x0, y0, -hl}};
            n.set(nx, ny, 0);
            q.transform(n);
            float sh = shade(n);
            for (float[] w : side) {
                a.set(w[0], w[1], w[2]);
                q.transform(a);
                int col = w[2] < 0 && m.capsule ? m.noseColor : m.bodyColor;
                vertex(buf, a.x + cx, a.y + cy, a.z + cz, col, sh, lj, lk);
            }
            // the ends: a triangle to the axis
            for (int end = -1; end <= 1; end += 2) {
                float z = end * hl;
                float[][] cap = end > 0 ? new float[][] {{0, 0, z}, {x1, y1, z}, {x0, y0, z}} : new float[][] {{0, 0, z}, {x0, y0, z}, {x1, y1, z}};
                n.set(0, 0, end);
                q.transform(n);
                float shc = shade(n);
                for (float[] w : cap) {
                    a.set(w[0], w[1], w[2]);
                    q.transform(a);
                    vertex(buf, a.x + cx, a.y + cy, a.z + cz, end < 0 ? m.noseColor : m.bodyColor, shc, lj, lk);
                }
            }
        }
    }

    // the light streak behind a bullet in water, bright while it is fast, gone below a fifth of its speed
    private static void streaks(BufferBuilder buf, Tessellator tess, List<Flying> flying, double camX, double camY, double camZ) {
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        GlStateManager.disableCull();
        GlStateManager.depthMask(false);
        GlStateManager.disableAlpha();
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        for (Flying fl : flying) {
            float op = (float) Math.clamp((fl.f - 0.2) / 0.6, 0, 1);
            if (op < 0.01F) continue;
            double vps = fl.speed * 20;
            double sl = Math.min(1.2, vps * 0.004);
            Vec3d b = fl.head.subtract(fl.dir.scale(fl.model.length)).subtract(camX, camY, camZ);
            Vec3d t = b.subtract(fl.dir.scale(sl));
            Vec3d side = fl.dir.crossProduct(b);
            if (side.lengthSquared() < 1e-12) continue;
            side = side.normalize().scale(fl.model.radius * 0.6);
            float[] head = {0.07F + 0.8F * op, 0.25F + 0.7F * op, 0.52F + 0.48F * op, 0.9F * op};
            float[] tail = {0.07F, 0.25F, 0.52F, 0F};
            Vec3d[] qd = {b.add(side), b.subtract(side), t.subtract(side), t.add(side)};
            for (int j = 0; j < 4; j++) {
                float[] c = j < 2 ? head : tail;
                buf.pos(qd[j].x, qd[j].y, qd[j].z).color(c[0], c[1], c[2], c[3]).endVertex();
            }
        }
        tess.draw();
    }
}
