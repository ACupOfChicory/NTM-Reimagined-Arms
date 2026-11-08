package acup.gunfeel.ai.imitation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.mags.IMagazine;

import acup.gunfeel.Prone;
import acup.gunfeel.ai.AiBot;
import acup.gunfeel.ai.AiBots;
import acup.gunfeel.ai.AiTeams;
import acup.gunfeel.ai.CoverQuery;
import acup.gunfeel.ai.Senses;
import acup.gunfeel.ai.TacticalMap;
import acup.gunfeel.ai.TargetMemory;
import acup.gunfeel.ai.VoxelRay;
import acup.gunfeel.smoke.SmokeClouds;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Fills a {@link FightView} from the world: one code for a player whose fight is recorded and for a bot that asks.
 * Whom it knows of differs as it must (a bot from its memory, a player from what came into his sight, {@link Sight});
 * the rest is the same rays: whether an enemy's eyes reach its head or chest, what cover it has where it stands from
 * the nearest. Server thread.
 */
public final class FightLook {
    private FightLook() {
    }

    /** enemies whose eyes are checked against it, nearest first (the rays' cost), and friends counted within */
    static final int RAYED = 3;
    static final double FRIENDS = 16;
    /** an enemy looking within this of it (cosine of 60 degrees) looks its way */
    static final double LOOKS_COS = 0.5;
    /** a player's sight: how far, and how wide (cosine of 55 degrees off the look) */
    static final double PLAYER_RANGE = 96, PLAYER_COS = Math.cos(Math.toRadians(55));
    /** a player forgets an enemy out of sight this long, ticks */
    static final int PLAYER_FORGETS = 600;

    /** who a player has seen and where last: the AI mobs that fight him */
    public static final class Sight {
        record Seen(int id, double x, double y, double z, long tick) {
        }

        final java.util.Map<Integer, Seen> seen = new java.util.HashMap<>();
    }

    public static FightView player(ServerLevel level, Player p, Sight sight, FightHistory h, long tick) {
        FightView v = self(p, tick);
        VoxelRay.Source voxels = AiBots.voxels(level);
        SmokeClouds smoke = SmokeClouds.get(level);
        Vec3 eye = p.getEyePosition(), look = p.getViewVector(1F);
        List<FightView.Enemy> out = new ArrayList<>();
        java.util.Set<Integer> visible = new java.util.HashSet<>();
        int friends = 0;
        for (AiBot b : AiBots.all(level)) {
            LivingEntity m = b.mob;
            if (!m.isAlive()) continue;
            double d2 = m.distanceToSqr(p);
            if (!AiTeams.enemy(m, p)) {
                if (d2 <= FRIENDS * FRIENDS) friends++;
                continue;
            }
            if (d2 > PLAYER_RANGE * PLAYER_RANGE) continue;
            Vec3 to = m.position().add(0, m.getBbHeight() * 0.6, 0).subtract(eye);
            double len = to.length();
            if (len > 1e-3 && to.dot(look) / len >= PLAYER_COS && seesAnyPart(voxels, smoke, eye, m)) {
                sight.seen.put(m.getId(), new Sight.Seen(m.getId(), m.getX(), m.getY(), m.getZ(), tick));
                visible.add(m.getId());
            }
        }
        for (Player o : level.players()) if (o != p && !o.isSpectator() && o.getTeam() != null && o.getTeam() == p.getTeam() && o.distanceToSqr(p) <= FRIENDS * FRIENDS)
            friends++;
        sight.seen.values().removeIf(s -> tick - s.tick() > PLAYER_FORGETS);
        long last = Long.MIN_VALUE;
        for (Sight.Seen s : sight.seen.values()) last = Math.max(last, s.tick());
        if (last != Long.MIN_VALUE) v.seenAgo = (int) Math.min(FightHistory.LONG, tick - last);
        for (Sight.Seen s : sight.seen.values()) {
            Entity e = level.getEntity(s.id());
            if (e == null || !e.isAlive()) continue;
            boolean vis = visible.contains(s.id());
            out.add(new FightView.Enemy(s.id(), vis ? e.getX() : s.x(), vis ? e.getY() : s.y(), vis ? e.getZ() : s.z(), vis, false, false));
        }
        v.friendsNear = friends;
        finish(level, p, v, out, voxels, smoke, h);
        return v;
    }

