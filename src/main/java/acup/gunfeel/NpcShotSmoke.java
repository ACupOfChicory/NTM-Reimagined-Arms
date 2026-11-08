package acup.gunfeel;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiConsumer;
import com.hbm.entity.ModEntities;
import com.hbm.entity.projectile.EntityBullet;
import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.factory.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Турели и стреляющие NPC NTM (notes/turrets-npc, -PcheckNpcShots): пуля каждого стрелка такая, как он её
 * создаёт (турель — MK4 без стрелка, моб — MK4 со стрелком-мобом, вертолёт — старая EntityBullet), и летит
 * настоящими тиками сервера: по мобу (тело, голова, конечность, за стеклом) и по полосам блоков (стекло, решётка,
 * бетон). Та же пуля у игрока (FakePlayer) — рядом, числа сравниваются. Дырки считает клиент по блоку.
 */
final class NpcShotSmoke {
    static final boolean ENABLED=Boolean.getBoolean("ntmgunfeel.checkNpcShots");
    private enum Kind { TURRET, MOB, PLAYER }
    private record Shooter(String name,BulletConfig config,float base,Kind kind) {
        boolean legacy() { return config==null; }
        float damage() { return legacy()?base:base*config.damageMult; }
    }
    // полоса блоков одного стрелка: что стоит на пути (front), бетон за ним (back), куда целится пуля
    private record Lane(int shooter,String row,BlockPos front,BlockPos back,Vec3 from) {}
    private static final List<Vec3> WAYS=List.of(new Vec3(0,0,1),new Vec3(1,0,0),new Vec3(-1,0,0),new Vec3(0,0,-1),new Vec3(0,-1,0),new Vec3(0,1,0));
    private static final List<Lane> LANES=new ArrayList<>();
    private static final ConcurrentLinkedQueue<String> FRONT=new ConcurrentLinkedQueue<>();
    private static List<Shooter> shooters;
    private static volatile boolean built;
    private static volatile long fired;
    private static int state;
    private static boolean ran;
    private NpcShotSmoke() {}

    private static List<Shooter> shooters() {
        if(shooters==null) shooters=List.of(
                new Shooter("Sentry p9_fmj",XFactory9mm.p9_fmj,5,Kind.TURRET),new Shooter("игрок p9_fmj",XFactory9mm.p9_fmj,5,Kind.PLAYER),
                new Shooter("Chekhov bmg50_fmj",XFactory50.bmg50_fmj,10,Kind.TURRET),new Shooter("игрок bmg50_fmj",XFactory50.bmg50_fmj,10,Kind.PLAYER),
                new Shooter("Friendly r556_fmj",XFactory556mm.r556_fmj,10,Kind.TURRET),new Shooter("игрок r556_fmj",XFactory556mm.r556_fmj,10,Kind.PLAYER),
                new Shooter("Jeremy shell_ap",XFactoryTurret.shell_ap,50,Kind.TURRET),new Shooter("игрок shell_ap",XFactoryTurret.shell_ap,50,Kind.PLAYER),
                new Shooter("маскмен r762_fmj",XFactory762mm.r762_fmj,5,Kind.MOB),new Shooter("игрок r762_fmj 5",XFactory762mm.r762_fmj,5,Kind.PLAYER),
                new Shooter("заражённый краб r762_fmj",XFactory762mm.r762_fmj,10,Kind.MOB),new Shooter("игрок r762_fmj 10",XFactory762mm.r762_fmj,10,Kind.PLAYER),
                new Shooter("вертолёт EntityBullet",null,40,Kind.MOB));
        return shooters;
    }

    @SuppressWarnings("unchecked")
    private static Entity bullet(ServerLevel level,Shooter s,Vec3 from,Vec3 way,float damage,LivingEntity mob,ServerPlayer player) {
        Entity owner=s.kind()==Kind.MOB?mob:s.kind()==Kind.PLAYER?player:null;
        if(s.legacy()) {
            // как вертолёт: 3 × 1,5 блока за тик, «chopper», не критическая
            var b=new EntityBullet((EntityType<? extends EntityBullet>)ModEntities.BULLET.get(),level,from.x,from.y,from.z);
            b.damage=damage;b.shootingEntity=owner;b.setChopper(true);b.setDeltaMovement(way.scale(4.5));
            return b;
        }
        // как spawnBullet турели: new EntityBulletBaseMK4(level, cfg, base, spread, yaw, pitch) у среза ствола
        var b=new EntityBulletBaseMK4(level,s.config(),s.base(),0,0,0);
        if(owner instanceof LivingEntity living) b.setThrower(living);
        b.damage=damage;b.setPos(from);b.setThrowableHeading(way.x,way.y,way.z,1F,0F);
        return b;
    }

