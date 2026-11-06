package acup.gunfeel.water;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import com.hbm.items.weapon.sedna.BulletConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import acup.gunfeel.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

// Bullets under water you can see (notes/water/bubble-demo.tpl.html): a low-poly model of the round (AmmoModels, ×6
// real size) while it flies in water, a hollow or soft point opening into a mushroom as it goes, a light streak
// behind it fading as it slows; and once the water stopped it, a body on the client only (SunkPhysics): it drifts
// on, tumbles and sinks at 1.2 blocks a second, and lies on the bottom (waterBodyLife seconds, at most waterBodyMax).
// A bullet that hit a block is gone, as in NTM. A round with an impact fuze is no body of ours: it sinks as NTM's own
// entity and goes off at the first thing it touches (the explosives part); its model is drawn at that entity while
// it lasts. A phosphorus pellet does not burn under water, it sinks as shot does.
public final class SunkBullets {
    private SunkBullets() {
    }

    private static @Nullable SunkPhysics phys;
    private static int physMax;
    private static double physLie;

    // how far a hollow / soft point has gone before it is fully open, and how far it opens (demo EXP)
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

    private record Tag(AmmoModels.Model model, float open) {
    }

    // the water stopped a bullet (WaterFx on STOP): a body where it stopped, moving on at 1.5 blocks a second
    static void stopped(Level level, WaterEvents.Event e) {
        if (Config.waterBodyMax <= 0) return;
        Entity bullet = e.bullet();
        BulletConfig config = WaterFx.config(e);
        String ammo = WaterFx.ammo(config);
        // an impact fuze (grenades, rockets, HE and incendiary rounds): NTM's entity sinks on, the explosives part's
        if (ammo == null || config == null || WaterClass.Fuze.of(config).any() || WaterClass.of(config).explosive) return;
        AmmoModels.Model m = AmmoModels.of("ammo_standard_" + ammo);
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

    public static double physicsMs() {
        return phys == null ? 0 : phys.lastMs();
    }

    // ---- bullets in flight under water, collected from the tracers each frame ----
    private record Flying(AmmoModels.Model model, Vec3 head, Vec3 dir, double f, double speed, float open) {
    }

    private static final List<Flying> FLYING = new ArrayList<>();

    // Tracers, each frame, each shot: its head, its direction, its f and speed (blocks a tick)
    public static void flying(Level level, @Nullable BulletConfig config, Vec3 head, Vec3 dir, double f, double speed) {
        if (!WaterFx.on() || dir.lengthSqr() < 1e-12) return;
        if (config == null || !WaterFx.wetAt(level, head)) return;
        Round r = ROUNDS.computeIfAbsent(config, SunkBullets::round);
        AmmoModels.Model m = r.model();
        if (m == null) return;
        float open = 0F;
        if (m.expands) {
            // opens over its first centimetres in water: the path so far from its f (f = exp(-s/L))
            double s = -r.length() * Math.log(Math.max(1e-6, f));
            open = (float) Math.min(1, s / (r.jhp() ? JHP_S : SP_S));
        }
        FLYING.add(new Flying(m, head, dir.normalize(), f, speed, open));
    }

    // a config's model, once: the frame asks for it per shot
    private record Round(AmmoModels.@Nullable Model model, boolean jhp, double length) {
    }

    private static final java.util.Map<BulletConfig, Round> ROUNDS = new java.util.IdentityHashMap<>();

    private static Round round(BulletConfig config) {
        String ammo = WaterFx.ammo(config);
        AmmoModels.Model m = ammo == null ? null : AmmoModels.of("ammo_standard_" + ammo);
        return new Round(m, ammo != null && "jhp".equals(variant(ammo)), m != null && m.expands ? WaterClass.of(config).length : 0);
    }

    private static String variant(String ammo) {
        int cut = ammo.indexOf('_');
        return cut < 0 ? "" : ammo.substring(cut + 1);
    }

    // ---- each frame: physics, then the models ----
    private static long lastNanos;
    private static final SunkPhysics.World WORLD = new SunkPhysics.World() {
        @Override
        public double[] boxes(int x, int y, int z) {
            Level level = Minecraft.getInstance().level;
            if (level == null) return EMPTY;
            BlockPos p = new BlockPos(x, y, z);
            VoxelShape shape = level.getBlockState(p).getCollisionShape(level, p);
            if (shape.isEmpty()) return EMPTY;
            List<AABB> boxes = shape.toAabbs();
            double[] out = new double[boxes.size() * 6];
            int i = 0;
            for (AABB b : boxes) {
                out[i++] = b.minX + x;
                out[i++] = b.minY + y;
                out[i++] = b.minZ + z;
                out[i++] = b.maxX + x;
                out[i++] = b.maxY + y;
                out[i++] = b.maxZ + z;
            }
            return out;
        }

        @Override
        public boolean water(int x, int y, int z) {
            Level level = Minecraft.getInstance().level;
            return level != null && WaterSurface.wet(level, x, y, z);
        }
    };
    private static final double[] EMPTY = new double[0];

    public static void submit(SubmitCustomGeometryEvent event, Level level, Vec3 cam) {
        long now = System.nanoTime();
        double dt = lastNanos == 0 ? 0 : Math.clamp((now - lastNanos) / 1e9, 0, 0.05);
        lastNanos = now;
        boolean paused = Minecraft.getInstance().isPaused();
        SunkPhysics ph = phys;
        if (ph != null && !ph.bodies().isEmpty() && !paused) ph.step(WORLD, dt);
        List<Fuzed> fuzed = fuzed(level, cam);
        boolean any = !FLYING.isEmpty() || !fuzed.isEmpty() || (ph != null && !ph.bodies().isEmpty());
        if (!any) return;
        List<Flying> flying = new ArrayList<>(FLYING);
        FLYING.clear();
        List<SunkPhysics.Body> bodies = ph == null ? List.of() : new ArrayList<>(ph.bodies());
        TextureAtlasSprite white = WaterParticle.sprite("ntmgunfeel:water_white");
        float u = (white.getU0() + white.getU1()) / 2, v = (white.getV0() + white.getV1()) / 2;
        event.getSubmitNodeCollector().submitCustomGeometry(event.getPoseStack(), RenderTypes.entitySolid(TextureAtlas.LOCATION_PARTICLES), (p, vc) -> {
            Quaternionf q = new Quaternionf();
            for (Flying fl : flying) {
                // the model's nose is -Z: turn -Z onto the direction of flight
                q.rotationTo(0, 0, -1, (float) fl.dir.x, (float) fl.dir.y, (float) fl.dir.z);
                // the model's centre half a length behind the head
                Vec3 c = fl.head.subtract(fl.dir.scale(fl.model.length / 2));
                mesh(level, p, vc, fl.model, fl.open, q, c, cam, 1F, u, v);
            }
            for (Fuzed z : fuzed) {
                q.rotationTo(0, 0, -1, (float) z.dir.x, (float) z.dir.y, (float) z.dir.z);
                mesh(level, p, vc, z.model, 0F, q, z.at, cam, 1F, u, v);
            }
            for (SunkPhysics.Body b : bodies) {
                double dx = b.px - cam.x, dy = b.py - cam.y, dz = b.pz - cam.z;
                if (dx * dx + dy * dy + dz * dz > 48 * 48) continue;
                Tag t = (Tag) b.tag;
                q.set(b.q[0], b.q[1], b.q[2], b.q[3]);
                mesh(level, p, vc, t.model, t.open, q, new Vec3(b.px, b.py, b.pz), cam, Math.max(0.05F, b.fade), u, v);
            }
        });
        if (!flying.isEmpty()) streaks(event, flying, cam);
    }

    // NTM's own sinking rounds with an impact fuze, stopped by the water: their model where the entity is, lying along
    // its heading (the explosives part moves them; gone with the entity)
    private record Fuzed(AmmoModels.Model model, Vec3 at, Vec3 dir) {
    }

    private static List<Fuzed> fuzed(Level level, Vec3 cam) {
        if (!WaterFx.on() || !(level instanceof net.minecraft.client.multiplayer.ClientLevel cl)) return List.of();
        List<Fuzed> out = null;
        float pt = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
        for (Entity en : cl.entitiesForRendering()) {
            if (!(en instanceof com.hbm.entity.projectile.EntityBulletBaseMK4 b) || !WaterClass.Fuze.of(b.config).any()) continue;
            if (en.distanceToSqr(cam) > 48 * 48 || !WaterBullet.stopped(en)) continue;
            Vec3 at = en.getPosition(pt);
            if (!WaterFx.wetAt(level, at)) continue;
            String ammo = WaterFx.ammo(b.config);
            AmmoModels.Model m = ammo == null ? null : AmmoModels.of("ammo_standard_" + ammo);
            if (m == null) continue;
            double yaw = Math.toRadians(en.getYRot());
            if (out == null) out = new ArrayList<>();
            out.add(new Fuzed(m, at, new Vec3(-Math.sin(yaw), 0, Math.cos(yaw))));
        }
        return out == null ? List.of() : out;
    }

    // the opened shape of each model, one buffer per model
    private static final java.util.Map<AmmoModels.Model, float[]> MORPH = new java.util.IdentityHashMap<>();

    // one model: full detail up to 8 blocks away, farther a six-sided prism of its size (a few quads, not hundreds)
    private static void mesh(Level level, PoseStack.Pose p, VertexConsumer vc, AmmoModels.Model m, float open, Quaternionf q, Vec3 c, Vec3 cam,
            float scale, float u, float v) {
        int light = LightCoordsUtil.getLightCoords(level, BlockPos.containing(c));
        float cx = (float) (c.x - cam.x), cy = (float) (c.y - cam.y), cz = (float) (c.z - cam.z);
        double d2 = cx * cx + cy * cy + cz * cz;
        Vector3f a = new Vector3f(), n = new Vector3f();
        if (d2 > 64) {
            prism(p, vc, m, q, cx, cy, cz, scale, light, u, v);
            return;
        }
        float[] pos = m.positions;
        if (open > 0 && m.mushroom != null) pos = m.morph(open, MORPH.computeIfAbsent(m, k -> new float[k.positions.length]));
        float[] nor = m.normals;
        int[] col = m.colors;
        int verts = m.positions.length / 3;
        for (int i = 0; i + 2 < verts; i += 3) {
            for (int k = 0; k < 4; k++) {
                int j = i + Math.min(k, 2);
                a.set(pos[j * 3], pos[j * 3 + 1], pos[j * 3 + 2]).mul(scale);
                q.transform(a);
                n.set(nor[j * 3], nor[j * 3 + 1], nor[j * 3 + 2]);
                q.transform(n);
                vc.addVertex(p, a.x + cx, a.y + cy, a.z + cz).setColor(0xFF000000 | col[j]).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
                        .setLight(light).setNormal(p, n.x, n.y, n.z);
            }
        }
    }

    private static void prism(PoseStack.Pose p, VertexConsumer vc, AmmoModels.Model m, Quaternionf q, float cx, float cy, float cz, float scale,
            int light, float u, float v) {
        float r = m.radius * scale, hl = m.length / 2 * scale;
        int sides = 6;
        Vector3f a = new Vector3f(), n = new Vector3f();
        for (int s = 0; s < sides; s++) {
            double t0 = s * Math.PI * 2 / sides, t1 = (s + 1) * Math.PI * 2 / sides;
            float x0 = (float) Math.cos(t0) * r, y0 = (float) Math.sin(t0) * r, x1 = (float) Math.cos(t1) * r, y1 = (float) Math.sin(t1) * r;
            float nx = (float) Math.cos((t0 + t1) / 2), ny = (float) Math.sin((t0 + t1) / 2);
            float[][] side = {{x0, y0, hl}, {x1, y1, hl}, {x1, y1, -hl}, {x0, y0, -hl}};
            for (float[] w : side) {
                a.set(w[0], w[1], w[2]);
                q.transform(a);
                n.set(nx, ny, 0);
                q.transform(n);
                int col = w[2] < 0 && m.capsule ? m.noseColor : m.bodyColor;
                vc.addVertex(p, a.x + cx, a.y + cy, a.z + cz).setColor(0xFF000000 | col).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
                        .setNormal(p, n.x, n.y, n.z);
            }
            // the ends: a fan of thin quads to the axis
            for (int end = -1; end <= 1; end += 2) {
                float z = end * hl;
                float[][] cap = {{0, 0, z}, {x0, y0, z}, {x1, y1, z}, {x1, y1, z}};
                if (end > 0) {
                    float[] tmp = cap[1];
                    cap[1] = cap[2];
                    cap[2] = tmp;
                }
                for (float[] w : cap) {
                    a.set(w[0], w[1], w[2]);
                    q.transform(a);
                    n.set(0, 0, end);
                    q.transform(n);
                    int col = end < 0 ? m.noseColor : m.bodyColor;
                    vc.addVertex(p, a.x + cx, a.y + cy, a.z + cz).setColor(0xFF000000 | col).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
                            .setLight(light).setNormal(p, n.x, n.y, n.z);
                }
            }
        }
    }

    // the light streak behind a bullet in water, bright while it is fast, gone below a fifth of its speed
    private static void streaks(SubmitCustomGeometryEvent event, List<Flying> flying, Vec3 cam) {
        event.getSubmitNodeCollector().submitCustomGeometry(event.getPoseStack(), RenderTypes.debugQuads(), (p, vc) -> {
            for (Flying fl : flying) {
                float op = (float) Math.clamp((fl.f - 0.2) / 0.6, 0, 1);
                if (op < 0.01F) continue;
                double vps = fl.speed * 20;
                double sl = Math.min(1.2, vps * 0.004);
                Vec3 b = fl.head.subtract(fl.dir.scale(fl.model.length)).subtract(cam);
                Vec3 t = b.subtract(fl.dir.scale(sl));
                Vec3 side = fl.dir.cross(b);
                if (side.lengthSqr() < 1e-12) continue;
                side = side.normalize().scale(fl.model.radius * 0.6);
                int head = argb(0.9F * op, 0.07F + 0.8F * op, 0.25F + 0.7F * op, 0.52F + 0.48F * op);
                int tail = argb(0F, 0.07F, 0.25F, 0.52F);
                for (int pass = 0; pass < 2; pass++) {
                    Vec3[] qd = {b.add(side), b.subtract(side), t.subtract(side), t.add(side)};
                    int[] cs = {head, head, tail, tail};
                    for (int j = 0; j < 4; j++) {
                        int i = pass == 0 ? j : 3 - j;
                        vc.addVertex(p, (float) qd[i].x, (float) qd[i].y, (float) qd[i].z).setColor(cs[i]);
                    }
                }
            }
        });
    }

    private static int argb(float a, float r, float g, float b) {
        return ((int) (Math.clamp(a, 0, 1) * 255) << 24) | ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255);
    }
}