    public static FightView bot(ServerLevel level, AiBot bot, FightHistory h, long tick) {
        LivingEntity self = bot.mob;
        FightView v = self(self, tick);
        List<FightView.Enemy> out = new ArrayList<>();
        long last = Long.MIN_VALUE;
        for (TargetMemory.Contact c : bot.memory.contacts()) {
            if (c.state == TargetMemory.State.VISIBLE || c.state == TargetMemory.State.LOST || c.state == TargetMemory.State.SEARCH)
                last = Math.max(last, c.state == TargetMemory.State.VISIBLE ? tick : c.lostTick);
            if (c.state == TargetMemory.State.VISIBLE) out.add(new FightView.Enemy(c.id, c.x, c.y, c.z, true, false, false));
            else if (c.state == TargetMemory.State.LOST || c.state == TargetMemory.State.SEARCH)
                out.add(new FightView.Enemy(c.id, c.lostX, c.lostY, c.lostZ, false, false, false));
        }
        if (last != Long.MIN_VALUE) v.seenAgo = (int) Math.max(0, Math.min(FightHistory.LONG, tick - last));
        int friends = 0;
        for (AiBot o : AiBots.all(level))
            if (o != bot && o.mob.isAlive() && !AiTeams.enemy(self, o.mob) && o.mob.distanceToSqr(self) <= FRIENDS * FRIENDS) friends++;
        v.friendsNear = friends;
        finish(level, self, v, out, AiBots.voxels(level), SmokeClouds.get(level), h);
        return v;
    }

    /** what it has: health, gun, stance */
    @SuppressWarnings("rawtypes")
    static FightView self(LivingEntity e, long tick) {
        FightView v = new FightView();
        v.tick = tick;
        v.x = e.getX();
        v.y = e.getY();
        v.z = e.getZ();
        v.yaw = e.getYHeadRot();
        v.pitch = e.getXRot();
        v.health = e.getMaxHealth() > 0 ? e.getHealth() / e.getMaxHealth() : 1;
        if (e.getLastHurtByMobTimestamp() > 0) v.hurtAgo = Math.max(0, e.tickCount - e.getLastHurtByMobTimestamp());
        ItemStack stack = e.getMainHandItem();
        if (stack.getItem() instanceof ItemGunBaseNT gun) {
            try {
                IMagazine mag = gun.getConfig(stack, 0).getReceivers(stack)[0].getMagazine(stack);
                if (mag != null) {
                    v.capacity = mag.getCapacity(stack);
                    v.loaded = mag.getAmount(stack, null);
                }
            } catch (RuntimeException ex) {
                // a magazine that wants an inventory: rounds not counted
            }
            v.reloading = ItemGunBaseNT.getState(stack, 0) == ItemGunBaseNT.GunState.RELOADING;
        }
        v.prone = Prone.lying(e);
        v.crouching = e.isCrouching();
        v.sprinting = e.isSprinting();
        return v;
    }