    private static void fly(Entity bullet,Vec3 from) {
        for(int i=0;i<40 && !bullet.isRemoved() && bullet.position().distanceTo(from)<40;i++) bullet.tick();
        bullet.discard();
    }

    // откуда точка — первая точка модели на пути пули (её нашли лучи HeadSmoke с одной из сторон)
    private static Vec3 way(LivingEntity target,Vec3 point) {
        if(point==null) return null;
        for(var d:WAYS) {
            var hit=target.getBoundingBox().clip(point.subtract(d.scale(3)),point.add(d.scale(.25)));
            if(hit.isPresent() && hit.get().distanceToSqr(point)<1e-6) return d;
        }
        return null;
    }

    private static String f(float v) { return String.format(Locale.ROOT,"%.2f",v); }

    static void run(ServerLevel level,LivingEntity target,Vec3 head,Vec3 body,Vec3 limb,BiConsumer<Boolean,String> check) {
        if(!ENABLED || ran) return;
        ran=true;
        Vec3 bodyWay=way(target,body),headWay=way(target,head),limbWay=way(target,limb);
        check.accept(bodyWay!=null && headWay!=null && limbWay!=null,"NPC: пути к точкам тела, головы, конечности: "+bodyWay+", "+headWay+", "+limbWay);
        if(bodyWay==null || headWay==null || limbWay==null) return;
        Vec3 home=target.position();
        BlockPos glass=BlockPos.containing(body.subtract(bodyWay.scale(1.5)));
        // стекло за мобом: пуля, оставшаяся в мобе, его не трогает
        BlockPos after=BlockPos.containing(body.add(bodyWay.scale(1.2)));
        var rows=new ArrayList<String>();
        rows.add("стрелок\tурон\tтело\tголова\tтело×1,75\tконечность\tтело×0,5\tза стеклом\tстекло разбито");
        var numbers=new HashMap<Integer,float[]>();
        try {
            var mob=net.minecraft.world.entity.EntityTypes.ZOMBIE.create(level,EntitySpawnReason.COMMAND);mob.setPos(8.5,160,-40);
            var player=net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(level);
            for(int i=0;i<shooters().size();i++) {
                var s=shooters().get(i);float base=s.damage();
                java.util.function.BiFunction<Vec3,Float,Float> shot=(at,amount)->{
                    Vec3 way=at==head?headWay:at==limb?limbWay:bodyWay;
                    target.setHealth(1000);target.invulnerableTime=0;target.hurtTime=0;
                    Vec3 from=at.subtract(way.scale(3));
                    fly(bullet(level,s,from,way,amount,mob,player),from);
                    float lost=1000-target.getHealth();
                    target.setPos(home);target.setDeltaMovement(Vec3.ZERO);
                    return lost;
                };
                // the part's multiplier after the armour, on the body's damage
                float b=shot.apply(body,base),h=shot.apply(head,base),l=shot.apply(limb,base),h2=b*1.75F,l2=b*.5F;
                BulletServer.forgetWear();
                level.setBlock(glass,Blocks.GLASS.defaultBlockState(),3);
                float behind=shot.apply(body,base);
                boolean broke=level.getBlockState(glass).isAir();
                level.setBlock(glass,Blocks.AIR.defaultBlockState(),3);
                BulletServer.forgetWear();
                level.setBlock(after,Blocks.GLASS.defaultBlockState(),3);
                float before=shot.apply(body,base);
                boolean whole=level.getBlockState(after).is(Blocks.GLASS);
                level.setBlock(after,Blocks.AIR.defaultBlockState(),3);
                // бронебойный снаряд (doesPenetrate) и старая пуля NTM (она не останавливается на мобе) проходят моба
                // насквозь и бьют стекло за ним — как в NTM
                boolean through=s.legacy() || s.config().doesPenetrate;
                check.accept(whole!=through && Math.abs(before-b)<.01,"NPC "+s.name()+": стекло за мобом цело="+whole+(through?" (пробивает моба)":"")+", урон="+f(before));
                numbers.put(i,new float[]{b,h,h2,l,l2,behind,broke?1:0});
                rows.add(s.name()+"\t"+f(base)+"\t"+f(b)+"\t"+f(h)+"\t"+f(h2)+"\t"+f(l)+"\t"+f(l2)+"\t"+f(behind)+"\t"+broke);
                check.accept(b>0 && Math.abs(h-h2)<.01 && Math.abs(l-l2)<.01,
                        "NPC "+s.name()+": урон "+f(base)+", тело="+f(b)+", голова="+f(h)+" (тело×1,75="+f(h2)+"), конечность="+f(l)+" (тело×0,5="+f(l2)+")");
                check.accept(behind>0,"NPC "+s.name()+": за стеклом урон="+f(behind)+" из "+f(b)+" ("+f(behind/b*100)+" %), стекло разбито="+broke);
            }
            // старая пуля по мобу, которого только что ударили (окно неуязвимости, lastHurt): первый удар или повтор
            // NTM решает её урон без зоны, множитель головы или конечности ложится на то, что прошло (после брони)
            int li=shooters().size()-1;var legacy=shooters().get(li);float d=legacy.damage();float[] fresh=numbers.get(li);
            for(float last:new float[]{d,d*.6F}) {
                java.util.function.Function<Vec3,Float> hit=at->{
                    Vec3 way=at==head?headWay:at==limb?limbWay:bodyWay;
                    target.setHealth(1000);target.invulnerableTime=0;target.hurtTime=0;
                    target.hurtServer(level,level.damageSources().generic(),last);
                    target.setHealth(1000);
                    Vec3 from=at.subtract(way.scale(3));
                    fly(bullet(level,legacy,from,way,d,mob,player),from);
                    float lost=1000-target.getHealth();
                    target.setPos(home);target.setDeltaMovement(Vec3.ZERO);target.invulnerableTime=0;
                    return lost;
                };
                float b=hit.apply(body),h=hit.apply(head),l=hit.apply(limb);
                String line="NPC "+legacy.name()+" после удара "+f(last)+": тело="+f(b)+", голова="+f(h)+", конечность="+f(l);
                rows.add(legacy.name()+" после удара "+f(last)+"\t"+f(d)+"\t"+f(b)+"\t"+f(h)+"\t\t"+f(l)+"\t\t\t");
                // урон не больше прошлого удара: NTM бьёт повтором целиком — как свежий выстрел в ту же точку
                if(d<=last) check.accept(Math.abs(b-fresh[0])<.01 && Math.abs(h-fresh[1])<.01 && Math.abs(l-fresh[3])<.01,
                        line+" (как без удара: "+f(fresh[0])+", "+f(fresh[1])+", "+f(fresh[3])+")");
                // больше: первый удар, сверх прошлого, и множитель на нём
                else check.accept(b>0 && b<fresh[0] && Math.abs(h-b*1.75F)<.01 && Math.abs(l-b*.5F)<.01,line+" (без удара тело="+f(fresh[0])+")");
            }
            // зоны старой пули падают: удар в голову идёт как в NTM (урон тела), сервер не падает
            LegacyBullets.failZones=true;
            try {
                Vec3 from=head.subtract(headWay.scale(3));
                target.setHealth(1000);target.invulnerableTime=0;target.hurtTime=0;
                fly(bullet(level,legacy,from,headWay,d,mob,player),from);
                float lost=1000-target.getHealth();
                target.setPos(home);target.setDeltaMovement(Vec3.ZERO);
                check.accept(Math.abs(lost-fresh[0])<.01,"NPC "+legacy.name()+": зоны упали, голова="+f(lost)+" (тело="+f(fresh[0])+")");
            } finally { LegacyBullets.failZones=false; }
            for(int i=0;i+1<shooters().size();i+=2) {
                var npc=numbers.get(i);var own=numbers.get(i+1);
                boolean same=true;for(int k=0;k<npc.length;k++) same&=Math.abs(npc[k]-own[k])<.01;
                check.accept(same,"NPC "+shooters().get(i).name()+" = "+shooters().get(i+1).name()+": "+Arrays.toString(npc)+" / "+Arrays.toString(own));
            }
            java.nio.file.Files.write(java.nio.file.Path.of(System.getProperty("ntmgunfeel.checkClient")).resolveSibling("npc-shots.tsv"),rows);
            lanes(level);
        } catch(Throwable e) { check.accept(false,"NPC: исключение "+e); }
        finally {
            level.setBlock(glass,Blocks.AIR.defaultBlockState(),3);
            level.setBlock(after,Blocks.AIR.defaultBlockState(),3);
            target.setPos(home);target.setDeltaMovement(Vec3.ZERO);
        }
    }

