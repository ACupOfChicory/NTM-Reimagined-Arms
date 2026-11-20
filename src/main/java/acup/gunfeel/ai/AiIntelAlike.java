package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import acup.gunfeel.EntityBody;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

/** The stands' reports through core against the former code, on the same level right after each tick. */
final class AiIntelAlike {
    long ticks, reports, groups, differ;
    private final List<String> first = new ArrayList<>();

    void tick(ServerLevel level, Map<String, List<AiBot>> sides, long now, long soonestNew) {
        long soonestOld = AiIntelOld.tick(level, sides, now);
        ticks++;
        same(soonestNew == soonestOld, "soonest at " + now + ": " + soonestNew + " / was " + soonestOld);
        for (String team : sides.keySet()) {
            long count = AiIntel.reports(level, team), wasCount = AiIntelOld.reports(level, team);
            reports++;
            same(count == wasCount, team + " reports at " + now + ": " + count + " / was " + wasCount);
            List<AiIntel.Group> a = AiIntel.groups(level, team);
            List<AiIntelOld.Group> b = AiIntelOld.groups(level, team);
            same(a.size() == b.size(), team + " groups at " + now + ": " + a.size() + " / was " + b.size());
            for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
                groups++;
                AiIntel.Group n = a.get(i);
                AiIntelOld.Group o = b.get(i);
                IntelBook.Group x = n.known(), y = o.known();
                boolean ok = Arrays.equals(n.ids(), o.ids()) && bits(n.x(), o.x()) && bits(n.y(), o.y())
                        && bits(n.z(), o.z()) && bits(n.r(), o.r()) && n.lead() == o.lead() && x.awake == y.awake
                        && x.seen == y.seen && x.lost == y.lost && Arrays.equals(x.kinds, y.kinds)
                        && Float.floatToRawIntBits(x.health) == Float.floatToRawIntBits(y.health)
                        && n.members().size() == o.members().size();
                for (int j = 0; j < Math.min(n.members().size(), o.members().size()); j++) {
                    LivingEntity m = o.members().get(j);
                    ok &= n.members().get(j) == (m == null ? null : EntityBody.of(m));
                }
                same(ok, team + " group " + i + " at " + now + ": " + Arrays.toString(n.ids()) + " / was " + Arrays.toString(o.ids()));
            }
        }
    }

    private static boolean bits(double a, double b) {
        return Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits(b);
    }

    private void same(boolean ok, String line) {
        if (!ok) {
            differ++;
            note(line);
        }
    }

    private void note(String line) {
        if (first.size() < 5) first.add(line);
    }

    String info() {
        StringBuilder out = new StringBuilder(String.format(Locale.ROOT,
                "intel alike (core's against the former code): %d ticks, %d reports, %d groups, %d differ", ticks, reports, groups, differ));
        for (String f : first) out.append("\n       ").append(f);
        return out.toString();
    }
}
