// Ported from 26.2's src/main/java/acup/gunfeel/Tracers.java at 0762086 (port/1.7.10-forge de8bdf7), cut down to what
// 1.7.10 can do without 26.2's model capture: the tracers, their flight through the client's world, NTM's standard
// ricochet, the server's stop point, and flat holes on block faces (cut to the hitbox and the pixels of plain cubes).
// The pure parts are in HoleMath and TracerPath (same numbers as 26.2); this file holds the world and the drawing.
// Left out (26.2 only, for now): holes on what a renderer draws (NTM machines and doors: RenderedMesh, Follow,
// meshHole, drawnHole, DecalSurface), piercing on the client (PassableClip), the graphics-card hole cache (Batch,
// holeCache), the late pass for glass, entity hits for unannounced shots, ClientSmoke / HoleBenchmark hooks.
package acup.gunfeel;

import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.items.ModItems;
import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.factory.GunFactory;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.IIcon;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;

// Client: TaCZ-like bullets. The server announces every NTM bullet (shot()); here it gets a thin tracer in the colours
// of its NTM tracer, flown along the same path through the client's world (from the muzzle for the player's own
// shots), and a bullet hole where it hits a block. NTM's own wide ribbon is then not drawn for those bullets.
// port: the announcements come in through shot / stop / impact with plain numbers (26.2: ShotPayload,
// BulletStopPayload, ImpactHolePayload), so the network code calls them on the client thread.
public final class Tracers {
    private static final int KINDS = Decals.COUNT;
    private static final ResourceLocation[] TEXTURES = new ResourceLocation[KINDS];
    static {
        for (int i = 0; i < KINDS; i++) TEXTURES[i] = new ResourceLocation("ntmgunfeel", "textures/effect/" + Decals.file(i) + ".png");
    }

    private Tracers() {
    }

    // ---- what the bullet rules say about a block (26.2: BulletRules.decal, hole, ricochet and the material)

    // port: 26.2 reads them from ntmgunfeel-bullets.txt by block state. Until that file has a 1.7.10 reader, the
    // vanilla material of the block decides (VanillaRules); a port of BulletRules sets `rules`.
    public interface Rules {
        // the marks a bullet leaves on it
        Decals.Set decal(Block block, int meta);

        // its holes follow only the hitbox, not its see-through pixels ("hole ... hitbox": glass)
        boolean hitboxOnly(Block block, int meta);

        // glass: holes on it are two-sided
        boolean glass(Block block, int meta);

        // NTM's standard ricochet may bounce a bullet off it
        boolean ricochet(Block block, int meta);
    }

    public static volatile Rules rules = new VanillaRules();

    // the material by the block's vanilla material, as 26.2's Materials steps 3-5 tell it by tags and sounds
    public static final class VanillaRules implements Rules {
        public static Materials.Material material(Block b) {
            Material m = b.getMaterial();
            if (m == Material.air) return Materials.Material.NONE;
            if (m == Material.glass || m == Material.ice || m == Material.packedIce) return Materials.Material.GLASS;
            if (m == Material.iron || m == Material.anvil) return Materials.Material.METAL;
            if (m == Material.wood || m == Material.gourd) return Materials.Material.WOOD;
            if (m == Material.ground || m == Material.grass || m == Material.sand || m == Material.clay || m == Material.snow
                    || m == Material.craftedSnow) return Materials.Material.SOIL;
            if (m == Material.cloth || m == Material.leaves || m == Material.plants || m == Material.vine || m == Material.sponge
                    || m == Material.cactus || m == Material.web || m == Material.carpet || m == Material.cake) return Materials.Material.SOFT;
            if (m == Material.rock) {
                String name = String.valueOf(Block.blockRegistry.getNameForObject(b)).toLowerCase(java.util.Locale.ROOT);
                return name.contains("concrete") ? Materials.Material.CONCRETE : Materials.Material.STONE;
            }
            return Materials.Material.NONE;
        }

        @Override
        public Decals.Set decal(Block block, int meta) {
            return Decals.Set.of(material(block));
        }

        @Override
        public boolean hitboxOnly(Block block, int meta) {
            // the mod's rule: "hole ~.*glass.*,hbm:reinforced_laminate,hbm:reinforced_laminate_pane hitbox"
            String name = String.valueOf(Block.blockRegistry.getNameForObject(block)).toLowerCase(java.util.Locale.ROOT);
            return name.contains("glass") || name.contains("reinforced_laminate");
        }

        @Override
        public boolean glass(Block block, int meta) {
            return material(block).glass() || hitboxOnly(block, meta);
        }

        @Override
        public boolean ricochet(Block block, int meta) {
            return true;
        }
    }

    // ---- the bullets

    private static final class Shot {
        final int id; // the server's entity id of the bullet
        final BulletConfig config;
        final int owner;
        final TracerPath path;
        final int dark, light;
        final double born, rootBorn;
        final long lifeTicks;
        double checked; // ticks of path already tested against the world
        double end = Double.NaN; // ticks at which it hit something
        boolean gone;
        int ricochets;

        Shot(int id, BulletConfig config, int owner, TracerPath path, int[] colors, long lifeTicks, double born) {
            this.id = id;
            this.config = config;
            this.owner = owner;
            this.path = path;
            this.dark = colors[0];
            this.light = colors[1];
            this.lifeTicks = lifeTicks;
            this.born = born;
            this.rootBorn = born;
        }

