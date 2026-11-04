// Ported from 26.2's src/main/java/acup/gunfeel/Tracers.java at 0762086 (port/1.7.10-forge de8bdf7), cut down to what
// 1.7.10 can do without 26.2's model capture: the tracers, their flight through the client's world, NTM's standard
// ricochet, the server's stop point, piercing as the server pierces (a hole in and a hole out), and holes on block faces
// (cut to the hitbox and the pixels of plain cubes, wrapped over plain cubes' edges by BlockDecal).
// The pure parts are in HoleMath and TracerPath (same numbers as 26.2); this file holds the world and the drawing.
// Holes on what a renderer draws (NTM machines and doors) come from MachineMesh, wrapped over the part they sit on.
// Left out (26.2 only, for now): DecalSurface over non-cube block models, the graphics-card hole cache (Batch, holeCache), the late pass for glass, entity hits for unannounced shots, ClientSmoke / HoleBenchmark hooks.
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

import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.Vec3d;
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

    public interface Rules {
        // the marks a bullet leaves on it
        Decals.Set decal(World world, Block block, int meta, int x, int y, int z);

        // its holes follow only the hitbox, not its see-through pixels ("hole ... hitbox": glass)
        boolean hitboxOnly(World world, Block block, int meta, int x, int y, int z);

        // glass: holes on it are two-sided
        boolean glass(World world, Block block, int meta, int x, int y, int z);

        // NTM's standard ricochet may bounce a bullet off it
        boolean ricochet(World world, Block block, int meta, int x, int y, int z);
    }

    // the bullet rules (ntmgunfeel-bullets.txt and the player's or server's file), as 26.2's Tracers asks them
    public static final class FileRules implements Rules {
        @Override
        public Decals.Set decal(World world, Block block, int meta, int x, int y, int z) {
            return BulletRules.decal(block, meta, world, x, y, z);
        }

        @Override
        public boolean hitboxOnly(World world, Block block, int meta, int x, int y, int z) {
            return "hitbox".equals(BulletRules.hole(block, meta, world, x, y, z)[1]);
        }

        // 26.2's glassLike: a "hole" line's glass or plain, else by the block's material
        @Override
        public boolean glass(World world, Block block, int meta, int x, int y, int z) {
            String drawn = BulletRules.hole(block, meta, world, x, y, z)[0];
            if (drawn != null) return drawn.equals("glass");
            return BulletRules.material(block, meta, world, x, y, z).material().glass();
        }

        @Override
        public boolean ricochet(World world, Block block, int meta, int x, int y, int z) {
            return BulletRules.ricochet(block, meta, world, x, y, z);
        }
    }

    public static volatile Rules rules = new FileRules();

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
        boolean whizzed; // already whizzed past the player
        int ricochets;
        float damage; // what is left of its damage (bullet rules "pierce")
        final Materials.Penetration penetration = new Materials.Penetration();
        final java.util.Set<Long> pierced = new java.util.HashSet<Long>(); // blocks it went through (BulletServer.key)

        Shot(int id, BulletConfig config, int owner, TracerPath path, int[] colors, long lifeTicks, double born, float damage) {
            this.id = id;
            this.damage = damage;
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
            this.whizzed = from.whizzed;
            this.ricochets = from.ricochets + 1;
            this.damage = from.damage;
            this.penetration.restore(from.penetration.spent());
            this.pierced.addAll(from.pierced);
            this.checked = 1e-4; // not at the very face it left
        }
    }

    private static final List<Shot> SHOTS = new ArrayList<Shot>();
    private static final LinkedHashMap<Integer, Shot> RECENT_SHOTS = new LinkedHashMap<Integer, Shot>();
    private static final List<Shot> BOUNCED = new ArrayList<Shot>();
    private static boolean serverAnnounces;
    private static double lastFrame = Double.NaN;
    private static final BulletClock FLIGHT_CLOCK = new BulletClock();

    // The tracers' time: the client's own ticks, not the world's. The world's time is set to the server's every second
    // (S03PacketTimeUpdate), and a server behind 20 ticks a second (a loaded world, a busy server) puts it back by a
    // few ticks each time: the clock, which never goes back, stood still for as long, the tracers in the air stopped and
    // new ones were not seen until it went on. A tick of the game that is not paused, as the world's.
    private static long clientTicks;

    private static double flightTime() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null) return FLIGHT_CLOCK.current();
        return FLIGHT_CLOCK.sample(clientTicks, framePartial(mc));
    }

    // This frame's partial tick from the game's timer. The timer is updated before the frame's ticks, RenderTickEvent
    // only after them: a tick's announcement read with the event's value (the last frame's) put the clock up to a tick
    // ahead of the frames.
    private static net.minecraft.util.Timer timer;

    private static float framePartial(Minecraft mc) {
        if (timer == null) timer = net.minecraftforge.fml.relauncher.ReflectionHelper.getPrivateValue(Minecraft.class, mc, "timer", "field_71428_T");
        return timer.renderPartialTicks;
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

    // True when this bullet type's tracer is drawn here instead of by NTM. Only a type already probed (at its first
    // announced shot): NTM's bullet is drawn in the world's passes, and under a shader pack one of them is the shadow
    // pass, where Shader Fixer does not run NTM's tracer at all (its WrapMethod on renderBulletStandard). A probe there
    // found no colours and kept that: the type had no tracer for the rest of the game (AM180's .22 in the owner's pack).
    public static boolean replaces(BulletConfig config) {
        if (!serverAnnounces || !Config.tracers || config == null || config.velocity < 5F) return false;
        int[] c = COLORS.get(config);
        return c != null && c != NONE;
    }

    // port: NTM 1.7.10 keeps the tracer as config.renderer, drawn for a bullet entity; called once for a bullet that
    // is never spawned, with a length of 1, while probing: the plain tracer (LegoClient.renderBulletStandard) tells its
    // colours to MixinLegoClient instead of drawing. Any other renderer draws for real, so it draws scaled to nothing,
    // with its GL state put back.
    private static int[] colors(BulletConfig config) {
        int[] c = COLORS.get(config);
        if (c != null) return c;
        World world = Minecraft.getMinecraft().world;
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
        if (mc.player != null && owner == mc.player.getEntityId() && GunClock.nanos() - muzzleTime < 500_000_000L
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
        if (TestLog.client) {
            // the shooter's own shot: where the client's camera was and looked as the server's shot came in (the owner:
            // "the guns shoot a little above the crosshair"), against the server's start and way
            boolean own = mc.player != null && owner == mc.player.getEntityId();
            TestLog.c(id, "fire", "ammo", config, "own", own, "at", v(path.start), "v", v(path.v), "damage", damage,
                    "eye", own ? v(new double[] {mc.player.posX, mc.player.posY, mc.player.posZ}) : "-",
                    "look", own ? mc.player.rotationPitch + "," + mc.player.rotationYaw : "-");
        }
        Shot s = new Shot(id, bc, owner, path, c, life, flightTime(), damage);
        SHOTS.add(s);
        RECENT_SHOTS.put(s.id, s);
        if (RECENT_SHOTS.size() > 512) RECENT_SHOTS.remove(RECENT_SHOTS.keySet().iterator().next());
        if (SHOTS.size() > 512) SHOTS.remove(0).gone = true;
        BulletFx.fly(() -> s.path.at(Math.min(Math.max(0, flightTime() - s.born), Double.isNaN(s.end) ? s.lifeTicks : s.end)),
                () -> !s.gone && Double.isNaN(s.end) && flightTime() - s.born < s.lifeTicks);
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
        World w = Minecraft.getMinecraft().world;
        if (w == null || !Config.bulletHoles || config < 0 || config >= BulletConfig.configs.size() || face < 0 || face > 5) return;
        BulletConfig bc = BulletConfig.configs.get(config);
        if (!ExplosiveAmmo.heavy(bc) || !Double.isFinite(px + py + pz + dx + dy + dz)) return;
        PENDING.addLast(new Pending(w, new double[] {px, py, pz}, face, new int[] {bx, by, bz}, bc, new double[] {dx, dy, dz}, null, shot));
    }

    // the holes on machines and doors now, and how many pieces each is wrapped into (the look scenario's log)
    public static String machineHoles() {
        int n = 0;
        StringBuilder pieces = new StringBuilder();
        java.util.TreeMap<String, Integer> kinds = new java.util.TreeMap<String, Integer>();
        for (Hole h : HOLES) if (h.machine()) {
            n++;
            pieces.append(pieces.length() == 0 ? "" : " ").append(h.pieces.size());
            String k = MachineMesh.kind(h.mesh);
            kinds.put(k, kinds.containsKey(k) ? kinds.get(k) + 1 : 1);
        }
        return n + " " + kinds + " (pieces " + pieces + ")";
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
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(e);
    }

    public static final class Events {
        @SubscribeEvent
        public void renderLast(RenderWorldLastEvent e) {
            if (acup.gunfeel.compat.Feature.TRACERS.on()) draw(e.getPartialTicks());
        }

        @SubscribeEvent
        public void frame(net.minecraftforge.fml.common.gameevent.TickEvent.RenderTickEvent e) {
            if (e.phase == net.minecraftforge.fml.common.gameevent.TickEvent.Phase.START) frame++;
        }

        // the server's announcements as soon as the tick has the packets (26.2: enqueueWork)
        @SubscribeEvent
        public void tick(net.minecraftforge.fml.common.gameevent.TickEvent.ClientTickEvent e) {
            if (e.phase != net.minecraftforge.fml.common.gameevent.TickEvent.Phase.END) return;
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.world != null && !mc.isGamePaused()) clientTicks++;
            if (acup.gunfeel.compat.Feature.TRACERS.on()) drain();
        }

        @SubscribeEvent
        public void unload(WorldEvent.Unload e) {
            if (e.getWorld().isRemote) clear();
        }
    }

    private static void draw(float partial) {
        Minecraft mc = Minecraft.getMinecraft();
        World world = mc.world;
        if (world == null) return;
        drain();
        CAMERA[0] = Minecraft.getMinecraft().getRenderManager().viewerPosX;
        CAMERA[1] = Minecraft.getMinecraft().getRenderManager().viewerPosY;
        CAMERA[2] = Minecraft.getMinecraft().getRenderManager().viewerPosZ;
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
        // the holes went with the world already this frame (drawWorldHoles); here only when that hook did not run
        if (holesFrame == frame) return;
        makePending();
        holes(mc, world, partial, now);
    }

    // the frames, and the one whose holes are drawn already, before the block cracks
    private static long frame, holesFrame = -1;

    // The holes with the world (MixinRenderGlobal, as RenderGlobal.drawBlockDamageTexture starts), as 26.2 draws them
    // with the blocks: under a shader pack (Angelica) RenderWorldLastEvent comes after the pack has finished its frame,
    // so holes drawn there missed its fog and light; and the block cracks drawn after them lie over them. Once a frame;
    // the caller's GL state (its blend for the cracks) is kept.
    public static void drawWorldHoles(float partial) {
        if (holesFrame == frame) return;
        if (!acup.gunfeel.compat.Feature.TRACERS.on()) return;
        Minecraft mc = Minecraft.getMinecraft();
        World world = mc.world;
        if (world == null) return;
        drain();
        CAMERA[0] = Minecraft.getMinecraft().getRenderManager().viewerPosX;
        CAMERA[1] = Minecraft.getMinecraft().getRenderManager().viewerPosY;
        CAMERA[2] = Minecraft.getMinecraft().getRenderManager().viewerPosZ;
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_POLYGON_BIT | GL11.GL_CURRENT_BIT);
        try {
            makePending();
            holes(mc, world, partial, GunClock.nanos());
        } finally {
            GL11.glPopAttrib();
        }
        holesFrame = frame;
    }

    // ---- flight

    // the last block met was a machine's window (MachineCells' glass): its sound and crumbs are glass's
    private static boolean window(BlockRay.Hit h) {
        return h instanceof BlockRay.GlassHit;
    }

    private static double[] point(BlockRay.Hit h) {
        return new double[] {h.ax, h.ay, h.az};
    }

    private static int[] cell(BlockRay.Hit h) {
        return new int[] {h.x, h.y, h.z};
    }

    // test the bullet's path up to t ticks against blocks (PassableClip: fence gaps, bars and grates by their model's
    // cells, as the server has it). It pierces them while it has the damage (PassableClip.pierced, as BulletServer.clip
    // and 26.2's fly): a hole where it goes in and where it comes out. Stops it at the first block that holds it.
    private static void fly(Shot s, World world, double t) {
        if (!Double.isNaN(s.path.confirmedEnd)) t = Math.min(t, s.path.confirmedEnd);
        if (!Double.isNaN(s.end) || t <= s.checked) return;
        // the real path, without the muzzle offset, decides what is hit
        double[] ra = s.path.tested(s.checked), rb = s.path.tested(t);
        double[] dir = {rb[0] - ra[0], rb[1] - ra[1], rb[2] - ra[2]};
        double len = Math.sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2]);
        BlockRay.Hit hit = null;
        if (len > 1e-6) {
            double[] unit = {dir[0] / len, dir[1] / len, dir[2] / len};
            Vec3d to = new Vec3d(rb[0], rb[1], rb[2]);
            hit = PassableClip.clip(world, new Vec3d(ra[0], ra[1], ra[2]), to);
            for (int i = 0; i < 16 && hit != null; i++) {
                int[] pos = cell(hit);
                if (s.config.onImpact != null) { // explosive: goes off on the first block
                    logStop(s, world, hit, "explosive", Float.NaN);
                    break;
                }
                long key = BulletServer.key(hit.x, hit.y, hit.z);
                boolean once = PassableClip.paidOnce(hit), known = once && s.pierced.contains(key);
                BlockRay.Hit out = PassableClip.exit(world, hit, unit[0], unit[1], unit[2]);
                if (out == null) {
                    logStop(s, world, hit, "no_exit", Float.NaN);
                    break;
                }
                float left = known ? s.damage : PassableClip.pierced(world, hit, out, s.damage, s.config, s.penetration);
                if (left < 0F) {
                    logStop(s, world, hit, "held", (float) hit.distance(out.ax, out.ay, out.az));
                    break;
                }
                if (TestLog.client && !known) TestLog.c(s.id, "block", "block", name(world, pos), "pos", v(pos), "at", v(point(hit)),
                        "material", TestLog.material(world, hit), "result", "pierce", "damage", s.damage, "left", left,
                        "path", hit.distance(out.ax, out.ay, out.az), "out", v(point(out)), "out_face", out.face);
                if (!known) {
                    if (once) s.pierced.add(key);
                    s.damage = left;
                    // from one steel panel straight into the next (two stacked steel corners): no hole, sound or
                    // particles on the face between them, where nothing shows; it went into this one from inside the last
                    if (!once || !fromInside(world, hit, unit)) hit(world, point(hit), hit.face, pos, s.config, unit, false, s.id, window(hit));
                    // a window: one hole, seen from both sides, and no second one a pixel behind it
                    if (once && !against(world, out))
                        hit(world, point(out), out.face, cell(out), s.config, new double[] {-unit[0], -unit[1], -unit[2]}, false, s.id, false);
                }
                // it comes out beyond this step's end: inside the block till the next one
                if (out.distanceSq(ra[0], ra[1], ra[2]) >= len * len) {
                    hit = null;
                    break;
                }
                hit = PassableClip.clip(world, new Vec3d(out.ax + unit[0] * 1e-3, out.ay + unit[1] * 1e-3, out.az + unit[2] * 1e-3), to);
            }
        }
        double[] stop = hit != null ? point(hit) : rb;
        if (!s.whizzed) s.whizzed = BulletFx.whizz(world, s.owner, ra, stop, s.path.speed);
        if (hit != null) {
            boolean window = window(hit);
            double[] at = point(hit);
            double reach = Math.sqrt(sq(at[0] - ra[0]) + sq(at[1] - ra[1]) + sq(at[2] - ra[2]));
            s.end = s.checked + (t - s.checked) * reach / len;
            int[] pos = cell(hit);
            int face = hit.face;
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
            } else if (!ExplosiveAmmo.heavy(s.config)) {
                hit(world, at, face, pos, s.config, unit, s.config.onImpact != null, s.id, window);
            }
        }
        s.checked = t;
        if (!Double.isNaN(s.path.confirmedEnd) && t >= s.path.confirmedEnd) s.end = s.path.confirmedEnd;
    }

    // the steel panels: a bullet goes from one straight into the next where they stand together (26.2's PANELS)
    private static boolean panel(Block b) {
        Object n = Mc.name(b);
        return "hbm:steel_corner".equals(n) || "hbm:steel_wall".equals(n);
    }

    // the bullet went into this steel panel straight from inside the last one: no hole where it went in
    private static boolean fromInside(World world, BlockRay.Hit hit, double[] unit) {
        if (!hit.inside || !panel(Mc.block(world, hit.x, hit.y, hit.z))) return false;
        return panel(Mc.block(world, (int) Math.floor(hit.ax - unit[0] * 2e-3), (int) Math.floor(hit.ay - unit[1] * 2e-3), (int) Math.floor(hit.az - unit[2] * 2e-3)));
    }

    // the steel panel's face lies against another panel's solid part (the bullet goes on straight into it): a hole
    // there could not be seen, it would only spill onto the faces around
    private static boolean against(World world, BlockRay.Hit hit) {
        if (!panel(Mc.block(world, hit.x, hit.y, hit.z))) return false;
        int[] off = HoleMath.offset(hit.face);
        double px = hit.ax + off[0] * 1e-3, py = hit.ay + off[1] * 1e-3, pz = hit.az + off[2] * 1e-3;
        int nx = (int) Math.floor(px), ny = (int) Math.floor(py), nz = (int) Math.floor(pz);
        if (nx == hit.x && ny == hit.y && nz == hit.z) return false;
        Block n = Mc.block(world, nx, ny, nz);
        if (!panel(n)) return false;
        List<net.minecraft.util.math.AxisAlignedBB> boxes = new ArrayList<net.minecraft.util.math.AxisAlignedBB>();
        Mc.collision(world, nx, ny, nz, new net.minecraft.util.math.AxisAlignedBB(px - 1e-4, py - 1e-4, pz - 1e-4,
                px + 1e-4, py + 1e-4, pz + 1e-4), boxes, null);
        return !boxes.isEmpty();
    }

    // the test log: the bullet stops at this block on the client (the hit itself: "end" in fly)
    private static void logStop(Shot s, World world, BlockRay.Hit hit, String why, float path) {
        if (!TestLog.client) return;
        int[] pos = cell(hit);
        TestLog.c(s.id, "block", "block", name(world, pos), "pos", v(pos), "at", v(point(hit)), "material", TestLog.material(world, hit),
                "result", "stop", "why", why, "damage", s.damage, "path", Float.isNaN(path) ? null : path);
    }

    // NTM's standard ricochet (BulletConfig.LAMBDA_STANDARD_RICOCHET) as the server has it: a bullet without an impact
    // effect meeting a face at no more than its ricochetAngle bounces off; glass breaks
    private static boolean ricochet(Shot s, World world, int[] pos, int face, double[] vel) {
        BulletConfig c = s.config;
        if (c.onImpact != null || c.onRicochet != BulletConfig.LAMBDA_STANDARD_RICOCHET) return false;
        Block b = Mc.block(world, pos[0], pos[1], pos[2]);
        if (Mc.material(b) == Material.AIR || Mc.material(b) == Material.GLASS || !rules.ricochet(world, b, Mc.meta(world, pos[0], pos[1], pos[2]), pos[0], pos[1], pos[2]))
            return false;
        return TracerPath.glances(face, vel, c.ricochetAngle);
    }

    // a hit's hole and its sound and particles
    private static void hit(World world, double[] at, int face, int[] pos, BulletConfig config, double[] dir, boolean explosive, int shot, boolean window) {
        if (Config.bulletHoles) PENDING.addLast(new Pending(world, at, face, pos, config, dir, null, shot));
        BulletFx.blockHit(world, pos, face, at, dir, explosive, window);
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
        // on what a renderer draws (NTM's machines and doors, MachineMesh): where it sits on its part; null = on a block face
        MachineMesh.Anchor mesh;
        // ... its pieces wrapped over the part's surface (MachineMesh.wrap); empty: a square on the hit plane
        List<float[]> pieces = java.util.Collections.emptyList();
        // on plain cubes: its pieces wrapped over their edges, in the world (BlockDecal.wrap); null = a flat square
        List<double[]> blockPieces;
        // ... and which cubes around were there when they were made (BlockDecal.around): built again when that changes
        long[] around;

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

        // on what a renderer draws (NTM machines and doors): these count for holeMaxMachines
        boolean machine() {
            return mesh != null;
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

    // a hole as a scenario sees it (HolesScenario): its block, hit face, where it is and its pieces over cube edges
    static final class HoleView {
        final int x, y, z, face;
        final double[] pos;
        final List<double[]> pieces;
        // a flat hole's quads as drawn (world coordinates, seen from 3 blocks off its face), for FencesScenario
        final List<double[][]> quads = new ArrayList<double[][]>();

        HoleView(Hole h) {
            x = h.x;
            y = h.y;
            z = h.z;
            face = h.face;
            pos = h.pos.clone();
            pieces = h.blockPieces;
            if (h.mesh != null || h.blockPieces != null) return;
            int[] o = HoleMath.offset(h.face);
            double[] cam = {h.pos[0] + o[0] * 3, h.pos[1] + o[1] * 3, h.pos[2] + o[2] * 3};
            for (double[] clip : HoleMath.cells(h.rect, h.mask)) {
                float[][] q = HoleMath.quad(h.pos, h.face, h.plane, h.cull, h.turn, h.size * 0.5F, clip, cam);
                if (q == null) continue;
                double[][] w = new double[q.length][];
                for (int i = 0; i < q.length; i++) w[i] = new double[] {q[i][0] + cam[0], q[i][1] + cam[1], q[i][2] + cam[2]};
                quads.add(w);
            }
        }
    }

    static List<HoleView> holesNow() {
        List<HoleView> out = new ArrayList<HoleView>();
        for (Hole h : HOLES) out.add(new HoleView(h));
        return out;
    }

    static int holeCount() {
        return HOLES.size();
    }

    private static void makePending() {
        long from = GunClock.nanos();
        int made = 0;
        World now = Minecraft.getMinecraft().world;
        while (!PENDING.isEmpty() && (made == 0 || GunClock.nanos() - from < HOLE_BUDGET_NS)) {
            Pending p = PENDING.pollFirst();
            if (p.world != now || !Config.bulletHoles) continue;
            Hole h = makeHole(p);
            if (TestLog.client) TestLog.c(p.shot, "hole", "block", name(p.world, p.pos), "pos", v(p.pos), "at", v(h != null ? h.pos : p.at),
                    "placed", h != null, "scratch", p.scratch != null, "holes", HOLES.size(), "max", Config.holeMax,
                    "wrapped", h == null || h.blockPieces == null ? 0 : h.blockPieces.size(), "pieces", h == null ? "-" : pieces(h.blockPieces));
            if (h != null && Trace.ON) TestLog.c(p.shot, "hole_made", "pos", v(p.pos), "at", v(h.pos), "face", h.face, "kind", h.kind,
                    "turn", h.turn, "size", h.size, "plane", h.plane, "rect", h.rect);
            made++;
        }
    }

    // a hole on what a renderer draws (MachineMesh: NTM's machines and doors), on the drawn triangle the bullet met;
    // none where nothing is drawn. Wrapped over the part's edges and cut to its surface (MachineMesh.wrap, 26.2's
    // DecalSurface); square on the hit plane when that finds nothing.
    private static Hole meshHole(Pending p, Block b, int meta) {
        if (p.dir == null) return null;
        MachineMesh.Hit hit = MachineMesh.hit(p.world, p.pos, p.at, p.dir);
        if (hit == null) return null;
        World world = p.world;
        int[] pos = p.pos;
        double[] n = hit.normal;
        int light = world.getCombinedLight(Mc.pos((int) Math.floor(hit.point[0] + n[0] * 0.3), (int) Math.floor(hit.point[1] + n[1] * 0.3), (int) Math.floor(hit.point[2] + n[2] * 0.3)), 0);
        boolean scorch = p.config.onImpact != null;
        // a machine's window (MachineGlass): a hole as on glass
        Decals.Set set = hit.glass ? rules.decal(world, net.minecraft.init.Blocks.GLASS, 0, pos[0], pos[1], pos[2]) : rules.decal(world, b, meta, pos[0], pos[1], pos[2]);
        int kind = scorch ? Decals.scorch(set.scorch()) : Decals.hole(set.hole());
        Random r = Rng.shot(p.shot);
        float size = (scorch ? Config.scorchSize : Config.holeSize) * ExplosiveAmmo.holeScale(p.config) * (0.8F + 0.4F * (float) r.nextDouble());
        int turn = HoleMath.withVariant((int) (r.nextDouble() * 8), r.nextDouble());
        int face = dominant(n);
        Hole h = new Hole(hit.point, face, turn, GunClock.nanos(), light, kind, size, null, null, Double.NaN, false, pos.clone(), b, meta, null, p.shot);
        h.mesh = hit.anchor;
        h.pieces = MachineMesh.wrap(hit, size * 0.5);
        HOLES.addLast(h);
        trimHoles();
        return h;
    }

    // the block face whose normal is nearest to n
    private static int dominant(double[] n) {
        int axis = Math.abs(n[0]) >= Math.abs(n[1]) && Math.abs(n[0]) >= Math.abs(n[2]) ? 0 : Math.abs(n[1]) >= Math.abs(n[2]) ? 1 : 2;
        return HoleMath.face(axis, n[axis] >= 0 ? 1 : -1);
    }

    // a flat hole on the hit face, or none: on a block drawn by its renderer (no block model: NTM's machines), on a
    // see-through pixel, or when none of the block's pixels lie around the hit
    private static Hole makeHole(Pending p) {
        World world = p.world;
        int[] pos = p.pos;
        Block b = Mc.block(world, pos[0], pos[1], pos[2]);
        int meta = Mc.meta(world, pos[0], pos[1], pos[2]);
        if (Mc.material(b) == Material.AIR) return null;
        if (Mc.renderType(b) < 0) return acup.gunfeel.compat.Feature.MACHINE_MESH.on() ? meshHole(p, b, meta) : null;
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
        int light = world.getCombinedLight(Mc.pos(pos[0] + off[0], pos[1] + off[1], pos[2] + off[2]), 0);
        boolean scorch = p.config.onImpact != null;
        Decals.Set set = rules.decal(world, b, meta, pos[0], pos[1], pos[2]);
        int kind = scorch ? Decals.scorch(set.scorch()) : Decals.hole(set.hole());
        Random r = Rng.shot(p.shot);
        float size = (scorch ? Config.scorchSize : Config.holeSize) * ExplosiveAmmo.holeScale(p.config) * (0.8F + 0.4F * (float) r.nextDouble());
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
            // a fence, wall, pane or bars (a shape of its own for bullets): only where that shape's surface is
            if (PassableClip.cells(world, b, meta, pos[0], pos[1], pos[2]) != null) {
                mask = HoleMath.onShape(mask, hit, face, pos, size * 0.5F, rect, new WorldShape(world));
                if (mask == HoleMath.NO_PIXELS) return null;
            }
            plane = mask.plane;
        }
        // one-sided (seen only from outside, like the face itself) when the pixels lie on the hitbox's face; two-sided
        // on glass and where the pixels lie inside the hitbox
        double onAxis = Double.isNaN(plane) ? hit[normal] : plane;
        boolean glass = rules.glass(world, b, meta, pos[0], pos[1], pos[2]);
        boolean cull = (boxes.fullCube(pos[0], pos[1], pos[2]) || Math.abs(onAxis - boxFace) < 1e-3) && !glass;
        Hole h = new Hole(hit, face, turn, GunClock.nanos(), light, kind, size, rect, mask, plane, cull, pos.clone(), b, meta,
                boxes.box(pos[0], pos[1], pos[2]), p.shot);
        // on an opaque cube, over its edges onto the faces around (26.2's drawnHole); a square that stays on the face is
        // drawn flat as before
        if (HoleMath.wholeFace(mask) && cull && opaqueCube(world, pos[0], pos[1], pos[2]))
            wrapOnCubes(world, h);
        HOLES.addLast(h);
        trimHoles();
        return h;
    }

    // the shape bullets meet in the world (HoleMath.Shape): a block's 1/16 cells where it has them, its collision
    // boxes elsewhere; each block's cells read once
    static final class WorldShape implements HoleMath.Shape {
        private final World world;
        private final java.util.Map<Long, long[]> cells = new java.util.HashMap<Long, long[]>();
        private static final long[] NONE = new long[0];

        WorldShape(World world) {
            this.world = world;
        }

        @Override
        public boolean filled(double px, double py, double pz) {
            int bx = (int) Math.floor(px), by = (int) Math.floor(py), bz = (int) Math.floor(pz);
            Block b = Mc.block(world, bx, by, bz);
            if (Mc.material(b) == Material.AIR) return false;
            long key = ((long) bx & 0x3FFFFFF) << 38 | ((long) by & 0xFFF) << 26 | ((long) bz & 0x3FFFFFF);
            long[] bits = cells.get(key);
            if (bits == null) {
                bits = PassableClip.cells(world, b, Mc.meta(world, bx, by, bz), bx, by, bz);
                if (bits == null) bits = NONE;
                cells.put(key, bits);
            }
            if (bits != NONE) return FenceCells.cell(bits, cell(px - bx), cell(py - by), cell(pz - bz));
            List<AxisAlignedBB> list = new ArrayList<AxisAlignedBB>(2);
            Mc.collision(world, bx, by, bz, new AxisAlignedBB(px - 1e-4, py - 1e-4, pz - 1e-4, px + 1e-4, py + 1e-4, pz + 1e-4), list, null);
            return !list.isEmpty();
        }

        private static int cell(double local) {
            return Math.max(0, Math.min(15, (int) Math.floor(local * 16)));
        }
    }

    private static void wrapOnCubes(World world, Hole h) {
        BlockDecal.Cubes cubes = (x, y, z) -> opaqueCube(world, x, y, z);
        int[] at = {h.x, h.y, h.z};
        h.around = BlockDecal.around(at, h.size * 0.5, cubes);
        h.blockPieces = BlockDecal.wrap(at, h.face, h.pos, h.size * 0.5, cubes);
    }

    private static boolean opaqueCube(World world, int x, int y, int z) {
        Block b = Mc.block(world, x, y, z);
        return Mc.opaque(b) && Mc.fullCube(b);
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
                Block b = Mc.block(world, h.x, h.y, h.z);
                if (h.mesh != null) {
                    // a machine's or door's hole goes with its tile entity; its part moving takes it along
                    if (b != h.block || MachineMesh.gone(h.mesh)) it.remove();
                    continue;
                }
                if (b != h.block) it.remove();
                else if (Mc.meta(world, h.x, h.y, h.z) != h.meta && !java.util.Arrays.equals(boxes.box(h.x, h.y, h.z), h.box)) it.remove();
                else if (h.around != null && !java.util.Arrays.equals(h.around, BlockDecal.around(new int[] {h.x, h.y, h.z}, h.size * 0.5,
                        (x, y, z) -> opaqueCube(world, x, y, z)))) wrapOnCubes(world, h); // a cube placed or broken next to it
            }
        }
        if (HOLES.isEmpty() || !Config.bulletHoles) return;
        drawHoles(mc, partial, now, life);
    }

    // ---- drawing (26.2: SubmitCustomGeometryEvent and HoleRender; here immediate GL after the world, as NTM draws)

    // a thin ribbon from tail to head, bright at the head, fading to nothing at the tail; both windings
    private static void drawStreaks(Minecraft mc, float partial, List<float[]> streaks) {
        mc.entityRenderer.disableLightmap();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glEnable(GL11.GL_BLEND);
        OpenGlHelper.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GL11.glDepthMask(false);
        GL11.glShadeModel(GL11.GL_SMOOTH);
        GL11.glColor4f(1F, 1F, 1F, 1F);
        Tess t = Tess.instance;
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
        List<Hole> meshHoles = new ArrayList<Hole>();
        List<double[][]> meshPlaces = new ArrayList<double[][]>();
        placeMeshHoles(now, life, meshHoles, meshPlaces);
        mc.entityRenderer.enableLightmap();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_BLEND);
        // the alpha as vanilla's translucent: what is under a hole keeps its own (a shader pack keeps its data there)
        OpenGlHelper.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glAlphaFunc(GL11.GL_GREATER, 0.003F);
        GL11.glDepthMask(false);
        GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
        GL11.glPolygonOffset(-1F, -10F);
        GL11.glColor4f(1F, 1F, 1F, 1F);
        Tess t = Tess.instance;
        drawMeshHoles(mc, t, now, life, meshHoles, meshPlaces);
        for (int group = 0; group < 2 * KINDS; group++) {
            int kind = group % KINDS;
            boolean cull = group >= KINDS;
            boolean started = false;
            for (Hole h : HOLES) {
                if (h.kind != kind || h.cull != cull || h.mesh != null || h.blockPieces != null) continue;
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
        mc.entityRenderer.disableLightmap();
    }

    // where the holes on machines and doors (MachineMesh) are now, read before the holes' GL state is set up (reading
    // runs the machines' renderers)
    private static void placeMeshHoles(long now, long life, List<Hole> holes, List<double[][]> places) {
        for (Hole h : HOLES) {
            if (HoleMath.alpha(now - h.born, life) <= 0) continue;
            if (h.mesh == null) {
                // a hole wrapped over plain cubes' edges: its pieces stay where they were made
                if (h.blockPieces != null && !h.blockPieces.isEmpty()) {
                    holes.add(h);
                    places.add(h.blockPieces.toArray(new double[0][]));
                }
                continue;
            }
            if (ShootScenario.world()) MachineMesh.lag(h.mesh);
            double[][] at;
            if (!h.pieces.isEmpty()) {
                List<double[]> pieces = MachineMesh.placePieces(h.mesh, h.pieces);
                at = pieces == null ? null : pieces.toArray(new double[0][]);
            } else {
                double[][] sq = MachineMesh.place(h.mesh);
                at = sq == null ? null : new double[][] {square(sq, h.size * 0.5)};
            }
            if (at == null) continue;
            holes.add(h);
            places.add(at);
        }
    }

    // a hole drawn square on its plane (place: point, normal, u, v) as one piece: {normal, corners {x, y, z, a, b}}
    private static double[] square(double[][] at, double half) {
        double[] p = at[0], n = at[1], u = at[2], v = at[3];
        double[] out = new double[3 + 4 * 5];
        out[0] = n[0];
        out[1] = n[1];
        out[2] = n[2];
        float[][] corners = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        for (int c = 0; c < 4; c++) {
            double a = (corners[c][0] * 2 - 1) * half, b = (corners[c][1] * 2 - 1) * half;
            for (int k = 0; k < 3; k++) out[3 + c * 5 + k] = p[k] + u[k] * a + v[k] * b;
            out[3 + c * 5 + 3] = corners[c][0];
            out[3 + c * 5 + 4] = corners[c][1];
        }
        return out;
    }

    // each where its part is now (or, wrapped over plain cubes, where it was made), pulled towards the camera, seen from
    // both sides
    // places: per hole its pieces, each {normal, corners {x, y, z, a, b}}
    private static final double TOWARD = 0.999;

    private static void drawMeshHoles(Minecraft mc, Tess t, long now, long life, List<Hole> holes, List<double[][]> places) {
        if (holes.isEmpty()) return;
        GL11.glDisable(GL11.GL_CULL_FACE);
        for (int kind = 0; kind < KINDS; kind++) {
            boolean started = false;
            for (int i = 0; i < holes.size(); i++) {
                Hole h = holes.get(i);
                if (h.kind != kind) continue;
                if (!started) {
                    started = true;
                    mc.getTextureManager().bindTexture(TEXTURES[kind]);
                    t.startDrawingQuads();
                }
                t.setBrightness(h.light);
                t.setColorRGBA_I(0xFFFFFF, HoleMath.alpha(now - h.born, life));
                for (double[] pc : places.get(i)) {
                    int corners = (pc.length - 3) / 5;
                    if (corners < 3) continue;
                    double nx = pc[0], ny = pc[1], nz = pc[2];
                    // 26.2 (b7b6933): on the surface itself, only pulled towards the camera along each corner's own line of
                    // sight (a tenth of a percent of the distance), so it stays on the same screen pixels and the pieces on
                    // two faces of an edge still meet. Lifted along each piece's normal instead, the pieces stood off their
                    // faces and an edge showed a strip of the surface between them.
                    double side = (CAMERA[0] - pc[3]) * nx + (CAMERA[1] - pc[4]) * ny + (CAMERA[2] - pc[5]) * nz >= 0 ? 1 : -1;
                    t.setNormal((float) (nx * side), (float) (ny * side), (float) (nz * side));
                    // a fan of quads (the last corner twice: a triangle)
                    for (int k = 1; k + 1 < corners; k += 2) {
                        int[] fan = {0, k, k + 1, Math.min(k + 2, corners - 1)};
                        for (int c : fan) {
                            int o = 3 + c * 5;
                            float[] uv = HoleMath.holeUv(h.turn, (float) pc[o + 3], (float) pc[o + 4]);
                            t.addVertexWithUV((pc[o] - CAMERA[0]) * TOWARD, (pc[o + 1] - CAMERA[1]) * TOWARD, (pc[o + 2] - CAMERA[2]) * TOWARD,
                                    uv[0], 1F - uv[1]);
                        }
                    }
                }
            }
            if (started) t.draw();
        }
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
            Block b = Mc.block(world, x, y, z);
            if (Mc.material(b) == Material.AIR) return null;
            AxisAlignedBB bb;
            try {
                bb = Mc.collisionBox(world, x, y, z);
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
            Block b = Mc.block(world, x, y, z);
            if (Mc.material(b) == Material.AIR) return false;
            return Mc.renderType(b) != 0 || rules.hitboxOnly(world, b, Mc.meta(world, x, y, z), x, y, z);
        }

        @Override
        public double shows(int x, int y, int z, int face, double lu, double lv) {
            Block b = Mc.block(world, x, y, z);
            if (Mc.material(b) == Material.AIR || Mc.renderType(b) != 0) return Double.NaN;
            Mc.bounds(b, world, x, y, z);
            double[] bounds = {Mc.bminX(), Mc.bminY(), Mc.bminZ(), Mc.bmaxX(),
                    Mc.bmaxY(), Mc.bmaxZ()};
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
                if (!Mc.sideShown(world, x, y, z, f)) continue;
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
                TextureAtlasSprite icon = McClient.icon(world, x, y, z, face);
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
        Block b = Mc.block(world, pos[0], pos[1], pos[2]);
        return Mc.name(b) + "[" + Mc.meta(world, pos[0], pos[1], pos[2]) + "]";
    }

    // a hole's pieces over cube edges for the test log: per piece its normal and corners, "-" when it is flat
    static String pieces(List<double[]> pieces) {
        if (pieces == null) return "-";
        StringBuilder b = new StringBuilder();
        for (double[] pc : pieces) {
            if (b.length() > 0) b.append(" | ");
            b.append(String.format(java.util.Locale.ROOT, "n(%.0f %.0f %.0f)", pc[0], pc[1], pc[2]));
            for (int o = 3; o + 4 < pc.length; o += 5)
                b.append(String.format(java.util.Locale.ROOT, " %.3f,%.3f,%.3f", pc[o], pc[o + 1], pc[o + 2]));
        }
        return b.toString();
    }

    private static String v(double[] p) {
        return String.format(java.util.Locale.ROOT, "(%.4f %.4f %.4f)", p[0], p[1], p[2]);
    }

    private static String v(int[] p) {
        return p[0] + "," + p[1] + "," + p[2];
    }
}
