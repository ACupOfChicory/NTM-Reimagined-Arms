package acup.gunfeel.ammo;

import acup.gunfeel.CoreServices;
import acup.gunfeel.host.AmmoCatalog;

/** Round matching without the game; identities are read live in rule order. */
public class NewAmmoBase {
    protected NewAmmoBase() {}

    private static final class CatalogPort {
        static final AmmoCatalog CATALOG = CoreServices.load(AmmoCatalog.class, AmmoCatalog.MISSING);
    }

    public static Object lookalike(Object c) {
        if (c == null) return null;
        return lookalike(c, CatalogPort.CATALOG);
    }

    static Object lookalike(Object c, AmmoCatalog catalog) {
        if (c == null) return null;
        if (c == catalog.duplex556()) return catalog.sp556();
        if (c == catalog.duplex762()) return catalog.sp762();
        if (catalog.rubber(c) || c == catalog.shock()) return catalog.slug();
        if (catalog.nuclear(c)) return catalog.sp50();
        return c;
    }

    public static Object waterLike(Object c) {
        return waterLike(c, CatalogPort.CATALOG);
    }

    static Object waterLike(Object c, AmmoCatalog catalog) {
        return catalog.nuclear(c) ? c : lookalike(c, catalog);
    }

    public static boolean noHole(Object c) {
        return c != null && noHole(c, CatalogPort.CATALOG);
    }

    static boolean noHole(Object c, AmmoCatalog catalog) {
        return c != null && (catalog.rubber(c) || catalog.nuclear(c) || c == catalog.shock());
    }
}