    /** nearest first; the nearest few: whether their eyes reach it; the nearest: what cover it has from it */
    private static void finish(ServerLevel level, LivingEntity self, FightView v, List<FightView.Enemy> found, VoxelRay.Source voxels, @Nullable SmokeClouds smoke,
            FightHistory h) {
        found.sort(Comparator.comparingDouble(e -> sq(e.x() - v.x, e.y() - v.y, e.z() - v.z)));
        double hgt = self.getBbHeight();
        Vec3 head = new Vec3(v.x, v.y + hgt * 0.9, v.z), chest = new Vec3(v.x, v.y + hgt * 0.55, v.z), legs = new Vec3(v.x, v.y + hgt * 0.2, v.z);
        List<FightView.Enemy> out = new ArrayList<>(found.size());
        for (int i = 0; i < found.size(); i++) {
            FightView.Enemy e = found.get(i);
            if (i >= RAYED) {
                out.add(e);
                continue;
            }
            Entity ent = level.getEntity(e.id());
            // its eyes where they are (whether it sees them or not: whether they reach it is a fact of the blocks, not of
            // where it looks; a player's camera turned away hides nothing), else where they were
            boolean there = ent != null && ent.isAlive();
            Vec3 eye = there ? ent.getEyePosition() : new Vec3(e.x(), e.y() + 1.6, e.z());
            boolean headSeen = Senses.clearLine(voxels, smoke, eye, head);
            boolean exposed = there && (headSeen || Senses.clearLine(voxels, smoke, eye, chest));
            boolean looks = false;
            if (exposed && ent != null) {
                Vec3 d = chest.subtract(eye);
                double len = d.length();
                looks = len > 1e-3 && ent.getViewVector(1F).dot(d) / len >= LOOKS_COS;
            }
            if (i == 0) v.coverHere = headSeen ? (Senses.clearLine(voxels, smoke, eye, legs) ? 0 : 1) : 2;
            out.add(new FightView.Enemy(e.id(), e.x(), e.y(), e.z(), e.visible(), exposed, looks));
        }
        v.enemies = out;
        h.update(v);
    }

    private static boolean seesAnyPart(VoxelRay.Source voxels, @Nullable SmokeClouds smoke, Vec3 eye, LivingEntity t) {
        double hgt = t.getBbHeight();
        for (double part : new double[] {0.9, 0.55, 0.2})
            if (Senses.clearLine(voxels, smoke, eye, new Vec3(t.getX(), t.getY() + hgt * part, t.getZ()))) return true;
        return false;
    }

    /**
     * The cover a fighter could take now from the enemies it knows (the bot's own search, CoverQuery, as a fight asks
     * it: places to shoot from count, threats after the first weigh against a place rather than rule it out), best
     * first, at most n.
     */
    public static List<CoverQuery.Spot> covers(ServerLevel level, LivingEntity self, FightView v, int n) {
        if (v.enemies.isEmpty()) return List.of();
        List<Vec3> threats = new ArrayList<>();
        for (FightView.Enemy e : v.enemies) {
            if (threats.size() >= 4) break;
            threats.add(new Vec3(e.x(), e.y() + 1.5, e.z()));
        }
        CoverQuery.Params p = new CoverQuery.Params();
        p.othersSoft = true;
        p.rayBudget = 48;
        // as the bot's fight query weighs a place to shoot from (CoverQuery.PEEK_FIGHT)
        p.peekBonus = 4;
        TacticalMap map = TacticalMap.of(level);
        return CoverQuery.find(map, AiBots.voxels(level), self.position(), threats, p, k -> false, n);
    }

    /** a spot's numbers for the model (FightFeatures.spot), against the view's nearest enemy */
    public static double[][] spotRows(List<CoverQuery.Spot> spots, FightView v) {
        double[][] out = new double[spots.size()][];
        double best = spots.isEmpty() ? 0 : spots.get(0).score();
        FightView.Enemy t = v.enemies.isEmpty() ? null : v.enemies.get(0);
        for (int k = 0; k < spots.size(); k++) {
            CoverQuery.Spot s = spots.get(k);
            out[k] = FightFeatures.spot(k, s.score(), best, s.height(), s.hard(), s.hasPeek(), v.x, v.z, BlockPos.getX(s.cell()) + 0.5,
                    BlockPos.getZ(s.cell()) + 0.5, t == null ? Double.NaN : t.x(), t == null ? Double.NaN : t.z());
        }
        return out;
    }

    private static double sq(double x, double y, double z) {
        return x * x + y * y + z * z;
    }
}