        // the same bullet bounced off a block: from there on with the mirrored velocity, born = the moment it bounced
        Shot(Shot from, double[] start, double[] v, double born, long lifeTicks) {
            this.id = from.id;
            this.config = from.config;
            this.owner = from.owner;
            this.path = new TracerPath(start, v, from.path.g);
            this.dark = from.dark;
            this.light = from.light;
            this.lifeTicks = lifeTicks;
            this.born = born;
            this.rootBorn = from.rootBorn;
            this.ricochets = from.ricochets + 1;
            this.checked = 1e-4; // not at the very face it left
        }
    }

    private static final List<Shot> SHOTS = new ArrayList<Shot>();
    private static final LinkedHashMap<Integer, Shot> RECENT_SHOTS = new LinkedHashMap<Integer, Shot>();
    private static final List<Shot> BOUNCED = new ArrayList<Shot>();
    private static boolean serverAnnounces;
    private static double lastFrame = Double.NaN;
    private static final BulletClock FLIGHT_CLOCK = new BulletClock();

    private static double flightTime() {
        World w = Minecraft.getMinecraft().theWorld;
        if (w == null) return FLIGHT_CLOCK.current();
        return FLIGHT_CLOCK.sample(w.getTotalWorldTime(), GunFeelClient.partial);
    }

    // NTM tracer colours per bullet type, {dark, light}; NONE = not a plain tracer (grenade, rocket...): NTM draws it
    private static final Map<BulletConfig, int[]> COLORS = new HashMap<BulletConfig, int[]>();
    private static final int[] NONE = new int[0];
    private static boolean probing;
    private static int[] probed;

    public static boolean probing() {
        return probing;
    }

    public static void probed(int dark, int light) {
        probed = new int[] {dark, light};
    }

    // true when this bullet type's tracer is drawn here instead of by NTM
    public static boolean replaces(BulletConfig config) {
        return serverAnnounces && Config.tracers && config != null && config.velocity >= 5F && colors(config) != NONE;
    }

    // port: NTM 1.7.10 keeps the tracer as config.renderer, drawn for a bullet entity; called once for a bullet that
    // is never spawned, with a length of 1, while probing: the plain tracer (LegoClient.renderBulletStandard) tells its
    // colours to MixinLegoClient instead of drawing. Any other renderer draws for real, so it draws scaled to nothing,
    // with its GL state put back.
    private static int[] colors(BulletConfig config) {
        int[] c = COLORS.get(config);
        if (c != null) return c;
        World world = Minecraft.getMinecraft().theWorld;
        if (world == null) return NONE; // asked again once there is a world
        c = NONE;
        if (config.renderer != null) {
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            GL11.glPushMatrix();
            try {
                GL11.glScalef(0F, 0F, 0F);
                EntityBulletBaseMK4 probe = new EntityBulletBaseMK4(world);
                probe.config = config;
                probe.velocity = probe.prevVelocity = 1.0;
                probed = null;
                probing = true;
                try {
                    config.renderer.accept(probe, 1F);
                } finally {
                    probing = false;
                }
                if (probed != null) c = probed;
            } catch (Throwable e) {
                // a tracer that draws something else: leave it to NTM
            } finally {
                GL11.glPopMatrix();
                GL11.glPopAttrib();
            }
        }
        COLORS.put(config, c);
        return c;
    }

    // ---- the server's announcements (26.2: ShotPayload, BulletStopPayload, ImpactHolePayload)

    // The network code may call these from its own thread (1.7.10 runs packet handlers on the network thread): they
    // are queued and run on the game's thread, at the next client tick or frame.
    private static final java.util.concurrent.ConcurrentLinkedQueue<Runnable> INBOX = new java.util.concurrent.ConcurrentLinkedQueue<Runnable>();

    private static void drain() {
        Runnable r;
        while ((r = INBOX.poll()) != null) r.run();
    }

    // A bullet the server fired: id its entity id, owner the shooter's entity id (-1 none), config the
    // BulletConfig id, (x, y, z) where it starts, (vx, vy, vz) blocks per tick as the server moves it (motion times
    // NTM's motionMult), gravity blocks per tick per tick, life ticks (BulletConfig.expires), damage what it carries,
    // (ex, ey, ez) the shooter's eyes it is tested from on its first tick (NaN: from its start).
    public static void shot(final int id, final int owner, final int config, final double x, final double y, final double z, final double vx,
            final double vy, final double vz, final float gravity, final int life, final float damage, final double ex, final double ey,
            final double ez) {
        INBOX.add(() -> shot0(id, owner, config, x, y, z, vx, vy, vz, gravity, life, damage, ex, ey, ez));
    }

