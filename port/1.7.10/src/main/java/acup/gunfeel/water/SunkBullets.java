package acup.gunfeel.water;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.particle.SpentCasing;

import acup.gunfeel.Config;
import acup.gunfeel.phys.Vec3;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.entity.Entity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import net.minecraft.world.chunk.EmptyChunk;

// Bullets under water you can see (notes/water/bubble-demo.tpl.html): a low-poly model of the round (AmmoModels, as
// big next to NTM's casing of the round as the real bullet next to its case, × waterBodyScale) while it flies in water,
// a hollow or soft point opening into a mushroom as it goes, a light streak behind it fading as it slows; and once the
// water stopped it, a body on the client only (SunkPhysics): it drifts on, tumbles and sinks at 1.2 blocks a second,
// and lies on the bottom (waterBodyLife seconds, at most waterBodyMax). A bullet that hit a block is gone, as in NTM.
// A round with an impact fuze is no body of ours: it sinks as NTM's own entity and goes off at the first thing it
// touches (the explosives part); its model is drawn at that entity while it lasts.
// port: 26.2's SunkBullets drawn with the Tessellator, lit as an entity is (RenderHelper's two lights, the lightmap),
// on the mod's white texture. Drawn with the world before the water (Tracers.drawWorldHoles, as the holes: the water,
// drawn after, lies over them as over 26.2's solid pass), else at RenderWorldLastEvent. The rounds in flight are taken
// from the tracers at RenderWorldLastEvent, so with the world they are a frame behind. A block's identity for the
// sleepers (X517: a round lying on a block falls when the block goes) is its id and metadata.
public final class SunkBullets {
    private SunkBullets() {
    }

    private static SunkPhysics phys;
    private static int physMax;
    private static double physLie;

    // how far a hollow / soft point has gone before it is fully open, and how far it opens (demo EXP)
    private static final double JHP_S = 0.12, SP_S = 0.2;
    private static final ResourceLocation WHITE = new ResourceLocation("ntmgunfeel", "textures/particle/water_white.png");

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

    private record Tag(AmmoModels.Model model, float open) {
    }

    // the water stopped a bullet (WaterFx on STOP): a body where it stopped, moving on at 1.5 blocks a second
    static void stopped(World level, WaterEvents.Event e) {
        if (Config.waterBodyMax <= 0 || broken) return;
        try {
            body(e);
        } catch (RuntimeException x) {
            broke(x);
        }
    }

    private static void body(WaterEvents.Event e) {
        BulletConfig config = WaterFx.config(e);
        String ammo = WaterFx.ammo(config);
        // an impact fuze (grenades, rockets, HE and incendiary rounds): NTM's entity sinks on, the explosives part's
        if (ammo == null || config == null || WaterClass.Fuze.ofConfig(config).any() || WaterClass.ofConfig(config).explosive) return;
        AmmoModels.Model m = model(config);
        if (m == null) return;
        float open = m.expands ? 1F : 0F;
        double r = m.radius * (1 + 0.75 * m.expandAmount * open) * (m.capsule ? 1 : 0.9);
        double len = m.length * (1 - 0.3 * m.expandAmount * open);
        double h = m.capsule ? Math.max(0, len / 2 - r) : 0;
        Vec3 d = e.dir().lengthSqr() > 1e-9 ? e.dir().normalize() : new Vec3(0, -1, 0);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double spin = (5 + rnd.nextDouble() * 5) * (rnd.nextBoolean() ? 1 : -1);
        double terminal = "flechette".equals(m.shape) ? 0.9 : ("stone".equals(WaterFx.calibre(config)) && !"ball".equals(m.shape) ? 0.7 : 1.2);
        phys().add(new Tag(m, open), e.at().x, e.at().y, e.at().z, d.x, d.y, d.z, r, h, d.x * 1.5, d.y * 1.5, d.z * 1.5, spin, terminal, 0, 0F,
                false);
    }

    // a blast nearby (the explosives part's event): the bodies near it pushed, the farther the less
    static void blast(Vec3 at, double power, boolean inc) {
        if (phys != null) phys.blast(at.x, at.y, at.z, power, inc);
    }

    static void clear() {
        if (phys != null) phys.clear();
        FLYING.clear();
        broken = false;
    }

    // An exception in the bodies' physics or in a model (a block of another mod in boxes, say, or a model that does not
    // load) would leave the world's drawing or the water's event and take the game with it: it is written to the log
    // once, and the rounds in water are off till this world or dimension is left (WaterFx.clientTick, clear)
    private static boolean broken;

