package acup.gunfeel.shock.client;

import java.util.Random;

import acup.gunfeel.Config;
import acup.gunfeel.GunFeel;
import acup.gunfeel.TestLog;
import acup.gunfeel.shock.ShockPayloads;
import acup.gunfeel.shock.ShockServer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Client: what a shock does to the player shocked (the server tells: ShockServer); the shooter has the hit marker of
 * any shot (HitMarkerClient). For the shock's length: the whole HUD glitches and shakes (ShockHud; its strength the
 * server's shockGlitch times the player's own shockGlitch, never under shockGlitchMin of the server's), the view
 * shakes a little (shockShake) and turns about on its own (shockCamera degrees a second at most: two slow waves each
 * way, so it wanders off and back, and the player's aim with it). All of it at full for shockSeconds, then wearing off
 * smoothly over the server's fade (ShockServer.envelope, the same as the slowing's: the view narrows with that by the
 * game's own rule for slowness). As strong as the glitch: discharges and a light vignette at the screen's edges
 * (ShockArcs).
 */
public final class ShockClient {
    private ShockClient() {
    }

    private static final Random RANDOM = new Random();

    // the shock now: its start and its time at full in game ticks (the client's own count), the waves of the view's turn
    private static long tick;
    private static long start = Long.MIN_VALUE;
    private static int length;
    private static final float[] WAVES = new float[8];
    private static long lastFrame;
    // the stand: shocks seen, the view's turn so far (degrees)
    public static int shocks;
    public static float turned;

    public static void init(IEventBus modBus) {
        ShockHud.init(modBus);
        // the discharges and the light vignette at the screen's edges, over the camera's own overlays
        modBus.addListener(net.neoforged.neoforge.client.event.RegisterGuiLayersEvent.class,
                e -> e.registerAbove(net.neoforged.neoforge.client.gui.VanillaGuiLayers.CAMERA_OVERLAYS,
                        Identifier.fromNamespaceAndPath("ntmgunfeel", "shock_arcs"), ShockHud::arcs));
        var bus = NeoForge.EVENT_BUS;
        bus.addListener(net.neoforged.neoforge.client.event.ClientTickEvent.Post.class, e -> {
            if (!Minecraft.getInstance().isPaused()) tick++;
        });
        bus.addListener(net.neoforged.neoforge.client.event.RenderFrameEvent.Pre.class, e -> turn(e.getPartialTick()));
        bus.addListener(net.neoforged.neoforge.client.event.ViewportEvent.ComputeCameraAngles.class, ShockClient::shake);
        bus.addListener(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut.class, e -> {
            clear();
            ShockHud.release();
        });
        // a respawn is a new player: the server's shock went with the old one
        bus.addListener(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.Clone.class, e -> clear());
    }

    public static void clear() {
        start = Long.MIN_VALUE;
        length = 0;
    }

    // ---- the packets

    public static void shock(ShockPayloads.Shock p) {
        start(p.ticks());
    }

    /** a shock of {@code ticks} at full (then its fade) starts now (the server's packet, or the stand) */
    public static void start(int ticks) {
        start = tick;
        length = Math.max(1, ticks);
        // each way two waves: a slow one (0.35 to 0.6 a second) and a quicker one (0.9 to 1.5), random phases
        for (int i = 0; i < 2; i++) {
            WAVES[i * 4] = 0.35F + RANDOM.nextFloat() * 0.25F;
            WAVES[i * 4 + 1] = RANDOM.nextFloat() * Mth.TWO_PI;
            WAVES[i * 4 + 2] = 0.9F + RANDOM.nextFloat() * 0.6F;
            WAVES[i * 4 + 3] = RANDOM.nextFloat() * Mth.TWO_PI;
        }
        lastFrame = System.nanoTime();
        shocks++;
        turned = 0F;
        if (TestLog.client) GunFeel.LOGGER.info("[shock] start ticks={}", ticks);
    }

    /** the client's game ticks (ShockArcs' pictures) */
    static long ticks() {
        return tick;
    }

    /** the tick the shock started at (a new shock, new discharges) */
    static long started() {
        return start;
    }

    // ---- how strong it is now

    /** seconds into the shock (partial ticks counted), or -1 when there is none (or it has faded out) */
    private static float age(float partial) {
        var player = Minecraft.getInstance().player;
        if (start == Long.MIN_VALUE || player == null || !player.isAlive()) return -1F;
        float t = tick - start + partial;
        return t >= length + ShockServer.FADE_TICKS ? -1F : t / 20F;
    }

    /** the shock's share now, 0 to 1: full, then wearing off (ShockServer.envelope) */
    public static float envelope(float partial) {
        float t = age(partial);
        return t < 0F ? 0F : ShockServer.envelope(t * 20F, length);
    }

    private static float partial() {
        return Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
    }

    private static boolean on() {
        return Config.enabled && acup.gunfeel.compat.Feature.SHOCK.on() && ShockServer.on();
    }

    /** the HUD's glitch now, 0 to 1 (ShockHud) */
    public static float glitch() {
        if (!on()) return 0F;
        float e = envelope(partial());
        if (e <= 0F) return 0F;
        float own = Math.max(Config.shockGlitch, ShockServer.glitchMin());
        return e * ShockServer.glitch() * own;
    }

    // ---- the view

    // the view turned about on its own, a bit each frame (the player's own turn, so the aim and the shots go with it)
    private static void turn(DeltaTracker delta) {
        long now = System.nanoTime();
        float dt = Math.min(0.1F, (now - lastFrame) / 1e9F);
        lastFrame = now;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.isPaused() || !on()) return;
        float partial = delta.getGameTimeDeltaPartialTick(false);
        float t = age(partial);
        if (t < 0F) return;
        float rate = ShockServer.cameraDeg() * envelope(partial);
        if (rate <= 0F) return;
        float yaw = rate * wave(0, t) * dt, pitch = rate * 0.45F * wave(1, t) * dt;
        p.setYRot(p.getYRot() + yaw);
        p.yRotO += yaw;
        float x = Math.clamp(p.getXRot() + pitch, -90F, 90F);
        p.xRotO += x - p.getXRot();
        p.setXRot(x);
        turned += Math.abs(yaw) + Math.abs(pitch);
    }

    // -1 to 1: the slow wave and the quick one
    private static float wave(int axis, float t) {
        int i = axis * 4;
        return 0.7F * Mth.sin(Mth.TWO_PI * WAVES[i] * t + WAVES[i + 1]) + 0.3F * Mth.sin(Mth.TWO_PI * WAVES[i + 2] * t + WAVES[i + 3]);
    }

    // the view's small shake: a jitter of the camera alone (not the aim), new about 25 times a second
    private static void shake(net.neoforged.neoforge.client.event.ViewportEvent.ComputeCameraAngles e) {
        if (!on()) return;
        float env = envelope((float) e.getPartialTick());
        float a = ShockServer.shakeDeg() * env;
        if (a <= 0F) return;
        double t = (tick + e.getPartialTick()) / 20.0;
        float y = (float) (Math.sin(t * 83.0) * 0.6 + Math.sin(t * 151.0 + 1.3) * 0.4) * a;
        float x = (float) (Math.sin(t * 97.0 + 2.1) * 0.6 + Math.sin(t * 173.0 + 0.4) * 0.4) * a;
        float r = (float) Math.sin(t * 61.0 + 0.7) * a * 0.8F;
        e.setYaw(e.getYaw() + y);
        e.setPitch(e.getPitch() + x);
        e.setRoll(e.getRoll() + r);
    }
}
