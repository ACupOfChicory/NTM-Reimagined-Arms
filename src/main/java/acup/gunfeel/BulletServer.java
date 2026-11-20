package acup.gunfeel;

import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.host.Body;

import com.hbm.entity.projectile.EntityBulletBaseMK4;

import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;

// Server side of BulletRules: blocks bullets break, and what a creature hit or killed by one gives off.
public final class BulletServer {
    private BulletServer() {
    }

    // what bullets did to blocks not broken yet (core: BlockWear), by place (BlockPos.asLong) and block; the cracks
    // and what is gone are the game's
    private static final BulletServerBase BLOCKS = new BulletServerBase();
    private static final BulletEffectsBase EFFECTS = new BulletEffectsBase();

    // dev (NpcShotSmoke): every block as if no bullet had hit it yet
    static void forgetWear() {
        BLOCKS.forgetWear();
    }

    // dev (BulletCheck): the wear as it is now, and back to it (a beam fired on the same block as a bullet)
    static Object wearNow() {
        return BLOCKS.wearNow();
    }

    static void wearBack(Object was) {
        BLOCKS.wearBack(was);
    }

    // NTM's old bullet (EntityBullet: the hunter chopper's, the cyber crab's) hit this block, as blockHit; it has no
    // BulletConfig and no water: its damage and who fired it are given (LegacyBullets)
    public static int blockHit(Entity bullet, @Nullable Entity owner, float damage, BlockHitResult hit) {
        return blockHit(bullet.level(), bullet.getId(), owner, damage, hit);
    }

    // the same for a shot with no entity of its own (a turret's or a Tesla coil's beam): id only for the test log
    public static int blockHit(Level world, int id, @Nullable Entity owner, float damage, BlockHitResult hit) {
        if (!(world instanceof ServerLevel level)) return 0;
        BlockPos pos = hit.getBlockPos().immutable();
        return BLOCKS.blockHit(level, acup.gunfeel.host.Worlds.of(level), pos, pos.getX(), pos.getY(), pos.getZ(),
                null, id, Reads.body(owner), damage, hit);
    }

    // the bullet hit this block: 0 = nothing happened, 1 = it broke and the bullet stops, 2 = it broke and the bullet
    // flies on
    public static int blockHit(EntityBulletBaseMK4 bullet, BlockHitResult hit) {
        if (!(bullet.level() instanceof ServerLevel level)) return 0;
        BlockPos pos = hit.getBlockPos().immutable();
        return BLOCKS.blockHit(level, acup.gunfeel.host.Worlds.of(level), pos, pos.getX(), pos.getY(), pos.getZ(),
                bullet, 0, null, 0F, hit);
    }

    private static final net.minecraft.sounds.SoundEvent RICOCHET_STEEL = net.minecraft.sounds.SoundEvent.createVariableRangeEvent(
            net.minecraft.resources.Identifier.fromNamespaceAndPath("ntmgunfeel", "bullet.ricochet.steel"));

    // NTM's ricochet sound (MixinRicochetSound): off a block whose hit sound is steel (bullet rules; a multiblock's
    // cell by its machine) our steel ricochet where the bullet hit instead, and true; else false (NTM's own plays)
    public static boolean ricochetSound(Level level, net.minecraft.world.phys.HitResult mop) {
        if (!(mop instanceof BlockHitResult hit) || level.isClientSide()) return false;
        var world = acup.gunfeel.host.Worlds.of(level);
        BlockPos pos = hit.getBlockPos();
        Vec3 at = hit.getLocation();
        return EFFECTS.ricochetSound(level, world, true, pos, pos.getX(), pos.getY(), pos.getZ(), at.x, at.y, at.z);
    }

    // a shot without an MK4 bullet (an NTM beam, BeamServer) pierced this block or flew through it ("through"): the
    // block is worn as by a bullet of that damage
    static void wearBy(Level world, int id, @Nullable Entity owner, float damage, BlockHitResult hit) {
        if (!(world instanceof ServerLevel level)) return;
        BlockPos pos = hit.getBlockPos().immutable();
        BLOCKS.wearBy(level, acup.gunfeel.host.Worlds.of(level), pos, pos.getX(), pos.getY(), pos.getZ(),
                null, id, Reads.body(owner), damage, hit);
    }

