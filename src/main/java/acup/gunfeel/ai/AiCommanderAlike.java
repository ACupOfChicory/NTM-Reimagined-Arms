package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.ai.AiIntelBase.Group;
import net.minecraft.server.level.ServerLevel;

/** The facade's queries against the former code, on the same orders and bodies. */
final class AiCommanderAlike {
    long asked, differ;
    private final Map<String, long[]> counts = new LinkedHashMap<>();
    private final List<String> first = new ArrayList<>();

    AiCommanderAlike() {
        for (String q : List.of("has", "going", "counted", "failed", "asleep", "groups", "red", "leashed", "goesRound", "presses",
                "breaksContact", "mapped", "passedBy", "far")) counts.put(q, new long[2]);
    }

    void tick(ServerLevel level) {
        long pressed = AiCommander.pressed;
        try {
            long now = level.getGameTime();
            AiCommander.mapped();
            List<AiSquads.Squad> all = new ArrayList<>(AiSquads.squads(level));
            all.addAll(AiCommander.far(level));
            for (AiSquads.Squad s : all) {
                List<Group> groups = AiCommander.groups(level, AiSquads.team(s.bots.get(0)));
                AiCommander.asleep(s);
                AiCommander.going(s, groups, now);
                AiCommander.counted(s, groups, now);
                for (Group g : groups) AiCommander.failed(s, g, now);
                for (AiBot b : s.bots) {
                    AiCommander.has(b, now);
                    AiCommander.red(b);
                    AiCommander.presses(b, now);
                    AiCommander.breaksContact(b, now);
                    AiCommander.passedBy(s, b, now);
                    int target = b.memory.focusId;
                    AiCommander.leashed(b, target, now);
                    TargetMemory.Contact c = b.memory.contact(target);
                    if (c != null) AiCommander.goesRound(b, c, now);
                }
            }
        } finally {
            // Sampling must not add to the stand's press count.
            AiCommander.pressed = pressed;
        }
    }

    private void same(String query, Object answer, Object was) {
        asked++;
        long[] n = counts.computeIfAbsent(query, k -> new long[2]);
        n[0]++;
        if (!java.util.Objects.equals(answer, was)) {
            differ++;
            n[1]++;
            if (first.size() < 5) first.add(query + ": " + answer + " / was " + was);
        }
    }

    private static String ids(@Nullable Group g) { return g == null ? "-" : Arrays.toString(g.ids()); }

    private static List<String> ids(List<Group> groups) { return groups.stream().map(AiCommanderAlike::ids).toList(); }

    private static List<List<Integer>> squads(List<AiSquads.Squad> squads) {
        return squads.stream().map(s -> s.bots.stream().map(b -> b.mob.getId()).toList()).toList();
    }

    void has(AiBot b, long tick, boolean a) { same("has", a, AiCommanderOld.has(b, tick)); }
    void going(AiSquads.Squad s, List<Group> g, long tick, @Nullable Group a) { same("going", ids(a), ids(AiCommanderOld.going(s, g, tick))); }
    void counted(AiSquads.Squad s, List<Group> g, long tick, @Nullable Group a) { same("counted", ids(a), ids(AiCommanderOld.counted(s, g, tick))); }
    void failed(AiSquads.Squad s, Group g, long tick, boolean a) { same("failed", a, AiCommanderOld.failed(s, g, tick)); }
    void asleep(AiSquads.Squad s, boolean a) { same("asleep", a, AiCommanderOld.asleep(s)); }
    void groups(ServerLevel l, String team, List<Group> a) { same("groups", ids(a), ids(AiCommanderOld.groups(l, team))); }
    void red(AiBot b, boolean a) { same("red", a, AiCommanderOld.red(b)); }
    void leashed(AiBot b, int target, long tick, boolean a) { same("leashed", a, AiCommanderOld.leashed(b, target, tick)); }
    void goesRound(AiBot b, TargetMemory.Contact c, long tick, boolean a) { same("goesRound", a, AiCommanderOld.goesRound(b, c, tick)); }
    void breaksContact(AiBot b, long tick, boolean a) { same("breaksContact", a, AiCommanderOld.breaksContact(b, tick)); }
    void mapped(boolean a) { same("mapped", a, AiCommanderOld.mapped()); }
    void passedBy(AiSquads.Squad s, AiBot b, long tick, int a) { same("passedBy", a, AiCommanderOld.passedBy(s, b, tick)); }
    void far(ServerLevel l, List<AiSquads.Squad> a) { same("far", squads(a), squads(AiCommanderOld.far(l))); }

    void presses(AiBot b, long tick, boolean a) {
        long before = AiCommanderOld.pressed;
        try {
            same("presses", a, AiCommanderOld.presses(b, tick));
        } finally {
            AiCommanderOld.pressed = before;
        }
    }

    String info() {
        StringBuilder out = new StringBuilder("commander alike (core's against the former code): " + asked + " asked, " + differ + " differ");
        counts.forEach((q, n) -> out.append("; ").append(q).append(' ').append(n[0]).append('/').append(n[1]));
        for (String f : first) out.append("\n       ").append(f);
        return out.toString();
    }
}
