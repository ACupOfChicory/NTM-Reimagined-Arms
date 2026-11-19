package acup.gunfeel.ai;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.function.DoubleSupplier;

import org.jspecify.annotations.Nullable;

/**
 * A shot's kick on an AI mob's view, from NTM's own recoil numbers for the gun. NTM works them out on the shooting
 * player's client (each gun's recoil lambda, into ItemGunBaseNT's static fields shared with the camera), so they are
 * read here from a table the AI check samples from those lambdas ({@value #TABLE}; AiCheckRecoil checks it is still
 * NTM's). Degrees as a player's camera gets them (CameraRecoil: NTM's number times cameraScale, 0.55 or the gun's own),
 * times the server's aiRecoil (1 by default, 0 for none). As on a player's camera, part of it stays (the gun's preset
 * in the jar: cameraClimb of the kick up and cameraClimbDeg more, cameraDriftDeg aside, grown over cameraClimbRamp shots
 * of a burst); the rest comes back by the gun's own share a tick (NTM's recoilRebound). What is left the mob's hand
 * takes back once it notices (AiAim).
 */
public final class AiRecoil {
    public static final String KEY = "aiRecoil";
    static final String TABLE = "/ntmgunfeel-ai-recoil.tsv";
    /** a player's camera kick, degrees per unit of NTM's recoil, for a gun without its own */
    static final float CAMERA_SCALE = 0.55F;
    /** a player's recoil while aiming, for a gun without its own (adsRecoil), and lying down (proneRecoil) */
    static final float ADS = 0.7F, PRONE = 0.5F;

    /**
     * One gun's recoil per shot, NTM's units: up and aside, their means and spreads, how much of the kick NTM lets
     * linger a tick (decay) and how much of the view comes back a tick (rebound).
     */
    public record Kick(float up, float upSpread, float side, float sideSpread, float decay, float rebound) {
        String tsv(String gun) {
            return String.format(Locale.ROOT, "%s\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f", gun, up, upSpread, side, sideSpread, decay, rebound);
        }
    }

    /** a gun's camera preset, as a player's camera has it: kick scale, the share that stays, degrees that stay up and
     * aside a shot, shots of a burst over which they grow, and the share of it all while aiming */
    record Camera(float scale, float climb, float climbDeg, float driftDeg, float ramp, float ads) {
        static final Camera DEFAULT = new Camera(CAMERA_SCALE, 0F, 0F, 0F, 0F, ADS);
    }

    /** for the stands: the strength whatever the key says; NaN = the key */
    static float override = Float.NaN;
    private static @Nullable Map<String, Kick> table;
    private static @Nullable Map<String, Camera> cameras;

    private AiRecoil() {
    }

    public static float strength() {
        if (!Float.isNaN(override)) return override;
        float v = acup.gunfeel.ReaimTimes.number(KEY, 1F);
        return Float.isFinite(v) ? Math.max(0F, v) : 1F;
    }

    /** the gun's recoil, or null for one NTM gives none */
    public static @Nullable Kick of(String gun) {
        return table().get(gun);
    }

    static synchronized Map<String, Kick> table() {
        if (table == null) table = read(AiRecoil.class.getResourceAsStream(TABLE));
        return table;
    }

    /** a table's lines: gun, up, up spread, side, side spread, decay, rebound; # starts a comment */
    static Map<String, Kick> read(@Nullable InputStream in) {
        Map<String, Kick> out = new HashMap<>();
        if (in == null) return out;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null;) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] f = line.split("\t");
                if (f.length < 7) continue;
                out.put(f[0], new Kick(Float.parseFloat(f[1]), Float.parseFloat(f[2]), Float.parseFloat(f[3]), Float.parseFloat(f[4]),
                        Float.parseFloat(f[5]), Float.parseFloat(f[6])));
            }
        } catch (IOException | RuntimeException e) {
            return out;
        }
        return out;
    }

    /** the gun's camera preset from the jar's gun presets (as a player's camera has it), a player's default without */
    static synchronized Camera camera(String gun) {
        if (cameras == null) cameras = cameras(AiRecoil.class.getResourceAsStream("/ntmgunfeel-guns.properties"));
        return cameras.getOrDefault(gun, Camera.DEFAULT);
    }

    static Map<String, Camera> cameras(@Nullable InputStream in) {
        Properties p = new Properties();
        try (in) {
            if (in != null) p.load(in);
        } catch (IOException | IllegalArgumentException e) {
            // no presets: every gun the default
        }
        Map<String, Camera> out = new HashMap<>();
        for (String k : p.stringPropertyNames()) {
            int dot = k.lastIndexOf('.');
            String what = k.substring(dot + 1);
            if (dot < 0 || !what.startsWith("camera") && !what.equals("adsRecoil")) continue;
            String gun = k.substring(0, dot);
            if (out.containsKey(gun)) continue;
            out.put(gun, new Camera(num(p, gun + ".cameraScale", CAMERA_SCALE), num(p, gun + ".cameraClimb", 0F), num(p, gun + ".cameraClimbDeg", 0F),
                    num(p, gun + ".cameraDriftDeg", 0F), num(p, gun + ".cameraClimbRamp", 0F), Math.clamp(num(p, gun + ".adsRecoil", ADS), 0F, 1F)));
        }
        return out;
    }

    private static float num(Properties p, String key, float def) {
        String v = p.getProperty(key);
        if (v == null) return def;
        try {
            float f = Float.parseFloat(v.trim());
            return Float.isFinite(f) ? f : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /**
     * One shot's kick in degrees, at random, for the shot-th shot of a burst (from 1), as a player's camera has it
     * aiming or not and lying or not (with the server's cameraScaleMin): {up and aside that come back, up and aside that
     * stay} (NTM's signs: up lifts the view, aside turns it left); drift: where the sideways wander is ({@link #drift}).
     */
    static float[] degrees(Kick k, String gun, float strength, int shot, float drift, boolean aiming, boolean lying, DoubleSupplier gauss) {
        Camera c = camera(gun);
        float ads = aiming ? c.ads() : 1F, prone = lying ? PRONE : 1F;
        float min = acup.gunfeel.ReaimTimes.number("cameraScaleMin", 0F);
        float s = Math.max(c.scale() * ads, min) * prone * strength;
        float grow = c.ramp() <= 1F ? 1F : Math.min(1F, shot / c.ramp()), more = ads * prone * grow * strength;
        float up = (k.up + k.upSpread * (float) gauss.getAsDouble()) * s, side = (k.side + k.sideSpread * (float) gauss.getAsDouble()) * s;
        float stays = c.climb() * grow;
        return new float[] {up * (1F - stays), side, up * stays + c.climbDeg() * more, c.driftDeg() * more * drift};
    }

    /** the side a burst's wander goes: a few shots one way, then it may turn (as a player's camera); g a normal number */
    static float drift(float prev, boolean first, float g) {
        return Math.clamp(first ? g : 0.8F * prev + 0.6F * g, -2.5F, 2.5F);
    }
}
