package acup.gunfeel.ai;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** The stands compare pure answers on the same live orders. */
final class AiCommanderStepAlike {
    long asked, differ;
    private final List<String> first = new ArrayList<>();
    private static final Method MATE_IN = method("mateIn", AiBot.class, AiCommander.Order.class, long.class);
    private static final Method IN_ROOM = method("inRoom", AiCommander.Order.class, double.class, double.class);
    private static final Method BAD = method("bad", AiCommander.Order.class, double.class, double.class);

    private static Method method(String name, Class<?>... args) {
        try {
            Method m = AiCommanderStepOld.class.getDeclaredMethod(name, args);
            m.setAccessible(true);
            return m;
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static boolean old(Method m, Object... args) {
        try {
            return (boolean) m.invoke(null, args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean oldMateIn(AiBot b, AiCommander.Order o, long tick) { return old(MATE_IN, b, o, tick); }
    static boolean oldInRoom(AiCommander.Order o, double x, double z) { return old(IN_ROOM, o, x, z); }
    static boolean oldBad(AiCommander.Order o, double x, double z) { return old(BAD, o, x, z); }

    boolean answer(String query, boolean now, boolean was) {
        asked++;
        if (now != was) {
            differ++;
            if (first.size() < 5) first.add(query + ": " + now + " / was " + was);
        }
        return now;
    }

    void tick(java.util.Collection<AiBot> bots, long tick) {
        for (AiBot b : bots) AiCommander.sampleStep(b, tick);
    }

    String info() {
        return "commander step alike (core's against the former code): " + asked + " answers, " + differ + " differ"
                + (first.isEmpty() ? "" : "\n       " + String.join("\n       ", first));
    }
}
