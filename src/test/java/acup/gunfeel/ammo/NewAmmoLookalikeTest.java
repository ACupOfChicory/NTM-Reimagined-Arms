package acup.gunfeel.ammo;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.factory.XFactory12ga;
import com.hbm.items.weapon.sedna.factory.XFactory50;
import com.hbm.items.weapon.sedna.factory.XFactory556mm;
import com.hbm.items.weapon.sedna.factory.XFactory762mm;

/**
 * The mod's rounds are found as the NTM round they are made after where a thing is looked up by the round itself
 * (NTM's tracer, the water's ammo name), and the rubber slug and the nuclear .50 leave no hole. Configs made without
 * their constructor (it needs the game), set into the static fields the setups fill.
 */
class NewAmmoLookalikeTest {
    private static final sun.misc.Unsafe U;

    static {
        try {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            U = (sun.misc.Unsafe) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static BulletConfig config() throws InstantiationException {
        return (BulletConfig) U.allocateInstance(BulletConfig.class);
    }

    private static Object put(Class<?> c, String field, Object value) throws ReflectiveOperationException {
        Field f = c.getDeclaredField(field);
        f.setAccessible(true);
        Object was = f.get(null);
        f.set(null, value);
        return was;
    }

    private BulletConfig d556, d762, rubber, nuke, sp556, sp762, slug, sp50;
    private Object[] was;

    @BeforeEach
    void set() throws ReflectiveOperationException {
        d556 = config();
        d762 = config();
        rubber = config();
        nuke = config();
        sp556 = config();
        sp762 = config();
        slug = config();
        sp50 = config();
        was = new Object[] {put(Duplex.class, "r556", d556), put(Duplex.class, "r762", d762), put(RubberSlug.class, "config", rubber),
                put(NuclearBmg.class, "config", nuke), put(XFactory556mm.class, "r556_sp", sp556), put(XFactory762mm.class, "r762_sp", sp762),
                put(XFactory12ga.class, "g12_slug", slug), put(XFactory50.class, "bmg50_sp", sp50)};
    }

    @AfterEach
    void back() throws ReflectiveOperationException {
        put(Duplex.class, "r556", was[0]);
        put(Duplex.class, "r762", was[1]);
        put(RubberSlug.class, "config", was[2]);
        put(NuclearBmg.class, "config", was[3]);
        put(XFactory556mm.class, "r556_sp", was[4]);
        put(XFactory762mm.class, "r762_sp", was[5]);
        put(XFactory12ga.class, "g12_slug", was[6]);
        put(XFactory50.class, "bmg50_sp", was[7]);
    }

    @Test
    void eachNewRoundLooksLikeItsNtmRound() {
        assertSame(sp556, NewAmmo.lookalike(d556));
        assertSame(sp762, NewAmmo.lookalike(d762));
        assertSame(slug, NewAmmo.lookalike(rubber));
        assertSame(sp50, NewAmmo.lookalike(nuke));
    }

    @Test
    void anyOtherRoundIsItself() throws InstantiationException {
        BulletConfig other = config();
        assertSame(other, NewAmmo.lookalike(other));
        assertSame(sp556, NewAmmo.lookalike(sp556));
        assertNull(NewAmmo.lookalike(null));
    }

    @Test
    void inWaterTheNuclearRoundStaysItself() {
        assertSame(nuke, NewAmmo.waterLike(nuke));
        assertSame(sp556, NewAmmo.waterLike(d556));
        assertSame(slug, NewAmmo.waterLike(rubber));
    }

    @Test
    void onlyTheRubberSlugAndTheNuclearRoundLeaveNoHole() {
        assertTrue(NewAmmo.noHole(rubber));
        assertTrue(NewAmmo.noHole(nuke));
        assertFalse(NewAmmo.noHole(d556));
        assertFalse(NewAmmo.noHole(d762));
        assertFalse(NewAmmo.noHole(sp556));
        assertFalse(NewAmmo.noHole(null));
    }
}