    // the bullet flew through a block it always flies through ("through"): counts as a hit on it
    public static void passed(EntityBulletBaseMK4 bullet, BlockHitResult hit) {
        if (!(bullet.level() instanceof ServerLevel level)) return;
        BlockPos pos = hit.getBlockPos().immutable();
        BLOCKS.passed(level, acup.gunfeel.host.Worlds.of(level), pos, pos.getX(), pos.getY(), pos.getZ(),
                bullet, 0, null, 0F, hit);
    }

    // the bullet's path this tick through blocks, piercing them while it has the damage (PassableClip.pierced):
    // each one pierced takes its share of the damage (and counts towards breaking it). `paid` = blocks it already
    // pierced (one it ended a tick inside of is not paid for again). The first block that stops it, else a miss at `to`.
    // A creature NTM finds on the path before a pierced block gets the damage from before that block (PierceSteps).
    // The walk's decisions are core's (PierceWalk); here the blocks (Way) and the bullet (Shot) as the game has them.
    public static BlockHitResult clip(EntityBulletBaseMK4 bullet, Level level, Vec3 from, Vec3 to, PaidBlocks paid) {
        java.util.function.Consumer<BlockHitResult> through = hit -> passed(bullet, hit);
        PierceSteps steps = PierceSteps.of(bullet);
        steps.start(from);
        // an explosive bullet goes off on the first block it meets, never pierces. A mob's laser shot goes through glass
        // (BeamLasers)
        if (bullet.config != null && bullet.config.onImpact != null)
            return stop(bullet, level, BeamLasers.clip(level, bullet.config, from, to, through), "explosive", Float.NaN);
        return PierceWalk.clip(new Way(bullet, level, from, to, through), new Shot(bullet, level, steps), from.x, from.y, from.z, to.x, to.y, to.z, paid);
    }

    // the blocks on the bullet's way this tick (PierceWalk.Way): BeamLasers' and PassableClip's
    private record Way(EntityBulletBaseMK4 bullet, Level level, Vec3 from, Vec3 to, java.util.function.Consumer<BlockHitResult> through)
            implements PierceWalk.Way<BlockHitResult> {
        @Override
        public BlockHitResult first(double ax, double ay, double az, double tx, double ty, double tz) {
            // the first step from the tick's own start
            Vec3 at = ax == from.x && ay == from.y && az == from.z ? from : new Vec3(ax, ay, az);
            return BeamLasers.clip(level, bullet.config, at, to, through);
        }

        @Override
        public boolean miss(BlockHitResult hit) {
            return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS;
        }

        @Override
        public double x(BlockHitResult hit) {
            return hit.getLocation().x;
        }

        @Override
        public double y(BlockHitResult hit) {
            return hit.getLocation().y;
        }

        @Override
        public double z(BlockHitResult hit) {
            return hit.getLocation().z;
        }

        @Override
        public long pos(BlockHitResult hit) {
            BlockPos p = hit.getBlockPos();
            return PosLong.block(p.getX(), p.getY(), p.getZ());
        }

        @Override
        public boolean once(BlockHitResult hit) {
            return PassableClip.paidOnce(hit);
        }

        @Override
        public @Nullable BlockHitResult exit(BlockHitResult hit, double dx, double dy, double dz) {
            return PassableClip.exit(level, hit, new Vec3(dx, dy, dz));
        }

        @Override
        public float water(BlockHitResult hit) {
            return acup.gunfeel.water.WaterTick.k(bullet, hit.getLocation());
        }

        @Override
        public BlockHitResult missAt(BlockHitResult last) {
            return BlockHitResult.miss(to, last.getDirection(), BlockPos.containing(to));
        }

        @Override
        public BlockHitResult missUp() {
            return BlockHitResult.miss(to, net.minecraft.core.Direction.UP, BlockPos.containing(to));
        }
    }

    // the bullet on its way (PierceWalk.Shot): its damage, its tick's blocks (PierceSteps), the wear and the test log
    private record Shot(EntityBulletBaseMK4 bullet, Level level, PierceSteps steps) implements PierceWalk.Shot<BlockHitResult> {
        @Override
        public float damage() {
            return bullet.damage;
        }

        @Override
        public double spent() {
            return steps.penetration.spent();
        }

