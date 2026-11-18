package acup.gunfeel.ai.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import acup.gunfeel.Config;
import acup.gunfeel.GunFeel;

/**
 * The AI mobs' moves from recordings of people (tools/motion/retarget_moves.py, moves_to_mod.py): a turn on the spot,
 * a start into a run, a stop from one; 100STYLE (Ian Mason, CC BY 4.0) and the CMU Graphics Lab Motion Capture
 * Database. Each is made in the mob's frame, its feet standing in the world for the turn or the way it was made with:
 * the mod plays it by how far its own mob has turned or gone ({@link Move#timeAt}), not by the clock, and the feet
 * stand however fast the mob goes. Only the legs; read from the jar once.
 */
public final class BotMoves {
    static final String PATH = "/assets/ntmgunfeel/animations/bot_moves.json";
    /** the turns on the spot played, a soldier's (100STYLE Swat_TR1), each to its right; to its left, mirrored */
    static final String TURN_90 = "turn90_right_swat", TURN_180 = "turn180_right_swat";

    /**
     * One move.
     *
     * @param curve   how far it has turned to its left (degrees) or gone ahead (blocks) by then, hz samples a second
     * @param drop    how far its hips go down, px (its legs are made for it)
     * @param stances each foot (right, left): the times it stands, from and to, seconds after each other
     */
    public record Move(String name, String kind, BotClip clip, float[] curve, float hz, float drop, float[][] stances) {
        /** how far it goes all told, the way it turns or goes (a turn to its right: its right) */
        public float total() {
            return Math.abs(curve[curve.length - 1]);
        }

        /**
         * The clip's time where the move has gone {@code along} its way (degrees or blocks, its sign its own way: a
         * turn to its right taken as turned right): the first time its curve comes that far (a step's sway back and
         * forth taken as standing), linear between its samples; its end past its total.
         */
        public float timeAt(float along) {
            float sign = curve[curve.length - 1] < 0F ? -1F : 1F, most = curve[0] * sign;
            if (along <= most) return 0F;
            for (int i = 1; i < curve.length; i++) {
                float v = curve[i] * sign;
                if (v <= most) continue;
                if (v >= along) {
                    // from the sample before (at most as far as the most yet, swayed back or not)
                    float prev = curve[i - 1] * sign, u = (along - prev) / (v - prev);
                    return Math.min(clip.length(), (i - 1 + u) / hz);
                }
                most = v;
            }
            return clip.length();
        }

        /**
         * how far it has turned its way by then (degrees, a turn to its right taken as turned right) or gone ahead
         * (blocks), linear between its samples
         */
        public float turnedAt(float t) {
            float sign = curve[curve.length - 1] < 0F ? -1F : 1F, at = Math.max(0F, t * hz);
            int i = Math.min((int) at, curve.length - 2);
            if (i < 0) return curve[0] * sign;
            return sign * (curve[i] + (curve[i + 1] - curve[i]) * Math.min(1F, at - i));
        }

        /** the first time from t both feet stand (BotMoves stances), or its end */
        public float downFrom(float t) {
            if (bothDown(t)) return t;
            float best = clip.length();
            for (float[] s : stances)
                for (int i = 0; i + 1 < s.length; i += 2)
                    if (s[i] > t && s[i] < best && bothDown(s[i])) best = s[i];
            return best;
        }

        /**
         * where to stop it from t, stood gone {@code along} its way: of the times from t both feet stand (t itself, as
         * far as it has gone, each both come down, its end) the one it has turned (or gone) nearest along, the first of
         * equals
         */
        public float settleAt(float t, float along) {
            float best = -1F, off = Float.MAX_VALUE, there = Math.max(t, timeAt(along));
            float[] at = new float[2 + stances[0].length / 2 + stances[1].length / 2 + 1];
            int n = 0;
            at[n++] = t;
            at[n++] = there;
            for (float[] s : stances)
                for (int i = 0; i + 1 < s.length; i += 2) at[n++] = s[i];
            at[n++] = clip.length();
            java.util.Arrays.sort(at, 0, n);
            for (int i = 0; i < n; i++) {
                float c = at[i];
                if (c < t || !bothDown(c) && c < clip.length()) continue;
                float d = Math.abs(turnedAt(c) - along);
                if (d < off - 1e-4F) {
                    off = d;
                    best = c;
                }
            }
            return best < 0F ? clip.length() : best;
        }

        /** both feet stand at this time (within a stance each) */
        public boolean bothDown(float t) {
            return down(stances[0], t) && down(stances[1], t);
        }

