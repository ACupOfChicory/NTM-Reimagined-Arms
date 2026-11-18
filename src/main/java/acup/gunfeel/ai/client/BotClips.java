package acup.gunfeel.ai.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import acup.gunfeel.GunFeel;

/**
 * The clips AI mobs play, from assets/ntmgunfeel/animations/bot.animation.json (tools/quaternius/bot_clips.py):
 * Quaternius' Universal Animation Library and SWAT, KayKit's Character Animations, all CC0; and from
 * bot_people.animation.json (tools/motion/actions_to_mod.py): actions recorded from people (the CMU Graphics Lab
 * Motion Capture Database); and from bot_gaits.animation.json (gaits_to_mod.py): gait loops recorded from people, by
 * their own names (BotGaits). Read from the jar once.
 */
public final class BotClips {
    static final String PATH = "/assets/ntmgunfeel/animations/bot.animation.json",
            PEOPLE = "/assets/ntmgunfeel/animations/bot_people.animation.json";
    public static final String IDLE = "idle", HIT_CHEST = "hit_chest", HIT_HEAD = "hit_head", THROW = "throw",
            WAVE = "wave";
    /** on the move, the legs only: walking, jogging, sprinting forward, walking back, sideways left and right */
    public static final String WALK = "walk", JOG = "jog", SPRINT = "sprint", BACK = "back", STRAFE_LEFT = "strafe_left",
            STRAFE_RIGHT = "strafe_right";
    /** a rifle's reload (the arms), a fall dead (the whole body), a hit that throws it back (the arms and body) */
    public static final String RELOAD = "reload", DEATH = "death", HIT_KNOCK = "hit_knock";
    /** a slide into cover, a forward roll (the whole body, AiPose.SLIDE, ROLL) */
    public static final String SLIDE = "slide", ROLL = "roll";
    /**
     * crouching, the legs and the trunk's lean, fitted under the head where vanilla's crouch has it (tools/quaternius
     * fit.py): down on a knee at a stand, a walk bent low on the move
     */
    public static final String KNEEL = "kneel", CROUCH_WALK = "crouch_walk";
    /** in the air, the legs; two more flinches (doubled up, thrown back); a door pushed open, the left arm */
    public static final String JUMP = "jump", HIT_FOLD = "hit_fold", HIT_BACK = "hit_back", DOOR = "door";
    /**
     * recorded from people (PEOPLE): a grenade thrown overarm standing, a short swing from the arm drawn back
     * (CMU 79_35); the left hand keeps the rifle, its arm down at the side
     */
    public static final String THROW_PERSON = "throw_person";

    private static volatile Map<String, BotClip> clips;

    private BotClips() {
    }

    public static BotClip get(String name) {
        Map<String, BotClip> c = clips;
        if (c == null) {
            synchronized (BotClips.class) {
                if (clips == null) clips = load();
                c = clips;
            }
        }
        return c.get(name);
    }

    static Map<String, BotClip> load() {
        Map<String, BotClip> out = new HashMap<>();
        load(PATH, out);
        load(PEOPLE, out);
        load(BotGaits.PATH, out);
        return out;
    }

    private static void load(String path, Map<String, BotClip> out) {
        try (InputStream in = BotClips.class.getResourceAsStream(path)) {
            if (in == null) {
                GunFeel.LOGGER.warn("AI mob clips missing: {}", path);
                return;
            }
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonObject anims = JsonParser.parseReader(r).getAsJsonObject().getAsJsonObject("animations");
                for (String name : anims.keySet()) {
                    // one clip unreadable leaves the others
                    try {
                        out.put(name, BotClip.parse(anims.getAsJsonObject(name)));
                    } catch (RuntimeException e) {
                        GunFeel.LOGGER.warn("AI mob clip {} unreadable: {}", name, e.toString());
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            GunFeel.LOGGER.warn("AI mob clips {} unreadable: {}", path, e.toString());
        }
    }
}