    // полосы блоков, по одной на стрелка: стекло, решётка (в стойку и в щель), бетон; за первыми тремя — бетон.
    // Ставятся сейчас, стреляют позже (fire): блоки должны дойти до клиента раньше пакетов выстрелов.
    private static void lanes(ServerLevel level) {
        for(int x=19;x<=28;x++) for(int y=100;y<=108;y++) for(int z=0;z<=2*shooters().size()+1;z++) level.setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),3);
        for(int i=0;i<shooters().size();i++) {
            int z=1+2*i;
            String[] rows={"стекло","решётка в стойку","решётка в щель","бетон"};
            BlockState[] front={Blocks.GLASS.defaultBlockState(),Blocks.IRON_BARS.defaultBlockState(),Blocks.IRON_BARS.defaultBlockState(),Blocks.CONCRETE.gray().defaultBlockState()};
            for(int r=0;r<4;r++) {
                int y=101+2*r;
                var f=new BlockPos(23,y,z);var back=r==3?null:new BlockPos(26,y,z);
                level.setBlock(f,front[r],3);if(back!=null) level.setBlock(back,Blocks.CONCRETE.gray().defaultBlockState(),3);
                LANES.add(new Lane(i,rows[r],f,back,new Vec3(20.5,y+.5,z+(r==2?.8:.5))));
            }
        }
        built=true;
    }

    private static void fire(ServerLevel level) {
        try {
            var mob=net.minecraft.world.entity.EntityTypes.ZOMBIE.create(level,EntitySpawnReason.COMMAND);mob.setPos(8.5,160,-40);
            var player=net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(level);
            BulletServer.forgetWear();
            for(var lane:LANES) {
                var s=shooters().get(lane.shooter());
                fly(bullet(level,s,lane.from(),new Vec3(1,0,0),s.damage(),mob,player),lane.from());
                FRONT.add(lane.shooter()+"|"+lane.row()+"|"+level.getBlockState(lane.front()).getBlock().getDescriptionId()
                        +"|"+(lane.back()==null?"-":level.getBlockState(lane.back()).getBlock().getDescriptionId()));
            }
        } catch(Throwable e) { FRONT.add("ошибка|"+e); }
        fired=System.nanoTime();
    }

    // клиент, каждый кадр после проверок мобов: true — готово
    static boolean finish() {
        if(!ENABLED || state==3) return true;
        var mc=net.minecraft.client.Minecraft.getInstance();
        if(state==0) {
            if(!built) { ClientSmoke.check(false,"NPC: полосы блоков не поставлены");state=3;return true; }
            fired=System.nanoTime()+1_500_000_000L; // блоки до клиента: полторы секунды
            state=1;return false;
        }
        if(state==1) {
            if(System.nanoTime()<fired) return false;
            fired=0;state=2;
            mc.getSingleplayerServer().execute(()->fire(mc.getSingleplayerServer().overworld()));
            return false;
        }
        if(fired==0 || System.nanoTime()-fired<4_000_000_000L) return false;
        state=3;
        var after=new HashMap<String,String>();
        for(var line:FRONT) { var p=line.split("\\|"); if(p.length==4) after.put(p[0]+"|"+p[1],p[2]+" / "+p[3]); else ClientSmoke.check(false,"NPC: "+line); }
        var rows=new ArrayList<String>();rows.add("стрелок\tполоса\tдырок спереди\tдырок на бетоне за\tблоки после (сервер)");
        var holes=new HashMap<String,int[]>();
        for(var lane:LANES) {
            int front=Tracers.holesOn(lane.front()),back=lane.back()==null?0:Tracers.holesOn(lane.back());
            holes.put(lane.shooter()+"|"+lane.row(),new int[]{front,back});
            String name=shooters().get(lane.shooter()).name();
            rows.add(name+"\t"+lane.row()+"\t"+front+"\t"+(lane.back()==null?"-":back)+"\t"+after.get(lane.shooter()+"|"+lane.row()));
            if(lane.row().equals("бетон")) ClientSmoke.check(front>0,"NPC "+name+": дырка в бетоне: "+front);
            else ClientSmoke.check(true,"NPC "+name+", "+lane.row()+": дырок спереди="+front+", на бетоне за="+back+", блоки после="+after.get(lane.shooter()+"|"+lane.row()));
        }
        for(int i=0;i+1<shooters().size();i+=2) for(var row:List.of("стекло","решётка в стойку","решётка в щель","бетон")) {
            var a=holes.get(i+"|"+row);var b=holes.get((i+1)+"|"+row);
            String sa=after.get(i+"|"+row),sb=after.get((i+1)+"|"+row);
            ClientSmoke.check(a!=null && b!=null && (a[0]>0)==(b[0]>0) && (a[1]>0)==(b[1]>0) && Objects.equals(sa,sb),
                    "NPC полоса «"+row+"»: "+shooters().get(i).name()+" "+Arrays.toString(a)+" "+sa+" = "+shooters().get(i+1).name()+" "+Arrays.toString(b)+" "+sb);
        }
        try { java.nio.file.Files.write(java.nio.file.Path.of(System.getProperty("ntmgunfeel.checkClient")).resolveSibling("npc-lanes.tsv"),rows); }
        catch(Exception e) { ClientSmoke.check(false,"NPC: запись npc-lanes.tsv: "+e); }
        return true;
    }
}
