package acup.gunfeel.hitmarker.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.Config;
import acup.gunfeel.GunFeel;
import acup.gunfeel.TestLog;
import acup.gunfeel.hitmarker.HitMarkerLook;
import acup.gunfeel.hitmarker.HitMarkerPayload;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;

// Client: the hit marker round the crosshair when the player's shot hurt someone (HitMarkers, the server's packet) and
// the damage indicator, an arc towards where the player's own damage came from. Both are HitMarkerLook's pixels, in
// game ticks of the client's own (paused: they stand still). Settings: hitMarker and damageIndicator (Config).
//  - The marker: the body's or the head's; a kill has the hit's mark and a bell. A shotgun's pellets come as many
//    packets in one tick: one mark (the head's if any pellet was on the head) and each sound once that tick.
//  - The indicator: the game tells the client of each damage it takes, with its source (handleDamageEvent, which
//    leaves it as the player's last damage source). From the entity that caused it, where it stood then, or else the
//    place the damage came from; damage with neither (a fall, fire, gas, a shot whose shooter the client does not
//    know) draws none, nor one from right where the player stands. The arc keeps to that place as the player turns.
public final class HitMarkerClient {
    private HitMarkerClient() {
    }

    // sounds: the body's tick, the head's ding, the kill's bell (the page's: forUI(sound, pitch, volume))
    private static final Sound BODY = new Sound("block.note_block.hat", 1.8F, 0.3F);
    private static final Sound HEAD = new Sound("entity.arrow.hit_player", 1.5F, 0.25F);
    private static final Sound KILL = new Sound("block.note_block.bell", 1.0F, 0.35F);

    private record Sound(String id, float pitch, float volume) {
        void play() {
            SoundEvent s = BuiltInRegistries.SOUND_EVENT.getValue(Identifier.withDefaultNamespace(id));
            if (s != null) Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(s, pitch, volume));
        }
    }

    private record Hurt(Vec3 from, double at) {
    }

    // the client's game ticks (not counting while paused) and this frame's partial tick
    private static long tick;
    private static HitMarkerLook.@Nullable Look look;
    private static double markAt;
    private static long markTick = Long.MIN_VALUE;
    // what this tick's hits have sounded already
    private static boolean bodySounded, headSounded, killSounded;
    private static final List<Hurt> HURTS = new ArrayList<>();
    private static @Nullable DamageSource lastSource;
    // the stand: marks shown (packets), on the head, kills; indicators, where the last one came from and its direction
    // then (degrees), and the last direction drawn
    public static int marks, heads, kills, indicators;
    public static @Nullable Vec3 lastFrom;
    public static float firstDir = Float.NaN, lastDir = Float.NaN;

    public static void init(IEventBus modBus) {
        modBus.addListener(net.neoforged.neoforge.client.event.RegisterGuiLayersEvent.class,
                e -> e.registerAbove(net.neoforged.neoforge.client.gui.VanillaGuiLayers.CROSSHAIR,
                        Identifier.fromNamespaceAndPath("ntmgunfeel", "hit_marker"), HitMarkerClient::draw));
        var bus = NeoForge.EVENT_BUS;
        bus.addListener(net.neoforged.neoforge.client.event.ClientTickEvent.Post.class, e -> {
            if (Minecraft.getInstance().isPaused()) return;
            tick++;
            damage();
        });
        bus.addListener(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.Clone.class, e -> clear());
        bus.addListener(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut.class, e -> clear());
    }

    public static void clear() {
        look = null;
        HURTS.clear();
        lastSource = null;
    }

    private static double now() {
        return tick + Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
    }

    // ---- the marker

    public static void receive(HitMarkerPayload p) {
        marks++;
        if (p.head()) heads++;
        if (p.kill()) kills++;
        if (TestLog.client) GunFeel.LOGGER.info("[hitmarker] head={} kill={}", p.head(), p.kill());
        if (!Config.hitMarker) return;
        if (markTick != tick) {
            markTick = tick;
            bodySounded = headSounded = killSounded = false;
            look = null;
        }
        // this tick's pellets: one mark, the head's over the body's
        if (look == null || p.head()) {
            look = p.head() ? HitMarkerLook.HEAD : HitMarkerLook.BODY;
            markAt = now();
        }
        if (p.head() ? !headSounded : !bodySounded) (p.head() ? HEAD : BODY).play();
        if (p.head()) headSounded = true; else bodySounded = true;
        if (p.kill() && !killSounded) {
            KILL.play();
            killSounded = true;
        }
    }

    // ---- the indicator

    private static void damage() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return;
        DamageSource s = p.getLastDamageSource();
        if (s == null || s == lastSource) return;
        lastSource = s;
        if (!Config.damageIndicator || p.hurtTime <= 0) return;
        Vec3 from = from(s, p);
        if (from == null) return;
        double dx = from.x - p.getX(), dz = from.z - p.getZ();
        if (dx * dx + dz * dz < 0.25) return;
        HURTS.add(new Hurt(from, now()));
        indicators++;
        lastFrom = from;
        firstDir = (float) HitMarkerLook.bearing(p.getYRot(), dx, dz);
        if (TestLog.client) GunFeel.LOGGER.info("[hitmarker] damage from {} dir={}", from, firstDir);
    }

    // where the damage came from: the entity that caused it, else the place the source names, else a living thing that
    // dealt it itself. Not a bullet's place when its shooter is out of the client's sight: that is where it hit
    private static @Nullable Vec3 from(DamageSource s, LocalPlayer p) {
        if (s.getEntity() != null && s.getEntity() != p) return s.getEntity().position();
        if (s.sourcePositionRaw() != null) return s.sourcePositionRaw();
        return s.getDirectEntity() instanceof net.minecraft.world.entity.LivingEntity e && e != p ? e.position() : null;
    }

    // ---- drawing

    private static void draw(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.gui.hud.isHidden()) return;
        double now = tick + delta.getGameTimeDeltaPartialTick(false);
        int cx = g.guiWidth() / 2, cy = g.guiHeight() / 2;
        if (!HURTS.isEmpty()) {
            // several arcs: each pixel once, at the strongest of them
            Map<Long, Integer> px = new HashMap<>();
            float pt = delta.getGameTimeDeltaPartialTick(false);
            double yaw = p.getViewYRot(pt), x = p.getX(pt), z = p.getZ(pt);
            HURTS.removeIf(h -> {
                double dir = HitMarkerLook.bearing(yaw, h.from.x - x, h.from.z - z);
                lastDir = (float) dir;
                return !HitMarkerLook.indicator(dir, now - h.at, (ix, iy, c) -> px.merge((long) ix << 32 | (iy & 0xFFFFFFFFL), c,
                        (a, b) -> Integer.compareUnsigned(a, b) >= 0 ? a : b));
            });
            px.forEach((k, c) -> {
                int ix = (int) (k >> 32), iy = (int) (long) k;
                g.fill(cx + ix, cy + iy, cx + ix + 1, cy + iy + 1, c);
            });
        }
        HitMarkerLook.Look l = look;
        if (l != null && !HitMarkerLook.marker(l, now - markAt, (ix, iy, c) -> g.fill(cx + ix, cy + iy, cx + ix + 1, cy + iy + 1, c))) look = null;
    }
}
