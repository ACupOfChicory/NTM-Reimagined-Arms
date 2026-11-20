package acup.gunfeel.ai;

import java.lang.reflect.Method;

import acup.gunfeel.PosLong;

/** The stands' read-only answers, after the working call. */
final class AiMarksmanAlike {
    long asked, differ, skipped;
    private static final Method NEAR;

    static {
        try {
            NEAR = AiMarksmanOld.class.getDeclaredMethod("near", AiMarksmanBase.State.class, int.class, int.class);
            NEAR.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    void answer(AiBot bot, int method, boolean answer) {
        // A cold role lookup writes its cache: never run it for the comparison.
        if (AiRole.on() && bot.assigned == null && bot.mob.getMainHandItem().getItem() != bot.roleFor) {
            skipped++;
            return;
        }
        boolean on = AiMarksmanOld.on;
        try {
            AiMarksmanOld.on = AiMarksman.on;
            boolean old = switch (method) {
                case 0 -> AiMarksmanOld.is(bot);
                case 1 -> AiMarksmanOld.perched(bot);
                case 2 -> AiMarksmanOld.there(bot);
                default -> throw new AssertionError(method);
            };
            asked++;
            if (answer != old) differ++;
        } finally {
            AiMarksmanOld.on = on;
        }
        var m = bot.marksman;
        if (m == null) return;
        for (long p : m.last) {
            if (p == Long.MIN_VALUE) continue;
            for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) {
                int px = PosLong.blockX(p) + x, pz = PosLong.blockZ(p) + z;
                near(m, px, pz, AiMarksmanBase.nearHistory(m, px, pz));
            }
        }
    }

    boolean near(AiMarksmanBase.State m, int x, int z, boolean answer) {
        try {
            boolean old = (boolean) NEAR.invoke(null, m, x, z);
            asked++;
            if (answer != old) differ++;
            return answer;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    String info() {
        return "     marksman alike (core's against the former code): " + asked + " answers, " + differ + " differ, " + skipped + " cold role lookups skipped";
    }
}
