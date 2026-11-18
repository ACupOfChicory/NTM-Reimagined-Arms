package acup.gunfeel.ai.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import acup.gunfeel.GunFeel;

/**
 * Each AI mob's own gait ahead (Config.botMoves clips): loops recorded from people, the legs only
 * (bot_gaits.animation.json, tools/motion/gaits_to_mod.py: CMU's runs and sprints, 100STYLE's walks, put on the mod's
 * legs held apart), one of each slot's variants for the walk, the jog and the sprint (BotPoser.gaitClips' first three)
 * and for going slower than the walk's run (BotPoser.strolling: a walk), by the mob's entity id: every client draws it
 * the same. Its walk is its jog's clip where the walk has that one (16_56's quick steps go on the run only). The clips
 * put in bare (their foot down on the floor already) and each at its own pace (BotPoser.pace): its feet stand. Going
 * back, aside and crouched the mod's own clips.
 */
public final class BotGaits {
    static final String PATH = "/assets/ntmgunfeel/animations/bot_gaits.animation.json";
    /** the slots in the file: the walk, the jog, the sprint; slower than the walk's clip is made for, the stroll */
    static final String WALK = "walk", JOG = "jog", SPRINT = "sprint", STROLL = "stroll";

    /** each jog with each sprint and each stroll, its walk by the jog; none if the file or a slot is missing */
    private static volatile BotGaitOverride @Nullable [] variants;

    private BotGaits() {
    }

    /** the mob's own (null with Config.botMoves old, or none read: the mod's own clips) */
    public static @Nullable BotGaitOverride own(int entity) {
        if (!BotMoves.on()) return null;
        BotGaitOverride[] v = all();
        return v.length == 0 ? null : v[pick(entity, v.length)];
    }

    /** variant n of count() (a stand's choice), or null if none were read */
    public static @Nullable BotGaitOverride variant(int n) {
        BotGaitOverride[] v = all();
        return v.length == 0 ? null : v[Math.floorMod(n, v.length)];
    }

    public static int count() {
        return all().length;
    }

    /** which of n a mob of this id plays: its id mixed (murmur3's finish), so mobs made one after another differ */
    static int pick(int entity, int n) {
        int h = entity;
        h ^= h >>> 16;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        h *= 0xC2B2AE35;
        h ^= h >>> 16;
        return Math.floorMod(h, n);
    }

    static BotGaitOverride[] all() {
        BotGaitOverride[] v = variants;
        if (v == null) {
            synchronized (BotGaits.class) {
                if (variants == null) variants = load();
                v = variants;
            }
        }
        return v;
    }

    private static BotGaitOverride[] load() {
        try (InputStream in = BotGaits.class.getResourceAsStream(PATH)) {
            if (in == null) {
                GunFeel.LOGGER.warn("AI mob gaits missing: {}", PATH);
                return new BotGaitOverride[0];
            }
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return variants(JsonParser.parseReader(r).getAsJsonObject());
            }
        } catch (IOException | RuntimeException e) {
            GunFeel.LOGGER.warn("AI mob gaits {} unreadable: {}", PATH, e.toString());
            return new BotGaitOverride[0];
        }
    }

    /** a clip's own pace (blocks a second) and how far down its hips are held (px) */
    private record Pace(float speed, float drop) {
    }

    static BotGaitOverride[] variants(JsonObject file) {
        Map<String, Pace> paces = new HashMap<>();
        JsonObject gaits = file.getAsJsonObject("gaits");
        for (String name : gaits.keySet()) {
            // one unreadable or not there leaves the others
            try {
                JsonObject g = gaits.getAsJsonObject(name);
                float speed = g.get("speed").getAsFloat(), drop = g.get("drop").getAsFloat();
                if (speed > 0F && drop >= 0F && Float.isFinite(speed) && Float.isFinite(drop) && BotClips.get(name) != null)
                    paces.put(name, new Pace(speed, drop));
            } catch (RuntimeException e) {
                GunFeel.LOGGER.warn("AI mob gait {} unreadable: {}", name, e.toString());
            }
        }
        JsonObject slots = file.getAsJsonObject("slots");
        List<String> walk = slot(slots, WALK, paces), jog = slot(slots, JOG, paces), sprint = slot(slots, SPRINT, paces),
                stroll = slot(slots, STROLL, paces);
        if (walk.isEmpty() || jog.isEmpty() || sprint.isEmpty()) return new BotGaitOverride[0];
        List<BotGaitOverride> out = new ArrayList<>();
        // none read for the stroll: the walk's clip at any speed
        int strolls = Math.max(1, stroll.size());
        for (int j = 0; j < jog.size(); j++)
            for (int s = 0; s < sprint.size(); s++)
                for (int k = 0; k < strolls; k++) {
                    String jn = jog.get(j), sn = sprint.get(s);
                    String wn = walk.contains(jn) ? jn : walk.get((j + s) % walk.size());
                    Pace w = paces.get(wn), jp = paces.get(jn), sp = paces.get(sn);
                    // the hips held as far down as the jog's (the loops are made for one height, retarget_mocap's
                    // 0.45 px: gaits_to_mod.py takes no other)
                    BotGaitOverride.Way st = stroll.isEmpty() ? null
                            : new BotGaitOverride.Way(stroll.get(k), paces.get(stroll.get(k)).speed(), paces.get(stroll.get(k)).drop());
                    out.add(new BotGaitOverride(wn, jn, sn, w.speed(), jp.speed(), sp.speed(), jp.drop(), true).withStroll(st));
                }
        return out.toArray(new BotGaitOverride[0]);
    }

    private static List<String> slot(JsonObject slots, String name, Map<String, Pace> paces) {
        List<String> out = new ArrayList<>();
        if (slots.has(name)) for (JsonElement e : slots.getAsJsonArray(name)) if (paces.containsKey(e.getAsString())) out.add(e.getAsString());
        return out;
    }
}
