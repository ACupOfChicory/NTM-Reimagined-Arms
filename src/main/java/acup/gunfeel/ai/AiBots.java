package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.smoke.SmokeClouds;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * The AI mobs of each level and what they can sense: the targets (players in survival or adventure, and any living
 * thing tagged {@value #TARGET_TAG}, for stands), the sounds the server plays and the hits they take.
 * Server thread only.
 */
public final class AiBots {
    public static final String TARGET_TAG = "armsai_target";
    /** sounds this close to an AI mob are its own or its fellows' (steps, shots): not news */
    static final double OWN_SOUND = 1.5;
    /** a sound that carries this far is loud: a shot, a blast */
    static final double LOUD = 24;
    /** the server key: the blocks from the nearest player within which AI mobs think (32 to 256) */
    public static final String RADIUS_KEY = "aiRadius";
    /** the cells kept built around each AI mob, blocks across */
    static final int WANT_RADIUS = 24;

    private AiBots() {
    }

    static final class PerLevel {
        final ServerLevel level;
        final Int2ObjectOpenHashMap<AiBot> bots = new Int2ObjectOpenHashMap<>();
        final List<LivingEntity> targets = new ArrayList<>();
        long targetsTick = Long.MIN_VALUE;
        final LiveVoxels voxels;
        /** cover cells held: cell -> the mob's id */
        final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap claims = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
        /** mob id -> its cell */
        final it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap claimOf = new it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap();

        PerLevel(ServerLevel level) {
            this.level = level;
            this.voxels = new LiveVoxels(level);
        }
    }

    private static final Map<ServerLevel, PerLevel> LEVELS = new WeakHashMap<>();

    public static AiBot attach(Mob mob, AiProfile profile) {
        PerLevel l = LEVELS.computeIfAbsent((ServerLevel) mob.level(), PerLevel::new);
        AiBot bot = l.bots.get(mob.getId());
        if (bot == null || bot.mob != mob) {
            bot = new AiBot(mob, profile);
            l.bots.put(mob.getId(), bot);
        }
        return bot;
    }

    public static @Nullable AiBot get(Entity e) {
        if (!(e.level() instanceof ServerLevel level)) return null;
        PerLevel l = LEVELS.get(level);
        if (l == null) return null;
        AiBot bot = l.bots.get(e.getId());
        return bot != null && bot.mob == e ? bot : null;
    }

    public static void detach(Mob mob) {
        if (!(mob.level() instanceof ServerLevel level)) return;
        PerLevel l = LEVELS.get(level);
        if (l == null) return;
        l.bots.remove(mob.getId());
        claimGone(l, mob.getId());
    }

    public static int count(ServerLevel level) {
        PerLevel l = LEVELS.get(level);
        return l == null ? 0 : l.bots.size();
    }

    public static List<AiBot> all(ServerLevel level) {
        PerLevel l = LEVELS.get(level);
        return l == null ? List.of() : new ArrayList<>(l.bots.values());
    }

    /** a bot thinks: the Goal calls this every tick of the mob */
    public static void think(AiBot bot, long tick) {
        PerLevel l = LEVELS.get((ServerLevel) bot.mob.level());
        if (l == null) return;
        bot.tick(l.voxels, SmokeClouds.get(l.level), targets(l, tick), tick);
    }

    /** the mob holds this cover cell now (Long.MIN_VALUE: none); the one it held before is free */
    public static void claim(AiBot bot, long cell) {
        PerLevel l = LEVELS.get((ServerLevel) bot.mob.level());
        if (l == null) return;
        int id = bot.mob.getId();
        if (l.claimOf.containsKey(id)) {
            long old = l.claimOf.remove(id);
            if (l.claims.get(old) == id) l.claims.remove(old);
        }
        if (cell != Long.MIN_VALUE) {
            l.claims.put(cell, id);
            l.claimOf.put(id, cell);
        }
    }

    /** cells another mob holds */
    public static java.util.function.LongPredicate heldByOthers(AiBot bot) {
        PerLevel l = LEVELS.get((ServerLevel) bot.mob.level());
        int id = bot.mob.getId();
        return l == null ? k -> false : k -> l.claims.containsKey(k) && l.claims.get(k) != id;
    }

    public static VoxelRay.Source voxels(ServerLevel level) {
        return LEVELS.computeIfAbsent(level, PerLevel::new).voxels;
    }

    /** the targets of a level, looked up again once a second */
    static List<LivingEntity> targets(PerLevel l, long tick) {
        if (l.targetsTick != Long.MIN_VALUE && tick >= l.targetsTick && tick - l.targetsTick < 20) return l.targets;
        l.targetsTick = tick;
        l.targets.clear();
        for (Player p : l.level.players()) if (p.isAlive() && !p.isSpectator() && !p.isCreative()) l.targets.add(p);
        for (Entity e : l.level.getAllEntities())
            if (e instanceof LivingEntity le && !(e instanceof Player) && le.isAlive() && e.entityTags().contains(TARGET_TAG)) l.targets.add(le);
        return l.targets;
    }

    /** the stands: look the targets up now, not within the second */
    public static void refreshTargets(ServerLevel level) {
        PerLevel l = LEVELS.computeIfAbsent(level, PerLevel::new);
        l.targetsTick = Long.MIN_VALUE;
    }

    public static void tick(ServerLevel level) {
        PerLevel l = LEVELS.get(level);
        if (l == null || l.bots.isEmpty()) return;
        if (level.getGameTime() % 20 != 0) return;
        for (AiBot b : l.bots.values()) if (b.mob.isRemoved()) claimGone(l, b.mob.getId());
        l.bots.values().removeIf(b -> b.mob.isRemoved());
        double r = radius(), r2 = r * r;
        for (AiBot b : l.bots.values()) {
            // here, between the mobs' ticks: a goal set must not change while it ticks
            AiAdapter.strip(b);
            // a player in any mode but spectator (an operator watching in creative too), or a stand's tagged target
            boolean near = false;
            for (Player p : level.players()) {
                if (p.isSpectator()) continue;
                double dx = p.getX() - b.mob.getX(), dz = p.getZ() - b.mob.getZ();
                if (dx * dx + dz * dz <= r2) {
                    near = true;
                    break;
                }
            }
            if (!near) for (LivingEntity t : targets(l, level.getGameTime())) {
                double dx = t.getX() - b.mob.getX(), dz = t.getZ() - b.mob.getZ();
                if (dx * dx + dz * dz <= r2) {
                    near = true;
                    break;
                }
            }
            if (b.active && !near) {
                b.gunner.release();
                b.mover.pause();
            }
            b.active = near;
        }
    }

    private static void claimGone(PerLevel l, int id) {
        if (!l.claimOf.containsKey(id)) return;
        long cell = l.claimOf.remove(id);
        if (l.claims.get(cell) == id) l.claims.remove(cell);
    }

    public static double radius() {
        return Math.max(32, Math.min(256, acup.gunfeel.ReaimTimes.number(RADIUS_KEY, 128F)));
    }

    /** an AI mob's tick, from its goal: senses and memory, its brain, its legs and its gun */
    static void tickBot(AiBot bot) {
        if (!bot.active || !acup.gunfeel.compat.Feature.MOB_AI.on() || !(bot.mob.level() instanceof ServerLevel level)) return;
        PerLevel l = LEVELS.get(level);
        if (l == null) return;
        long tick = level.getGameTime();
        TacticalMap map = TacticalMap.of(level);
        if ((tick + bot.mob.getId()) % 40 == 0) map.want(bot.mob.getBlockX(), bot.mob.getBlockY(), bot.mob.getBlockZ(), WANT_RADIUS, 8);
        bot.tick(l.voxels, SmokeClouds.get(level), targets(l, tick), tick);
        if (bot.brain != null) bot.brain.tick(bot, tick);
        bot.prone.tick(level, bot.mover, tick);
        bot.mover.tick(level, map, tick);
        int reserve = bot.gunner.ammo.reserve();
        bot.gunner.tick(level);
        if (bot.gunner.ammo.reserve() != reserve) AiAdapter.keep(bot);
    }

    public static void forget(ServerLevel level) {
        LEVELS.remove(level);
    }

    // ------------------------------------------------------------------ hearing

    public static void sound(ServerLevel level, double x, double y, double z, Holder<SoundEvent> sound, SoundSource source, float volume) {
        PerLevel l = LEVELS.get(level);
        if (l == null || l.bots.isEmpty()) return;
        switch (source) {
            case MUSIC, RECORDS, AMBIENT, WEATHER, UI, VOICE -> {
                return;
            }
            default -> {
            }
        }
        // a quiet sound (a step plays at 0.15) does not carry as far as the game lets players hear it
        double range = sound.value().getRange(volume) * (volume < 1 ? Math.sqrt(volume) : 1);
        for (AiBot b : l.bots.values()) {
            double dx = b.mob.getX() - x, dy = b.mob.getY() - y, dz = b.mob.getZ() - z;
            if (dx * dx + dy * dy + dz * dz < OWN_SOUND * OWN_SOUND) return;
        }
        long tick = level.getGameTime();
        boolean loud = range >= LOUD;
        for (AiBot b : l.bots.values()) {
            double reach = range * b.profile.hearing();
            double dx = b.mob.getX() - x, dy = b.mob.getY() - y, dz = b.mob.getZ() - z;
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 > reach * reach) continue;
            hear(b, x, y, z, Math.sqrt(d2), tick, loud, false, 0.08, 1);
        }
    }

    /** hits one AI mob took from another (the stands) */
    public static int friendlyHits;

    /**
     * Another AI mob in the line from from to to, or a little past it: its box, grown by the cone's width where it
     * stands (plus 0.3), crosses the line. The line of fire a shot keeps clear.
     */
    static boolean friendInLine(ServerLevel level, Mob shooter, Vec3 from, Vec3 to, double coneDegrees) {
        PerLevel l = LEVELS.get(level);
        if (l == null) return false;
        Vec3 d = to.subtract(from);
        double len = d.length();
        if (len < 1e-3) return false;
        Vec3 u = d.scale(1 / len), end = to.add(u);
        double tan = Math.tan(Math.toRadians(coneDegrees));
        for (AiBot b : l.bots.values()) {
            Mob m = b.mob;
            if (m == shooter || m.isRemoved() || !m.isAlive()) continue;
            net.minecraft.world.phys.AABB box = m.getBoundingBox();
            double t = box.getCenter().subtract(from).dot(u);
            if (t <= 0 || t > len + 1) continue;
            net.minecraft.world.phys.AABB grown = box.inflate(0.3 + t * tan);
            if (grown.contains(from) || grown.clip(from, end).isPresent()) return true;
        }
        return false;
    }

    /** a hit tells where it came from, roughly; one from another AI mob is no threat */
    public static void hurt(Mob mob, DamageSource source) {
        AiBot b = get(mob);
        if (b == null) return;
        Entity from = source.getEntity();
        if (from instanceof Mob m && m != mob && get(m) != null) {
            friendlyHits++;
            return;
        }
        Vec3 at = from != null ? from.position() : source.getSourcePosition();
        if (at == null) return;
        hear(b, at.x, at.y, at.z, at.distanceTo(mob.position()), mob.level().getGameTime(), true, true, 0.15, 2);
    }

    /** error: the ear's error as a part of the distance (at least min blocks), across */
    static void hear(AiBot b, double x, double y, double z, double distance, long tick, boolean loud, boolean hurt, double error, double min) {
        double sigma = Math.max(min, error * distance);
        var rng = b.mob.getRandom();
        b.memory.heard(x + rng.nextGaussian() * sigma, y + rng.nextGaussian() * Math.min(sigma, 2), z + rng.nextGaussian() * sigma,
                tick, loud, hurt);
    }
}
