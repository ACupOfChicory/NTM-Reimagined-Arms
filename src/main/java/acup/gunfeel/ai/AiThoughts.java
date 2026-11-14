package acup.gunfeel.ai;

import java.util.Locale;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.DebugLog;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;

/**
 * A soldier's thoughts in words, for bug reports. The lines /armsai debug shows over each AI mob: what it does, whom it
 * knows of and how (sees, is noticing, remembers, hears), and what it decided and why. And the decision journal in
 * logs/ntm-gunfeel-debug.log: a line when its mode changes, its target changes, it lies down or gets up, it holds fire
 * for one of its own, it starts a reload, or it gives up a cover. The journal is on while a player watches /armsai
 * debug, or after /armsai log on; off, a soldier's tick tests one flag and builds no text.
 */
public final class AiThoughts {
    /** why a soldier does what it does now: AiSoldier sets it where it decides; shown as it is */
    enum Why {
        IDLE("knows of nothing: stays"),
        HIT("hit from out of sight: turns there"),
        HIT_COVER("to cover: hit from out of sight"),
        NOISE_COVER("to cover: a loud noise"),
        ALERT_HIT("watches where the hit came from"),
        ALERT_LOUD("watches a loud noise"),
        ALERT_SOUND("watches a sound"),
        ALERT_HIDE("in cover from a noise: hides, then looks out"),
        ALERT_PEEK("looks out where the noise came from"),
        SEEN_COVER("to cover: target in sight"),
        ANSWER("a new fight, near or looked at: a burst where it stands, then cover"),
        WALK_FIRE("to cover at a walk, firing: the target near"),
        NO_COVER("no cover found: fights here"),
        NO_COVER_FAR("no cover, far off: lies, fights"),
        NO_COVER_PINNED("no cover, pinned down: lies, fights"),
        HIDE("hides, then peeks"),
        DRY("no rounds left: hides"),
        RELOAD("reloads in cover"),
        INVESTIGATE("looked out for a noise, saw nothing: creeps to where it came from"),
        BETTER_COVER("in cover a while: a better place found, goes there"),
        FALL_BACK("shaken: falls back to cover farther off, while its squad covers it"),
        LOW_FIGHT("low cover (or none to peek from): bursts from it"),
        LOW_RELOAD("low cover: reloads, down for a long reload"),
        PEEK_BURST("out to its peek for a burst"),
        BACK_DONE("back in: burst done"),
        BACK_EMPTY("back in: gun empty"),
        BACK_LONG("back in: out too long"),
        LOST_HIDE("lost them: hides, then looks"),
        LOW_HIDE("lost them: waits behind low cover"),
        LOW_LOOK("lost them: looks over low cover"),
        PEEK_LOOK("looks out where they went"),
        BACK_GONE("back in: not where they went"),
        HOLD("watches where they went"),
        HOLD_RUN("to cover, watches where they went"),
        HOLD_RELOAD("reloads, watches where they went"),
        SEARCH("searches round where they went"),
        SEARCH_NONE("nothing left to search: watches"),
        FLANK("its squad's flanker: round the side, to cover that sees where they went"),
        SUPPRESS("one of its squad on the move: keeps where they went under fire"),
        BLIND("pinned down behind low cover: fires blind over it"),
        THROW("throws a grenade where they went out of sight"),
        GRENADE("a grenade near: runs from where it bursts");

        final String label;

        Why(String label) {
            this.label = label;
        }
    }

    /** /armsai log on: the journal runs with no one watching too */
    static boolean log;

    /** whether the journal runs now */
    static boolean on() {
        return log || AiDebug.watching();
    }

    // what the journal said of this soldier last, and the tick it did
    private long noted = Long.MIN_VALUE / 2;
    private AiSoldier.@Nullable Mode mode;
    private int target = Integer.MIN_VALUE;
    private TargetMemory.@Nullable State state;
    private boolean lying, blocked, reloading;

    /** after a soldier's tick, with the journal on: a line for each thing that changed */
    void note(AiSoldier s, AiBot bot, long tick) {
        if (!(bot.mob.level() instanceof ServerLevel level)) return;
        // the journal just came on, or the mob slept a while: what it does now, not a change from long ago
        boolean fresh = tick - noted > 20;
        noted = tick;
        TargetMemory.Contact c = bot.memory.focus(bot.mob.getX(), bot.mob.getZ());
        int id = c == null ? Integer.MIN_VALUE : c.id;
        TargetMemory.State st = c == null ? null : c.state;
        AiGunner g = bot.gunner;
        boolean lie = bot.prone.lying(), block = g.blockedTicks > 0, reload = g.reloading();
        if (fresh || s.mode != mode)
            write(bot, tick, (fresh || mode == null ? "" : label(mode) + " -> ") + label(s.mode) + ": " + s.why.label + " (" + target(bot, level, tick) + ")");
        else if (id != target || st != state) write(bot, tick, "target: " + target(bot, level, tick));
        if (!fresh) {
            if (lie != lying) write(bot, tick, (lie ? "lies down: " : "gets up: ") + s.why.label);
            if (block != blocked) write(bot, tick, block ? "holds fire: friend in the line" : "stops holding fire");
            if (reload && !reloading) write(bot, tick, "reloads: " + g.loaded() + " in the gun, " + reserve(g) + " besides");
        }
        mode = s.mode;
        target = id;
        state = st;
        lying = lie;
        blocked = block;
        reloading = reload;
    }