    private static void shot0(int id, int owner, int config, double x, double y, double z, double vx, double vy, double vz, float gravity,
            int life, float damage, double ex, double ey, double ez) {
        Rng.announce(id);
        serverAnnounces = true;
        if (!Config.tracers && !Config.bulletHoles && !Config.bulletFx) return;
        if (config < 0 || config >= BulletConfig.configs.size()) return;
        BulletConfig bc = BulletConfig.configs.get(config);
        if (bc.velocity < 5F) return;
        int[] c = colors(bc);
        if (c == NONE) return;
        TracerPath path = new TracerPath(new double[] {x, y, z}, new double[] {vx, vy, vz}, gravity);
        Minecraft mc = Minecraft.getMinecraft();
        // the player's own shot leaves the muzzle drawn in their hands
        if (mc.thePlayer != null && owner == mc.thePlayer.getEntityId() && GunClock.nanos() - muzzleTime < 500_000_000L
                && mc.gameSettings.thirdPersonView == 0) {
            double[] m = muzzleWorld();
            if (m != null) {
                double[] off = {m[0] - x, m[1] - y, m[2] - z};
                if (off[0] * off[0] + off[1] * off[1] + off[2] * off[2] <= 9.0) path.muzzleOffset = off;
            }
        }
        // fired from a gun: the path is tested from the shooter's eyes, as on the server, so a wall right in front
        // of the muzzle still gets its hole
        if (!Double.isNaN(ex)) path.eye = new double[] {ex, ey, ez};
        if (TestLog.client) TestLog.c(id, "fire", "ammo", config, "own", mc.thePlayer != null && owner == mc.thePlayer.getEntityId(),
                "at", v(path.start), "v", v(path.v), "damage", damage);
        Shot s = new Shot(id, bc, owner, path, c, life, flightTime());
        SHOTS.add(s);
        RECENT_SHOTS.put(s.id, s);
        if (RECENT_SHOTS.size() > 512) RECENT_SHOTS.remove(RECENT_SHOTS.keySet().iterator().next());
        if (SHOTS.size() > 512) SHOTS.remove(0).gone = true;
    }

    // the server stopped the bullet `age` ticks after it left, at (x, y, z): the tracer ends there, and the holes
    // the client made beyond it go
    public static void stop(final int shot, final double age, final double x, final double y, final double z) {
        INBOX.add(() -> stop0(shot, age, x, y, z));
    }

    private static void stop0(int shot, double age, double x, double y, double z) {
        if (!Double.isFinite(age) || age < 0 || !Double.isFinite(x + y + z)) return;
        double[] point = {x, y, z};
        Shot recent = RECENT_SHOTS.get(shot);
        if (recent != null) {
            double[] v = recent.path.v;
            for (Iterator<Pending> it = PENDING.iterator(); it.hasNext();) {
                Pending p = it.next();
                if (p.shot == shot && beyond(p.at, point, v)) it.remove();
            }
            for (Iterator<Hole> it = HOLES.iterator(); it.hasNext();) {
                Hole h = it.next();
                if (h.shot == shot && beyond(h.pos, point, v)) it.remove();
            }
        }
        for (Shot s : SHOTS) if (s.id == shot && (recent == null || s.born == recent.born)) {
            s.path.confirmedEnd = Math.max(0, age - (s.born - s.rootBorn));
            s.end = s.checked >= s.path.confirmedEnd ? s.path.confirmedEnd : Double.NaN;
            s.path.stopPoint = point;
        }
    }

    private static boolean beyond(double[] at, double[] stop, double[] v) {
        return (at[0] - stop[0]) * v[0] + (at[1] - stop[1]) * v[1] + (at[2] - stop[2]) * v[2] > 1e-5;
    }

    // a heavy explosive round (a 40 mm grenade, a rocket) hit the block (bx, by, bz) on `face` at (px, py, pz),
    // flying along (dx, dy, dz): the server tells, the client makes its scorch (it flies none of its own there)
    public static void impact(final int shot, final int config, final int bx, final int by, final int bz, final int face, final double px,
            final double py, final double pz, final double dx, final double dy, final double dz) {
        INBOX.add(() -> impact0(shot, config, bx, by, bz, face, px, py, pz, dx, dy, dz));
    }

    private static void impact0(int shot, int config, int bx, int by, int bz, int face, double px, double py, double pz, double dx, double dy,
            double dz) {
        World w = Minecraft.getMinecraft().theWorld;
        if (w == null || !Config.bulletHoles || config < 0 || config >= BulletConfig.configs.size() || face < 0 || face > 5) return;
        BulletConfig bc = BulletConfig.configs.get(config);
        if (!heavy(bc) || !Double.isFinite(px + py + pz + dx + dy + dz)) return;
        PENDING.addLast(new Pending(w, new double[] {px, py, pz}, face, new int[] {bx, by, bz}, bc, new double[] {dx, dy, dz}, null, shot));
    }

    // 26.2: ExplosiveAmmo.heavy. port: NTM 1.7.10 has one ammo item with the kinds as its meta (GunFactory.EnumAmmo)
    static boolean heavy(BulletConfig config) {
        if (config == null || config.ammo == null || config.onImpact == null || config.ammo.item != ModItems.ammo_standard) return false;
        GunFactory.EnumAmmo[] all = GunFactory.EnumAmmo.values();
        int meta = config.ammo.meta;
        if (meta < 0 || meta >= all.length) return false;
        return heavyPath("ammo_standard_" + all[meta].name().toLowerCase(java.util.Locale.ROOT));
    }

    // 26.2: ExplosiveAmmo.heavyPath
    static boolean heavyPath(String path) {
        return path.startsWith("ammo_standard_g40_") || path.startsWith("ammo_standard_rocket_");
    }

    static float holeScale(BulletConfig config) {
        return heavy(config) ? 1.5F : 1F;
    }

    // /gunfeel clearholes: every hole, scratch and scorch mark in this world, and the ones still to be made; how many
    public static int clearHoles() {
        int n = HOLES.size() + PENDING.size();
        HOLES.clear();
        PENDING.clear();
        return n;
    }

    public static void clear() {
        INBOX.clear();
        for (Shot s : SHOTS) s.gone = true;
        SHOTS.clear();
        RECENT_SHOTS.clear();
        HOLES.clear();
        PENDING.clear();
        FLIGHT_CLOCK.reset();
        lastFrame = Double.NaN;
        serverAnnounces = false;
    }

