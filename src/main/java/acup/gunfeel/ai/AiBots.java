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
    /** a sound this near a player in creative or spectator (from their middle) is theirs */
    static final double UNSEEN_SOUND = 2.0;
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
        /** mob id -> the cell beside its cover it steps out to (its peek), if it has one */
        final it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap peekOf = new it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap();

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

    public static int count(ServerLevel level) {
        PerLevel l = LEVELS.get(level);
        return l == null ? 0 : l.bots.size();
    }

    /** the level's AI mobs as they are (no copy): read only, on the server thread, not while one is added or let go */
    static java.util.Collection<AiBot> bots(ServerLevel level) {
        PerLevel l = LEVELS.get(level);
        return l == null ? List.of() : l.bots.values();
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
        claim(bot, cell, Long.MIN_VALUE);
    }

    /** holds a cover cell (none: Long.MIN_VALUE) and the peek beside it */
    public static void claim(AiBot bot, long cell, long peek) {
        PerLevel l = LEVELS.get((ServerLevel) bot.mob.level());
        if (l == null) return;
        int id = bot.mob.getId();
        l.peekOf.remove(id);
        if (cell != Long.MIN_VALUE && peek != Long.MIN_VALUE && peek != cell) l.peekOf.put(id, peek);
        if (l.claimOf.containsKey(id)) {
            long old = l.claimOf.remove(id);
            if (l.claims.get(old) == id) l.claims.remove(old);
        }
        if (cell != Long.MIN_VALUE) {
            l.claims.put(cell, id);
            l.claimOf.put(id, cell);
        }
    }

    /** the cover cells (first) and peeks (second) that the AI mobs on its side hold, not its own */
    public static long[][] friendsSpots(AiBot bot) {
        PerLevel l = LEVELS.get((ServerLevel) bot.mob.level());
        if (l == null) return new long[][] {{}, {}};
        it.unimi.dsi.fastutil.longs.LongArrayList covers = new it.unimi.dsi.fastutil.longs.LongArrayList(), peeks = new it.unimi.dsi.fastutil.longs.LongArrayList();
        int self = bot.mob.getId();
        for (var e : l.claimOf.int2LongEntrySet()) {
            int id = e.getIntKey();
            AiBot other = id == self ? null : l.bots.get(id);
            if (other == null || !AiTeams.own(bot.mob, other.mob)) continue;
            covers.add(e.getLongValue());
            if (l.peekOf.containsKey(id)) peeks.add(l.peekOf.get(id));
        }
        return new long[][] {covers.toLongArray(), peeks.toLongArray()};
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

    /**
     * whoever an AI mob of a level may fight, looked up again once a second: players, the AI mobs in a team (AiTeams
     * says whom each fights) and the stands' tagged targets
     */
    static List<LivingEntity> targets(PerLevel l, long tick) {
        if (l.targetsTick != Long.MIN_VALUE && tick >= l.targetsTick && tick - l.targetsTick < 20) return l.targets;
        l.targetsTick = tick;
        l.targets.clear();
        for (Player p : l.level.players()) if (p.isAlive() && !p.isSpectator() && !p.isCreative()) l.targets.add(p);
        for (Entity e : l.level.getAllEntities())
            if (e instanceof LivingEntity le && !(e instanceof Player) && le.isAlive() && e.entityTags().contains(TARGET_TAG)) l.targets.add(le);
        for (AiBot b : l.bots.values()) if (b.mob.isAlive() && b.mob.getTeam() != null && !l.targets.contains(b.mob)) l.targets.add(b.mob);
        return l.targets;
    }

    /** for the stands: every AI mob thinks, players near or not */
    static boolean awake;

    /** the stands (the server stand's AI phases among them): every AI mob thinks, players near or not */
    public static void wakeAll(boolean on) {
        awake = on;
    }

    /** the stands: look the targets up now, not within the second */
    public static void refreshTargets(ServerLevel level) {
        PerLevel l = LEVELS.computeIfAbsent(level, PerLevel::new);
        l.targetsTick = Long.MIN_VALUE;
    }

    public static void tick(ServerLevel level) {
        PerLevel l = LEVELS.get(level);
        if (l == null || l.bots.isEmpty()) {
            // none left: their squads hold none of them (nor the level) any more
            AiSquads.forget(level);
            AiGrenades.forget(level);
            AiEntry.forget(level);
            AiClearing.forget(level);
            return;
        }
        AiDoors.tick();
        AiBlockKinds.tick();
        AiSquads.tick(level, l.bots.values());
        AiGrenades.tick(level);
        if (level.getGameTime() % 20 != 0) return;
        for (AiBot b : l.bots.values()) if (b.mob.isRemoved()) claimGone(l, b.mob.getId());
        l.bots.values().removeIf(b -> b.mob.isRemoved());
        double r = radius(), r2 = r * r;
        for (AiBot b : l.bots.values()) {
            // here, between the mobs' ticks: a goal set must not change while it ticks
            AiAdapter.strip(b);
            // a player in any mode but spectator (an operator watching in creative too), or a stand's tagged target
            boolean near = awake;
            for (Player p : level.players()) {
                if (p.isSpectator()) continue;
                double dx = p.getX() - b.mob.getX(), dz = p.getZ() - b.mob.getZ();
                if (dx * dx + dz * dz <= r2) {
                    near = true;
                    break;
                }
            }
            if (!near) for (LivingEntity t : targets(l, level.getGameTime())) {
                if (get(t) != null) continue;
                double dx = t.getX() - b.mob.getX(), dz = t.getZ() - b.mob.getZ();
                if (dx * dx + dz * dz <= r2) {
                    near = true;
                    break;
                }
            }
            if (b.active && !near) {
                b.gunner.release();
                b.mover.pause();
                if (b.brain != null) b.brain.sleep(b, level.getGameTime());
            }
            b.active = near;
        }
    }

    private static void claimGone(PerLevel l, int id) {
        l.peekOf.remove(id);
        if (!l.claimOf.containsKey(id)) return;
        long cell = l.claimOf.remove(id);
        if (l.claims.get(cell) == id) l.claims.remove(cell);
    }

    public static double radius() {
        return Math.max(32, Math.min(256, acup.gunfeel.ReaimTimes.number(RADIUS_KEY, 128F)));
    }

    /** an AI mob's tick, from its goal: senses and memory, its brain, its legs and its gun */
    static void tickBot(AiBot bot) {
        long t0 = System.nanoTime();
        tickBot0(bot);
        tickNanos += System.nanoTime() - t0;
    }

    /** the stands: AI mobs' ticks, and of those the ones pinned down */
    public static long botTicks, pinnedTicks;

    /** the server stand: time in AI mobs' ticks (their guns' NTM work too), and in their looks; server thread */
    public static long tickNanos;

    public static long lookNanos() {
        return Senses.nanos;
    }

    public static long looks() {
        return Senses.looks;
    }

    private static void tickBot0(AiBot bot) {
        if (!bot.active || !acup.gunfeel.compat.Feature.MOB_AI.on() || !(bot.mob.level() instanceof ServerLevel level)) return;
        PerLevel l = LEVELS.get(level);
        if (l == null) return;
        long tick = level.getGameTime();
        TacticalMap map = TacticalMap.of(level);
        if ((tick + bot.mob.getId()) % 40 == 0) {
            map.want(bot.mob.getBlockX(), bot.mob.getBlockY(), bot.mob.getBlockZ(), WANT_RADIUS, 8);
            if (AiTerrain.ON) AiTerrain.of(level).want(bot.mob.getBlockX(), bot.mob.getBlockZ());
        }
        bot.tick(l.voxels, SmokeClouds.get(level), targets(l, tick), tick);
        // a shot that flies close past it is one at it: as a hit, from where it was fired
        Entity shooter = bot.prone.watchShots(level, tick);
        if (shooter != null) bot.dash.nearMiss(tick);
        boolean pinned = bot.prone.pinned(tick);
        botTicks++;
        if (pinned) pinnedTicks++;
        bot.confidence.tick(pinned, bot.prone.suppressed(), tick);
        if (shooter != null) hear(bot, shooter.getX(), shooter.getY(), shooter.getZ(), shooter.distanceTo(bot.mob), tick, true, true, 0.15, 2);
        if (bot.brain != null) bot.brain.tick(bot, tick);
        bot.prone.tick(level, bot.mover, tick, bot.brain != null && bot.stoop.down, bot.dash);
        bot.mover.hold(bot.gunner.steady());
        bot.mover.tick(level, map, tick);
        AiDoors.ahead(level, bot.mob);
        int reserve = bot.gunner.ammo.reserve();
        // a slide's or a roll's step is under a run's, and it still goes on its way, not facing them
        bot.gunner.runs = bot.mover.runs() || bot.dash.on(tick);
        bot.gunner.tick(level);
        if (bot.gunner.ammo.reserve() != reserve) AiAdapter.keep(bot);
    }

    public static void forget(ServerLevel level) {
        LEVELS.remove(level);
    }

    // ------------------------------------------------------------------ hearing

    /** a shot's sound this near one in flight (it lands, it ricochets) is its shooter's, blocks */
    static final double SHOT_SOUND = 3;

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
        // a player in creative or spectator is not seen, nor heard: the sounds about them (steps, shots) go unheard
        for (Player p : level.players()) {
            if (!p.isCreative() && !p.isSpectator()) continue;
            double dx = p.getX() - x, dy = p.getY() + 1 - y, dz = p.getZ() - z;
            if (dx * dx + dy * dy + dz * dz < UNSEEN_SOUND * UNSEEN_SOUND) return;
        }
        // an AI mob's own sound (its steps, its shots): heard only by those that fight it
        AiBot from = null;
        for (AiBot b : l.bots.values()) {
            double dx = b.mob.getX() - x, dy = b.mob.getY() - y, dz = b.mob.getZ() - z;
            if (dx * dx + dy * dy + dz * dz < OWN_SOUND * OWN_SOUND) {
                from = b;
                break;
            }
        }
        // a player's own sound (steps, shots): no news to the AI mobs of its team
        Player fromPlayer = null;
        if (from == null) for (Player p : level.players()) {
            if (p.isSpectator() || !p.isAlive()) continue;
            double dx = p.getX() - x, dy = p.getY() + 1 - y, dz = p.getZ() - z;
            if (dx * dx + dy * dy + dz * dz < UNSEEN_SOUND * UNSEEN_SOUND) {
                fromPlayer = p;
                break;
            }
        }
        // a shot landing (no mob or player by it): the one who fired it made it; a creative or spectating player's
        // goes unheard, an AI mob's is heard by those that fight it, a player's is no news to its team
        if (from == null && fromPlayer == null) {
            Entity shooter = null;
            double best = SHOT_SOUND * SHOT_SOUND;
            for (net.minecraft.world.entity.projectile.Projectile pr : level.getEntitiesOfClass(net.minecraft.world.entity.projectile.Projectile.class,
                    new net.minecraft.world.phys.AABB(x - SHOT_SOUND, y - SHOT_SOUND, z - SHOT_SOUND, x + SHOT_SOUND, y + SHOT_SOUND, z + SHOT_SOUND))) {
                double d = pr.distanceToSqr(x, y, z);
                if (pr.getOwner() != null && d < best) {
                    best = d;
                    shooter = pr.getOwner();
                }
            }
            if (shooter instanceof Player p && (p.isCreative() || p.isSpectator())) return;
            if (shooter instanceof Player p) fromPlayer = p;
            else if (shooter instanceof Mob m) from = l.bots.get(m.getId());
        }
        // made by a creature nobody fights (a cow's call, a chicken's steps, a zombie's groan: a creature's sound, not
        // a blast's or a block's): no news. Heard, they
        // turned to each, all the time, by a farm (stand: up to 30 degrees a tick, 4 a tick on average)
        if (from == null && fromPlayer == null && (source == SoundSource.NEUTRAL || source == SoundSource.HOSTILE)) {
            long t0 = System.nanoTime();
            boolean by = bystander(level, x, y, z);
            bystanderNanos += System.nanoTime() - t0;
            if (by) return;
        }
        long tick = level.getGameTime();
        boolean loud = range >= LOUD;
        if (soundTrace != null) soundTrace.accept(level, new SoundSeen(sound.value().location().toString(), x, y, z, volume, range, loud, from != null, fromPlayer != null));
        for (AiBot b : l.bots.values()) {
            if (from != null && !AiTeams.enemy(b.mob, from.mob)) continue;
            if (fromPlayer != null && AiTeams.own(b.mob, fromPlayer)) continue;
            double reach = range * b.profile.hearing();
            double dx = b.mob.getX() - x, dy = b.mob.getY() - y, dz = b.mob.getZ() - z;
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 > reach * reach) continue;
            hear(b, x, y, z, Math.sqrt(d2), tick, loud, false, 0.08, 1);
        }
    }

    /** a living thing by the sound (OWN_SOUND) that is no player, no AI mob and no stand's target: it made it */
    private static boolean bystander(ServerLevel level, double x, double y, double z) {
        double r = OWN_SOUND;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new net.minecraft.world.phys.AABB(x - r, y - r, z - r, x + r, y + r, z + r)))
            if (!(e instanceof Player) && get(e) == null && !e.entityTags().contains(TARGET_TAG) && e.distanceToSqr(x, y, z) < r * r) return true;
        return false;
    }

    /** the stands: a sound that reached the AI (its name, where, how loud, whether a mob or a player made it) */
    record SoundSeen(String name, double x, double y, double z, float volume, double range, boolean loud, boolean ofMob, boolean ofPlayer) {
    }

    /** the stands: every sound that reaches the AI, or null */
    static java.util.function.@org.jspecify.annotations.Nullable BiConsumer<ServerLevel, SoundSeen> soundTrace;

    /** the bench: time spent finding whether a creature nobody fights made a sound, ns */
    public static long bystanderNanos;

    /** hits one AI mob took from another (the stands) */
    public static int friendlyHits;

    /**
     * One of its own (AiTeams.own: an AI mob it does not fight, a player of its team) in the line from from to to, or
     * a little past it: its box, grown by the cone's width where it stands (plus 0.3), crosses the line. The line of
     * fire a shot keeps clear.
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
            if (m == shooter || m.isRemoved() || !m.isAlive() || !AiTeams.own(shooter, m)) continue;
            if (crosses(m, from, u, end, len, tan)) return true;
        }
        if (shooter.getTeam() != null) for (Player p : level.players())
            if (!p.isSpectator() && p.isAlive() && AiTeams.own(shooter, p) && crosses(p, from, u, end, len, tan)) return true;
        return false;
    }

    // whether the entity is in the cone from `from` along u (len long, widening by tan a block)
    private static boolean crosses(LivingEntity m, Vec3 from, Vec3 u, Vec3 end, double len, double tan) {
        net.minecraft.world.phys.AABB box = m.getBoundingBox();
        double t = box.getCenter().subtract(from).dot(u);
        if (t <= 0 || t > len + 1) return false;
        net.minecraft.world.phys.AABB grown = box.inflate(0.3 + t * tan);
        return grown.contains(from) || grown.clip(from, end).isPresent();
    }

    /** a hit tells where it came from, roughly; one from its own (an AI mob it does not fight, a player of its team) is no threat */
    public static void hurt(Mob mob, DamageSource source, float amount) {
        AiBot b = get(mob);
        if (b == null) return;
        Entity from = source.getEntity();
        if (from instanceof LivingEntity le && le != mob && AiTeams.own(mob, le)) {
            if (get(le) != null) friendlyHits++;
            return;
        }
        long tick = mob.level().getGameTime();
        // hurt by someone or something that went off, not by fire or a fall
        if (from != null || source.getDirectEntity() != null || source.getSourcePosition() != null)
            b.confidence.hurt(amount / Math.max(1F, mob.getMaxHealth()), tick);
        Vec3 at = from != null ? from.position() : source.getSourcePosition();
        if (at == null) return;
        hear(b, at.x, at.y, at.z, at.distanceTo(mob.position()), tick, true, true, 0.15, 2);
        // hurt by someone (a bullet, a beam, an arrow, a punch, their grenade): heard where they stand, and they may be
        // a target it lost; a blast no one set off is heard where it went off, and is not them
        b.memory.shot(from != null ? tick : Long.MIN_VALUE);
    }

    /** any living thing hurt: an AI mob that hit a fighter not of its own side is the surer for it (AiConfidence) */
    public static void landed(LivingEntity victim, DamageSource source, float amount) {
        if (amount <= 0 || !(source.getEntity() instanceof Mob m) || m == victim || !fighter(victim) || AiTeams.own(m, victim)) return;
        AiBot b = get(m);
        if (b != null) b.confidence.hit(m.level().getGameTime());
    }

    /**
     * Any living thing down: the AI mobs near it of its side lose heart, those it fought take it (an AI mob, a player
     * or a stand's target down near them, not of their side: AiConfidence)
     */
    public static void died(LivingEntity victim) {
        if (!(victim.level() instanceof ServerLevel level)) return;
        PerLevel l = LEVELS.get(level);
        if (l == null) return;
        if (!fighter(victim)) return;
        long tick = level.getGameTime();
        double r2 = AiConfidence.NEAR * AiConfidence.NEAR;
        AiBot nearest = null;
        double best = Double.MAX_VALUE;
        for (AiBot b : l.bots.values()) {
            Mob m = b.mob;
            if (m == victim || !m.isAlive()) continue;
            double d2 = m.distanceToSqr(victim);
            if (d2 > r2) continue;
            boolean own = AiTeams.own(m, victim);
            b.confidence.add(own ? -AiConfidence.FRIEND_DOWN : AiConfidence.FOE_DOWN, tick);
            if (own && d2 < best) {
                best = d2;
                nearest = b;
            }
        }
        // the nearest of its side calls it out
        if (nearest != null) AiVoice.say(level, nearest, AiVoice.Line.MAN_DOWN, tick);
    }

    /** one that fights: an AI mob, a player, a stand's target (not a cow) */
    private static boolean fighter(LivingEntity e) {
        return get(e) != null || e instanceof Player || e.entityTags().contains(TARGET_TAG);
    }

    /** error: the ear's error as a part of the distance (at least min blocks), across */
    static void hear(AiBot b, double x, double y, double z, double distance, long tick, boolean loud, boolean hurt, double error, double min) {
        double sigma = Math.max(min, error * distance);
        var rng = b.mob.getRandom();
        b.memory.heard(x, y, z, x + rng.nextGaussian() * sigma, y + rng.nextGaussian() * Math.min(sigma, 2), z + rng.nextGaussian() * sigma,
                tick, loud, hurt);
    }
}