        @Override
        public float left(BlockHitResult in, BlockHitResult out, float damage) {
            return PassableClip.pierced(level, in, out, damage, bullet.config, steps.penetration);
        }

        @Override
        public void pierce(BlockHitResult in, BlockHitResult out, float left, float k, double spentBefore) {
            BlockPos pos = in.getBlockPos().immutable();
            if (TestLog.server) TestLog.s(bullet.getId(), "block", "block", level.getBlockState(pos), "pos", pos, "at", in.getLocation(),
                    "material", WorldLog.material(level, in), "result", "pierce", "damage", bullet.damage, "left", left,
                    "path", in.getLocation().distanceTo(out.getLocation()));
            if (level instanceof ServerLevel server) {
                BlockState state = level.getBlockState(pos);
                BulletRuleText.Break rule = BulletRules.breakRule(state);
                if (rule != null && rule.mode() != 0) wear(server, bullet, pos, state, rule, k);
            }
            steps.pierced(in, out, bullet.damage, spentBefore);
            bullet.damage = left;
        }

        @Override
        public BlockHitResult stop(BlockHitResult hit, @Nullable BlockHitResult out, String why) {
            return BulletServer.stop(bullet, level, hit, why, out == null ? Float.NaN : (float) hit.getLocation().distanceTo(out.getLocation()));
        }
    }

    // the test log: the bullet stops at this block (NTM's onImpact takes it from here: a ricochet, a break rule)
    private static BlockHitResult stop(EntityBulletBaseMK4 bullet, Level level, BlockHitResult hit, String why, float path) {
        if (!TestLog.server || level.isClientSide() || hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS) return hit;
        BlockPos pos = hit.getBlockPos();
        TestLog.s(bullet.getId(), "block", "block", level.getBlockState(pos), "pos", pos, "at", hit.getLocation(), "material", WorldLog.material(level, hit),
                "result", "stop", "why", why, "damage", bullet.damage, "path", Float.isNaN(path) ? null : path);
        TestLog.s(bullet.getId(), "end", "by", "block", "block", level.getBlockState(pos), "pos", pos, "at", hit.getLocation());
        return hit;
    }

    // Used by Shot.pierce until the path moves to core too.
    private static boolean wear(ServerLevel level, EntityBulletBaseMK4 bullet, BlockPos pos, BlockState state, BulletRuleText.Break rule, float k) {
        return BLOCKS.wearShot(level, acup.gunfeel.host.Worlds.of(level), bullet, pos,
                pos.getX(), pos.getY(), pos.getZ(), BlockStates.number(state), rule, k);
    }

    public static void tick() { BLOCKS.tick(); }

    /** 26.2's world writes and bullet block reads. */
    public static final class Reads implements acup.gunfeel.host.WorldEdit, acup.gunfeel.host.BulletBlocks, acup.gunfeel.host.ShotEffects {
        private static final Map<String, BlockState> PARSED = new HashMap<>();

        private static @Nullable Body body(@Nullable Entity owner) {
            return owner == null ? null : EntityBody.of(owner);
        }

        @Override public BulletRuleText.@Nullable Break rule(int state) { return BulletRules.breakRule(BlockStates.state(state)); }
        @Override public float water(Object bullet, Object hit) {
            return acup.gunfeel.water.WaterTick.k((EntityBulletBaseMK4) bullet, ((BlockHitResult) hit).getLocation());
        }
        @Override public BulletServerBase.Shooter shooter(Object shot) {
            EntityBulletBaseMK4 bullet = (EntityBulletBaseMK4) shot;
            int id = bullet.getId();
            Entity owner = bullet.getOwner();
            float damage = bullet.damage;
            return new BulletServerBase.Shooter(id, body(owner), damage);
        }
        @Override public int shotId(Object bullet) { return ((EntityBulletBaseMK4) bullet).getId(); }
        @Override public Object location(Object hit) { return ((BlockHitResult) hit).getLocation(); }

