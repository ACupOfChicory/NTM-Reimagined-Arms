package acup.gunfeel.ammo;

import java.util.function.BiConsumer;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.factory.XFactory556mm;
import com.hbm.items.weapon.sedna.factory.XFactory762mm;
import com.hbm.registration.RegistryHandle;

import acup.gunfeel.BalanceFinal;
import acup.gunfeel.DebugLog;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
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

    /** the front bullet of the shot under way on this thread (MixinDuplexShot: the next Duplex bullet is its rear one) */
    private static final ThreadLocal<EntityBulletBaseMK4> FRONT = new ThreadLocal<>();

    /** a shot begins: no front bullet yet */
    public static void shotStart() {
        FRONT.remove();
    }

    /**
     * a bullet of the shot about to be added to the world: a Duplex one either becomes the front one, or is the rear one
     * of the front before it and is set behind it, off its heading (DuplexRule.rear)
     */
    public static void place(Entity e) {
        if (!(e instanceof EntityBulletBaseMK4 b) || !is(b.config)) return;
        EntityBulletBaseMK4 front = FRONT.get();
        if (front == null || front.config != b.config || front.getThrower() != b.getThrower()) {
            FRONT.set(b);
            return;
        }
        FRONT.remove();
        Vec3 v = front.getDeltaMovement();
        double[] r = DuplexRule.rear(v.x, v.y, v.z, b.getRandom()::nextGaussian);
        b.setDeltaMovement(r[0], r[1], r[2]);
        double hyp = Math.sqrt(r[0] * r[0] + r[2] * r[2]);
        float yaw = (float) Math.toDegrees(Math.atan2(r[0], r[2])), pitch = (float) Math.toDegrees(Math.atan2(r[1], hyp));
        b.setYRot(yaw);
        b.yRotO = yaw;
        b.setXRot(pitch);
        b.xRotO = pitch;
        double len = v.length();
        Vec3 at = len < 1e-9 ? front.position() : front.position().subtract(v.scale(DuplexRule.BEHIND / len));
        b.setPos(at.x, at.y, at.z);
        b.xo = at.x;
        b.yo = at.y;
        b.zo = at.z;
        b.xOld = at.x;
        b.yOld = at.y;
        b.zOld = at.z;
    }
}
