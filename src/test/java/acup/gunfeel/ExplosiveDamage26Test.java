package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import acup.gunfeel.host.ExplosiveShots;
import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.explosion.vanillant.ExplosionVNT;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.phys.HitResult;

class ExplosiveDamage26Test {
    @Test
    void wiringRegistersTheReads() {
        var wiring=assertInstanceOf(Wiring.class,CoreServices.root());
        assertTrue(wiring.port(ExplosiveShots.class) instanceof ExplosiveDamage.Reads);
    }

    @Test
    void smokeFindsSuppressionOnBase() throws Exception {
        var field=ExplosiveDamageBase.class.getDeclaredField("SUPPRESS");
        assertEquals(ThreadLocal.class,field.getType());
        field.setAccessible(true);
        assertInstanceOf(ThreadLocal.class,field.get(null));
    }

    @Test void processorStaysTypedAndBlastIsInherited() throws Exception {
        assertEquals(ExplosiveDamage.class,ExplosiveDamage.Processor.class.getDeclaringClass());
        assertEquals(void.class,ExplosiveDamage.Processor.class.getDeclaredMethod("gunfeel$bind",ExplosionVNT.class).getReturnType());
        assertEquals(ExplosiveDamageBase.Blast.class,ExplosiveDamage.Processor.class.getDeclaredMethod("gunfeel$blast").getReturnType());
        assertEquals(boolean.class,ExplosiveDamage.Processor.class.getDeclaredMethod("gunfeel$explosive").getReturnType());
        assertSame(ExplosiveDamageBase.Blast.class,ExplosiveDamage.Blast.class);
        assertSame(ExplosiveDamageBase.Context.class,ExplosiveDamage.Context.class);
        assertFalse(Arrays.stream(ExplosiveDamageBase.class.getDeclaredClasses()).anyMatch(c->c.getSimpleName().equals("Processor")));
    }

    @Test void typedFacadesAndInheritedStaticsKeepTheirSignatures() throws Exception {
        Class<?> type=ExplosiveDamage.class,blast=ExplosiveDamageBase.Blast.class;
        assertEquals(Entity.class,type.getDeclaredMethod("bulletNow").getReturnType());
        assertEquals(Entity.class,type.getDeclaredMethod("struckNow").getReturnType());
        assertEquals(EntityBulletBaseMK4.class,type.getDeclaredMethod("bulletOf",blast).getReturnType());
        assertEquals(boolean.class,type.getDeclaredMethod("impact",java.util.function.BiConsumer.class,EntityBulletBaseMK4.class,HitResult.class,boolean.class).getReturnType());
        assertEquals(void.class,type.getDeclaredMethod("update",java.util.function.Consumer.class,EntityBulletBaseMK4.class).getReturnType());
        assertEquals(blast,type.getDeclaredMethod("bind",ExplosionVNT.class,float.class).getReturnType());
        assertEquals(boolean.class,type.getDeclaredMethod("struck",blast,Entity.class).getReturnType());
        assertEquals(float.class,type.getDeclaredMethod("damage",blast,Entity.class,float.class).getReturnType());
        assertEquals(boolean.class,type.getDeclaredMethod("rocketDirect",blast,Entity.class).getReturnType());
        assertEquals(boolean.class,type.getDeclaredMethod("attack",LivingEntity.class,DamageSource.class,float.class,boolean.class,boolean.class,double.class,float.class,float.class).getReturnType());
        assertEquals(ExplosiveDamageBase.class,type.getMethod("finish",blast).getDeclaringClass());
        assertNull(ExplosiveDamage.bulletOf(null));assertFalse(ExplosiveDamage.struck(null,null));
        assertEquals(Float.floatToRawIntBits(-0F),Float.floatToRawIntBits(ExplosiveDamage.damage(null,null,-0F)));
        ExplosiveDamage.finish(null);
        assertInstanceOf(ExplosiveDamage.Reads.class,CoreServices.root().port(ExplosiveShots.class));
    }

