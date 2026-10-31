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
import net.minecraft.network.play.server.S29PacketSoundEffect;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.BlockEvent;

// port: copied from 26.2 (0762086) with changes for 1.7.10, each marked "port:": BlockState -> (Block, meta),
// BlockPos -> x y z, BlockHitResult -> BlockRay.Hit (a miss is null), ServerLevel -> WorldServer; the break event,
// mob griefing, cracks (destroyBlockInWorldPartially), breaking (func_147480_a), particles and sounds of 1.7.10.
// Not here yet: our steel ricochet sound (MixinRicochetSound; the port has no sounds of its own), the mod's head test
// (HeadDamage: the hitboxes' part; NTM's own height test is used).
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
        if (!(bullet.worldObj instanceof WorldServer)) return 0;
        WorldServer level = (WorldServer) bullet.worldObj;
        Block block = level.getBlock(x, y, z);
        if (block.getMaterial() == Material.air) return 0;
        int meta = level.getBlockMetadata(x, y, z);
        BulletRules.Break rule = BulletRules.breakRule(block, meta, level, x, y, z);
        if (rule == null || rule.mode() == 0) return 0;
        String was = TestLog.block(level, x, y, z);
        if (!wear(level, bullet, x, y, z, block, meta, rule)) return 0;
        if (TestLog.server) TestLog.s(bullet.getEntityId(), "end", "by", rule.pass() ? "broke_pass" : "broke", "block", was,
                "pos", new int[] {x, y, z}, "at", TestLog.point(ax, ay, az));
        return rule.pass() ? 2 : 1;
    }

    // the bullet flew through a block it always flies through ("through"): counts as a hit on it
    public static void passed(EntityBulletBaseMK4 bullet, BlockRay.Hit hit) {
        if (!(bullet.worldObj instanceof WorldServer)) return;
        WorldServer level = (WorldServer) bullet.worldObj;
        Block block = level.getBlock(hit.x, hit.y, hit.z);
        int meta = level.getBlockMetadata(hit.x, hit.y, hit.z);
        if (TestLog.server) TestLog.s(bullet.getEntityId(), "through", "block", TestLog.block(level, hit.x, hit.y, hit.z), "pos", TestLog.pos(hit),
                "at", TestLog.point(hit));
        BulletRules.Break rule = BulletRules.breakRule(block, meta, level, hit.x, hit.y, hit.z);
        if (rule == null || rule.mode() == 0) return;
        wear(level, bullet, hit.x, hit.y, hit.z, block, meta, rule);
    }

    // port: a block's place as one number, for the blocks a bullet paid for
    public static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) y & 0xFFFL) << 26 | (long) z & 0x3FFFFFFL;
    }

    // the bullet's path this tick through blocks, piercing them while it has the damage (PassableClip.pierced):
    // each one pierced takes its share of the damage (and counts towards breaking it). `paid` = blocks it already
    // pierced (one it ended a tick inside of is not paid for again). The first block that stops it, else null (a miss).
    // A creature NTM finds on the path before a pierced block gets the damage from before that block (PierceSteps).
    public static BlockRay.Hit clip(EntityBulletBaseMK4 bullet, World level, Vec3 from, Vec3 to, Set<Long> paid) {
        Consumer<BlockRay.Hit> through = hit -> passed(bullet, hit);
        PierceSteps steps = PierceSteps.of(bullet);
        steps.start(from.xCoord, from.yCoord, from.zCoord);
        // an explosive bullet goes off on the first block it meets, never pierces
        if (bullet.config != null && bullet.config.onImpact != null) return stop(bullet, level, PassableClip.clip(level, from, to, through), "explosive", Float.NaN);
        Vec3 at = from;
        for (int i = 0; i < 16; i++) {
            BlockRay.Hit hit = PassableClip.clip(level, at, to, through);
            if (hit == null) return null;
            double dx = to.xCoord - at.xCoord, dy = to.yCoord - at.yCoord, dz = to.zCoord - at.zCoord;
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 1e-6) return hit;
            dx /= len;
            dy /= len;
            dz /= len;
            long pos = key(hit.x, hit.y, hit.z);
            boolean known = paid.contains(pos);
            BlockRay.Hit out = PassableClip.exit(level, hit, dx, dy, dz);
            if (out == null) return stop(bullet, level, hit, "no_exit", Float.NaN);
            double spentBefore = steps.penetration.spent();
            float left = known ? bullet.damage : PassableClip.pierced(level, hit, out, bullet.damage, bullet.config, steps.penetration);
            if (left < 0F) return stop(bullet, level, hit, "held", (float) hit.distance(out.ax, out.ay, out.az));
            if (TestLog.server && !known) TestLog.s(bullet.getEntityId(), "block", "block", TestLog.block(level, hit.x, hit.y, hit.z), "pos", TestLog.pos(hit),
                    "at", TestLog.point(hit), "material", TestLog.material(level, hit.x, hit.y, hit.z), "result", "pierce", "damage", bullet.damage,
                    "left", left, "path", hit.distance(out.ax, out.ay, out.az));
            if (!known) {
                paid.add(pos);
                if (level instanceof WorldServer) {
                    Block block = level.getBlock(hit.x, hit.y, hit.z);
                    int meta = level.getBlockMetadata(hit.x, hit.y, hit.z);
                    BulletRules.Break rule = BulletRules.breakRule(block, meta, level, hit.x, hit.y, hit.z);
                    if (rule != null && rule.mode() != 0) wear((WorldServer) level, bullet, hit.x, hit.y, hit.z, block, meta, rule);
                }
                steps.pierced(hit, out, bullet.damage, spentBefore);
                bullet.damage = left;
            }
            // it comes out beyond this tick's end: it stays inside the block till the next tick
            if (out.distanceSq(at.xCoord, at.yCoord, at.zCoord) >= len * len) return null;
            at = Vec3.createVectorHelper(out.ax + dx * 1e-3, out.ay + dy * 1e-3, out.az + dz * 1e-3);
        }
        return null;
    }

    // the test log: the bullet stops at this block (NTM's onImpact takes it from here: a ricochet, a break rule)
    private static BlockRay.Hit stop(EntityBulletBaseMK4 bullet, World level, BlockRay.Hit hit, String why, float path) {
        if (!TestLog.server || level.isRemote || hit == null) return hit;
        String block = TestLog.block(level, hit.x, hit.y, hit.z);
        TestLog.s(bullet.getEntityId(), "block", "block", block, "pos", TestLog.pos(hit), "at", TestLog.point(hit),
                "material", TestLog.material(level, hit.x, hit.y, hit.z), "result", "stop", "why", why, "damage", bullet.damage,
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

    // one hit on the block by its rule: true when it broke
    private static boolean wear(WorldServer level, EntityBulletBaseMK4 bullet, int x, int y, int z, Block block, int meta, BulletRules.Break rule) {
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
            boolean may = player.capabilities.allowEdit && !MinecraftServer.getServer().isBlockProtected(level, x, y, z, player);
            BlockEvent.BreakEvent event = may ? new BlockEvent.BreakEvent(x, y, z, level, block, meta, player) : null;
            if (event != null) MinecraftForge.EVENT_BUS.post(event);
            if (event == null || event.isCanceled()) {
                worn(bullet, name, x, y, z, "protected");
                return false;
            }
        } else if (!level.getGameRules().getGameRuleBooleanValue("mobGriefing")) {
            worn(bullet, name, x, y, z, "no_mob_griefing");
            return false;
        }
        // too weak to harm it
        if (rule.min() > 0F && !(bullet.damage > rule.min())) {
            worn(bullet, name, x, y, z, "too_weak", "min", rule.min());
            return false;
        }
        long now = level.getTotalWorldTime();
        forget(now);
        if (rule.mode() == 2 || rule.mode() == 3 || rule.mode() == 4) {
            Spot spot = new Spot(level, x, y, z);
            Wear w = WEAR.get(spot);
            if (w != null && w.block() != block) w = null;
            float add = rule.mode() == 2 ? 1F : Math.max(0F, bullet.damage - rule.min());
            float need = rule.mode() == 3 ? rule.amount() * hardness : rule.amount();
            float done = (w == null ? 0F : w.done()) + add;
            if (need > 0F && done < need) {
                WEAR.put(spot, new Wear(done, now, block));
                worn(bullet, name, x, y, z, "wear", "done", done, "need", need, "min", rule.min());
                level.destroyBlockInWorldPartially(crackId(x, y, z), x, y, z, Math.min(9, (int) (done / need * 10F)));
                return false;
            }
            WEAR.remove(spot);
            level.destroyBlockInWorldPartially(crackId(x, y, z), x, y, z, -1);
        }
        worn(bullet, name, x, y, z, "broke", "min", rule.min());
        level.func_147480_a(x, y, z, rule.drop());
        if (rule.replace() != null) {
            Object[] with = parse(rule.replace());
            if (with != null) level.setBlock(x, y, z, (Block) with[0], (Integer) with[1], 3);
        }
        return true;
    }

    private static void forget(long now) {
        Iterator<Map.Entry<Spot, Wear>> it = WEAR.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Spot, Wear> e = it.next();
            if (now - e.getValue().at() < FORGET_TICKS) continue;
            Spot s = e.getKey();
            s.level().destroyBlockInWorldPartially(crackId(s.x(), s.y(), s.z()), s.x(), s.y(), s.z(), -1);
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
            boolean gone = !level.blockExists(s.x(), s.y(), s.z()) || level.getBlock(s.x(), s.y(), s.z()) != e.getValue().block();
            if (!gone && level.getTotalWorldTime() - e.getValue().at() < FORGET_TICKS) continue;
            level.destroyBlockInWorldPartially(crackId(s.x(), s.y(), s.z()), s.x(), s.y(), s.z(), -1);
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
        if (b != null && b.getMaterial() != Material.air || "minecraft:air".equals(id)) out = new Object[] {b, meta & 15};
        else GunFeel.LOGGER.warn("Bullet rules: bad replace block {}", text);
        PARSED.put(text, out);
        return out;
    }

    // a bullet hit a creature (alive before the hit): its "entity" or, when it died, "kill" effect
    // `damage` and `health`: the bullet's damage and the creature's health before the hit (NaN: not a living one), for
    // the test log: what it took, and whether NTM counted it in the head (x headshotMult)
    public static void entityHit(EntityBulletBaseMK4 bullet, Entity target, Vec3 at, boolean wasAlive, float damage, float health) {
        if (TestLog.server) {
            float now = target instanceof EntityLivingBase ? ((EntityLivingBase) target).getHealth() : Float.NaN;
            TestLog.s(bullet.getEntityId(), "end", "by", "entity", "entity", BulletRules.entityId(target),
                    "at", at == null ? null : TestLog.point(at.xCoord, at.yCoord, at.zCoord),
                    "killed", wasAlive && !target.isEntityAlive(), "damage", damage, "head", head(bullet, target, at),
                    // port: the mod's head test (HeadDamage) is the hitboxes' part; here NTM's own by height
                    "headSource", "height", "headReason", "-",
                    "health", health, "after", now, "took", health - now, "left", bullet.damage, "flies", !bullet.isDead);
        }
        if (!(bullet.worldObj instanceof WorldServer) || !wasAlive) return;
        WorldServer level = (WorldServer) bullet.worldObj;
        boolean dead = target instanceof EntityLivingBase && !target.isEntityAlive();
        play(level, bullet, target, at, BulletRules.entity("entity", target));
        if (dead) play(level, bullet, target, at, BulletRules.entity("kill", target));
    }

    // NTM's headshot test (BulletConfig.LAMBDA_STANDARD_ENTITY_HIT): the hit point in the top two head heights
    private static boolean head(EntityBulletBaseMK4 bullet, Entity target, Vec3 at) {
        if (!(target instanceof EntityLivingBase) || at == null || bullet.config == null || !(bullet.config.headshotMult > 1F)) return false;
        EntityLivingBase living = (EntityLivingBase) target;
        double head = living.height - living.getEyeHeight();
        return at.yCoord > living.posY + living.height - head * 2.0;
    }

    // the test log: how worn the shooter's gun is (worn / durability) and NTM's factor on its damage by that
    public static String wearText(Entity owner) {
        if (!(owner instanceof EntityLivingBase)) return "-";
        ItemStack stack = ((EntityLivingBase) owner).getHeldItem();
        if (stack == null || !(stack.getItem() instanceof com.hbm.items.weapon.sedna.ItemGunBaseNT)) return "-";
        com.hbm.items.weapon.sedna.ItemGunBaseNT gun = (com.hbm.items.weapon.sedna.ItemGunBaseNT) stack.getItem();
        float dura = gun.getConfig(stack, 0).getDurability(stack), worn = com.hbm.items.weapon.sedna.ItemGunBaseNT.getWear(stack, 0);
        // port: AmmoStats.wear of 26.2 (the HUD's part): full damage up to 3/4 worn, then down
        float p = dura <= 0 ? 0F : worn / dura;
        float factor = dura <= 0 ? 1F : p < 0.75F ? 1F : 1F - (p - 0.75F) * 2F;
        return String.format(java.util.Locale.ROOT, "%.0f/%.0f x%.2f", worn, dura, factor);
    }

    private static void play(WorldServer level, EntityBulletBaseMK4 bullet, Entity target, Vec3 at, BulletRules.Effect fx) {
        if (fx.empty()) return;
        double px = at == null ? target.posX : at.xCoord, py = at == null ? target.posY + target.height / 2 : at.yCoord,
                pz = at == null ? target.posZ : at.zCoord;
        for (BulletRules.Particle part : fx.particles()) {
            String o = particle(part.id(), null, 0);
            if (o != null) level.func_147487_a(o, px, py, pz, part.count(), 0.1, 0.1, 0.1, 0.05);
        }
        BulletRules.Sound s = fx.sound();
        if (s == null) return;
        // port: 1.7.10 plays sounds by name ("minecraft:" is left out of vanilla's)
        String id = s.id().startsWith("minecraft:") ? s.id().substring(10) : s.id();
        float pitch = s.pitch() * (0.95F + level.rand.nextFloat() * 0.1F);
        if (fx.toAll()) {
            level.playSoundEffect(px, py, pz, id, s.volume(), pitch);
        } else if (bullet.getThrower() instanceof EntityPlayerMP) {
            ((EntityPlayerMP) bullet.getThrower()).playerNetServerHandler.sendPacket(new S29PacketSoundEffect(id, px, py, pz, s.volume(), pitch));
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
