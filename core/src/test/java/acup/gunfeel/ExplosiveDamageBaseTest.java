package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.ref.Reference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import acup.gunfeel.host.ExplosiveShots;

/** Differential traces against the pre-port class, including entry presence in ThreadLocalMap. */
class ExplosiveDamageBaseTest {
    private record Token(String name) {}
    private static final class Config {
        boolean heavy=true,rocket=true;
        float threshold=Float.intBitsToFloat(0x3ec00001),piercing=-0F;
        @Override public String toString(){return "config";}
    }
    private static final class Bullet {
        final String name;
        Object world,thrower;
        Config config=new Config();
        boolean client,removed=true;
        Bullet(String name,Object world,Object thrower){this.name=name;this.world=world;this.thrower=thrower;}
        @Override public String toString(){return name;}
    }
    private static final class Target {
        boolean living=true,alive=true;
        @Override public String toString(){return "target";}
    }
    private record Hit(Object target,boolean hole) {}
    private record Explosion(Object world,Object exploder) {}
    private record Slot(boolean present,Object value) {}
    private static final RuntimeException FAILURE=new IllegalStateException("probe");

    @SuppressWarnings("unchecked")
    private static ThreadLocal<Object> local(String name) {
        try {
            Field f=ExplosiveDamageBase.class.getDeclaredField(name);f.setAccessible(true);
            return (ThreadLocal<Object>)f.get(null);
        } catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }

