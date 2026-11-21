package acup.gunfeel.ammo;

import java.util.function.BiConsumer;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.factory.XFactory556mm;
import com.hbm.items.weapon.sedna.factory.XFactory762mm;
import com.hbm.registration.RegistryHandle;

import acup.gunfeel.BalanceFinal;
import acup.gunfeel.DebugLog;
import acup.gunfeel.host.DuplexShots;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.IEventBus;

/**
 * The Duplex rounds, 5.56 and 7.62: two bullets a shot (DuplexRule), each the same gun's SP round in all but its damage,
 * DuplexRule.SHARE of the SP's (with the balance: the scheme's SP pair of the gun, BalanceFinal.shareOf). Go where NTM's
 * SP round of the calibre goes. The rear bullet is set by MixinDuplexShot as the shot adds it to the world.
 */
public final class Duplex {
    public static RegistryHandle<Item> R556, R762;
    private static BulletConfig r556, r762;

    private Duplex() {
    }

    public static void register(IEventBus modBus) {
        R556 = NewAmmo.item(modBus, "ammo_r556_duplex");
        R762 = NewAmmo.item(modBus, "ammo_r762_duplex");
        modBus.addListener(net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent.class, e -> e.enqueueWork(Duplex::setup));
    }

    public static BulletConfig r556() {
        return r556;
    }

    public static BulletConfig r762() {
        return r762;
    }

    public static boolean is(BulletConfig c) {
        return c != null && (c == r556 || c == r762);
    }

    static synchronized void setup() {
        if (r556 != null) return;
        r556 = make(XFactory556mm.r556_sp, R556);
        r762 = make(XFactory762mm.r762_sp, R762);
        int a = NewAmmo.addTo(r556, g -> true, acc -> acc.contains(XFactory556mm.r556_sp));
        int b = NewAmmo.addTo(r762, g -> true, acc -> acc.contains(XFactory762mm.r762_sp));
        DebugLog.LOGGER.info("The Duplex rounds: configs {} and {}, in {} and {} magazines", r556.id, r762.id, a, b);
    }

    // the SP round with its item, two bullets, each hit SHARE of the SP's
    private static BulletConfig make(BulletConfig sp, RegistryHandle<Item> item) {
        BulletConfig c = sp.clone().setItem(() -> item.get()).setProjectiles(2);
        BiConsumer<EntityBulletBaseMK4, HitResult> hit = sp.onEntityHit;
        c.onEntityHit = (bullet, mop) -> {
            bullet.damage *= BalanceFinal.shareOf(bullet, bullet.getThrower(), c, sp, DuplexRule.SHARE);
            if (hit != null) hit.accept(bullet, mop);
        };
        return c;
    }

    /** a shot begins: no front bullet yet */
    public static void shotStart() {
        DuplexBase.shotStart();
    }

    /**
     * a bullet of the shot about to be added to the world: a Duplex one either becomes the front one, or is the rear one
     * of the front before it and is set behind it, off its heading (DuplexRule.rear)
     */
    public static void place(Entity e) {
        DuplexBase.place(e);
    }

    /** 26.2's live bullets for the Duplex pair. */
    public static final class Reads implements DuplexShots {
        @Override public boolean duplex(Object e) { return e instanceof EntityBulletBaseMK4 b && is(b.config); }
        @Override public Object config(Object b) { return ((EntityBulletBaseMK4) b).config; }
        @Override public Object thrower(Object b) { return ((EntityBulletBaseMK4) b).getThrower(); }
        @Override public double mx(Object b) { return ((EntityBulletBaseMK4) b).getDeltaMovement().x; }
        @Override public double my(Object b) { return ((EntityBulletBaseMK4) b).getDeltaMovement().y; }
        @Override public double mz(Object b) { return ((EntityBulletBaseMK4) b).getDeltaMovement().z; }
        @Override public double px(Object b) { return ((EntityBulletBaseMK4) b).position().x; }
        @Override public double py(Object b) { return ((EntityBulletBaseMK4) b).position().y; }
        @Override public double pz(Object b) { return ((EntityBulletBaseMK4) b).position().z; }
        @Override public double gaussian(Object b) { return ((EntityBulletBaseMK4) b).getRandom().nextGaussian(); }

        @Override
        public void write(Object rear, double mx, double my, double mz, float yaw, float pitch, double ax, double ay, double az) {
            EntityBulletBaseMK4 b = (EntityBulletBaseMK4) rear;
            b.setDeltaMovement(mx, my, mz);
            b.setYRot(yaw);
            b.yRotO = yaw;
            b.setXRot(pitch);
            b.xRotO = pitch;
            b.setPos(ax, ay, az);
            b.xo = ax;
            b.yo = ay;
            b.zo = az;
            b.xOld = ax;
            b.yOld = ay;
            b.zOld = az;
        }
    }
}
