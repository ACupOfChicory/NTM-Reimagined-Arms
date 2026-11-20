package acup.gunfeel.ai;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.IntPredicate;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;

/** The stands' pure queries, after the working call. */
final class AiMedicAlike {
    long asked, differ;
    private final List<String> first = new ArrayList<>();
    private static final Method FIGHT = method("fight", AiBot.class);
    private static final Method SERVED = method("served", ServerLevel.class, AiBot.class, int.class);

    private static Method method(String name, Class<?>... args) {
        try {
            Method m = AiMedicOld.class.getDeclaredMethod(name, args);
            m.setAccessible(true);
            return m;
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static Object invoke(Method method, Object... args) {
        try {
            return method.invoke(null, args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    <T> T answer(String query, T now, Supplier<T> former) {
        boolean on = AiMedicOld.on, cover = AiMedicOld.cover;
        try {
            AiMedicOld.on = AiMedic.on;
            AiMedicOld.cover = AiMedic.cover;
            T was = former.get();
            asked++;
            if (!Objects.equals(now, was)) {
                differ++;
                if (first.size() < 5) first.add(query + ": " + now + " / was " + was);
            }
            return now;
        } finally {
            AiMedicOld.on = on;
            AiMedicOld.cover = cover;
        }
    }

    TargetMemory.@Nullable Contact fight(AiBot bot, Supplier<TargetMemory.@Nullable Contact> working) {
        List<AiBot> bots = new ArrayList<>(AiBots.bots((ServerLevel) bot.mob.level()));
        if (!bots.contains(bot)) bots.add(bot);
        AiSquads.Squad squad = AiSquads.of(bot);
        if (squad != null) for (AiBot b : squad.bots) if (!bots.contains(b)) bots.add(b);
        int[] before = bots.stream().mapToInt(b -> b.memory.focusId).toArray();
        TargetMemory.Contact now = working.get();
        int[] after = bots.stream().mapToInt(b -> b.memory.focusId).toArray();
        for (int i = 0; i < bots.size(); i++) bots.get(i).memory.focusId = before[i];
        try {
            return answer("fight", now, () -> (TargetMemory.Contact) invoke(FIGHT, bot));
        } finally {
            for (int i = 0; i < bots.size(); i++) bots.get(i).memory.focusId = after[i];
        }
    }

    boolean served(ServerLevel level, AiBot bot, int id, boolean now) {
        List<AiBot> bots = new ArrayList<>(AiBots.bots(level));
        Item[] guns = bots.stream().map(b -> b.roleFor).toArray(Item[]::new);
        AiRole[] roles = bots.stream().map(b -> b.role).toArray(AiRole[]::new);
        int[] focus = bots.stream().mapToInt(b -> b.memory.focusId).toArray();
        try {
            return answer("served", now, () -> (boolean) invoke(SERVED, level, bot, id));
        } finally {
            for (int i = 0; i < bots.size(); i++) {
                bots.get(i).roleFor = guns[i];
                bots.get(i).role = roles[i];
                bots.get(i).memory.focusId = focus[i];
            }
        }
    }

    boolean after(AiBot bot, ServerLevel level, boolean result, Runnable fight, IntPredicate served) {
        AiMedic.on();
        AiMedic.cover();
        AiMedic.share();
        AiMedic.hurt(bot.mob);
        fight.run();
        served.test(bot.mob.getId());
        if (bot.medic != null && bot.medic.to >= 0 && bot.medic.to != bot.mob.getId()) served.test(bot.medic.to);
        for (var p : level.players()) {
            AiMedic.hurt(p);
            served.test(p.getId());
        }
        return result;
    }

    String info() {
        return asked + " answers, " + differ + " differ" + (first.isEmpty() ? "" : "; " + String.join("; ", first));
    }
}