    // Read the map without ThreadLocal.get(): get() itself would insert an absent initial value.
    private static Object rawField(Object object,Field field) throws ReflectiveOperationException {
        Class<?> type=Class.forName("sun.misc.Unsafe");
        Field f=type.getDeclaredField("theUnsafe");f.setAccessible(true);Object unsafe=f.get(null);
        long offset=(long)type.getMethod("objectFieldOffset",Field.class).invoke(unsafe,field);
        return type.getMethod("getObject",Object.class,long.class).invoke(unsafe,object,offset);
    }
    private static Slot slot(ThreadLocal<?> key) {
        try {
            Object map=rawField(Thread.currentThread(),Thread.class.getDeclaredField("threadLocals"));
            if(map==null)return new Slot(false,null);
            Object[] table=(Object[])rawField(map,map.getClass().getDeclaredField("table"));
            for(Object entry:table)if(entry!=null && ((Reference<?>)entry).get()==key)
                return new Slot(true,rawField(entry,entry.getClass().getDeclaredField("value")));
            return new Slot(false,null);
        } catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    private static Object field(Object object,String name) {
        try {Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
        catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    private static String bits(float f){return Integer.toHexString(Float.floatToRawIntBits(f));}

    private static final class Engine {
        final Fake shots=new Fake();
        final ExplosiveDamageOld old;
        final List<String> trace=shots.log;
        Engine(boolean reference){old=reference?new ExplosiveDamageOld(shots):null;shots.engine=this;clear();}
        ThreadLocal<?> currentKey(){return old==null?local("CURRENT"):old.CURRENT;}
        ThreadLocal<?> depthKey(){return old==null?local("DEPTH"):old.DEPTH;}
        ThreadLocal<?> suppressKey(){return old==null?local("SUPPRESS"):old.SUPPRESS;}
        void clear(){currentKey().remove();depthKey().remove();suppressKey().remove();}
        void depth(int n){if(old==null)local("DEPTH").set(n);else old.DEPTH.set(n);}
        void suppress(boolean b){if(old==null)local("SUPPRESS").set(b);else old.SUPPRESS.set(b);}
        boolean suppressed(){return old==null?ExplosiveDamageBase.suppressed():old.suppressed();}
        Object current(){return old==null?ExplosiveDamageBase.current():old.current();}
        Object bulletNow(){return old==null?ExplosiveDamageBase.bulletNow():old.bulletNow();}
        Object struckNow(){return old==null?ExplosiveDamageBase.struckNow(shots):old.struckNow();}
        boolean impact(BiConsumer<Object,Object> cb,Bullet b,Hit h,boolean a){
            return old==null?ExplosiveDamageBase.impact(cb,b,h,a,shots):old.impact(cb,b,h,a);
        }
        void update(Consumer<Object> cb,Bullet b){if(old==null)ExplosiveDamageBase.update(cb,b,shots);else old.update(cb,b);}
        void enter(){if(old==null)ExplosiveDamageBase.enter();else old.enter();}
        void exit(){if(old==null)ExplosiveDamageBase.exit();else old.exit();}
        Object bind(Explosion x,float base){return old==null?ExplosiveDamageBase.bind(x,base,shots):old.bind(x,base);}
        Object bulletOf(Object b){return old==null?ExplosiveDamageBase.bulletOf((ExplosiveDamageBase.Blast)b):old.bulletOf((ExplosiveDamageOld.Blast)b);}
        boolean struck(Object b,Object t){return old==null?ExplosiveDamageBase.struck((ExplosiveDamageBase.Blast)b,t,shots):old.struck((ExplosiveDamageOld.Blast)b,t);}
        boolean launched(Object b){return old==null?ExplosiveDamageBase.launched((ExplosiveDamageBase.Blast)b,shots):old.launched((ExplosiveDamageOld.Blast)b);}
        boolean rocket(Object b,Object t){return old==null?ExplosiveDamageBase.rocketDirect((ExplosiveDamageBase.Blast)b,t,shots):old.rocketDirect((ExplosiveDamageOld.Blast)b,t);}
        boolean launchedNow(){return old==null?ExplosiveDamageBase.launchedNow(shots):old.launchedNow();}
        float area(float f){return old==null?ExplosiveDamageBase.area(f):old.area(f);}
        float damage(Object b,Object t,float f){return old==null?ExplosiveDamageBase.damage((ExplosiveDamageBase.Blast)b,t,f,shots):old.damage((ExplosiveDamageOld.Blast)b,t,f);}
        void finish(Object b){if(old==null)ExplosiveDamageBase.finish((ExplosiveDamageBase.Blast)b,shots);else old.finish((ExplosiveDamageOld.Blast)b);}
        boolean attack(Object t,Object s,float d,boolean a,boolean b,double k,float th,float p){
            return old==null?ExplosiveDamageBase.attack(t,s,d,a,b,k,th,p,shots):old.attack(t,s,d,a,b,k,th,p);
        }
        String snapshot(){
            Slot c=slot(currentKey());Object v=c.value;
            return "C="+c.present+":"+(v==null?null:field(v,"bullet")+"/"+field(v,"impact")+"/"+field(v,"depth")+"/"+field(v,"exploded"))
                    +" D="+slot(depthKey())+" S="+slot(suppressKey());
        }
        void record(String name){trace.add(name+":"+snapshot());}
    }
    private static final class Fake implements ExplosiveShots {
        Engine engine;
        final List<String> log=new ArrayList<>();
        Runnable onExploder=()->{},onAttack=()->{},onContact=()->{},onHole=()->{};
        private <T> T read(String op,T value){log.add(op+"="+value);return value;}
        @Override public boolean client(Object b){return read("client:"+b,((Bullet)b).client);}
        @Override public Object config(Object b){return read("config:"+b,((Bullet)b).config);}
        @Override public Object thrower(Object b){return read("thrower:"+b,((Bullet)b).thrower);}
        @Override public Object level(Object b){return read("level:"+b,((Bullet)b).world);}
        @Override public Object world(Object x){return read("world",((Explosion)x).world);}
        @Override public Object exploder(Object x){Object v=read("exploder",((Explosion)x).exploder);onExploder.run();return v;}
        @Override public Object struck(Object h){return read("struck",h instanceof Hit hit?hit.target:null);}
        @Override public boolean heavy(Object c){return read("heavy",c!=null && ((Config)c).heavy);}
        @Override public boolean rocket(Object c){return read("rocket",c!=null && ((Config)c).rocket);}
        @Override public boolean removed(Object b){return read("removed:"+b,((Bullet)b).removed);}
        @Override public boolean hole(Object b,Object h){
            engine.record("hole:"+b);onHole.run();return h instanceof Hit hit && hit.hole;
        }
        @Override public boolean living(Object t){return read("living",t instanceof Target target && target.living);}
        @Override public boolean alive(Object t){return read("alive",((Target)t).alive);}
        @Override public boolean attack(Object t,Object s,float d,boolean a,boolean b,double k,float th,float p){
            log.add("attack:"+t+":"+s+":"+bits(d)+":"+a+":"+b+":"+Long.toHexString(Double.doubleToRawLongBits(k))+":"+bits(th)+":"+bits(p));
            engine.record("attack");assertTrue(engine.suppressed());onAttack.run();return true;
        }
        @Override public void contact(Object raw,Object t,float d){
            log.add("contact:"+raw+":"+t+":"+bits(d));onContact.run();
            Bullet b=(Bullet)raw;
            Object source=thrower(b);
            engine.attack(t,source,d,true,true,0,b.config.threshold,b.config.piercing);
        }
    }
    private record Scene(Bullet bullet,Target target,Hit hit,Explosion blast) {
        static Scene make(){
            Object world=new Token("world"),thrower=new Token("thrower");
            Bullet b=new Bullet("bullet",world,thrower);Target t=new Target();
            return new Scene(b,t,new Hit(t,false),new Explosion(world,thrower));
        }
    }
    private static void compare(Consumer<Engine> scenario){
        List<String> expected;
        Engine old=new Engine(true);
        try {scenario.accept(old);old.record("after");expected=List.copyOf(old.trace);}finally{old.clear();}
        Engine core=new Engine(false);
        try {scenario.accept(core);core.record("after");assertEquals(expected,core.trace);}finally{core.clear();}
    }
    @AfterEach void clear(){local("CURRENT").remove();local("DEPTH").remove();local("SUPPRESS").remove();}

    @Test void nestedImpactUpdateExplodeRestoresIdentityAndDepth(){compare(e->{
        Scene s=Scene.make();Bullet inner=new Bullet("inner",s.bullet.world,s.bullet.thrower);
        assertFalse(e.impact((b,h)->{
            Object outer=e.current();assertSame(b,e.bulletNow());assertSame(s.target,e.struckNow());
            e.enter();assertNull(e.bind(s.blast,10));
            e.update(q->{
                assertSame(inner,e.bulletNow());assertNull(e.struckNow());
                Object nested=e.current();assertNotNull(e.bind(s.blast,10));e.enter();
                assertFalse(e.impact((bb,hh)->{
                    assertSame(s.bullet,e.bulletNow());assertNotNull(e.bind(s.blast,10));
                    e.enter();assertNull(e.bind(s.blast,10));e.exit();e.exit();e.exit();e.exit();
                    assertEquals(0,slot(e.depthKey()).value());
                },s.bullet,s.hit,false));
                assertSame(nested,e.current());assertEquals(2,slot(e.depthKey()).value());
            },inner);
            assertSame(outer,e.current());assertEquals(1,slot(e.depthKey()).value());e.exit();
            assertNotNull(e.bind(s.blast,10));e.record("outer");
        },s.bullet,s.hit,false));
        assertFalse(slot(e.currentKey()).present());assertEquals(new Slot(true,0),slot(e.depthKey()));
    });}

    @Test void bindGuardsShortCircuitAndUseIdentity(){compare(e->{
        Scene s=Scene.make();assertNull(e.bind(s.blast,10));assertTrue(e.trace.isEmpty());
        e.impact((b,h)->{
            e.suppress(true);int n=e.trace.size();assertNull(e.bind(s.blast,10));assertEquals(n,e.trace.size());e.suppress(false);
            e.enter();n=e.trace.size();assertNull(e.bind(s.blast,10));assertEquals(n,e.trace.size());e.exit();
            Config c=s.bullet.config;s.bullet.config=null;
            n=e.trace.size();assertNull(e.bind(s.blast,10));assertEquals(List.of("config:bullet=null"),e.trace.subList(n,e.trace.size()));s.bullet.config=c;
            n=e.trace.size();assertNull(e.bind(new Explosion(new Token("world"),s.bullet.thrower),10));
            assertEquals(List.of("config:bullet=config","level:bullet=Token[name=world]","world=Token[name=world]"),e.trace.subList(n,e.trace.size()));
            assertNull(e.bind(new Explosion(s.bullet.world,new Token("thrower")),10));
            assertFalse((boolean)field(e.current(),"exploded"));
            Object blast=e.bind(s.blast,10);assertNotNull(blast);assertSame(s.bullet,e.bulletOf(blast));assertTrue((boolean)field(e.current(),"exploded"));
        },s.bullet,s.hit,false);
    });}

    @Test void clientCallbacksDoNotCreateContextOrDepth(){compare(e->{
        Scene s=Scene.make();s.bullet.client=true;
        assertFalse(e.impact((b,h)->{assertSame(s.bullet,b);assertSame(s.hit,h);e.record("client-impact");},s.bullet,s.hit,true));
        e.update(b->{assertSame(s.bullet,b);e.record("client-update");},s.bullet);
        assertFalse(slot(e.currentKey()).present());assertFalse(slot(e.depthKey()).present());
        s.bullet.client=false;
        e.impact((b,h)->{
            Object outer=e.current();s.bullet.client=true;
            e.update(q->{assertSame(outer,e.current());},s.bullet);
            e.impact((bb,hh)->assertSame(outer,e.current()),s.bullet,s.hit,true);
        },s.bullet,s.hit,false);
    });}

    @Test void callbacksAndHolesThrowAndStillRestore(){compare(e->{
        Scene s=Scene.make();
        assertSame(FAILURE,assertThrows(RuntimeException.class,()->e.impact((b,h)->{e.enter();throw FAILURE;},s.bullet,s.hit,true)));
        assertFalse(slot(e.currentKey()).present());assertEquals(new Slot(true,0),slot(e.depthKey()));
        e.depth(7);
        assertSame(FAILURE,assertThrows(RuntimeException.class,()->e.update(b->{e.enter();throw FAILURE;},s.bullet)));
        assertFalse(slot(e.currentKey()).present());assertEquals(new Slot(true,7),slot(e.depthKey()));
        e.impact((b,h)->{
            Object outer=e.current();
            assertThrows(RuntimeException.class,()->e.update(q->{e.enter();throw FAILURE;},s.bullet));assertSame(outer,e.current());
            e.shots.onHole=()->{assertSame(s.bullet,e.bulletNow());e.enter();throw FAILURE;};
            assertThrows(RuntimeException.class,()->e.impact((bb,hh)->{},s.bullet,new Hit(null,true),true));
            assertSame(outer,e.current());assertEquals(7,slot(e.depthKey()).value());
            e.shots.onHole=()->{};
        },s.bullet,s.hit,false);
    });}

    @Test void damagePaysBeforeReentryAndOnlyOnceIncludingSelf(){compare(e->{
        Scene s=Scene.make();
        e.impact((b,h)->{
            Object blast=e.bind(s.blast,31.25F);
            assertTrue(e.struck(blast,s.target));assertTrue(e.rocket(blast,s.target));assertTrue(e.launched(blast));assertTrue(e.launchedNow());
            e.shots.onExploder=()->{assertTrue((boolean)field(blast,"contactPaid"));assertFalse(e.struck(blast,s.target));};
            assertEquals(bits(7.75F*.4F+31.25F*.6F),bits(e.damage(blast,s.target,7.75F)));
            assertFalse(e.rocket(blast,s.target));assertEquals(bits(7.75F*.4F),bits(e.damage(blast,s.target,7.75F)));
            int n=e.trace.size();e.finish(blast);assertEquals(n,e.trace.size());
            e.shots.onExploder=()->{};
            Object self=e.bind(new Explosion(s.bullet.world,s.target),13F);
            // This blast belongs to another initiator, so it must not bind.
            assertNull(self);
            s.bullet.thrower=s.target;self=e.bind(new Explosion(s.bullet.world,s.target),13F);assertNotNull(self);
            assertEquals(bits(3F*.4F+(13F*.5F)*.6F),bits(e.damage(self,s.target,3F)));
            assertEquals(bits(3F*.4F),bits(e.damage(e.bind(new Explosion(s.bullet.world,s.target),13F),new Target(),3F)));
        },s.bullet,s.hit,false);
    });}

    @Test void finishPaysBeforeAttackAndNeverRetriesOnException(){compare(e->{
        Scene s=Scene.make();
        e.impact((b,h)->{
            Object blast=e.bind(s.blast,Float.intBitsToFloat(0x42180001));
            e.shots.onContact=()->{
                assertTrue((boolean)field(blast,"contactPaid"));int n=e.trace.size();e.finish(blast);assertEquals(n,e.trace.size());
            };
            e.shots.onAttack=()->{assertTrue((boolean)field(blast,"contactPaid"));throw FAILURE;};
            assertSame(FAILURE,assertThrows(RuntimeException.class,()->e.finish(blast)));
            assertFalse(e.suppressed());int n=e.trace.size();e.finish(blast);assertEquals(n,e.trace.size());
        },s.bullet,s.hit,false);
    });}

    @Test void finishWithoutAttackForMissingDeadNonlivingOrPaid(){compare(e->{
        Scene s=Scene.make();int n=e.trace.size();e.finish(null);assertEquals(n,e.trace.size());
        for(int mode=0;mode<5;mode++){
            s.target.living=mode!=1;s.target.alive=mode!=2;
            Hit hit=mode==0?new Hit(null,true):s.hit;
            final int k=mode;
            e.impact((b,h)->{
                Object blast=e.bind(s.blast,Float.intBitsToFloat(0x3f800001));
                if(k==3)e.damage(blast,s.target,9);
                e.finish(blast);e.finish(blast);
                assertEquals(k==3 || k==4,(boolean)field(blast,"contactPaid"));
            },s.bullet,hit,false);
        }
        assertEquals(1,e.trace.stream().filter(x->x.startsWith("contact:")).count());
    });}

    @Test void attackPreservesBothPreviousGuardsOnSuccessAndFailure(){compare(e->{
        Scene s=Scene.make();Object source=new Token("source");
        for(boolean before:new boolean[]{false,true}){
            e.suppress(before);
            assertTrue(e.attack(s.target,source,-0F,false,true,-0D,Float.intBitsToFloat(0x7fc12345),Float.MIN_VALUE));
            assertEquals(before,e.suppressed());
            e.shots.onAttack=()->{throw FAILURE;};
            assertSame(FAILURE,assertThrows(RuntimeException.class,()->e.attack(s.target,source,1,true,false,1.25,0,1)));
            assertEquals(before,e.suppressed());e.shots.onAttack=()->{};
        }
    });}

    @Test void holeUsesRemovedAndRunsInsideContextBeforeRestore(){compare(e->{
        Scene s=Scene.make();
        for(boolean announce:new boolean[]{false,true})for(boolean removed:new boolean[]{false,true})
            for(boolean heavy:new boolean[]{false,true})for(boolean hole:new boolean[]{false,true})for(boolean explode:new boolean[]{false,true}){
                s.bullet.removed=removed;s.bullet.config.heavy=heavy;Hit hit=new Hit(null,hole);
                int start=e.trace.size();
                e.shots.onHole=()->{assertSame(s.bullet,e.bulletNow());assertSame(hit,field(e.current(),"impact"));assertEquals(4,slot(e.depthKey()).value());};
                boolean actual=e.impact((b,h)->{if(explode)e.bind(s.blast,1);e.enter();e.enter();e.enter();e.enter();},s.bullet,hit,announce);
                assertEquals(announce && removed && heavy && hole,actual);
                List<String> tail=e.trace.subList(start,e.trace.size());
                assertEquals(announce?1:0,tail.stream().filter(x->x.startsWith("removed:")).count());
                assertEquals(announce && removed?1:0,tail.stream().filter(x->x.startsWith("heavy=")).count());
                assertEquals(announce && removed && heavy?1:0,tail.stream().filter(x->x.startsWith("hole:")).count());
                assertFalse(slot(e.currentKey()).present());assertEquals(new Slot(true,0),slot(e.depthKey()));
            }
    });}

    @Test void absentNullAndPresentThreadLocalsHaveDifferentRestoration(){compare(e->{
        Scene s=Scene.make();assertFalse(slot(e.depthKey()).present());assertFalse(slot(e.currentKey()).present());
        e.enter();e.exit();assertFalse(slot(e.depthKey()).present());
        // enter/exit read CURRENT with get(): a present null must be removed by the callback wrapper.
        assertEquals(new Slot(true,null),slot(e.currentKey()));
        e.impact((b,h)->{},s.bullet,s.hit,false);
        assertEquals(new Slot(false,null),slot(e.currentKey()));assertEquals(new Slot(true,0),slot(e.depthKey()));
        e.depth(9);e.update(b->{e.enter();e.enter();},s.bullet);
        assertEquals(new Slot(false,null),slot(e.currentKey()));assertEquals(new Slot(true,9),slot(e.depthKey()));
        e.impact((b,h)->{
            Object c=e.current();e.update(q->{},s.bullet);assertSame(c,slot(e.currentKey()).value());
            e.impact((bb,hh)->{},s.bullet,s.hit,false);assertSame(c,slot(e.currentKey()).value());
        },s.bullet,s.hit,false);
    });}

    @Test void rawFloatBitsAndClassificationForAllNullableInputs(){compare(e->{
        Scene s=Scene.make();assertNull(e.bulletOf(null));assertFalse(e.struck(null,s.target));assertFalse(e.rocket(null,s.target));
        assertFalse(e.launched(null));assertTrue(e.launchedNow());assertNull(e.struckNow());assertNull(e.bulletNow());
        for(int raw:new int[]{0,0x80000000,1,0x3f800001,0x7f7fffff,0x7f800000,0xff800000,0x7fc12345}){
            float f=Float.intBitsToFloat(raw);assertEquals(raw,Float.floatToRawIntBits(e.damage(null,s.target,f)));
            e.trace.add("area:"+bits(e.area(f)));
            for(boolean self:new boolean[]{false,true}){
                s.bullet.thrower=self?s.target:new Token("thrower");Explosion x=new Explosion(s.bullet.world,s.bullet.thrower);
                e.impact((b,h)->{
                    Object blast=e.bind(x,f);assertTrue(e.struck(blast,s.target));
                    e.trace.add("direct:"+bits(e.damage(blast,s.target,f)));e.trace.add("repeat:"+bits(e.damage(blast,s.target,f)));
                },s.bullet,s.hit,false);
            }
        }
        e.impact((b,h)->{
            Object blast=e.bind(new Explosion(s.bullet.world,s.bullet.thrower),1);
            s.bullet.config.rocket=false;s.bullet.config.heavy=false;
            assertFalse(e.launched(blast));assertFalse(e.launchedNow());assertFalse(e.rocket(blast,s.target));
        },s.bullet,new Hit(null,true),false);
    });}

    @Test void missingPortAlwaysThrows() throws Exception {
        for(var method:ExplosiveShots.class.getDeclaredMethods()){
            Object[] args=new Object[method.getParameterCount()];Class<?>[] types=method.getParameterTypes();
            for(int i=0;i<types.length;i++){
                if(types[i]==float.class)args[i]=1F;
                else if(types[i]==double.class)args[i]=1D;
                else if(types[i]==boolean.class)args[i]=true;
            }
            InvocationTargetException thrown=assertThrows(InvocationTargetException.class,()->method.invoke(ExplosiveShots.MISSING,args));
            assertInstanceOf(UnsupportedOperationException.class,thrown.getCause());
        }
    }
}
