package acup.gunfeel.ai;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Mob;

/**
 * What AI mobs call out (stage3-plan 3.4): a short line at a moment of the fight (a grenade thrown or seen, a
 * reload, a fall back, a friend down), in a voice of its own (one of VOICES, by the mob), as a sound where it stands:
 * the game's voice sounds (SoundSource.VOICE: the player's Voice/Speech slider), which no AI mob hears (AiBots.sound).
 * Not too often: one mob no oftener than BOT_GAP, no two near one another at once (CHATTER), and the same line
 * not again near where it was just said (LINE_GAP). An urgent line (a grenade thrown or seen, a friend down) is said over the first
 * two. Server setting aiVoices; a player turns them off or down on their client (AiVoiceClient). Server thread.
 */
public final class AiVoice {
    public static final String KEY = "aiVoices";
    /** voices the lines are recorded in (assets ntmgunfeel/sounds/ai/voice/v<n>/) */
    public static final int VOICES = 3;
    /** as loud as a sound gets (a player's aiVoiceVolume turns it down); heard this far (sounds.json's attenuation alike) */
    public static final float VOLUME = 1F, RANGE = 32F;
    /** ticks: a mob says nothing again so soon; no one says a thing within CHATTER_NEAR of one who just did */
    static final int BOT_GAP = 80, CHATTER = 30;
    /** blocks: near enough to talk over one another */
    static final double CHATTER_NEAR = 8;
    /** ticks and blocks: the same line not again within these of where it was said (an urgent one sooner) */
    static final int LINE_GAP = 200, URGENT_GAP = 60;
    static final double LINE_NEAR = 16;
    /** lines a second at most by one squad (a mob alone: by itself), the urgent ones too */
    static final int SQUAD_SECOND = 2;

    public enum Line {
        CONTACT("contact", false), FRAG_OUT("frag_out", true), SMOKE_OUT("smoke_out", false), GRENADE("grenade", true),
        RELOADING("reloading", false), FALLING_BACK("falling_back", false), FLANKING("flanking", false), MAN_DOWN("man_down", true),
        MOVING("moving", false), HIT("hit", false);

        /** its sound: ntmgunfeel:ai.voice.v<n>.<id> */
        public final String id;
        final boolean urgent;

        Line(String id, boolean urgent) {
            this.id = id;
            this.urgent = urgent;
        }
    }

    /** a line said: where, when */
    record Said(double x, double z, Line line, long tick, Object by) {
    }

    private static final Map<ServerLevel, ArrayDeque<Said>> SAID = new WeakHashMap<>();
    /** the stands: lines said, by Line; and kept back */
    static final int[] said = new int[Line.values().length];
    static int kept;
    /** the stands: when each line was said and by whom, its squad (the mob itself when alone); only while counting */
    static boolean counting;
    record Heard(long tick, Object by) {
    }
    static final List<Heard> heard = new ArrayList<>();
    /** ticks: a second, for the most said in one */
    static final int SECOND = 20;

    private AiVoice() {
    }

    static boolean enabled() {
        return acup.gunfeel.ReaimTimes.flag(KEY, true);
    }

    /** its voice, the same each time for a mob (by its UUID's hash: kept over a reload) */
    public static int voice(int id) {
        return Math.floorMod(id * 0x9E3779B1, VOICES);
    }

    /** the sound of a line in a voice */
    public static Identifier sound(int voice, Line line) {
        return Identifier.fromNamespaceAndPath("ntmgunfeel", "ai.voice.v" + (voice + 1) + "." + line.id);
    }

    /** a mob calls out a line, if it may now (allowed); whether it did */
    static boolean say(ServerLevel level, AiBot bot, Line line, long tick) {
        if (!enabled()) return false;
        Mob m = bot.mob;
        ArrayDeque<Said> recent = SAID.computeIfAbsent(level, k -> new ArrayDeque<>());
        AiSquads.Squad sq = AiSquads.of(bot);
        Object by = sq != null ? sq : bot;
        if (!allowed(recent, bot.saidAt, m.getX(), m.getZ(), line, tick, by)) {
            kept++;
            return false;
        }
        recent.addLast(new Said(m.getX(), m.getZ(), line, tick, by));
        bot.saidAt = tick;
        said[line.ordinal()]++;
        if (counting) heard.add(new Heard(tick, by));
        int own = m.getUUID().hashCode();
        Holder<SoundEvent> s = Holder.direct(SoundEvent.createFixedRangeEvent(sound(voice(own), line), RANGE));
        // a voice of its own a little higher or lower, the same each time
        float pitch = 0.94F + 0.12F * Math.floorMod(own * 0x2545F491, 100) / 100F;
        level.playSound(null, m.getX(), m.getEyeY(), m.getZ(), s, SoundSource.VOICE, VOLUME, pitch);
        return true;
    }

    /**
     * May a mob of squad by (itself when alone) that last spoke at saidAt say this line here now, with these said
     * lately (oldest first; those older than any gap are let go)?
     */
    static boolean allowed(ArrayDeque<Said> recent, long saidAt, double x, double z, Line line, long tick, Object by) {
        while (!recent.isEmpty() && tick - recent.peekFirst().tick() > LINE_GAP) recent.pollFirst();
        if (!line.urgent && tick - saidAt < BOT_GAP) return false;
        int gap = line.urgent ? URGENT_GAP : LINE_GAP, squad = 0;
        for (Said s : recent) {
            if (s.by() == by && tick - s.tick() < SECOND && ++squad >= SQUAD_SECOND) return false;
            double d2 = (s.x() - x) * (s.x() - x) + (s.z() - z) * (s.z() - z);
            if (s.line() == line && tick - s.tick() < gap && d2 < LINE_NEAR * LINE_NEAR) return false;
            if (!line.urgent && tick - s.tick() < CHATTER && d2 < CHATTER_NEAR * CHATTER_NEAR) return false;
        }
        return true;
    }

    /** the stands: a new stage, nothing counted */
    static void reset() {
        java.util.Arrays.fill(said, 0);
        kept = 0;
        heard.clear();
    }

    /** the stands: the most lines one squad (or a mob alone) said within a second, of these (in their order) */
    static int mostASecond(List<Heard> h) {
        Map<Object, ArrayDeque<Long>> by = new HashMap<>();
        int most = 0;
        for (Heard x : h) {
            ArrayDeque<Long> q = by.computeIfAbsent(x.by(), k -> new ArrayDeque<>());
            q.addLast(x.tick());
            while (x.tick() - q.peekFirst() >= SECOND) q.pollFirst();
            most = Math.max(most, q.size());
        }
        return most;
    }

    static int total() {
        int n = 0;
        for (int k : said) n += k;
        return n;
    }

    /** the stands: what was said */
    static String info() {
        StringBuilder b = new StringBuilder(String.format(Locale.ROOT, "%d lines (", total()));
        boolean first = true;
        for (Line l : Line.values()) {
            if (said[l.ordinal()] == 0) continue;
            b.append(first ? "" : ", ").append(l.id).append(' ').append(said[l.ordinal()]);
            first = false;
        }
        return b.append(String.format(Locale.ROOT, "), kept back %d, most a second by a squad %d", kept, mostASecond(heard))).toString();
    }
}
