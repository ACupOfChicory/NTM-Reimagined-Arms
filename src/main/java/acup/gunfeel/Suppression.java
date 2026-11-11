package acup.gunfeel;

import org.jspecify.annotations.Nullable;

import com.hbm.items.weapon.sedna.BulletConfig;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

// Suppression under fire, on the player's client (SuppressionScale has the numbers). What raises it: someone else's
// bullet passing close (the same pass the whizz is played on, Tracers → BulletFx.pass), someone else's bullet stopping
// near (in a block, a creature, water: where the tracer ends, once a bullet), a blast near (NTM's and vanilla's, told
// by the server: SuppressionServer). Not the player's own bullets and blasts, not lasers and beams, not a grenade in
// flight (only its blast). Each is counted once: the bullets only here, the blasts only by the server's packet.
// What it gives: the screen darkens, from the edges more (at most 40 % at the edges, 22 % in the middle, at a full
// scale), the other sounds go down (the world's to 40 %, the fight's own to 79 %), and, when the server turns it on,
// a jolt of the view at each event. The server sets the forces, ranges and limits; the player may take less darkening
// and muffling (suppressionDarken, suppressionMuffle).
public final class Suppression {
    private Suppression() {
    }

    private static final SuppressionScale SCALE = new SuppressionScale();
    private static long tick;
    // the muffling the playing sounds were last set to
    private static float muffled = 1F;
    // the last blast counted: the same one told twice (vanilla's within NTM's) counts once
    private static @Nullable Vec3 lastBlast;
    private static long lastBlastTick = Long.MIN_VALUE / 2;
    // /gunfeel suppression debug: each event and the scale each tick to the log ("[suppression]")
    public static volatile boolean debug;

