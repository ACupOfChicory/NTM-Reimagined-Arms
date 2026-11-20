package acup.gunfeel.ai;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** The stands compare pure answers on the same live orders. */
final class StaffMoveAlike {
    long asked, differ;
    private final List<String> first = new ArrayList<>();
    private static final Method IN_PLACE;

    static {
        try {
            IN_PLACE = StaffMoveOld.class.getDeclaredMethod("inPlace", AiBot.class, AiCommander.Order.class);
            IN_PLACE.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    <T> T answer(String query, T now, T was) {
        asked++;
        if (!Objects.deepEquals(now, was)) {
            differ++;
            if (first.size() < 5) first.add(query + ": " + java.util.Arrays.deepToString(new Object[] {now})
                    + " / was " + java.util.Arrays.deepToString(new Object[] {was}));
        }
        return now;
    }

    void tick(java.util.Collection<AiBot> bots) {
        for (AiBot b : bots) {
            AiCommander.Order o = b.order;
            if (o == null) continue;
            StaffMove.speed(o);
            StaffMove.atCover(b, o);
            answer("inPlace", StaffMoveBase.atPlace(AiCommander.STAFF, b, o), oldInPlace(b, o));
            if (o.job != null) {
                List<AiBot> of = StaffMove.squad(o.job);
                StaffMove.halves(of.stream().map(AiBot::role).toList());
                StaffMove.rushing(of.size());
                StaffMoveBase.State st = o.job.move;
                double foe = st.foe ? Math.hypot(st.foeX - b.mob.getX(), st.foeZ - b.mob.getZ()) - st.foeR : Double.MAX_VALUE;
                HqGrid grid = AiHqMap.of((net.minecraft.server.level.ServerLevel) b.mob.level(), AiSquads.team(b));
                int x = grid == null ? -1 : grid.cellX(b.mob.getX()), z = grid == null ? -1 : grid.cellZ(b.mob.getZ());
                StaffMove.mode(foe, x >= 0 && z >= 0 && grid.seen(x, z) > 0.5, o.job.kind == StaffPlan.Kind.ADVANCE,
                        Math.hypot(o.x - b.mob.getX(), o.z - b.mob.getZ()) - o.r * 0.5);
            }
            if (o.route != null) {
                double s = StaffMove.along(o.route, b.mob.getX(), b.mob.getZ());
                StaffMove.at(o.route, s);
            }
        }
    }

    static boolean oldInPlace(AiBot b, AiCommander.Order o) {
        try {
            return (boolean) IN_PLACE.invoke(null, b, o);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    String info() {
        return "staffmove alike (core's against the former code): " + asked + " answers, " + differ + " differ"
                + (first.isEmpty() ? "" : "\n       " + String.join("\n       ", first));
    }
}
