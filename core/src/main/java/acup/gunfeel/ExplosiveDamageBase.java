package acup.gunfeel;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import acup.gunfeel.host.ExplosiveShots;

/** The context lives only inside a bullet handler, not around every world explosion. */
public class ExplosiveDamageBase {
    public static final class Context {
        final Object bullet;
        final Object impact;
        boolean exploded;
        final int depth;
        Context(Object bullet,Object impact){this.bullet=bullet;this.impact=impact;this.depth=DEPTH.get();}
    }
    public static final class Blast {
        final Context context;
        final float base;
        final Object explosion;
        boolean contactPaid;
        Blast(Context context,float base,Object explosion){this.context=context;this.base=base;this.explosion=explosion;}
    }
    private static final ThreadLocal<Context> CURRENT=new ThreadLocal<>();
    private static final ThreadLocal<Boolean> SUPPRESS=ThreadLocal.withInitial(()->false);
    private static final ThreadLocal<Integer> DEPTH=ThreadLocal.withInitial(()->0);
    protected ExplosiveDamageBase() {}
    private static final class ShotsPort {
        static final ExplosiveShots SHOTS=CoreServices.load(ExplosiveShots.class,ExplosiveShots.MISSING);
    }
    public static boolean suppressed(){return SUPPRESS.get();}
    public static Context current(){return CURRENT.get();}
    /** the bullet whose impact is being dealt now (an explosive bullet's blast comes with no bullet of its own), or null */
    public static Object bulletNow(){var c=CURRENT.get();return c==null?null:c.bullet;}
    /** the creature the bullet whose impact is being dealt now struck, or null (a block, none) */
    public static Object struckNow(){return struckNow(ShotsPort.SHOTS);}
    static Object struckNow(ExplosiveShots shots){var c=CURRENT.get();return c!=null?shots.struck(c.impact):null;}
    public static boolean impact(BiConsumer<Object,Object> callback,Object bullet,Object hit,boolean announce){
        return impact(callback,bullet,hit,announce,ShotsPort.SHOTS);
    }
    static boolean impact(BiConsumer<Object,Object> callback,Object bullet,Object hit,boolean announce,ExplosiveShots shots){
        if(shots.client(bullet)){callback.accept(bullet,hit);return false;}
        int previousDepth=DEPTH.get();
        var previous=CURRENT.get();CURRENT.set(new Context(bullet,hit));
        try {
            callback.accept(bullet,hit);
            // Only an actually triggered fuze: no hole on a bounce or thrower protection.
            // ... and not on water (the surface fuze's hit)
            if(announce && shots.removed(bullet) && shots.heavy(shots.config(bullet)))return shots.hole(bullet,hit);
            return false;
        } finally {DEPTH.set(previousDepth);if(previous==null)CURRENT.remove();else CURRENT.set(previous);}
    }
    public static void update(Consumer<Object> callback,Object bullet){update(callback,bullet,ShotsPort.SHOTS);}
    static void update(Consumer<Object> callback,Object bullet,ExplosiveShots shots){
        if(shots.client(bullet)){callback.accept(bullet);return;}
        var previous=CURRENT.get();CURRENT.set(new Context(bullet,null));
        int previousDepth=DEPTH.get();
        try{callback.accept(bullet);}finally{DEPTH.set(previousDepth);if(previous==null)CURRENT.remove();else CURRENT.set(previous);}
    }
    public static void enter(){if(CURRENT.get()!=null)DEPTH.set(DEPTH.get()+1);}
    public static void exit(){if(CURRENT.get()!=null)DEPTH.set(Math.max(0,DEPTH.get()-1));}
    public static Blast bind(Object explosion,float base){return bind(explosion,base,ShotsPort.SHOTS);}
    static Blast bind(Object explosion,float base,ExplosiveShots shots){
        var context=CURRENT.get();
        if(context==null || suppressed() || DEPTH.get()!=context.depth || shots.config(context.bullet)==null
                || shots.level(context.bullet)!=shots.world(explosion) || shots.thrower(context.bullet)!=shots.exploder(explosion))return null;
        context.exploded=true;return new Blast(context,base,explosion);
    }
    /** the round or bullet the blast is bound to (its impact set it off), or null */
    public static Object bulletOf(Blast blast){return blast==null?null:blast.context.bullet;}
    /** the blast's round struck {@code target} and its contact is not paid yet (call before damage()) */
    public static boolean struck(Blast blast,Object target){return struck(blast,target,ShotsPort.SHOTS);}
    static boolean struck(Blast blast,Object target,ExplosiveShots shots){
        if(blast==null || blast.contactPaid)return false;
        Object hit=shots.struck(blast.context.impact);return hit!=null && hit==target;
    }
    /** the blast is a launcher's round's (40 mm, rocket), not an explosive bullet's */
    public static boolean launched(Blast blast){return launched(blast,ShotsPort.SHOTS);}
    static boolean launched(Blast blast,ExplosiveShots shots){return blast!=null && shots.heavy(shots.config(blast.context.bullet));}
    /** a rocket's blast on the creature the rocket struck, before its contact share is paid (BalanceArmor's power by tier) */
    public static boolean rocketDirect(Blast blast,Object target){return rocketDirect(blast,target,ShotsPort.SHOTS);}
    static boolean rocketDirect(Blast blast,Object target,ExplosiveShots shots){
        if(blast==null || blast.contactPaid || !shots.rocket(shots.config(blast.context.bullet)))return false;
        Object hit=shots.struck(blast.context.impact);return hit!=null && hit==target;
    }
    /** a blast with no bound bullet: none being dealt (a hand grenade), or the bullet now is a launcher's round */
    public static boolean launchedNow(){return launchedNow(ShotsPort.SHOTS);}
    static boolean launchedNow(ExplosiveShots shots){var c=CURRENT.get();return c==null || shots.heavy(shots.config(c.bullet));}
    /** the blast's share of an explosive bullet's damage, out of NTM's {@code original} */
    public static float area(float original){return ExplosiveRule.area(original);}
    public static float damage(Blast blast,Object target,float original){return damage(blast,target,original,ShotsPort.SHOTS);}
    static float damage(Blast blast,Object target,float original,ExplosiveShots shots){
        if(blast==null)return original;
        Object hit=shots.struck(blast.context.impact);
        boolean direct=hit!=null && hit==target && !blast.contactPaid;
        // a blast hits no part of the body: no head's or limb's multiplier
        if(direct)blast.contactPaid=true;
        float base=blast.base*ExplosiveRule.onSelf(target==shots.exploder(blast.explosion));
        return ExplosiveRule.total(original,base,1,direct);
    }
    public static boolean attack(Object target,Object source,float damage,boolean first,boolean second,double knockback,float threshold,float piercing){
        return attack(target,source,damage,first,second,knockback,threshold,piercing,ShotsPort.SHOTS);
    }
    static boolean attack(Object target,Object source,float damage,boolean first,boolean second,double knockback,float threshold,float piercing,ExplosiveShots shots){
        boolean previous=SUPPRESS.get();SUPPRESS.set(true);
        try{return shots.attack(target,source,damage,first,second,knockback,threshold,piercing);}
        finally{SUPPRESS.set(previous);}
    }
    public static void finish(Blast blast){finish(blast,ShotsPort.SHOTS);}
    static void finish(Blast blast,ExplosiveShots shots){
        if(blast==null || blast.contactPaid)return;
        Object target=shots.struck(blast.context.impact);
        if(target==null || !shots.living(target) || !shots.alive(target))return;
        // A large model's visible part can be outside the vanilla blast box.
        blast.contactPaid=true;var bullet=blast.context.bullet;
        float damage=blast.base*ExplosiveRule.CONTACT;
        shots.contact(bullet,target,damage);
    }
}