        /**
         * how far through its step the foot (0 right, 1 left) is at this time: 0 lifted .. 1 set down (from the end of its
         * stance before to the start of the next, or the clip's start or end)
         */
        public float swing(int foot, float t) {
            float[] s = stances[foot];
            float lift = 0F, land = clip.length();
            for (int i = 0; i + 1 < s.length; i += 2) {
                if (s[i + 1] <= t) lift = Math.max(lift, s[i + 1]);
                if (s[i] > t) land = Math.min(land, s[i]);
            }
            return land <= lift ? 1F : Math.max(0F, Math.min(1F, (t - lift) / (land - lift)));
        }

        /** the foot (0 right, 1 left) stands at this time */
        public boolean down(int foot, float t) {
            return down(stances[foot], t);
        }

        private static boolean down(float[] s, float t) {
            for (int i = 0; i + 1 < s.length; i += 2)
                if (t >= s[i] && t <= s[i + 1]) return true;
            return false;
        }

        /** the same move the other way: the legs mirrored (BotClip.mirrored), its turn the other way */
        Move mirrored() {
            float[] c = curve.clone();
            if ("turn".equals(kind)) for (int i = 0; i < c.length; i++) c[i] = -c[i];
            return new Move(name + "_mirrored", kind, clip.mirrored(), c, hz, drop, new float[][] { stances[1], stances[0] });
        }
    }

    private static volatile Map<String, Move> moves;

    private BotMoves() {
    }

    /** the moves played at all (Config.botMoves: clips, the default; old: the mod's own steps, to set them side by side) */
    public static boolean on() {
        return !"old".equals(System.getProperty("ntmgunfeel.botMoves", Config.botMoves));
    }

    /** by name; a name ending "_mirrored" the move mirrored */
    public static @Nullable Move get(String name) {
        Map<String, Move> m = moves;
        if (m == null) {
            synchronized (BotMoves.class) {
                if (moves == null) moves = load();
                m = moves;
            }
        }
        return m.get(name);
    }

    /** the turn on the spot to its left (or right), a quarter or half a turn */
    public static @Nullable Move turn(boolean left, boolean half) {
        String n = half ? TURN_180 : TURN_90;
        return get(left ? n + "_mirrored" : n);
    }

    static Map<String, Move> load() {
        Map<String, Move> out = new HashMap<>();
        try (InputStream in = BotMoves.class.getResourceAsStream(PATH)) {
            if (in == null) {
                GunFeel.LOGGER.warn("AI mob moves missing: {}", PATH);
                return out;
            }
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonObject all = JsonParser.parseReader(r).getAsJsonObject().getAsJsonObject("moves");
                for (String name : all.keySet()) {
                    // one move unreadable leaves the others
                    try {
                        Move m = parse(name, all.getAsJsonObject(name));
                        out.put(name, m);
                        out.put(name + "_mirrored", m.mirrored());
                    } catch (RuntimeException e) {
                        GunFeel.LOGGER.warn("AI mob move {} unreadable: {}", name, e.toString());
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            GunFeel.LOGGER.warn("AI mob moves unreadable: {}", e.toString());
        }
        return out;
    }

    static Move parse(String name, JsonObject o) {
        BotClip clip = new BotClip(o.get("length").getAsFloat(), BotClip.Mode.ONCE);
        JsonObject bones = o.getAsJsonObject("bones");
        for (String bone : bones.keySet()) {
            JsonObject b = bones.getAsJsonObject(bone);
            float[] t = floats(b.getAsJsonArray("t")), r = floats(b.getAsJsonArray("r"));
            // a key each time, three numbers each: else it would fail drawn, not read
            if (t.length == 0 || r.length != 3 * t.length) throw new IllegalArgumentException(bone + ": " + t.length + " keys, " + r.length + " values");
            clip.rotation.put(bone, new BotClip.Track(t, r));
        }
        JsonObject st = o.getAsJsonObject("stances");
        float[][] stances = { pairs(st.getAsJsonArray("right")), pairs(st.getAsJsonArray("left")) };
        float[] curve = floats(o.getAsJsonArray("curve"));
        float hz = o.get("hz").getAsFloat();
        if (curve.length < 2 || !(hz > 0F)) throw new IllegalArgumentException("curve of " + curve.length + " at " + hz + " Hz");
        return new Move(name, o.get("kind").getAsString(), clip, curve, hz, o.get("drop").getAsFloat(), stances);
    }

    private static float[] floats(JsonArray a) {
        float[] out = new float[a.size()];
        for (int i = 0; i < out.length; i++) out[i] = a.get(i).getAsFloat();
        return out;
    }

    private static float[] pairs(JsonArray a) {
        float[] out = new float[a.size() * 2];
        for (int i = 0; i < a.size(); i++) {
            JsonArray p = a.get(i).getAsJsonArray();
            out[i * 2] = p.get(0).getAsFloat();
            out[i * 2 + 1] = p.get(1).getAsFloat();
        }
        return out;
    }
}