    public static void init(net.neoforged.bus.api.IEventBus modBus) {
        modBus.addListener(net.neoforged.neoforge.client.event.RegisterGuiLayersEvent.class,
                e -> e.registerAbove(net.neoforged.neoforge.client.gui.VanillaGuiLayers.CAMERA_OVERLAYS,
                        Identifier.fromNamespaceAndPath("ntmgunfeel", "suppression"), Suppression::layer));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.ClientTickEvent.Post.class, e -> tick());
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut.class, e -> clear());
    }

    public static float level() {
        return SCALE.level();
    }

    public static float target() {
        return SCALE.target();
    }

    public static void clear() {
        SCALE.clear();
        lastBlast = null;
    }

    private static boolean on() {
        return acup.gunfeel.compat.Feature.SUPPRESSION.on() && SuppressionServer.on() && Minecraft.getInstance().player != null;
    }

    // not a bullet that counts: the player's own, a laser's
    private static boolean skip(int owner, @Nullable BulletConfig config) {
        LocalPlayer p = Minecraft.getInstance().player;
        return p == null || owner == p.getId() || BeamLasers.laser(config) || p.isSpectator();
    }

    // someone's bullet passed `d` blocks from the eyes (BulletFx.pass, from Tracers); true when it counted, so once
    public static boolean pass(int owner, @Nullable BulletConfig config, double d, double speed) {
        if (!on() || skip(owner, config)) return false;
        float f = SuppressionScale.pass(d, speed, SuppressionServer.passRange()) * SuppressionServer.passShare();
        if (f <= 0F) return false;
        event("pass", f, true, d);
        return true;
    }

    // someone's bullet stopped at `at` (Tracers: the end of its path, not where it bounced on); an explosive one counts
    // by its blast instead
    public static void impact(int owner, @Nullable BulletConfig config, Vec3 at, double speed) {
        if (!on() || skip(owner, config)) return;
        if (config != null && (config.onImpact != null || ExplosiveAmmo.heavy(config))) return;
        double d = Minecraft.getInstance().player.getEyePosition().distanceTo(at);
        float f = SuppressionScale.impact(d, speed, SuppressionServer.impactRange()) * SuppressionServer.impactShare();
        if (f > 0F) event("impact", f, true, d);
    }

    // the server: a blast went off near
    public static void blast(SuppressionBlastPayload p) {
        if (!on() || Minecraft.getInstance().player.isSpectator()) return;
        Vec3 at = new Vec3(p.x(), p.y(), p.z());
        if (lastBlast != null && tick - lastBlastTick <= 2 && lastBlast.distanceToSqr(at) <= 1.0) return;
        lastBlast = at;
        lastBlastTick = tick;
        double d = Minecraft.getInstance().player.getEyePosition().distanceTo(at);
        float f = SuppressionScale.blast(d, p.size(), SuppressionServer.blastRange()) * SuppressionServer.blastShare();
        if (f > 0F) event("blast", f, false, d);
    }

    private static void event(String what, float force, boolean burst, double d) {
        float added = SCALE.add(force, burst, tick);
        if (debug || TestLog.client) GunFeel.LOGGER.info("[suppression] {} d={} force={} added={} target={} level={}", what,
                String.format(java.util.Locale.ROOT, "%.2f", d), String.format(java.util.Locale.ROOT, "%.1f", force),
                String.format(java.util.Locale.ROOT, "%.1f", added), String.format(java.util.Locale.ROOT, "%.1f", SCALE.target()),
                String.format(java.util.Locale.ROOT, "%.1f", SCALE.level()));
        LocalPlayer p = Minecraft.getInstance().player;
        // Squad's flinch: the view turned once a little to a random side, and it stays there
        if (p != null && SuppressionServer.flinch() && !p.isPassenger()) {
            float a = SuppressionScale.flinch(added, SuppressionServer.flinchDeg());
            double t = p.getRandom().nextDouble() * Math.PI * 2.0;
            p.setYRot(p.getYRot() + (float) Math.cos(t) * a);
            p.setXRot(Math.clamp(p.getXRot() + (float) Math.sin(t) * a, -90F, 90F));
            if (debug) GunFeel.LOGGER.info("[suppression] flinch {} deg", a);
        }
    }

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            if (SCALE.level() > 0F || SCALE.target() > 0F) clear();
        } else if (!mc.isPaused()) {
            tick++;
            SCALE.tick(SuppressionScale.MAX / (SuppressionServer.recoverySeconds() * 20F));
            if (!SuppressionServer.on()) SCALE.clear();
        }
        // the sounds already playing follow the muffling as it changes
        float m = SuppressionScale.muffle(SCALE.share(), muffleStrength(), false);
        if (Math.abs(m - muffled) > 0.01F || m == 1F && muffled != 1F) {
            muffled = m;
            mc.getSoundManager().refreshCategoryVolume(SoundSource.MASTER);
        }
        if (debug && SCALE.level() > 0F) GunFeel.LOGGER.info("[suppression] tick level={} target={} muffle={}", SCALE.level(), SCALE.target(), m);
    }

    private static float darkStrength() {
        return SuppressionServer.darken() * Config.suppressionDarken;
    }

    private static float muffleStrength() {
        return SuppressionServer.muffle() * Config.suppressionMuffle;
    }

    // ---- the sounds (MixinSuppressionMuffle)

    // a sound's volume factor now
    public static float volume(SoundInstance s) {
        float r = SCALE.share();
        if (r <= 0F) return 1F;
        SoundSource src = s.getSource();
        if (src == SoundSource.MUSIC || src == SoundSource.RECORDS || src == SoundSource.UI || src == SoundSource.MASTER) return 1F;
        return SuppressionScale.muffle(r, muffleStrength(), fight(s.getIdentifier()));
    }

    // a sound of the fight: a gun's, a blast's, a bullet's (NTM's weapon sounds, the mod's own, vanilla's blast)
    static boolean fight(@Nullable Identifier id) {
        if (id == null) return false;
        String ns = id.getNamespace(), path = id.getPath();
        if (ns.equals("ntmgunfeel")) return true;
        if (ns.equals("hbm")) return path.startsWith("weapon.") || path.startsWith("turret.") || path.contains("explosion");
        return path.contains("explode") || path.contains("explosion");
    }

    // ---- the screen

    private static void layer(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gui.hud.isHidden()) return;
        draw(g, SCALE.share(), darkStrength());
    }

    static void draw(GuiGraphicsExtractor g, float r, float k) {
        if (r <= 0.01F || k <= 0F) return;
        int w = g.guiWidth(), h = g.guiHeight();
        float fill = SuppressionScale.fill(r, k);
        if (fill > 0F) g.fill(0, 0, w, h, argb(fill));
        if (r < 0.1F) return;
        // the edges: rings from the edge in, each darker the closer to the edge
        int n = SuppressionScale.rings(r);
        float band = Math.min(w, h) * SuppressionScale.edgeWidth(r);
        for (int i = 0; i < n; i++) {
            int a = Math.round(band * i / n), b = Math.round(band * (i + 1) / n);
            if (b <= a) continue;
            float alpha = SuppressionScale.ringAlpha(i, n, r, k);
            if (alpha <= 0.002F) continue;
            int c = argb(alpha);
            g.fill(a, a, w - a, b, c); // top
            g.fill(a, h - b, w - a, h - a, c); // bottom
            g.fill(a, b, b, h - b, c); // left
            g.fill(w - b, b, w - a, h - b, c); // right
        }
    }

    private static int argb(float alpha) {
        return Math.clamp(Math.round(alpha * 255F), 0, 255) << 24;
    }
}
