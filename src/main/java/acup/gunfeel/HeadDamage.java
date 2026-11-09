package acup.gunfeel;

import java.lang.ref.WeakReference;
import java.util.UUID;
import com.hbm.entity.projectile.EntityBulletBaseMK4;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.HitResult;

/**
 * The head and limb multiplier of a shot (ModelHeads.multiplier) and the decision behind it, kept for the log even
 * after a killing hit.
 *
 * <p>An NTM bullet or beam (MixinHeadDamage) takes it after the armour: NTM's threshold and resistance (DT, DR) and
 * vanilla's armour first, then the part hit (the owner: a bullet goes through the armour, then hurts the body). The
 * multiplier is armed for the one hurt the bullet's NTM call makes ({@link #attack}) and applied in
 * LivingDamageEvent.Pre, which NeoForge fires after both. Before the armour, a limb's half of a bullet had the whole
 * threshold taken off it and a head's 1.75 kept it all: an FBI agent took one shot or more than five.
 */
public final class HeadDamage {
    private record Last(WeakReference<EntityBulletBaseMK4> bullet,UUID target,ModelHeads.Hit hit) {}
    private static final ThreadLocal<Last> LAST=new ThreadLocal<>();
    private record Pending(LivingEntity target,float multiplier) {}
    private static final ThreadLocal<Pending> PENDING=new ThreadLocal<>();
    private HeadDamage() {}

    public static void register() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOW,false,
                net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Pre.class,HeadDamage::afterArmor);
    }
    private static void afterArmor(net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Pre e) {
        var p=PENDING.get();
        if(p==null || p.target()!=e.getEntity()) return;
        PENDING.remove();
        e.setNewDamage(e.getNewDamage()*p.multiplier());
    }
    /** the NTM call that hurts the target with this damage */
    @FunctionalInterface public interface Hurt { boolean hurt(float damage); }
    /** the stand's switch: the multiplier on the damage before the armour, as it was until the owner moved it */
    public static volatile boolean standBeforeArmor=false;
    /** the bullet's hit on {@code target}: {@code hurt} with {@code damage}, its part's multiplier taken after the armour */
    public static boolean attack(LivingEntity target,float damage,float multiplier,Hurt hurt) {
        if(standBeforeArmor) return hurt.hurt(damage*multiplier);
        var previous=PENDING.get();
        if(multiplier!=1F) PENDING.set(new Pending(target,multiplier)); else PENDING.remove();
        try { return hurt.hurt(damage); }
        finally { if(previous!=null) PENDING.set(previous); else PENDING.remove(); }
    }
    /** an MK4 bullet's damage on {@code target} before the armour; its part's multiplier: {@link #multiplier} */
    public static float base(EntityBulletBaseMK4 bullet,LivingEntity target,HitResult impact,float original) {
        // a player's bullet: the part its client saw it hit (ClientHits)
        var hit=ClientHits.part(bullet,target);if(hit==null)hit=hit(target,impact);LAST.set(new Last(new WeakReference<>(bullet),target.getUUID(),hit));
        return hit.applied()?bullet.damage:original;
    }
    /** the multiplier of the part the last {@link #base} or {@link #beamMultiplier} found */
    public static float multiplier(EntityBulletBaseMK4 bullet,LivingEntity target) {
        var last=LAST.get();
        if(last==null || last.bullet().get()!=bullet || !last.target().equals(target.getUUID()) || !last.hit().applied()) return 1F;
        return ModelHeads.multiplier(last.hit().head(),last.hit().source().equals("limbs"));
    }
    public static float beamMultiplier(LivingEntity target,HitResult impact) {
        var hit=hit(target,impact);
        return hit.applied()?ModelHeads.multiplier(hit.head(),hit.source().equals("limbs")):1F;
    }
    private static ModelHeads.Hit hit(LivingEntity target,HitResult impact) {
        var hit=ModelHits.head(target,impact.getLocation());
        if(hit.applied() && (!hit.head() || hit.source().equals("height")) && ModelHits.limb(target,impact.getLocation()))
            return new ModelHeads.Hit(true,false,"limbs","проверенная конечность выше высотной догадки головы",1F);
        return hit;
    }
    public static ModelHeads.Hit last(EntityBulletBaseMK4 bullet,net.minecraft.world.entity.Entity target,net.minecraft.world.phys.Vec3 point) {
        var last=LAST.get();if(last!=null && last.bullet().get()==bullet && last.target().equals(target.getUUID())) return last.hit();
        return ModelHits.head(target,point);
    }
}
