package acup.gunfeel.ammo;

import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.factory.XFactoryFlamer;

import acup.gunfeel.DebugLog;
import net.neoforged.bus.api.IEventBus;

/**
 * Both sides, at load: Mister Topaz's diesel, napalm and balefire and the Fritz turret's two flames get FlamerArc's speed,
 * gravity and life. Topaz's gas (a burner's flame, NTM's 10 ticks with no fall), the hand flamer and the Daybreaker
 * stay NTM's. A config NTM has changed from the straight beam this was made for is left as it is; the Fritz aims for the
 * arc (MixinFritzArc) only when its flames fall.
 */
public final class FlamerGravity {
    private static volatile boolean fritz;

    private FlamerGravity() {
    }

    public static void register(IEventBus modBus) {
        modBus.addListener(net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent.class, e -> e.enqueueWork(FlamerGravity::setup));
    }

    /** the Fritz's flames fall (FlamerArc), so it aims for the arc */
    public static boolean fritz() {
        return fritz;
    }

    static synchronized void setup() {
        if (fritz) return;
        // the turret's classes now, so MixinFritzArc is applied (a failed mixin turns the system off) before a flame falls
        try {
            Class.forName("com.hbm.blockentity.turret.BlockEntityTurretFritz", false, FlamerGravity.class.getClassLoader());
        } catch (Throwable t) {
            DebugLog.LOGGER.warn("Flamer gravity: NTM's Fritz turret not loaded, flames left as NTM's", t);
            return;
        }
        if (!acup.gunfeel.compat.Feature.FLAMER_ARC.on()) return;
        int topaz = 0;
        for (BulletConfig c : new BulletConfig[] {XFactoryFlamer.flame_topaz_diesel, XFactoryFlamer.flame_topaz_napalm,
                XFactoryFlamer.flame_topaz_balefire}) {
            if (!beam(c, 60)) continue;
            arc(c, FlamerArc.TOPAZ_LIFE);
            topaz++;
        }
        // both or neither: a falling flame the turret aimed at in a straight line would fall short
        BulletConfig diesel = XFactoryFlamer.flame_nograv, balefire = XFactoryFlamer.flame_nograv_bf;
        if (beam(diesel, 100) && beam(balefire, 100)) {
            arc(diesel, FlamerArc.FRITZ_LIFE);
            arc(balefire, FlamerArc.FRITZ_BALEFIRE_LIFE);
            fritz = true;
        }
        DebugLog.LOGGER.info("Flamer gravity: {} of Topaz's 3 flames fall, the Fritz's {} (speed {}, drop {} a tick²)",
                topaz, fritz ? "too" : "not", FlamerArc.SPEED, FlamerArc.FALL);
    }

    // NTM's straight beam this was made for: speed 1, no gravity, this life
    static boolean beam(BulletConfig c, int life) {
        return c != null && c.velocity == 1F && c.gravity == 0 && c.expires == life;
    }

    static void arc(BulletConfig c, int life) {
        c.setVel(FlamerArc.SPEED).setGrav(FlamerArc.GRAVITY).setLife(life);
    }
}
