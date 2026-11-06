package acup.gunfeel;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import com.hbm.entity.projectile.EntityBulletBaseMK4;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.BlockEvent;

// port: copied from 26.2 (0762086) with changes for 1.7.10, each marked "port:": BlockState -> (Block, meta),
// BlockPos -> x y z, BlockHitResult -> BlockRay.Hit (a miss is null), ServerLevel -> WorldServer; the break event,
// mob griefing, cracks (destroyBlockInWorldPartially), breaking (func_147480_a), particles and sounds of 1.7.10.
// Server side of BulletRules: blocks bullets break, and what a creature hit or killed by one gives off.
public final class BulletServer {
    private BulletServer() {
    }

    // what bullets did to a block not broken yet: hits or damage so far, and the game tick the last came in
    // the block it was done to: once that block is broken or replaced (by hand, a piston, a command) the wear and its
    // cracks go
    private record Wear(float done, long at, Block block) {
    }

    private record Spot(WorldServer level, int x, int y, int z) {
    }

    private static final Map<Spot, Wear> WEAR = new HashMap<>();
    // 20 s at 20 TPS; game ticks, so a paused or slowed server does not forget it sooner
    private static final long FORGET_TICKS = 400L;

    // the bullet hit this block: 0 = nothing happened, 1 = it broke and the bullet stops, 2 = it broke and the bullet
    // flies on
    public static int blockHit(EntityBulletBaseMK4 bullet, int x, int y, int z, double ax, double ay, double az) {
        if (!(bullet.world instanceof WorldServer)) return 0;
        WorldServer level = (WorldServer) bullet.world;
        Block block = Mc.block(level, x, y, z);
        if (Mc.material(block) == Material.AIR) return 0;
        int meta = Mc.meta(level, x, y, z);
        BulletRules.Break rule = BulletRules.breakRule(block, meta, level, x, y, z);
        if (rule == null || rule.mode() == 0) return 0;
        String was = TestLog.block(level, x, y, z);
        if (!wear(level, bullet, x, y, z, block, meta, rule, acup.gunfeel.water.WaterTick.k(bullet, new Vec3d(ax, ay, az)))) return 0;
        if (TestLog.server) TestLog.s(bullet.getEntityId(), "end", "by", rule.pass() ? "broke_pass" : "broke", "block", was,
                "pos", new int[] {x, y, z}, "at", TestLog.point(ax, ay, az));
        return rule.pass() ? 2 : 1;
    }

    // NTM's ricochet sound (MixinRicochetSound): off a block whose hit sound is steel (bullet rules; a multiblock's
    // cell by its machine) our steel ricochet where the bullet hit instead, and true; else false (NTM's own plays)
    public static boolean ricochetSound(World level, net.minecraft.util.math.RayTraceResult mop) {
        if (mop == null || mop.typeOfHit != net.minecraft.util.math.RayTraceResult.Type.BLOCK || level.isRemote) return false;
        Block block = Mc.block(level, mop.getBlockPos().getX(), mop.getBlockPos().getY(), mop.getBlockPos().getZ());
        if (Mc.material(block) == Material.AIR) return false;
        int meta = Mc.meta(level, mop.getBlockPos().getX(), mop.getBlockPos().getY(), mop.getBlockPos().getZ());
        BulletRules.Sound s = BulletRules.hit(block, meta, level, mop.getBlockPos().getX(), mop.getBlockPos().getY(), mop.getBlockPos().getZ()).sound();
        boolean steel = s != null && (s.id().startsWith("ntmgunfeel:bullet.hit.steel") || s.id().startsWith("ntmgunfeel:bullet.hit.metal"));
        if (TestLog.server) TestLog.s(TestLog.shot(), "ricochet", "block", TestLog.block(level, mop.getBlockPos().getX(), mop.getBlockPos().getY(), mop.getBlockPos().getZ()),
                "pos", new int[] {mop.getBlockPos().getX(), mop.getBlockPos().getY(), mop.getBlockPos().getZ()}, "material", TestLog.material(level, mop.getBlockPos().getX(), mop.getBlockPos().getY(), mop.getBlockPos().getZ()),
                "result", "bounced", "sound", steel ? "ours" : "ntm");
        if (!steel) return false;
        double x = mop.hitVec != null ? mop.hitVec.x : mop.getBlockPos().getX() + .5, y = mop.hitVec != null ? mop.hitVec.y : mop.getBlockPos().getY() + .5,
                z = mop.hitVec != null ? mop.hitVec.z : mop.getBlockPos().getZ() + .5;
        // port: 26.2 sends it within 16 blocks and fades it out over 32 (sounds.json); 1.12.2 alone would fade it over 16
        Mc.playSoundFaded(level, x, y, z, "ntmgunfeel:bullet.ricochet.steel", 1.0F, 0.9F + level.rand.nextFloat() * 0.2F, 16.0);
        return true;
    }

