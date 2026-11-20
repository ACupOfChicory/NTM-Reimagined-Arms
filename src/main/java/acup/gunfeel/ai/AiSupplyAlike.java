package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** The stands' pure queries against the former supplier. */
final class AiSupplyAlike {
    long asked, differ;
    private final List<String> first = new ArrayList<>();

    <T> T answer(String query, T now, Supplier<T> former) {
        AiSupplyOld.on = AiSupply.on;
        AiSupplyOld.outOn = AiSupply.outOn;
        T was = former.get();
        asked++;
        if (!Objects.equals(now, was)) {
            differ++;
            if (first.size() < 5) first.add(query + ": " + now + " / was " + was);
        }
        return now;
    }

    <T> T focus(String query, AiBot b, Supplier<T> working, Supplier<T> former) {
        int before = b.memory.focusId;
        T now = working.get();
        int after = b.memory.focusId;
        b.memory.focusId = before;
        try {
            return answer(query, now, former);
        } finally {
            b.memory.focusId = after;
        }
    }

    String info() {
        return asked + " answers, " + differ + " differ" + (first.isEmpty() ? "" : "; " + String.join("; ", first));
    }
}
