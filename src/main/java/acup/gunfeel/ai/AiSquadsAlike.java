package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * The stands' comparison of core's squads (AiSquadsBase, through AiSquads) with the former code (AiSquadsOld) on the
 * same AI mobs, every tick right after AiSquads made or kept its squads: the squads (their mobs in order, target,
 * flanker, base of fire, middle, roles' slots) and each mob's answers (live, on the move, covering, may move, base of
 * fire, covers a fall back, its squad, its squad's middle, each role's holder; for its focus: flanks, may hunt, others
 * hunt, suppresses). Off but on a stand (AiSquads.alike).
 */
final class AiSquadsAlike {
    long ticks, squads, squadsDiffer, asked, answersDiffer;
    private final List<String> first = new ArrayList<>();

    void tick(ServerLevel level, Collection<AiBot> all) {
        AiSquadsOld.tick(level, all);
        ticks++;
        List<AiSquads.Squad> now = AiSquads.squads(level);
        List<AiSquadsOld.Squad> was = AiSquadsOld.squads(level);
        StringBuilder a = new StringBuilder(), b = new StringBuilder();
        for (AiSquads.Squad s : now) squad(a, s.bots, s.target, s.flanker, s.based, s.cx, s.cz, s.slots);
        for (AiSquadsOld.Squad s : was) squad(b, s.bots, s.target, s.flanker, s.based, s.cx, s.cz, s.slots);
        squads += now.size();
        if (!a.toString().equals(b.toString())) {
            squadsDiffer++;
            note("squads at " + level.getGameTime() + ": " + a + " / was " + b);
        }
        long pressed = AiCommander.pressed;
        long tick = level.getGameTime();
        for (AiBot bot : all) {
            String x = answers(bot, now, tick, true), y = answers(bot, was, tick, false);
            asked++;
            if (!x.equals(y)) {
                answersDiffer++;
                note("mob " + bot.mob.getId() + " at " + tick + ": " + x + " / was " + y);
            }
        }
        if (!AiSquads.roles(all).equals(AiSquadsOld.roles(all))) {
            answersDiffer++;
            note("roles at " + tick + ": " + AiSquads.roles(all) + " / was " + AiSquadsOld.roles(all));
        }
        // the comparison's own presses not counted in the stands' figures
        AiCommander.pressed = pressed;
    }

    private static void squad(StringBuilder out, List<AiBot> bots, int target, int flanker, boolean based, double cx, double cz,
            java.util.EnumMap<AiRole, AiBot> slots) {
        out.append('[');
        for (AiBot b : bots) out.append(b.mob.getId()).append(' ');
        out.append("t").append(target).append(" f").append(flanker).append(based ? " based" : "").append(' ')
                .append(Double.doubleToLongBits(cx)).append(',').append(Double.doubleToLongBits(cz));
        for (var e : slots.entrySet()) out.append(' ').append(e.getKey().word()).append('=').append(e.getValue().mob.getId());
        out.append(']');
    }

    private static String answers(AiBot bot, List<? extends Object> squads, long tick, boolean core) {
        StringBuilder out = new StringBuilder();
        Object s = core ? AiSquads.of(bot) : AiSquadsOld.of(bot);
        Vec3 m = core ? AiSquads.middle(bot) : AiSquadsOld.middle(bot);
        out.append(s == null ? -1 : squads.indexOf(s)).append(' ').append(m == null ? "-" : m.toString());
        out.append(core ? AiSquads.live(bot) : AiSquadsOld.live(bot)).append(core ? AiSquads.moving(bot) : AiSquadsOld.moving(bot))
                .append(core ? AiSquads.covering(bot) : AiSquadsOld.covering(bot)).append(core ? AiSquads.mayMove(bot) : AiSquadsOld.mayMove(bot))
                .append(core ? AiSquads.base(bot) : AiSquadsOld.base(bot)).append(core ? AiSquads.coversFallBack(bot) : AiSquadsOld.coversFallBack(bot));
        for (AiRole r : AiRole.values()) {
            AiBot h = core ? AiSquads.holder(bot, r) : AiSquadsOld.holder(bot, r);
            out.append(' ').append(h == null ? -1 : h.mob.getId());
        }
        int t = bot.memory.focusId;
        if (t >= 0) {
            out.append(" focus ").append(core ? AiSquads.flanks(bot, t) : AiSquadsOld.flanks(bot, t))
                    .append(core ? AiSquads.mayHunt(bot, t) : AiSquadsOld.mayHunt(bot, t))
                    .append(core ? AiSquads.othersHunt(bot, t) : AiSquadsOld.othersHunt(bot, t));
            TargetMemory.Contact c = bot.memory.contact(t);
            if (c != null) out.append(core ? AiSquads.suppresses(bot, c, tick) : AiSquadsOld.suppresses(bot, c, tick));
        }
        return out.toString();
    }

    private void note(String line) {
        if (first.size() < 5) first.add(line);
    }

    boolean alike() {
        return squadsDiffer == 0 && answersDiffer == 0 && asked > 0;
    }

    String info() {
        StringBuilder out = new StringBuilder(String.format(Locale.ROOT,
                "squads alike (core's against the former code): %d ticks, %d squads, %d differ; %d mobs' answers, %d differ", ticks, squads,
                squadsDiffer, asked, answersDiffer));
        for (String f : first) out.append("\n       ").append(f);
        return out.toString();
    }
}