    // ---- the muzzle (26.2: muzzle(PoseStack) from MixinItemRenderWeaponBase.renderSmokeNodes)

    private static final FloatBuffer MATRIX = BufferUtils.createFloatBuffer(16);
    // the muzzle in view space and the hands' projection scale (1 / tan(fov / 2)), as last drawn
    private static final float[] MUZZLE = new float[3];
    private static float handScale;
    private static long muzzleTime;
    // the world's view (positions from RenderManager.renderPos), its projection scale and the camera, last frame
    private static float[] worldView;
    private static float worldScale = 1F;
    private static final double[] CAMERA = new double[3];

    private static float[] matrix(int which) {
        MATRIX.clear();
        GL11.glGetFloat(which, MATRIX);
        float[] m = new float[16];
        MATRIX.get(m);
        return m;
    }

    // NTM draws the smoke at the muzzle of the gun in the player's hands: the GL matrix there is in view space, with
    // the hands' own field of view (NTM's model FOV). The muzzle is moved to where it shows on screen in the world's view.
    public static void muzzle() {
        float[] mv = matrix(GL11.GL_MODELVIEW_MATRIX);
        MUZZLE[0] = mv[12];
        MUZZLE[1] = mv[13];
        MUZZLE[2] = mv[14];
        handScale = matrix(GL11.GL_PROJECTION_MATRIX)[5];
        muzzleTime = GunClock.nanos();
    }

    private static double[] muzzleWorld() {
        if (worldView == null || handScale <= 0F || worldScale <= 0F) return null;
        float[] inv = Mat4.invertAffine(worldView);
        if (inv == null) return null;
        float k = handScale / worldScale;
        float[] w = Mat4.point(inv, MUZZLE[0] * k, MUZZLE[1] * k, MUZZLE[2]);
        return new double[] {CAMERA[0] + w[0], CAMERA[1] + w[1], CAMERA[2] + w[2]};
    }

    // ---- each frame

    public static void register() {
        Events e = new Events();
        MinecraftForge.EVENT_BUS.register(e);
        cpw.mods.fml.common.FMLCommonHandler.instance().bus().register(e);
    }

    public static final class Events {
        @SubscribeEvent
        public void renderLast(RenderWorldLastEvent e) {
            draw(e.partialTicks);
        }

        // the server's announcements as soon as the tick has the packets (26.2: enqueueWork)
        @SubscribeEvent
        public void tick(cpw.mods.fml.common.gameevent.TickEvent.ClientTickEvent e) {
            if (e.phase == cpw.mods.fml.common.gameevent.TickEvent.Phase.END) drain();
        }

        @SubscribeEvent
        public void unload(WorldEvent.Unload e) {
            if (e.world.isRemote) clear();
        }
    }

    private static void draw(float partial) {
        Minecraft mc = Minecraft.getMinecraft();
        World world = mc.theWorld;
        if (world == null) return;
        drain();
        CAMERA[0] = RenderManager.renderPosX;
        CAMERA[1] = RenderManager.renderPosY;
        CAMERA[2] = RenderManager.renderPosZ;
        worldView = matrix(GL11.GL_MODELVIEW_MATRIX);
        worldScale = matrix(GL11.GL_PROJECTION_MATRIX)[5];
        long now = GunClock.nanos();
        // a frame's worth of flight, so a fast bullet's tracer does not break into dashes
        double tickNow = flightTime();
        double frameTicks = Double.isNaN(lastFrame) ? 0.05 : Math.min(0.25, Math.max(0.01, tickNow - lastFrame));
        lastFrame = tickNow;
        // one screen pixel at one block away, for the tracer's least width
        float pixel = 2F / Math.max(1e-3F, worldScale) / Math.max(1, mc.displayHeight);
        List<float[]> streaks = new ArrayList<float[]>();
        BOUNCED.clear();
        for (Iterator<Shot> it = SHOTS.iterator(); it.hasNext();) {
            Shot s = it.next();
            double t = Math.max(0, tickNow - s.born);
            fly(s, world, Math.min(t, s.lifeTicks));
            BulletClock.Segment segment = BulletClock.segment(t, s.end, s.lifeTicks, Math.max(Config.tracerLength / s.path.speed, frameTicks * 1.5));
            if (segment.expired()) {
                if (TestLog.client && Double.isNaN(s.end)) TestLog.c(s.id, "end", "by", "life", "at", v(s.path.at(s.lifeTicks)));
                s.gone = true;
                it.remove();
                continue;
            }
            if (!Config.tracers || !segment.visible()) continue;
            double[] h = s.path.at(segment.head()), b = s.path.at(segment.tail());
            streaks.add(new float[] {(float) (h[0] - CAMERA[0]), (float) (h[1] - CAMERA[1]), (float) (h[2] - CAMERA[2]),
                    (float) (b[0] - CAMERA[0]), (float) (b[1] - CAMERA[1]), (float) (b[2] - CAMERA[2]),
                    Float.intBitsToFloat(s.light), Float.intBitsToFloat(s.dark), pixel});
        }
        SHOTS.addAll(BOUNCED);
        for (Shot bounced : BOUNCED) RECENT_SHOTS.put(bounced.id, bounced);
        BOUNCED.clear();
        if (!streaks.isEmpty()) drawStreaks(mc, partial, streaks);
        makePending();
        holes(mc, world, partial, now);
    }

    // ---- flight