        /** The machine's rule state (26.2: Cells.ruleState, then BlockStates.number). */
        @Override public int ruleState(Object level, Object pos, int state) {
            BulletRules.init();
            return BlockStates.number(Cells.ruleState((Level) level, (BlockPos) pos, BlockStates.state(state)));
        }
        /** The diagnostic material (26.2: WorldLog.material). */
        @Override public String material(Object level, Object pos) { return WorldLog.material((Level) level, (BlockPos) pos); }
        /** Resolve a particle (26.2: BulletServer.particle). */
        @Override public @Nullable Object particle(String name, int hitState) {
            return BulletServer.particle(name, hitState < 0 ? null : BlockStates.state(hitState));
        }
        @Override public @Nullable Body owner(Object bullet) { return Reads.body(((EntityBulletBaseMK4) bullet).getOwner()); }
        /** Emit particles (26.2: ServerLevel.sendParticles). */
        @Override public void particles(Object level, Object particle, double x, double y, double z, int count) {
            ((ServerLevel) level).sendParticles((ParticleOptions) particle, x, y, z, count, 0.1, 0.1, 0.1, 0.05);
        }
        /** Resolve a sound (26.2: Identifier.tryParse, registry or direct Holder). */
        @Override public @Nullable Object sound(String name) {
            Identifier id = Identifier.tryParse(name);
            if (id == null) return null;
            return BuiltInRegistries.SOUND_EVENT.get(id).<Holder<SoundEvent>>map(h -> h)
                    .orElseGet(() -> Holder.direct(SoundEvent.createVariableRangeEvent(id)));
        }
        /** Play nearby (26.2: Level.playSound, PLAYERS). */
        @SuppressWarnings("unchecked")
        @Override public void playAll(Object level, Object sound, double x, double y, double z, float volume, float pitch) {
            if (sound instanceof SoundEvent event) ((Level) level).playSound(null, x, y, z, event, SoundSource.PLAYERS, volume, pitch);
            else ((Level) level).playSound(null, x, y, z, (Holder<SoundEvent>) sound, SoundSource.PLAYERS, volume, pitch);
        }
        /** Send to one player (26.2: ClientboundSoundPacket with the given seed). */
        @SuppressWarnings("unchecked")
        @Override public void playTo(Body player, Object sound, double x, double y, double z, float volume, float pitch, long seed) {
            ((ServerPlayer) EntityBody.entity(player)).connection.send(new ClientboundSoundPacket((Holder<SoundEvent>) sound,
                    SoundSource.PLAYERS, x, y, z, volume, pitch, seed));
        }
        /** The permanent steel sound (26.2: BulletServer.RICOCHET_STEEL). */
        @Override public Object ricochetSteel() { return RICOCHET_STEEL; }

        @Override public void cracks(Object level, int breaker, Object pos, int stage) {
            ((Level) level).destroyBlockProgress(breaker, (BlockPos) pos, stage);
        }

        @Override public void destroy(Object level, Object pos, boolean drop, @Nullable Body by) {
            ((Level) level).destroyBlock((BlockPos) pos, drop, EntityBody.entity(by));
        }

        @Override public void set(Object level, Object pos, int stateNumber, int flags) {
            ((Level) level).setBlock((BlockPos) pos, BlockStates.state(stateNumber), flags);
        }

        @Override public int mayBreak(Object world, @Nullable Body by, Object pos, int state) {
            ServerLevel level = (ServerLevel) world;
            Entity owner = EntityBody.entity(by);
            if (owner instanceof ServerPlayer player) {
                BreakBlockEvent event = player.mayBuild() ? new BreakBlockEvent(level, (BlockPos) pos, BlockStates.state(state), player) : null;
                if (event != null) NeoForge.EVENT_BUS.post(event);
                if (event == null || event.isCanceled()) return PROTECTED;
            } else if (!level.getGameRules().get(GameRules.MOB_GRIEFING)) {
                return NO_MOB_GRIEFING;
            }
            return ALLOW;
        }

        @Override public int parseState(Object world, String text) {
            ServerLevel level = (ServerLevel) world;
            if (PARSED.containsKey(text)) {
                BlockState cached = PARSED.get(text);
                return cached == null ? -1 : BlockStates.number(cached);
            }
            BlockState out = null;
            try {
                out = BlockStateParser.parseForBlock(level.registryAccess().lookupOrThrow(Registries.BLOCK), text, false).blockState();
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger("ntm-gunfeel").warn("Bullet rules: bad replace block state {}", text);
            }
            PARSED.put(text, out);
            return out == null ? -1 : BlockStates.number(out);
        }