    private static MethodNode method(String name) throws Exception {
        ClassNode c=new ClassNode();
        try(InputStream in=ExplosiveDamage.Reads.class.getResourceAsStream("/acup/gunfeel/ExplosiveDamage$Reads.class")){
            assertNotNull(in);new ClassReader(in).accept(c,0);
        }
        return c.methods.stream().filter(m->m.name.equals(name)).findFirst().orElseThrow();
    }
    private static List<String> accesses(String name) throws Exception {
        List<String> result=new ArrayList<>();
        for(AbstractInsnNode i:method(name).instructions){
            if(i instanceof MethodInsnNode m)result.add(m.owner+"."+m.name+m.desc);
            if(i instanceof FieldInsnNode f)result.add(f.owner+"."+f.name+":"+f.desc);
        }
        return result;
    }

    @Test void contactRetainsDamageSourceAndConfigReadOrder() throws Exception {
        assertEquals(List.of(
                "com/hbm/entity/projectile/EntityBulletBaseMK4.getThrower()Lnet/minecraft/world/entity/LivingEntity;",
                "com/hbm/entity/projectile/EntityBulletBaseMK4.config:Lcom/hbm/items/weapon/sedna/BulletConfig;",
                "com/hbm/items/weapon/sedna/BulletConfig.dmgClass:Lcom/hbm/util/DamageClass;",
                "com/hbm/items/weapon/sedna/BulletConfig.getDamage(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/LivingEntity;Lcom/hbm/util/DamageClass;)Lnet/minecraft/world/damagesource/DamageSource;",
                "com/hbm/entity/projectile/EntityBulletBaseMK4.config:Lcom/hbm/items/weapon/sedna/BulletConfig;",
                "com/hbm/items/weapon/sedna/BulletConfig.armorThresholdNegation:F",
                "com/hbm/entity/projectile/EntityBulletBaseMK4.config:Lcom/hbm/items/weapon/sedna/BulletConfig;",
                "com/hbm/items/weapon/sedna/BulletConfig.armorPiercingPercent:F",
                "acup/gunfeel/ExplosiveDamage.attack(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/damagesource/DamageSource;FZZDFF)Z"),accesses("contact"));
    }

    @Test void holeRetainsAllGuardsAndRepeatedLocationReads() throws Exception {
        List<String> calls=accesses("hole");
        String water="acup/gunfeel/water/WaterTick.surfaceHit(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/HitResult;)Z";
        String type="net/minecraft/world/phys/BlockHitResult.getType()Lnet/minecraft/world/phys/HitResult$Type;";
        String level="com/hbm/entity/projectile/EntityBulletBaseMK4.level()Lnet/minecraft/world/level/Level;";
        assertTrue(calls.indexOf(water)>=0 && calls.indexOf(water)<calls.indexOf(type) && calls.indexOf(type)<calls.indexOf(level));
        assertEquals(1,calls.stream().filter(water::equals).count());assertEquals(1,calls.stream().filter(level::equals).count());
        assertEquals(3,calls.stream().filter("net/minecraft/world/phys/HitResult.getLocation()Lnet/minecraft/world/phys/Vec3;"::equals).count());
        assertEquals(1,calls.stream().filter("net/minecraft/world/phys/BlockHitResult.getLocation()Lnet/minecraft/world/phys/Vec3;"::equals).count());
        assertEquals(1,calls.stream().filter(x->x.startsWith("net/minecraft/world/phys/Vec3.normalize")).count());
        assertEquals(1,calls.stream().filter(x->x.startsWith("net/neoforged/neoforge/network/PacketDistributor.sendToPlayersNear")).count());
        var shots=new ExplosiveDamage.Reads();assertFalse(shots.hole(null,null));
        assertNull(shots.struck(null));assertFalse(shots.living(new Object()));assertFalse(shots.heavy(null));assertFalse(shots.rocket(null));
    }
}
