package acup.gunfeel.ai.client;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * One Bedrock animation (format 1.8.0, linear keys) as tools/quaternius writes it: per bone a rotation (degrees,
 * ModelPart.xRot/yRot/zRot from the default pose) and a position (pixels from the default pivot, Y up). Read once,
 * sampled every frame without allocating.
 */
public final class BotClip {
    public enum Mode { LOOP, ONCE, HOLD }

    /** one channel: key times (seconds) and their values, x y z after each other */
    record Track(float[] times, float[] values) {
        void sample(float t, float[] out) {
            int n = times.length;
            if (t <= times[0] || n == 1) {
                copy(0, out);
                return;
            }
            if (t >= times[n - 1]) {
                copy(n - 1, out);
                return;
            }
            int lo = 0, hi = n - 1;
            while (hi - lo > 1) {
                int mid = (lo + hi) >>> 1;
                if (times[mid] <= t) lo = mid;
                else hi = mid;
            }
            float u = (t - times[lo]) / (times[hi] - times[lo]);
            for (int i = 0; i < 3; i++) {
                float a = values[lo * 3 + i], b = values[hi * 3 + i];
                out[i] = a + (b - a) * u;
            }
        }

        private void copy(int k, float[] out) {
            out[0] = values[k * 3];
            out[1] = values[k * 3 + 1];
            out[2] = values[k * 3 + 2];
        }
    }

    final float length;
    final Mode mode;
    final Map<String, Track> rotation = new HashMap<>(), position = new HashMap<>();

    BotClip(float length, Mode mode) {
        this.length = length;
        this.mode = mode;
    }

    static BotClip parse(JsonObject a) {
        JsonElement loop = a.get("loop");
        Mode mode = loop == null ? Mode.ONCE
                : loop.getAsJsonPrimitive().isString() ? ("hold_on_last_frame".equals(loop.getAsString()) ? Mode.HOLD : Mode.ONCE)
                : loop.getAsBoolean() ? Mode.LOOP : Mode.ONCE;
        BotClip c = new BotClip(a.get("animation_length").getAsFloat(), mode);
        JsonObject bones = a.getAsJsonObject("bones");
        for (String bone : bones.keySet()) {
            JsonObject b = bones.getAsJsonObject(bone);
            if (b.has("rotation")) c.rotation.put(bone, track(b.getAsJsonObject("rotation")));
            if (b.has("position")) c.position.put(bone, track(b.getAsJsonObject("position")));
        }
        return c;
    }

    private static Track track(JsonObject keys) {
        TreeMap<Float, JsonArray> sorted = new TreeMap<>();
        for (String t : keys.keySet()) sorted.put(Float.parseFloat(t), keys.getAsJsonObject(t).getAsJsonArray("post"));
        float[] times = new float[sorted.size()], values = new float[sorted.size() * 3];
        int k = 0;
        for (var e : sorted.entrySet()) {
            times[k] = e.getKey();
            for (int i = 0; i < 3; i++) values[k * 3 + i] = e.getValue().get(i).getAsFloat();
            k++;
        }
        return new Track(times, values);
    }

    /** The clip's own time for {@code seconds} since it started: wrapped for a loop, held at the end otherwise. */
    public float local(float seconds) {
        if (mode == Mode.LOOP && length > 0F) {
            float t = seconds % length;
            return t < 0F ? t + length : t;
        }
        return Math.max(0F, Math.min(seconds, length));
    }

    /** Rotation of {@code bone} at clip time {@code t} into out (degrees); false if the clip leaves it alone. */
    public boolean rotation(String bone, float t, float[] out) {
        Track tr = rotation.get(bone);
        if (tr == null) return false;
        tr.sample(t, out);
        return true;
    }

    /** Position of {@code bone} at clip time {@code t} into out (pixels, Y up); false if the clip leaves it alone. */
    public boolean position(String bone, float t, float[] out) {
        Track tr = position.get(bone);
        if (tr == null) return false;
        tr.sample(t, out);
        return true;
    }

    public float length() {
        return length;
    }

    public Mode mode() {
        return mode;
    }
}