        @Override public Object blockToken(int state) { return BlockStates.state(state).getBlock(); }
        @Override public boolean gone(Object world, long pos, Object token) {
            ServerLevel level = (ServerLevel) world;
            BlockPos at = BlockPos.of(pos);
            return !level.isLoaded(at) || level.getBlockState(at).getBlock() != token;
        }
        @Override public long time(Object level) { return ((ServerLevel) level).getGameTime(); }
        @Override public Object position(long pos) { return BlockPos.of(pos); }
        @Override public Object stateObject(int state) { return BlockStates.state(state); }
    }


    // a bullet hit a creature (alive before the hit): its "entity" or, when it died, "kill" effect
    // `damage` and `health`: the bullet's damage and the creature's health before the hit (NaN: not a living one), for
    // the test log: what it took, and whether NTM counted it in the head (x headshotMult)
    public static void entityHit(EntityBulletBaseMK4 bullet, Entity target, Vec3 at, boolean wasAlive, float damage, float health) {
        if (TestLog.server) {
            float now = target instanceof LivingEntity l ? l.getHealth() : Float.NaN;
            TestLog.s(bullet.getId(), "end", "by", "entity", "entity", BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()), "at", at,
                    "killed", wasAlive && !target.isAlive(), "damage", damage, "head", head(bullet, target, at),
                    "headSource", HeadDamage.last(bullet,target,at).source(), "headReason", HeadDamage.last(bullet,target,at).reason(),
                    "health", health, "after", now, "took", health - now, "left", bullet.damage, "flies", !bullet.isRemoved());
        }
        if (!(bullet.level() instanceof ServerLevel level) || !wasAlive) return;
        var world = acup.gunfeel.host.Worlds.of(level);
        BulletRules.init();
        EFFECTS.entityHit(level, world, true, bullet, EntityBody.of(target),
                at != null, at == null ? 0 : at.x, at == null ? 0 : at.y, at == null ? 0 : at.z, wasAlive);
    }

    // NTM's headshot test (BulletConfig.LAMBDA_STANDARD_ENTITY_HIT): the hit point in the top two head heights
    private static boolean head(EntityBulletBaseMK4 bullet, Entity target, @org.jspecify.annotations.Nullable Vec3 at) {
        if (!(target instanceof LivingEntity living) || at == null || bullet.config == null || !(bullet.config.headshotMult > 1F)) return false;
        var precise=HeadDamage.last(bullet,target,at);
        if(precise.applied()) return precise.head();
        return HeadHeight.byHeight(at.y, living.getY(), living.getBbHeight(), living.getEyeHeight());
    }

    // the test log: how worn the shooter's gun is (worn / durability) and NTM's factor on its damage by that
    public static String wearText(@org.jspecify.annotations.Nullable Entity owner) {
        if (!(owner instanceof LivingEntity le) || !(le.getMainHandItem().getItem() instanceof com.hbm.items.weapon.sedna.ItemGunBaseNT gun)) return "-";
        net.minecraft.world.item.ItemStack stack = le.getMainHandItem();
        float dura = gun.getConfig(stack, 0).getDurability(stack), worn = com.hbm.items.weapon.sedna.ItemGunBaseNT.getWear(stack, 0);
        return String.format(java.util.Locale.ROOT, "%.0f/%.0f x%.2f", worn, dura, AmmoStats.wear(worn, dura));
    }

    // a particle from its rule name: "block" (of `hit`), "block:<id>", or a plain particle id
    static @Nullable ParticleOptions particle(String name, @Nullable BlockState hit) {
        if (name.equalsIgnoreCase("block")) return hit == null ? null : new BlockParticleOption(ParticleTypes.BLOCK, hit);
        if (name.startsWith("block:")) {
            Identifier id = Identifier.tryParse(name.substring(6));
            if (id == null) return null;
            return BuiltInRegistries.BLOCK.getOptional(id).map(b -> (ParticleOptions) new BlockParticleOption(ParticleTypes.BLOCK, b.defaultBlockState())).orElse(null);
        }
        Identifier id = Identifier.tryParse(name);
        if (id == null) return null;
        return BuiltInRegistries.PARTICLE_TYPE.getOptional(id).filter(t -> t instanceof SimpleParticleType).map(t -> (ParticleOptions) (SimpleParticleType) t).orElse(null);
    }
}
