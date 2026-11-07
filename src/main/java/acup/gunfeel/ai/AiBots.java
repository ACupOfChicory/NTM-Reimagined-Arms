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

    private AiBots() {
    }

    static final class PerLevel {
        final ServerLevel level;
        final Int2ObjectOpenHashMap<AiBot> bots = new Int2ObjectOpenHashMap<>();
        final List<LivingEntity> targets = new ArrayList<>();
        long targetsTick = Long.MIN_VALUE;
        final LiveVoxels voxels;

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
        if (l != null) l.bots.remove(mob.getId());
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
        if (level.getGameTime() % 20 == 0) l.bots.values().removeIf(b -> b.mob.isRemoved());
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

    /** a hit tells where it came from, roughly */
    public static void hurt(Mob mob, DamageSource source) {
        AiBot b = get(mob);
        if (b == null) return;
        Entity from = source.getEntity();
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