    /** a decision of a moment (a cover given up, a step aside), with the journal on */
    void event(AiBot bot, long tick, String what) {
        if (bot.mob.level() instanceof ServerLevel level) write(bot, tick, what + " (" + target(bot, level, tick) + ")");
    }

    private static void write(AiBot bot, long tick, String what) {
        DebugLog.LOGGER.info(String.format(Locale.ROOT, "armsai #%d (%.1f, %.1f, %.1f) t=%d: %s", bot.mob.getId(), bot.mob.getX(), bot.mob.getY(),
                bot.mob.getZ(), tick, what));
    }

    /**
     * The debug view's lines over a soldier: what it does (its mode, shooting or reloading, lying; its rounds, its
     * health), whom it knows of and how, and what it decided and why.
     */
    static String describe(AiSoldier s, AiBot bot) {
        AiGunner g = bot.gunner;
        StringBuilder b = new StringBuilder(128).append(label(s.mode()));
        if (g.reloading()) b.append(", reloading");
        else if (g.wantsFire() && g.blockedTicks == 0) b.append(", shooting");
        if (bot.prone.lying()) b.append(", lying");
        if (g.loaded() >= 0) b.append("  ").append(g.loaded()).append('/').append(reserve(g));
        b.append("  ").append(hp(bot.mob));
        // what it is to its squad, when that is more than a rifleman by its gun
        AiRole role = AiRole.of(bot);
        if (role != AiRole.RIFLE || bot.assigned != null) b.append("  ").append(role.word());
        if (!(bot.mob.level() instanceof ServerLevel level)) return b.toString();
        b.append('\n').append(target(bot, level, level.getGameTime()));
        b.append('\n').append(g.blockedTicks > 0 ? "holds fire: friend in the line" : s.why.label);
        return b.toString();
    }

    static String hp(Mob mob) {
        return String.format(Locale.ROOT, "%.1f/%.0f hp", mob.getHealth(), mob.getMaxHealth());
    }

    private static String reserve(AiGunner g) {
        return g.ammo.infinite() ? "inf" : String.valueOf(g.ammo.reserve());
    }

    static String label(AiSoldier.Mode m) {
        return switch (m) {
            case IDLE -> "idle";
            case ALERT -> "alert";
            case TO_COVER -> "to cover";
            case COVER -> "cover";
            case PEEK -> "peek";
            case HOLD -> "holding";
            case SEARCH -> "search";
        };
    }

    /**
     * Whom it knows of and how: the target in sight; else one it is noticing (ticks in sight of the ticks it needs);
     * else the one it remembers (since it went out of sight); else the last thing it heard; else nothing.
     */
    static String target(AiBot bot, ServerLevel level, long tick) {
        TargetMemory m = bot.memory;
        TargetMemory.Contact c = m.focus(bot.mob.getX(), bot.mob.getZ());
        if (c != null && c.state == TargetMemory.State.VISIBLE) return "sees " + name(level, c.id) + " " + blocks(bot, c.x, c.y, c.z) + " bl";
        TargetMemory.Contact noticing = null;
        for (TargetMemory.Contact k : m.contacts())
            if (k.state != TargetMemory.State.VISIBLE && k.sightTicks > 0 && (noticing == null || k.sightTicks > noticing.sightTicks)) noticing = k;
        if (noticing != null) return "noticing " + name(level, noticing.id) + " " + noticing.sightTicks + "/" + noticing.notice;
        if (c != null) return String.format(Locale.ROOT, "remembers %s %.1f s, %d bl", name(level, c.id), (tick - c.lostTick) / 20.0,
                blocks(bot, c.lostX, c.lostY, c.lostZ));
        TargetMemory.Noise n = m.noise();
        if (n != null) return String.format(Locale.ROOT, "%s %d bl, %.1f s ago", n.hurt ? "hit from" : n.loud ? "hears loud" : "hears a sound",
                blocks(bot, n.x, n.y, n.z), (tick - n.tick) / 20.0);
        return "no target";
    }

    /** a player's name; a mob's name and its entity id, to tell mobs of a kind apart */
    private static String name(ServerLevel level, int id) {
        Entity e = level.getEntity(id);
        if (e == null) return "#" + id;
        String n = e.getName().getString();
        if (n.length() > 16) n = n.substring(0, 16);
        return e instanceof Player ? n : n + " #" + id;
    }

    private static long blocks(AiBot bot, double x, double y, double z) {
        double dx = x - bot.mob.getX(), dy = y - bot.mob.getY(), dz = z - bot.mob.getZ();
        return Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz));
    }
}