    private static MovingObjectPosition rayTrace(World world, double[] a, double[] b) {
        // func_147447_a moves the vectors it is given: fresh ones
        return world.func_147447_a(Vec3.createVectorHelper(a[0], a[1], a[2]), Vec3.createVectorHelper(b[0], b[1], b[2]), false, true, false);
    }

    // test the bullet's path up to t ticks against blocks; stops it at the first block it meets
    private static void fly(Shot s, World world, double t) {
        if (!Double.isNaN(s.path.confirmedEnd)) t = Math.min(t, s.path.confirmedEnd);
        if (!Double.isNaN(s.end) || t <= s.checked) return;
        // the real path, without the muzzle offset, decides what is hit
        double[] ra = s.path.tested(s.checked), rb = s.path.tested(t);
        double[] dir = {rb[0] - ra[0], rb[1] - ra[1], rb[2] - ra[2]};
        double len = Math.sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2]);
        MovingObjectPosition hit = len > 1e-6 ? rayTrace(world, ra, rb) : null;
        if (hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK && hit.hitVec != null) {
            double[] at = {hit.hitVec.xCoord, hit.hitVec.yCoord, hit.hitVec.zCoord};
            double reach = Math.sqrt(sq(at[0] - ra[0]) + sq(at[1] - ra[1]) + sq(at[2] - ra[2]));
            s.end = s.checked + (t - s.checked) * reach / len;
            int[] pos = {hit.blockX, hit.blockY, hit.blockZ};
            int face = hit.sideHit;
            double[] unit = {dir[0] / len, dir[1] / len, dir[2] / len};
            if (TestLog.client) TestLog.c(s.id, "end", "by", "block", "block", name(world, pos), "pos", v(pos), "at", v(at));
            double[] vel = s.path.velocity(s.end);
            if (ricochet(s, world, pos, face, vel)) {
                // NTM's standard ricochet: a scratch, no hit effects (the server plays the ricochet's sound); the bullet
                // flies on mirrored on the face's axis unless that was its last
                if (TestLog.client) TestLog.c(s.id, "ricochet", "block", name(world, pos), "pos", v(pos), "bounce", s.ricochets + 1);
                if (Config.bulletHoles) PENDING.addLast(new Pending(world, at, face, pos, s.config, unit, normalized(vel), s.id));
                if (s.ricochets < s.config.maxRicochetCount) {
                    long left = (long) Math.max(0.0, s.lifeTicks - s.end);
                    if (left > 0) BOUNCED.add(new Shot(s, at, TracerPath.bounce(face, vel), s.born + s.end, left));
                }
            } else if (!heavy(s.config)) {
                hit(world, at, face, pos, s.config, unit, s.config.onImpact != null, s.id);
            }
        }
        s.checked = t;
        if (!Double.isNaN(s.path.confirmedEnd) && t >= s.path.confirmedEnd) s.end = s.path.confirmedEnd;
    }

    // NTM's standard ricochet (BulletConfig.LAMBDA_STANDARD_RICOCHET) as the server has it: a bullet without an impact
    // effect meeting a face at no more than its ricochetAngle bounces off; glass breaks
    private static boolean ricochet(Shot s, World world, int[] pos, int face, double[] vel) {
        BulletConfig c = s.config;
        if (c.onImpact != null || c.onRicochet != BulletConfig.LAMBDA_STANDARD_RICOCHET) return false;
        Block b = world.getBlock(pos[0], pos[1], pos[2]);
        if (b.getMaterial() == Material.air || b.getMaterial() == Material.glass || !rules.ricochet(b, world.getBlockMetadata(pos[0], pos[1], pos[2])))
            return false;
        return TracerPath.glances(face, vel, c.ricochetAngle);
    }

    // a hit's hole and its sound and particles
    private static void hit(World world, double[] at, int face, int[] pos, BulletConfig config, double[] dir, boolean explosive, int shot) {
        if (Config.bulletHoles) PENDING.addLast(new Pending(world, at, face, pos, config, dir, null, shot));
        BulletFx.blockHit(world, pos, face, at, dir, explosive);
    }

    // ---- holes

    // pos = where on the face it lies; rect = the area of the face it may cover, {uMin, uMax, vMin, vMax} on the
    // face's two axes; mask = which 1/16-block cells of the face show the block's pixels (null = all); box = the
    // block's hitbox when it was made (it goes when that changes)
    private static final class Hole {
        final double[] pos;
        final int face, turn, light, kind, shot;
        final long born;
        final float size;
        final double[] rect;
        final HoleMath.Mask mask;
        final double plane;
        final boolean cull;
        final int x, y, z, meta;
        final Block block;
        final double[] box;

        Hole(double[] pos, int face, int turn, long born, int light, int kind, float size, double[] rect, HoleMath.Mask mask, double plane,
                boolean cull, int[] at, Block block, int meta, double[] box, int shot) {
            this.pos = pos;
            this.face = face;
            this.turn = turn;
            this.born = born;
            this.light = light;
            this.kind = kind;
            this.size = size;
            this.rect = rect;
            this.mask = mask;
            this.plane = plane;
            this.cull = cull;
            this.x = at[0];
            this.y = at[1];
            this.z = at[2];
            this.block = block;
            this.meta = meta;
            this.box = box;
            this.shot = shot;
        }

        // port: no holes on what a renderer draws yet (26.2: NTM machines and doors), so none count for holeMaxMachines
        boolean machine() {
            return false;
        }
    }

    // holes waiting to be made: a burst's holes (a shotgun) are made over the next frames, as many as fit in
    // HOLE_BUDGET_NS a frame (at least one), instead of all in the frame they were hit
    private static final class Pending {
        final World world;
        final double[] at, dir, scratch;
        final int face, shot;
        final int[] pos;
        final BulletConfig config;

        Pending(World world, double[] at, int face, int[] pos, BulletConfig config, double[] dir, double[] scratch, int shot) {
            this.world = world;
            this.at = at;
            this.face = face;
            this.pos = pos;
            this.config = config;
            this.dir = dir;
            this.scratch = scratch;
            this.shot = shot;
        }
    }

    private static final ArrayDeque<Pending> PENDING = new ArrayDeque<Pending>();
    private static final ArrayDeque<Hole> HOLES = new ArrayDeque<Hole>();
    private static final long HOLE_BUDGET_NS = 3_000_000L;

    static int pendingHoles() {
        return PENDING.size();
    }

    static int holeCount() {
        return HOLES.size();
    }

    private static void makePending() {
        long from = GunClock.nanos();
        int made = 0;
        World now = Minecraft.getMinecraft().theWorld;
        while (!PENDING.isEmpty() && (made == 0 || GunClock.nanos() - from < HOLE_BUDGET_NS)) {
            Pending p = PENDING.pollFirst();
            if (p.world != now || !Config.bulletHoles) continue;
            Hole h = makeHole(p);
            if (TestLog.client) TestLog.c(p.shot, "hole", "block", name(p.world, p.pos), "pos", v(p.pos), "at", v(h != null ? h.pos : p.at),
                    "placed", h != null, "scratch", p.scratch != null, "holes", HOLES.size(), "max", Config.holeMax);
            if (h != null && Trace.ON) TestLog.c(p.shot, "hole_made", "pos", v(p.pos), "at", v(h.pos), "face", h.face, "kind", h.kind,
                    "turn", h.turn, "size", h.size, "plane", h.plane, "rect", h.rect);
            made++;
        }
    }

    // a flat hole on the hit face, or none: on a block drawn by its renderer (no block model: NTM's machines), on a
    // see-through pixel, or when none of the block's pixels lie around the hit
    private static Hole makeHole(Pending p) {
        World world = p.world;
        int[] pos = p.pos;
        Block b = world.getBlock(pos[0], pos[1], pos[2]);
        int meta = world.getBlockMetadata(pos[0], pos[1], pos[2]);
        if (b.getMaterial() == Material.air || b.getRenderType() < 0) return null;
        WorldPixels pixels = new WorldPixels(world);
        int face = p.face;
        int normal = HoleMath.axis(face);
        double[] hit = p.at.clone();
        // where the ray met the hitbox, on the face's axis
        double boxFace = hit[normal];
        boolean hitboxOnly = pixels.hitboxOnly(pos[0], pos[1], pos[2]);
        if (!hitboxOnly) {
            // a plain cube's model is its hitbox: the hole is where the bullet met it, unless that pixel is see-through
            int[] ax = HoleMath.axes(face);
            if (Double.isNaN(pixels.shows(pos[0], pos[1], pos[2], face, hit[ax[0]] - pos[ax[0]], hit[ax[1]] - pos[ax[1]]))) return null;
        }
        int[] off = HoleMath.offset(face);
        int light = world.getLightBrightnessForSkyBlocks(pos[0] + off[0], pos[1] + off[1], pos[2] + off[2], 0);
        boolean scorch = p.config.onImpact != null;
        Decals.Set set = rules.decal(b, meta);
        int kind = scorch ? Decals.scorch(set.scorch()) : Decals.hole(set.hole());
        Random r = Rng.shot(p.shot);
        float size = (scorch ? Config.scorchSize : Config.holeSize) * holeScale(p.config) * (0.8F + 0.4F * (float) r.nextDouble());
        int turn = (int) (r.nextDouble() * 8);
        if (p.scratch != null) {
            kind = Decals.scratch(set.scratch());
            turn = HoleMath.scratchTurn(p.scratch, face);
            size = Config.holeSize * 1.4F * (0.9F + 0.2F * (float) r.nextDouble());
        }
        turn = HoleMath.withVariant(turn, r.nextDouble());
        WorldBoxes boxes = new WorldBoxes(world);
        double[] rect = HoleMath.faceRect(pos, face, boxes);
        HoleMath.Mask mask = null;
        double plane = Double.NaN;
        if (!hitboxOnly) {
            mask = HoleMath.mask(hit, face, pos, size * 0.5F, rect, pixels);
            if (mask == HoleMath.NO_PIXELS) return null;
            plane = mask.plane;
        }
        // one-sided (seen only from outside, like the face itself) when the pixels lie on the hitbox's face; two-sided
        // on glass and where the pixels lie inside the hitbox
        double onAxis = Double.isNaN(plane) ? hit[normal] : plane;
        boolean glass = rules.glass(b, meta);
        boolean cull = (boxes.fullCube(pos[0], pos[1], pos[2]) || Math.abs(onAxis - boxFace) < 1e-3) && !glass;
        Hole h = new Hole(hit, face, turn, GunClock.nanos(), light, kind, size, rect, mask, plane, cull, pos.clone(), b, meta,
                boxes.box(pos[0], pos[1], pos[2]), p.shot);
        HOLES.addLast(h);
        trimHoles();
        return h;
    }

    private static int trimmedMax = -1, trimmedMachines = -1;

    private static void trimHoles() {
        trimmedMax = Config.holeMax;
        trimmedMachines = Config.holeMaxMachines;
        HoleLimits.trim(HOLES, Hole::machine, h -> h.born, false, Config.holeMax);
        HoleLimits.trim(HOLES, Hole::machine, h -> h.born, true, Config.holeMaxMachines);
    }

    private static long blocksChecked;

    // their life, their limits, whether their blocks are still there; then drawn
    private static void holes(Minecraft mc, World world, float partial, long now) {
        if (HOLES.isEmpty()) return;
        long life = HoleMath.lifeNanos(Config.holeLife);
        HoleMath.expire(HOLES, now, life, h -> h.born);
        // the limits changed (/gunfeel quality, the config file): applied now, not when the next hole is made
        if (Config.holeMax != trimmedMax || Config.holeMaxMachines != trimmedMachines) trimHoles();
        // the block it was made on was broken, replaced or changed its shape (a door opened): gone with it (four times
        // a second, not every frame)
        if (now - blocksChecked > 250_000_000L) {
            blocksChecked = now;
            WorldBoxes boxes = new WorldBoxes(world);
            for (Iterator<Hole> it = HOLES.iterator(); it.hasNext();) {
                Hole h = it.next();
                Block b = world.getBlock(h.x, h.y, h.z);
                if (b != h.block) it.remove();
                else if (world.getBlockMetadata(h.x, h.y, h.z) != h.meta && !java.util.Arrays.equals(boxes.box(h.x, h.y, h.z), h.box)) it.remove();
            }
        }
        if (HOLES.isEmpty() || !Config.bulletHoles) return;
        drawHoles(mc, partial, now, life);
    }

    // ---- drawing (26.2: SubmitCustomGeometryEvent and HoleRender; here immediate GL after the world, as NTM draws)

    // a thin ribbon from tail to head, bright at the head, fading to nothing at the tail; both windings
    private static void drawStreaks(Minecraft mc, float partial, List<float[]> streaks) {
        mc.entityRenderer.disableLightmap(partial);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glEnable(GL11.GL_BLEND);
        OpenGlHelper.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GL11.glDepthMask(false);
        GL11.glShadeModel(GL11.GL_SMOOTH);
        GL11.glColor4f(1F, 1F, 1F, 1F);
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        for (float[] k : streaks) {
            float[][] q = TracerPath.streak(new float[] {k[0], k[1], k[2]}, new float[] {k[3], k[4], k[5]}, Config.tracerWidth, k[8]);
            if (q == null) continue;
            int light = Float.floatToRawIntBits(k[6]) & 0xFFFFFF, dark = Float.floatToRawIntBits(k[7]) & 0xFFFFFF;
            for (int pass = 0; pass < 2; pass++) {
                for (int j = 0; j < 4; j++) {
                    int i = pass == 0 ? j : 3 - j;
                    if (i < 2) t.setColorRGBA_I(light, 0xFF);
                    else t.setColorRGBA_I(dark, 0x30);
                    t.addVertex(q[i][0], q[i][1], q[i][2]);
                }
            }
        }
        t.draw();
        GL11.glShadeModel(GL11.GL_FLAT);
        GL11.glDepthMask(true);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    // Like entity_translucent but without writing depth (overlapping holes would hide each other's soft edges), with
    // a depth bias that keeps them on the face they lie on; one-sided holes (on a cube's face) culled, the others
    // not. In the order they were made, a newer hole over an older one; grouped by texture.
    private static void drawHoles(Minecraft mc, float partial, long now, long life) {
        mc.entityRenderer.enableLightmap(partial);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_BLEND);
        OpenGlHelper.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GL11.glAlphaFunc(GL11.GL_GREATER, 0.003F);
        GL11.glDepthMask(false);
        GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
        GL11.glPolygonOffset(-1F, -10F);
        GL11.glColor4f(1F, 1F, 1F, 1F);
        Tessellator t = Tessellator.instance;
        for (int group = 0; group < 2 * KINDS; group++) {
            int kind = group % KINDS;
            boolean cull = group >= KINDS;
            boolean started = false;
            for (Hole h : HOLES) {
                if (h.kind != kind || h.cull != cull) continue;
                int alpha = HoleMath.alpha(now - h.born, life);
                if (alpha <= 0) continue;
                for (double[] clip : HoleMath.cells(h.rect, h.mask)) {
                    float[][] q = HoleMath.quad(h.pos, h.face, h.plane, h.cull, h.turn, h.size * 0.5F, clip, CAMERA);
                    if (q == null) continue;
                    if (!started) {
                        started = true;
                        if (cull) GL11.glEnable(GL11.GL_CULL_FACE);
                        else GL11.glDisable(GL11.GL_CULL_FACE);
                        mc.getTextureManager().bindTexture(TEXTURES[kind]);
                        t.startDrawingQuads();
                    }
                    t.setBrightness(h.light);
                    t.setColorRGBA_I(0xFFFFFF, alpha);
                    t.setNormal(q[0][5], q[0][6], q[0][7]);
                    for (float[] c : q) t.addVertexWithUV(c[0], c[1], c[2], c[3], c[4]);
                }
            }
            if (started) t.draw();
        }
        GL11.glPolygonOffset(0F, 0F);
        GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
        GL11.glDepthMask(true);
        GL11.glAlphaFunc(GL11.GL_GREATER, 0.1F);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_CULL_FACE);
        mc.entityRenderer.disableLightmap(partial);
    }

    // ---- the world behind HoleMath's interfaces

    // the blocks' collision boxes (26.2: getCollisionShape bounds)
    static final class WorldBoxes implements HoleMath.Boxes {
        private final World world;

        WorldBoxes(World world) {
            this.world = world;
        }

        @Override
        public double[] box(int x, int y, int z) {
            Block b = world.getBlock(x, y, z);
            if (b.getMaterial() == Material.air) return null;
            AxisAlignedBB bb;
            try {
                bb = b.getCollisionBoundingBoxFromPool(world, x, y, z);
            } catch (RuntimeException e) {
                return null;
            }
            return bb == null ? null : new double[] {bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ};
        }

        @Override
        public boolean fullCube(int x, int y, int z) {
            double[] b = box(x, y, z);
            return b != null && b[0] == x && b[1] == y && b[2] == z && b[3] == x + 1 && b[4] == y + 1 && b[5] == z + 1;
        }
    }

    // What the blocks show: a plain cube (render type 0) by its faces' icons, like RenderBlocks draws them; any other
    // block by its hitbox (26.2 asks its baked quads; 1.7.10 has none for blocks a RenderBlocks method draws).
    static final class WorldPixels implements HoleMath.Pixels {
        private final World world;

        WorldPixels(World world) {
            this.world = world;
        }

        @Override
        public boolean hitboxOnly(int x, int y, int z) {
            Block b = world.getBlock(x, y, z);
            if (b.getMaterial() == Material.air) return false;
            return b.getRenderType() != 0 || rules.hitboxOnly(b, world.getBlockMetadata(x, y, z));
        }

        @Override
        public double shows(int x, int y, int z, int face, double lu, double lv) {
            Block b = world.getBlock(x, y, z);
            if (b.getMaterial() == Material.air || b.getRenderType() != 0) return Double.NaN;
            b.setBlockBoundsBasedOnState(world, x, y, z);
            double[] bounds = {b.getBlockBoundsMinX(), b.getBlockBoundsMinY(), b.getBlockBoundsMinZ(), b.getBlockBoundsMaxX(),
                    b.getBlockBoundsMaxY(), b.getBlockBoundsMaxZ()};
            int normal = HoleMath.axis(face);
            int[] ax = HoleMath.axes(face);
            int step = HoleMath.step(face);
            if (lu < bounds[ax[0]] - 1e-4 || lu > bounds[3 + ax[0]] + 1e-4 || lv < bounds[ax[1]] - 1e-4 || lv > bounds[3 + ax[1]] + 1e-4)
                return Double.NaN;
            double near = Double.NaN;
            boolean shown = false;
            for (int s = -1; s <= 1; s += 2) {
                int f = HoleMath.face(normal, s);
                int[] o = HoleMath.offset(f);
                if (!b.shouldSideBeRendered(world, x + o[0], y + o[1], z + o[2], f)) continue;
                double depth = s > 0 ? bounds[3 + normal] : bounds[normal];
                double[] local = new double[3];
                local[ax[0]] = lu;
                local[ax[1]] = lv;
                local[normal] = depth;
                int px = sample(b, x, y, z, f, local, bounds);
                // behind the nearest face found so far: hidden by it
                if (!Double.isNaN(near) && (depth - near) * step < -1e-4) continue;
                if (Double.isNaN(near) || (depth - near) * step > 1e-4) {
                    near = depth;
                    shown = false;
                }
                if (px == 1) shown = true;
            }
            return shown ? near : Double.NaN;
        }

        // the face's pixel at that point of the block: 0 see-through, 1 shown (anything unreadable counts as shown)
        private int sample(Block b, int x, int y, int z, int face, double[] local, double[] bounds) {
            try {
                IIcon icon = b.getIcon(world, x, y, z, face);
                if (!(icon instanceof TextureAtlasSprite)) return 1;
                TextureAtlasSprite sprite = (TextureAtlasSprite) icon;
                int w = sprite.getIconWidth(), h = sprite.getIconHeight();
                if (w <= 0 || h <= 0 || sprite.getFrameCount() <= 0) return 1;
                int[][] frame = sprite.getFrameTextureData(0);
                if (frame == null || frame.length == 0 || frame[0] == null || frame[0].length < w * h) return 1;
                double[] uv = HoleMath.faceUv(face, local, bounds);
                int px = Math.min(w - 1, Math.max(0, (int) (uv[0] / 16.0 * w)));
                int py = Math.min(h - 1, Math.max(0, (int) (uv[1] / 16.0 * h)));
                return (frame[0][py * w + px] >>> 24) == 0 ? 0 : 1;
            } catch (RuntimeException e) {
                return 1;
            }
        }
    }

    // ---- small things

    private static double sq(double d) {
        return d * d;
    }

    private static double[] normalized(double[] v) {
        double l = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        return l < 1e-12 ? v.clone() : new double[] {v[0] / l, v[1] / l, v[2] / l};
    }

    private static String name(World world, int[] pos) {
        Block b = world.getBlock(pos[0], pos[1], pos[2]);
        return Block.blockRegistry.getNameForObject(b) + "[" + world.getBlockMetadata(pos[0], pos[1], pos[2]) + "]";
    }

    private static String v(double[] p) {
        return String.format(java.util.Locale.ROOT, "(%.4f %.4f %.4f)", p[0], p[1], p[2]);
    }

    private static String v(int[] p) {
        return p[0] + "," + p[1] + "," + p[2];
    }
}
