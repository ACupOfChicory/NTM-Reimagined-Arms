package acup.gunfeel;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import acup.gunfeel.host.ExplosiveShots;

/** The old class with game reads replaced by the fake port; decisions and save order stay verbatim. */
final class ExplosiveDamageOld {
    final ThreadLocal<Context> CURRENT=new ThreadLocal<>();
    final ThreadLocal<Boolean> SUPPRESS=ThreadLocal.withInitial(()->false);
    final ThreadLocal<Integer> DEPTH=ThreadLocal.withInitial(()->0);
    final ExplosiveShots shots;
    ExplosiveDamageOld(ExplosiveShots shots){this.shots=shots;}
    final class Context {
        final Object bullet,impact;
        boolean exploded;
        final int depth;
        Context(Object bullet,Object impact){this.bullet=bullet;this.impact=impact;this.depth=DEPTH.get();}
    }
    static final class Blast {
        final Context context;
        final float base;
        final Object explosion;
        boolean contactPaid;
        Blast(Context context,float base,Object explosion){this.context=context;this.base=base;this.explosion=explosion;}
    }
    boolean suppressed(){return SUPPRESS.get();}
    Context current(){return CURRENT.get();}
    Object bulletNow(){var c=CURRENT.get();return c==null?null:c.bullet;}
    Object struckNow(){var c=CURRENT.get();return c!=null?shots.struck(c.impact):null;}
    boolean impact(BiConsumer<Object,Object> callback,Object bullet,Object hit,boolean announce){
        if(shots.client(bullet)){callback.accept(bullet,hit);return false;}
        int previousDepth=DEPTH.get();
        var previous=CURRENT.get();CURRENT.set(new Context(bullet,hit));
        try {
            callback.accept(bullet,hit);
            // Only an actually triggered fuze: no hole on a bounce or thrower protection.
            // ... and not on water (the surface fuze's hit)
            if(announce && shots.removed(bullet) && shots.heavy(shots.config(bullet)) && shots.hole(bullet,hit))return true;
            return false;
        } finally {DEPTH.set(previousDepth);if(previous==null)CURRENT.remove();else CURRENT.set(previous);}
    }
    void update(Consumer<Object> callback,Object bullet){
        if(shots.client(bullet)){callback.accept(bullet);return;}
        var previous=CURRENT.get();CURRENT.set(new Context(bullet,null));
        int previousDepth=DEPTH.get();
        try{callback.accept(bullet);}finally{DEPTH.set(previousDepth);if(previous==null)CURRENT.remove();else CURRENT.set(previous);}
    }
    void enter(){if(CURRENT.get()!=null)DEPTH.set(DEPTH.get()+1);}
    void exit(){if(CURRENT.get()!=null)DEPTH.set(Math.max(0,DEPTH.get()-1));}
    Blast bind(Object explosion,float base){
        var context=CURRENT.get();
        if(context==null || suppressed() || DEPTH.get()!=context.depth || shots.config(context.bullet)==null
                || shots.level(context.bullet)!=shots.world(explosion) || shots.thrower(context.bullet)!=shots.exploder(explosion))return null;
        context.exploded=true;return new Blast(context,base,explosion);
    }
    Object bulletOf(Blast blast){return blast==null?null:blast.context.bullet;}
    boolean struck(Blast blast,Object target){
        if(blast==null || blast.contactPaid)return false;
        Object hit=shots.struck(blast.context.impact);return hit!=null && hit==target;
    }
    boolean launched(Blast blast){return blast!=null && shots.heavy(shots.config(blast.context.bullet));}
    boolean rocketDirect(Blast blast,Object target){
        if(blast==null || blast.contactPaid || !shots.rocket(shots.config(blast.context.bullet)))return false;
        Object hit=shots.struck(blast.context.impact);return hit!=null && hit==target;
    }
    boolean launchedNow(){var c=CURRENT.get();return c==null || shots.heavy(shots.config(c.bullet));}
    float area(float original){return ExplosiveRule.area(original);}
    float damage(Blast blast,Object target,float original){
        if(blast==null)return original;
        Object hit=shots.struck(blast.context.impact);
        boolean direct=hit!=null && hit==target && !blast.contactPaid;
        // a blast hits no part of the body: no head's or limb's multiplier
        if(direct)blast.contactPaid=true;
        float base=blast.base*ExplosiveRule.onSelf(target==shots.exploder(blast.explosion));
        return ExplosiveRule.total(original,base,1,direct);
    }
    boolean attack(Object target,Object source,float damage,boolean first,boolean second,double knockback,float threshold,float piercing){
        boolean previous=SUPPRESS.get();SUPPRESS.set(true);
        try{return shots.attack(target,source,damage,first,second,knockback,threshold,piercing);}
        finally{SUPPRESS.set(previous);}
    }
    void finish(Blast blast){
        if(blast==null || blast.contactPaid)return;
        Object target=shots.struck(blast.context.impact);
        if(target==null || !shots.living(target) || !shots.alive(target))return;
        // A large model's visible part can be outside the vanilla blast box.
        blast.contactPaid=true;var bullet=blast.context.bullet;
        float damage=blast.base*ExplosiveRule.CONTACT;
        shots.contact(bullet,target,damage);
    }
}
