package acup.gunfeel.gl54;

import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.mags.IMagazine;
import com.hbm.items.weapon.sedna.mags.MagazineFullReload;

// TEMPORARY. The GL-54 takes a grenade item that is only for trying it (gl54_grenade_test, in no creative tab): a box
// magazine of 8 that fills all at once, as NTM's MK108 does. Stage 2 of the GL-54 (notes/gl54/mag-system.md) replaces
// it with the 20 mm magazine as the ammunition: only this class changes then, the gun asks it for `magazine()`.
public final class Gl54Ammo {
    public static final int CAPACITY = 8;
    private static BulletConfig he;

    private Gl54Ammo() {
    }

    // the high-explosive grenade, made once
    public static synchronized BulletConfig he() {
        if (he == null) he = Gl54Grenade.create(() -> Gl54Items.GRENADE_TEST.get());
        return he;
    }

    public static IMagazine<BulletConfig> magazine() {
        return new MagazineFullReload(0, CAPACITY).addConfigs(he());
    }
}