    // the bullet flew through a block it always flies through ("through"): counts as a hit on it
    public static void passed(EntityBulletBaseMK4 bullet, BlockRay.Hit hit) {
        if (!(bullet.world instanceof WorldServer)) return;
        WorldServer level = (WorldServer) bullet.world;
        Block block = Mc.block(level, hit.x, hit.y, hit.z);
        int meta = Mc.meta(level, hit.x, hit.y, hit.z);
        if (TestLog.server) TestLog.s(bullet.getEntityId(), "through", "block", TestLog.block(level, hit.x, hit.y, hit.z), "pos", TestLog.pos(hit),
                "at", TestLog.point(hit));
        BulletRules.Break rule = BulletRules.breakRule(block, meta, level, hit.x, hit.y, hit.z);
        if (rule == null || rule.mode() == 0) return;
        wear(level, bullet, hit.x, hit.y, hit.z, block, meta, rule, acup.gunfeel.water.WaterTick.k(bullet, new Vec3d(hit.ax, hit.ay, hit.az)));
    }

    // port: a block's place as one number, for the blocks a bullet paid for
    public static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) y & 0xFFFL) << 26 | (long) z & 0x3FFFFFFL;
    }

    // the bullet's path this tick through blocks, piercing them while it has the damage (PassableClip.pierced):
    // each one pierced takes its share of the damage (and counts towards breaking it). `paid` = blocks it already
    // pierced (one it ended a tick inside of is not paid for again). The first block that stops it, else null (a miss).
    // A creature NTM finds on the path before a pierced block gets the damage from before that block (PierceSteps).
    public static BlockRay.Hit clip(EntityBulletBaseMK4 bullet, World level, Vec3d from, Vec3d to, Set<Long> paid) {
        Consumer<BlockRay.Hit> through = hit -> passed(bullet, hit);
        PierceSteps steps = PierceSteps.of(bullet);
        steps.start(from.x, from.y, from.z);
        // an explosive bullet goes off on the first block it meets, never pierces
        if (bullet.config != null && bullet.config.onImpact != null) return stop(bullet, level, PassableClip.clip(level, from, to, through), "explosive", Float.NaN);
        Vec3d at = from;
        for (int i = 0; i < 16; i++) {
            BlockRay.Hit hit = PassableClip.clip(level, at, to, through);
            if (hit == null) return null;
            double dx = to.x - at.x, dy = to.y - at.y, dz = to.z - at.z;
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 1e-6) return hit;
            dx /= len;
            dy /= len;
            dz /= len;
            long pos = key(hit.x, hit.y, hit.z);
            boolean once = PassableClip.paidOnce(hit), known = once && paid.contains(pos);
            BlockRay.Hit out = PassableClip.exit(level, hit, dx, dy, dz);
            if (out == null) return stop(bullet, level, hit, "no_exit", Float.NaN);
            double spentBefore = steps.penetration.spent();
            // behind water it has f² of the damage (WaterTick.k), kept in the tick's units
            float k = acup.gunfeel.water.WaterTick.k(bullet, new Vec3d(hit.ax, hit.ay, hit.az));
            float left = known ? bullet.damage : k == 1F ? PassableClip.pierced(level, hit, out, bullet.damage, bullet.config, steps.penetration)
                    : k <= 0F ? -1F : PassableClip.pierced(level, hit, out, bullet.damage * k, bullet.config, steps.penetration) / k;
            if (left < 0F) return stop(bullet, level, hit, "held", (float) hit.distance(out.ax, out.ay, out.az));
            if (TestLog.server && !known) TestLog.s(bullet.getEntityId(), "block", "block", TestLog.block(level, hit.x, hit.y, hit.z), "pos", TestLog.pos(hit),
                    "at", TestLog.point(hit), "material", TestLog.material(level, hit), "result", "pierce", "damage", bullet.damage,
                    "left", left, "path", hit.distance(out.ax, out.ay, out.az));
            if (!known) {
                if (once) paid.add(pos);
                if (level instanceof WorldServer) {
                    Block block = Mc.block(level, hit.x, hit.y, hit.z);
                    int meta = Mc.meta(level, hit.x, hit.y, hit.z);
                    BulletRules.Break rule = BulletRules.breakRule(block, meta, level, hit.x, hit.y, hit.z);
                    if (rule != null && rule.mode() != 0) wear((WorldServer) level, bullet, hit.x, hit.y, hit.z, block, meta, rule, k);
                }
                steps.pierced(hit, out, bullet.damage, spentBefore);
                bullet.damage = left;
            }
            // it comes out beyond this tick's end: it stays inside the block till the next tick
            if (out.distanceSq(at.x, at.y, at.z) >= len * len) return null;
            at = new Vec3d(out.ax + dx * 1e-3, out.ay + dy * 1e-3, out.az + dz * 1e-3);
        }
        return null;
    }

    // the test log: the bullet stops at this block (NTM's onImpact takes it from here: a ricochet, a break rule)
    private static BlockRay.Hit stop(EntityBulletBaseMK4 bullet, World level, BlockRay.Hit hit, String why, float path) {
        if (!TestLog.server || level.isRemote || hit == null) return hit;
        String block = TestLog.block(level, hit.x, hit.y, hit.z);
        TestLog.s(bullet.getEntityId(), "block", "block", block, "pos", TestLog.pos(hit), "at", TestLog.point(hit),
                "material", TestLog.material(level, hit), "result", "stop", "why", why, "damage", bullet.damage,
                "path", Float.isNaN(path) ? null : path);
        TestLog.s(bullet.getEntityId(), "end", "by", "block", "block", block, "pos", TestLog.pos(hit), "at", TestLog.point(hit));
        return hit;
    }

    private static void worn(EntityBulletBaseMK4 bullet, String block, int x, int y, int z, String result, Object... more) {
        if (!TestLog.server) return;
        Object[] kv = new Object[8 + more.length];
        Object[] head = {"block", block, "pos", new int[] {x, y, z}, "result", result, "damage", bullet.damage};
        System.arraycopy(head, 0, kv, 0, 8);
        System.arraycopy(more, 0, kv, 8, more.length);
        TestLog.s(bullet.getEntityId(), "wear", kv);
    }

    // one hit on the block by its rule: true when it broke. k: the share of the bullet's damage left there (water,
    // WaterTick.k)
    private static boolean wear(WorldServer level, EntityBulletBaseMK4 bullet, int x, int y, int z, Block block, int meta, BulletRules.Break rule,
            float k) {
        float damage = bullet.damage * k;
        String name = TestLog.server ? TestLog.block(level, x, y, z) : "";
        float hardness = PassableClip.hardness(level, block, x, y, z);
        if (hardness < 0F) { // bedrock and the like
            worn(bullet, name, x, y, z, "unbreakable");
            return false;
        }
        Entity owner = bullet.getThrower();
        if (owner instanceof EntityPlayerMP) {
            // port: 26.2's mayBuild() and BreakBlockEvent: an adventure player or spawn protection keeps it, then the
            // event (claims)
            EntityPlayerMP player = (EntityPlayerMP) owner;
            boolean may = player.capabilities.allowEdit && !net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance().isBlockProtected(level, Mc.pos(x, y, z), player);
            BlockEvent.BreakEvent event = may ? new BlockEvent.BreakEvent(level, Mc.pos(x, y, z), Mc.state(level, x, y, z), player) : null;
            if (event != null) MinecraftForge.EVENT_BUS.post(event);
            if (event == null || event.isCanceled()) {
                worn(bullet, name, x, y, z, "protected");
                return false;
            }
        } else if (!level.getGameRules().getBoolean("mobGriefing")) {
            worn(bullet, name, x, y, z, "no_mob_griefing");
            return false;
        }
        // too weak to harm it
        if (rule.min() > 0F && !(damage > rule.min())) {
            worn(bullet, name, x, y, z, "too_weak", "min", rule.min());
            return false;
        }
        long now = level.getTotalWorldTime();
        forget(now);
        if (rule.mode() == 2 || rule.mode() == 3 || rule.mode() == 4) {
            Spot spot = new Spot(level, x, y, z);
            Wear w = WEAR.get(spot);
            if (w != null && w.block() != block) w = null;
            float add = rule.mode() == 2 ? 1F : Math.max(0F, damage - rule.min());
            float need = rule.mode() == 3 ? rule.amount() * hardness : rule.amount();
            float done = (w == null ? 0F : w.done()) + add;
            if (need > 0F && done < need) {
                WEAR.put(spot, new Wear(done, now, block));
                worn(bullet, name, x, y, z, "wear", "done", done, "need", need, "min", rule.min());
                level.sendBlockBreakProgress(crackId(x, y, z), Mc.pos(x, y, z), Math.min(9, (int) (done / need * 10F)));
                return false;
            }
            WEAR.remove(spot);
            level.sendBlockBreakProgress(crackId(x, y, z), Mc.pos(x, y, z), -1);
        }
        worn(bullet, name, x, y, z, "broke", "min", rule.min());
        Mc.breakBlock(level, x, y, z, rule.drop());
        if (rule.replace() != null) {
            Object[] with = parse(rule.replace());
            if (with != null) Mc.setBlock(level, x, y, z, (Block) with[0], (Integer) with[1], 3);
        }
        return true;
    }

    private static void forget(long now) {
        Iterator<Map.Entry<Spot, Wear>> it = WEAR.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Spot, Wear> e = it.next();
            if (now - e.getValue().at() < FORGET_TICKS) continue;
            Spot s = e.getKey();
            s.level().sendBlockBreakProgress(crackId(s.x(), s.y(), s.z()), Mc.pos(s.x(), s.y(), s.z()), -1);
            it.remove();
        }
    }

    private static int ticks;

    // every few server ticks: wear on a block that is no longer there (broken, replaced, its chunk gone) goes with its cracks
    public static void tick() {
        if (WEAR.isEmpty() || ++ticks % 5 != 0) return;
        Iterator<Map.Entry<Spot, Wear>> it = WEAR.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Spot, Wear> e = it.next();
            Spot s = e.getKey();
            WorldServer level = s.level();
            boolean gone = !level.isBlockLoaded(Mc.pos(s.x(), s.y(), s.z())) || Mc.block(level, s.x(), s.y(), s.z()) != e.getValue().block();
            if (!gone && level.getTotalWorldTime() - e.getValue().at() < FORGET_TICKS) continue;
            level.sendBlockBreakProgress(crackId(s.x(), s.y(), s.z()), Mc.pos(s.x(), s.y(), s.z()), -1);
            it.remove();
        }
    }

    // a breaker id per block for its cracks, apart from the entity ids players use
    private static int crackId(int x, int y, int z) {
        // port: 26.2 hashes its BlockPos; the same spread of ids from x, y, z
        int h = (y + z * 31) * 31 + x;
        return -1_000_000 - (h & 0x3fffffff);
    }

    private static final Map<String, Object[]> PARSED = new HashMap<>();

    // port: "id" or "id:meta" (26.2: a block state); null when no such block
    private static Object[] parse(String text) {
        if (PARSED.containsKey(text)) return PARSED.get(text);
        Object[] out = null;
        String id = text;
        int meta = 0;
        int last = text.lastIndexOf(':');
        if (last > 0 && text.indexOf(':') != last) {
            try {
                meta = Integer.parseInt(text.substring(last + 1));
                id = text.substring(0, last);
            } catch (NumberFormatException e) {
                // the whole text is the id
            }
        }
        Block b = Block.getBlockFromName(id);
        if (b == null && id.indexOf(':') > 0) b = Block.getBlockFromName(id.substring(0, id.indexOf(':') + 1) + "tile." + id.substring(id.indexOf(':') + 1));
        if (b != null && Mc.material(b) != Material.AIR || "minecraft:air".equals(id)) out = new Object[] {b, meta & 15};
        else GunFeel.LOGGER.warn("Bullet rules: bad replace block {}", text);
        PARSED.put(text, out);
        return out;
    }

    // a bullet hit a creature (alive before the hit): its "entity" or, when it died, "kill" effect
    // `damage` and `health`: the bullet's damage and the creature's health before the hit (NaN: not a living one), for
    // the test log: what it took, and whether NTM counted it in the head (x headshotMult)
    public static void entityHit(EntityBulletBaseMK4 bullet, Entity target, Vec3d at, boolean wasAlive, float damage, float health) {
        if (TestLog.server) {
            float now = target instanceof EntityLivingBase ? ((EntityLivingBase) target).getHealth() : Float.NaN;
            TestLog.s(bullet.getEntityId(), "end", "by", "entity", "entity", BulletRules.entityId(target),
                    "at", at == null ? null : TestLog.point(at.x, at.y, at.z),
                    "killed", wasAlive && !target.isEntityAlive(), "damage", damage, "head", head(bullet, target, at),
                    "headSource", HeadDamage.last(bullet, target, phys(at)).source(), "headReason", HeadDamage.last(bullet, target, phys(at)).reason(),
                    "health", health, "after", now, "took", health - now, "left", bullet.damage, "flies", !bullet.isDead);
        }
        if (!(bullet.world instanceof WorldServer) || !wasAlive) return;
        WorldServer level = (WorldServer) bullet.world;
        boolean dead = target instanceof EntityLivingBase && !target.isEntityAlive();
        play(level, bullet, target, at, BulletRules.entity("entity", target));
        if (dead) play(level, bullet, target, at, BulletRules.entity("kill", target));
    }

    // NTM's headshot test (BulletConfig.LAMBDA_STANDARD_ENTITY_HIT): the hit point in the top two head heights
    private static boolean head(EntityBulletBaseMK4 bullet, Entity target, Vec3d at) {
        if (!(target instanceof EntityLivingBase) || at == null || bullet.config == null || !(bullet.config.headshotMult > 1F)) return false;
        ModelHeads.Hit precise = HeadDamage.last(bullet, target, phys(at));
        if (precise.applied()) return precise.head();
        EntityLivingBase living = (EntityLivingBase) target;
        double head = living.height - living.getEyeHeight();
        return at.y > living.posY + living.height - head * 2.0;
    }

    private static acup.gunfeel.phys.Vec3 phys(Vec3d at) {
        return at == null ? null : new acup.gunfeel.phys.Vec3(at.x, at.y, at.z);
    }

    // the test log: how worn the shooter's gun is (worn / durability) and NTM's factor on its damage by that
    public static String wearText(Entity owner) {
        if (!(owner instanceof EntityLivingBase)) return "-";
        ItemStack stack = ((EntityLivingBase) owner).getHeldItemMainhand();
        if (stack == null || !(stack.getItem() instanceof com.hbm.items.weapon.sedna.ItemGunBaseNT)) return "-";
        com.hbm.items.weapon.sedna.ItemGunBaseNT gun = (com.hbm.items.weapon.sedna.ItemGunBaseNT) stack.getItem();
        float dura = gun.getConfig(stack, 0).getDurability(stack), worn = com.hbm.items.weapon.sedna.ItemGunBaseNT.getWear(stack, 0);
        // port: AmmoStats.wear of 26.2 (the HUD's part): full damage up to 3/4 worn, then down
        float p = dura <= 0 ? 0F : worn / dura;
        float factor = dura <= 0 ? 1F : p < 0.75F ? 1F : 1F - (p - 0.75F) * 2F;
        return String.format(java.util.Locale.ROOT, "%.0f/%.0f x%.2f", worn, dura, factor);
    }

    private static void play(WorldServer level, EntityBulletBaseMK4 bullet, Entity target, Vec3d at, BulletRules.Effect fx) {
        if (fx.empty()) return;
        double px = at == null ? target.posX : at.x, py = at == null ? target.posY + target.height / 2 : at.y,
                pz = at == null ? target.posZ : at.z;
        for (BulletRules.Particle part : fx.particles()) {
            String o = particle(part.id(), null, 0);
            if (o != null) Mc.spawnAll(level, o, px, py, pz, part.count(), 0.1, 0.1, 0.1, 0.05);
        }
        BulletRules.Sound s = fx.sound();
        if (s == null) return;
        // port: 1.12.2 plays a SoundEvent by its id (Mc.sound)
        String id = s.id();
        float pitch = s.pitch() * (0.95F + level.rand.nextFloat() * 0.1F);
        if (fx.toAll()) {
            Mc.playSound(level, px, py, pz, id, s.volume(), pitch);
        } else if (bullet.getThrower() instanceof EntityPlayerMP) {
            Mc.playSoundTo((EntityPlayerMP) bullet.getThrower(), px, py, pz, id, s.volume(), pitch);
        }
    }

    // port: a particle from its rule name as 1.7.10 names it: "block" (of `hit`) and "block:<id>" the block's crack
    // ("blockcrack_<id>_<meta>"), a particle id without its "minecraft:" ("minecraft:smoke" is "smoke")
    static String particle(String name, Block hit, int meta) {
        if (name.equalsIgnoreCase("block")) return hit == null ? null : "blockcrack_" + Block.getIdFromBlock(hit) + "_" + meta;
        if (name.startsWith("block:")) {
            Object[] b = parse(name.substring(6));
            return b == null ? null : "blockcrack_" + Block.getIdFromBlock((Block) b[0]) + "_" + b[1];
        }
        int c = name.indexOf(':');
        return c < 0 ? name : name.substring(c + 1);
    }
}