    private static void broke(RuntimeException e) {
        if (broken) return;
        broken = true;
        acup.gunfeel.GunFeel.LOGGER.error("rounds in water failed, off till the world is left", e);
        if (phys != null) phys.clear();
        FLYING.clear();
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

    // for the stand (WaterFxScenario)
    public static List<SunkPhysics.Body> list() {
        return phys == null ? Collections.<SunkPhysics.Body>emptyList() : phys.bodies();
    }

    public static double physicsMs() {
        return phys == null ? 0 : phys.lastMs();
    }

    // ---- bullets in flight under water, collected from the tracers each frame ----
    private record Flying(AmmoModels.Model model, Vec3 head, Vec3 dir, double f, double speed, float open) {
    }

    private static final List<Flying> FLYING = new ArrayList<>();
    // drawn so far (for the stand): rounds in flight, bodies
    public static int flyingDrawn, bodiesDrawn;

    // Tracers, each frame, each shot: its head, its direction, its f and speed (blocks a tick)
    public static void flying(World level, BulletConfig config, Vec3 head, Vec3 dir, double f, double speed) {
        if (!WaterFx.on() || broken || dir.lengthSqr() < 1e-12) return;
        if (config == null || !WaterFx.wetAt(level, head)) return;
        try {
            fly(config, head, dir, f, speed);
        } catch (RuntimeException e) {
            broke(e);
        }
    }

    private static void fly(BulletConfig config, Vec3 head, Vec3 dir, double f, double speed) {
        if (roundsScale != Config.waterBodyScale) {
            ROUNDS.clear();
            roundsScale = Config.waterBodyScale;
        }
        Round r = ROUNDS.computeIfAbsent(config, SunkBullets::round);
        AmmoModels.Model m = r.model();
        if (m == null) return;
        float open = 0F;
        if (m.expands) {
            // opens over its first centimetres in water: the path so far from its f (f = exp(-s/L))
            double s = -r.length() * Math.log(Math.max(1e-6, f));
            open = (float) Math.min(1, s / (r.jhp() ? JHP_S : SP_S));
        }
        // a frame that drew none (no RenderWorldLastEvent before the next world) leaves no more than a few hundred
        if (FLYING.size() < 512) FLYING.add(new Flying(m, head, dir.normalize(), f, speed, open));
    }

    // a config's model, once: the frame asks for it per shot
    private record Round(AmmoModels.Model model, boolean jhp, double length) {
    }

    private static final Map<BulletConfig, Round> ROUNDS = new IdentityHashMap<>();
    private static float roundsScale = Float.NaN;

    private static Round round(BulletConfig config) {
        String ammo = WaterFx.ammo(config);
        AmmoModels.Model m = model(config);
        return new Round(m, ammo != null && "jhp".equals(variant(ammo)), m != null && m.expands ? WaterClass.ofConfig(config).length : 0);
    }

    // the model of a round: as big next to NTM's casing of it as the real bullet next to its case, × waterBodyScale
    public static AmmoModels.Model model(BulletConfig config) {
        String ammo = WaterFx.ammo(config);
        if (ammo == null || config == null) return null;
        AmmoModels.Casing casing = casing(config.casing);
        return AmmoModels.of("ammo_standard_" + ammo, casing, Config.waterBodyScale);
    }

    static AmmoModels.Casing casing(SpentCasing c) {
        if (c == null || c.getType() == null) return null;
        return new AmmoModels.Casing(c.getType().name(), c.getScaleX(), c.getScaleY(), c.getScaleZ());
    }

    private static String variant(String ammo) {
        int cut = ammo.indexOf('_');
        return cut < 0 ? "" : ammo.substring(cut + 1);
    }

    // ---- each frame: physics, then the models ----
    private static long lastNanos;
    private static final double[] EMPTY = new double[0];
    private static final List<AxisAlignedBB> BOXES = new ArrayList<>();
    private static final SunkPhysics.World WORLD = new SunkPhysics.World() {
        @Override
        @SuppressWarnings("unchecked")
        public double[] boxes(int x, int y, int z) {
            World level = Minecraft.getMinecraft().theWorld;
            if (level == null) return EMPTY;
            Block b = level.getBlock(x, y, z);
            if (b.getMaterial().isLiquid()) return EMPTY;
            BOXES.clear();
            // the block's own boxes (stairs, fences: several), as an entity meets them. port: no entity (26.2: an
            // empty collision context); a block that wants one and throws is a full cube
            try {
                b.addCollisionBoxesToList(level, x, y, z, AxisAlignedBB.getBoundingBox(x, y, z, x + 1, y + 1, z + 1), BOXES, null);
            } catch (RuntimeException ex) {
                return new double[] {x, y, z, x + 1, y + 1, z + 1};
            }
            if (BOXES.isEmpty()) return EMPTY;
            double[] out = new double[BOXES.size() * 6];
            int i = 0;
            for (AxisAlignedBB a : BOXES) {
                out[i++] = a.minX;
                out[i++] = a.minY;
                out[i++] = a.minZ;
                out[i++] = a.maxX;
                out[i++] = a.maxY;
                out[i++] = a.maxZ;
            }
            return out;
        }

        @Override
        public boolean water(int x, int y, int z) {
            World level = Minecraft.getMinecraft().theWorld;
            return level != null && WaterSurface.wet(level, x, y, z);
        }

        @Override
        public int block(int x, int y, int z) {
            World level = Minecraft.getMinecraft().theWorld;
            // 26.2: the block state's identity; here the block's id and metadata
            return level == null ? 0 : Block.getIdFromBlock(level.getBlock(x, y, z)) << 4 | level.getBlockMetadata(x, y, z);
        }

        @Override
        public boolean loaded(int x, int z) {
            World level = Minecraft.getMinecraft().theWorld;
            // port: the client's chunkExists is always true; a chunk not loaded is its EmptyChunk
            return level != null && !(level.getChunkFromChunkCoords(x >> 4, z >> 4) instanceof EmptyChunk);
        }
    };

    // the frame, and the one already drawn with the world (Tracers.drawWorldHoles), for the RenderWorldLastEvent fallback
    private static long frame, drawnFrame = -1;

    public static void newFrame() {
        frame++;
    }

    public static boolean drawnThisFrame() {
        return drawnFrame == frame;
    }

    // cam = the camera (RenderManager.renderPos), the world's view in the modelview
    public static void render(World level, double camX, double camY, double camZ, float partial) {
        drawnFrame = frame;
        if (broken) return;
        // port: drawn here, at once (26.2: later, with the frame, each part its own guard); the GL state is put back
        // by draw's own finally
        try {
            draw(level, camX, camY, camZ, partial);
        } catch (RuntimeException e) {
            broke(e);
        }
    }

    private static void draw(World level, double camX, double camY, double camZ, float partial) {
        long now = System.nanoTime();
        double dt = lastNanos == 0 ? 0 : Math.clamp((now - lastNanos) / 1e9, 0, 0.05);
        lastNanos = now;
        Minecraft mc = Minecraft.getMinecraft();
        SunkPhysics ph = phys;
        if (ph != null && !ph.bodies().isEmpty() && !mc.isGamePaused()) ph.step(WORLD, dt);
        Vec3 cam = new Vec3(camX, camY, camZ);
        List<Fuzed> fuzed = fuzed(level, cam, partial);
        boolean any = !FLYING.isEmpty() || !fuzed.isEmpty() || (ph != null && !ph.bodies().isEmpty());
        if (!any) return;
        List<Flying> flying = new ArrayList<>(FLYING);
        FLYING.clear();
        List<SunkPhysics.Body> bodies = ph == null ? Collections.<SunkPhysics.Body>emptyList() : new ArrayList<>(ph.bodies());
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_LIGHTING_BIT | GL11.GL_CURRENT_BIT);
        mc.entityRenderer.enableLightmap(partial);
        try {
            mc.getTextureManager().bindTexture(WHITE);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(true);
            GL11.glEnable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL12.GL_RESCALE_NORMAL);
            GL11.glColor4f(1F, 1F, 1F, 1F);
            RenderHelper.enableStandardItemLighting();
            Tessellator t = Tessellator.instance;
            t.startDrawing(GL11.GL_TRIANGLES);
            try {
                Quaternionf q = new Quaternionf();
                for (Flying fl : flying) {
                    // the model's nose is -Z: turn -Z onto the direction of flight
                    q.rotationTo(0, 0, -1, (float) fl.dir.x, (float) fl.dir.y, (float) fl.dir.z);
                    // the model's centre half a length behind the head
                    Vec3 c = fl.head.subtract(fl.dir.scale(fl.model.length / 2));
                    mesh(level, t, fl.model, fl.open, q, c, cam, 1F);
                    flyingDrawn++;
                }
                for (Fuzed z : fuzed) {
                    q.rotationTo(0, 0, -1, (float) z.dir.x, (float) z.dir.y, (float) z.dir.z);
                    mesh(level, t, z.model, 0F, q, z.at, cam, 1F);
                }
                for (SunkPhysics.Body b : bodies) {
                    double dx = b.px - cam.x, dy = b.py - cam.y, dz = b.pz - cam.z;
                    if (dx * dx + dy * dy + dz * dz > 48 * 48) continue;
                    Tag tag = (Tag) b.tag;
                    q.set(b.q[0], b.q[1], b.q[2], b.q[3]);
                    mesh(level, t, tag.model, tag.open, q, new Vec3(b.px, b.py, b.pz), cam, Math.max(0.05F, b.fade));
                    bodiesDrawn++;
                }
            } finally {
                t.draw();
            }
            RenderHelper.disableStandardItemLighting();
            if (!flying.isEmpty()) streaks(flying, cam);
        } finally {
            mc.entityRenderer.disableLightmap(partial);
            GL11.glPopAttrib();
        }
    }

    // NTM's own sinking rounds with an impact fuze, stopped by the water: their model where the entity is, lying along
    // its heading (the explosives part moves them; gone with the entity)
    private record Fuzed(AmmoModels.Model model, Vec3 at, Vec3 dir) {
    }

    private static List<Fuzed> fuzed(World level, Vec3 cam, float pt) {
        if (!WaterFx.on()) return Collections.emptyList();
        List<Fuzed> out = null;
        for (Object o : level.loadedEntityList) {
            if (!(o instanceof EntityBulletBaseMK4 b) || !WaterClass.Fuze.ofConfig(b.config).any()) continue;
            Entity en = b;
            track(en);
            Vec3 at = new Vec3(en.lastTickPosX + (en.posX - en.lastTickPosX) * pt, en.lastTickPosY + (en.posY - en.lastTickPosY) * pt,
                    en.lastTickPosZ + (en.posZ - en.lastTickPosZ) * pt);
            if (at.distanceToSqr(cam) > 48 * 48 || !WaterBullet.stopped(en)) continue;
            if (!WaterFx.wetAt(level, at)) continue;
            String ammo = WaterFx.ammo(b.config);
            AmmoModels.Model m = ammo == null ? null : model(b.config);
            if (m == null) continue;
            if (out == null) out = new ArrayList<>();
            out.add(new Fuzed(m, at, headingOf(en)));
        }
        return out == null ? Collections.<Fuzed>emptyList() : out;
    }

    // port: the level way it goes while it flies, kept for its model once it sinks (26.2: WaterTick.end, from the
    // client bullet's own motion). NTM 1.7.10's client copy does not tick: the server's moves of it come, and its last
    // tick's move is this (sinking straight down keeps the way it flew)
    static void track(Entity en) {
        double dx = en.posX - en.lastTickPosX, dz = en.posZ - en.lastTickPosZ;
        // at least a hundredth of a block a tick: the server's motion of a sinking one is (0, at most 0.05 down, 0)
        if (dx * dx + dz * dz > 1e-4 && en instanceof WaterBullet w) w.gunfeel$sunkHeading(WaterTick.flat(dx, dz));
    }

    // the way a sunk round with an impact fuze lies (also for the stand)
    public static Vec3 headingOf(Entity en) {
        Vec3 h = en instanceof WaterBullet w ? w.gunfeel$sunkHeading() : null;
        return h != null ? h : WaterTick.heading(en.rotationYaw);
    }

    // the opened shape of each model, one buffer per model
    private static final Map<AmmoModels.Model, float[]> MORPH = new IdentityHashMap<>();

    // one model: full detail up to 8 blocks away, farther a six-sided prism of its size (a few faces, not hundreds)
    private static void mesh(World level, Tessellator t, AmmoModels.Model m, float open, Quaternionf q, Vec3 c, Vec3 cam, float scale) {
        t.setBrightness(level.getLightBrightnessForSkyBlocks((int) Math.floor(c.x), (int) Math.floor(c.y), (int) Math.floor(c.z), 0));
        float cx = (float) (c.x - cam.x), cy = (float) (c.y - cam.y), cz = (float) (c.z - cam.z);
        double d2 = cx * cx + cy * cy + cz * cz;
        Vector3f a = new Vector3f(), n = new Vector3f();
        if (d2 > 64) {
            prism(t, m, q, cx, cy, cz, scale);
            return;
        }
        float[] pos = m.positions;
        if (open > 0 && m.mushroom != null) pos = m.morph(open, MORPH.computeIfAbsent(m, k -> new float[k.positions.length]));
        float[] nor = m.normals;
        int[] col = m.colors;
        int verts = m.positions.length / 3;
        // a model short of normals or colours fails here, before a corner of it is in the buffer: a part of a
        // triangle there would shift all that is drawn after it
        if (pos.length < verts * 3 || nor.length < verts * 3 || col.length < verts) throw new IllegalStateException("broken model " + m.shape);
        for (int j = 0; j < verts; j++) {
            a.set(pos[j * 3], pos[j * 3 + 1], pos[j * 3 + 2]).mul(scale);
            q.transform(a);
            n.set(nor[j * 3], nor[j * 3 + 1], nor[j * 3 + 2]);
            q.transform(n);
            t.setColorOpaque_I(col[j] & 0xFFFFFF);
            t.setNormal(n.x, n.y, n.z);
            t.addVertexWithUV(a.x + cx, a.y + cy, a.z + cz, 0.5, 0.5);
        }
    }

    private static void prism(Tessellator t, AmmoModels.Model m, Quaternionf q, float cx, float cy, float cz, float scale) {
        float r = m.radius * scale, hl = m.length / 2 * scale;
        int sides = 6;
        Vector3f a = new Vector3f(), n = new Vector3f();
        for (int s = 0; s < sides; s++) {
            double t0 = s * Math.PI * 2 / sides, t1 = (s + 1) * Math.PI * 2 / sides;
            float x0 = (float) Math.cos(t0) * r, y0 = (float) Math.sin(t0) * r, x1 = (float) Math.cos(t1) * r, y1 = (float) Math.sin(t1) * r;
            float nx = (float) Math.cos((t0 + t1) / 2), ny = (float) Math.sin((t0 + t1) / 2);
            // the side as two triangles (26.2: one quad)
            float[][] side = {{x0, y0, hl}, {x1, y1, hl}, {x1, y1, -hl}, {x1, y1, -hl}, {x0, y0, -hl}, {x0, y0, hl}};
            for (float[] w : side) {
                a.set(w[0], w[1], w[2]);
                q.transform(a);
                n.set(nx, ny, 0);
                q.transform(n);
                int col = w[2] < 0 && m.capsule ? m.noseColor : m.bodyColor;
                t.setColorOpaque_I(col & 0xFFFFFF);
                t.setNormal(n.x, n.y, n.z);
                t.addVertexWithUV(a.x + cx, a.y + cy, a.z + cz, 0.5, 0.5);
            }
            // the ends: a triangle to the axis
            for (int end = -1; end <= 1; end += 2) {
                float z = end * hl;
                float[][] cap = end > 0 ? new float[][] {{0, 0, z}, {x1, y1, z}, {x0, y0, z}} : new float[][] {{0, 0, z}, {x0, y0, z}, {x1, y1, z}};
                for (float[] w : cap) {
                    a.set(w[0], w[1], w[2]);
                    q.transform(a);
                    n.set(0, 0, end);
                    q.transform(n);
                    int col = end < 0 ? m.noseColor : m.bodyColor;
                    t.setColorOpaque_I(col & 0xFFFFFF);
                    t.setNormal(n.x, n.y, n.z);
                    t.addVertexWithUV(a.x + cx, a.y + cy, a.z + cz, 0.5, 0.5);
                }
            }
        }
    }

    // the light streak behind a bullet in water, bright while it is fast, gone below a fifth of its speed
    // (26.2: debugQuads, plain colour, both faces)
    private static void streaks(List<Flying> flying, Vec3 cam) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_BLEND);
        OpenGlHelper.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GL11.glDepthMask(false);
        GL11.glShadeModel(GL11.GL_SMOOTH);
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        // full bright: the lightmap is still on (26.2: debugQuads, no light)
        t.setBrightness(0xF000F0);
        try {
            for (Flying fl : flying) {
                float op = (float) Math.clamp((fl.f - 0.2) / 0.6, 0, 1);
                if (op < 0.01F) continue;
                double vps = fl.speed * 20;
                double sl = Math.min(1.2, vps * 0.004);
                Vec3 b = fl.head.subtract(fl.dir.scale(fl.model.length)).subtract(cam);
                Vec3 tl = b.subtract(fl.dir.scale(sl));
                Vec3 side = fl.dir.cross(b);
                if (side.lengthSqr() < 1e-12) continue;
                side = side.normalize().scale(fl.model.radius * 0.6);
                float[] head = {0.07F + 0.8F * op, 0.25F + 0.7F * op, 0.52F + 0.48F * op, 0.9F * op};
                float[] tail = {0.07F, 0.25F, 0.52F, 0F};
                Vec3[] qd = {b.add(side), b.subtract(side), tl.subtract(side), tl.add(side)};
                float[][] cs = {head, head, tail, tail};
                for (int j = 0; j < 4; j++) {
                    t.setColorRGBA_F(cs[j][0], cs[j][1], cs[j][2], cs[j][3]);
                    t.addVertex(qd[j].x, qd[j].y, qd[j].z);
                }
            }
        } finally {
            t.draw();
        }
    }
}
