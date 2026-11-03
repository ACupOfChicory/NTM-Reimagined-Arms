package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;

// The mixins into NTM LateMixins hands on, for the compat tests (its lists are package-private)
public final class LateMixinsAccess {
    private LateMixinsAccess() {
    }

    public static List<String> all() {
        List<String> out = new ArrayList<String>(LateMixins.COMMON);
        out.addAll(LateMixins.CLIENT);
        return out;
    }
}
