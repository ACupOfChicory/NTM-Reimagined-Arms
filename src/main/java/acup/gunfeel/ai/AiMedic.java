package acup.gunfeel.ai;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.ReaimTimes;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * The squad's medic (stage 5, R4a; no one lies down wounded: that is R4b, the owner's word first). A soldier with
 * the role (AiRole.MEDIC: by word, or one of those spawned by number, aiMedicShare) carries kits (aiMedicKits). Now
 * and then it looks who of its squad, or a player of its side, is hurt: less than WOUNDED of their health. It goes
 * to the one hurt worst within REACH that no other medic goes to, while it is not pinned down itself, no one it sees
 * is near, and the other is not out in sight of one (as AiSupply's runs): runs up, kneels by them TREAT ticks, and a
 * kit gives them HEAL of their health back. One that moves off on the way is followed; one that dies, or a run too
 * long, and it gives up. It does not fire meanwhile. Hurt itself, it treats itself where it is, where none of them
 * sees it. After a fight the same: it goes round the hurt. The kits are not endless and are kept on the mob. No one
 * else heals: a soldier's health is as before but for the medic's kits.
 * <p>
 * R4a+ (aiMedicCover), care under fire (TCCC): while one of its squad sees one of them, the medic fights as a
 * rifleman and goes to none, itself neither ({@link #step}); one hurt with a medic to come goes to cover by itself and
 * does not leave it for better ({@link #waits}, AiSoldier). It treats them once none of its squad sees them. On the
 * stand (30 fights a mode) a medic running in a fight cost the side more than its kits gave back, with smoke or not.
 */
public final class AiMedic {
    /** the server settings: medics treat the hurt; kits one carries; the share of mobs spawned by number that are medics */
    public static final String KEY = "aiMedic", KITS_KEY = "aiMedicKits", SHARE_KEY = "aiMedicShare";
    /** the server setting: a medic goes to none while its squad sees them, and the hurt go to cover for it */
    public static final String COVER_KEY = "aiMedicCover";
    static final int KITS = 6;
    static final float SHARE = 0.12F;
    /** off only for the stands' "before" (cover: care under fire alone) */
    static boolean on = true, cover = true;
    /** where the kits are kept on the mob */
    static final String KITS_TAG = "armsaiMedic";
    /** hurt: less than this share of its health; a kit gives back this share */
    static final float WOUNDED = 0.6F, HEAL = 0.5F;
    /** a medic looks this often; a run takes this long at most; after a run given up, the next look; a kit takes this long; ticks */
    static final int EVERY = 10, RUN_MAX = 300, GIVEN_UP = 100, TREAT = 60;
    /** it goes to one this near at most; treats this near; a patient this far off once treating is begun is followed again; blocks */
    static final double REACH = 24, HAND = 2, LEAVE = 3.5;
    /** a new way to one on the move once they are this far from where it goes; blocks */
    static final double REGOAL = 2;
    /** after a kit, its next look; a player hurt by a mob this lately is out in sight of them; ticks */
    static final int AFTER = 20, PLAYER_HIT = 100;

    /** the stands: runs begun, runs given up, kits used (on others, on itself), health given back */
    public static int runs, gaveUp, treated, self;
    /** the stands: the hurt off to cover for it (AiSoldier); looks with none taken as its squad sees them */
    public static int toCover, underFire;
    /** the stands: looks with none taken, by why: pinned down, lying, one of them near */
    public static final int[] idle = new int[3];
    /** the stands: medics down on a run to one hurt, treating one, at neither (fighting as a rifleman) */
    public static final int[] down = new int[3];
    public static double healed;

    private AiMedic() {
    }

    public static boolean on() {
        return on && ReaimTimes.flag(KEY, true);
    }

    /** care under fire: the medics on, and this */
    static boolean cover() {
        return cover && on() && ReaimTimes.flag(COVER_KEY, true);
    }

    /**
     * Hurt and one to treat it: less than WOUNDED of its health, not a medic itself, and a medic of its squad with kits
     * left. Such a one goes to cover (AiSoldier) and waits there.
     */
    static boolean waits(AiBot bot) {
        if (!hurt(bot.mob) || !cover() || medic(bot)) return false;
        AiSquads.Squad squad = AiSquads.of(bot);
        if (squad != null) for (AiBot o : squad.bots) if (o != bot && AiSquads.live(o) && medic(o) && kits(o) > 0) return true;
        return false;
    }

    static float share() {
        return Math.max(0F, Math.min(1F, ReaimTimes.number(SHARE_KEY, SHARE)));
    }

    /** a medic's own state: its kits; who it goes to (an id), since when; the tick its treating began; the next look */
    static final class State {
        int kits = -1, to = -1;
        long at, treating = Long.MIN_VALUE, next = Long.MIN_VALUE;
    }

    /** its kits: read off the mob, or what a new medic carries */
    static int kits(AiBot bot) {
        State m = state(bot);
        if (m.kits < 0) {
            var kept = bot.mob.getPersistentData().getInt(KITS_TAG);
            m.kits = kept.isPresent() ? Math.max(0, kept.get()) : Math.max(0, Math.min(64, Math.round(ReaimTimes.number(KITS_KEY, KITS))));
            keep(bot);
        }
        return m.kits;
    }

    private static State state(AiBot bot) {
        return bot.medic == null ? bot.medic = new State() : bot.medic;
    }

    private static void keep(AiBot bot) {
        if (bot.medic != null && bot.medic.kits >= 0) bot.mob.getPersistentData().putInt(KITS_TAG, bot.medic.kits);
    }

    /** a medic now: the setting on and the role */
    static boolean medic(AiBot bot) {
        return on() && AiRole.of(bot) == AiRole.MEDIC;
    }

    /** hurt enough to be treated: alive, less than WOUNDED of its health */
    static boolean hurt(LivingEntity e) {
        return e.isAlive() && e.getHealth() < WOUNDED * e.getMaxHealth();
    }

    /**
     * A medic, this tick: true while it runs to one hurt or treats one (it does nothing else then); false: it fights
     * as a rifleman. Called by its brain after what keeps it alive (a grenade to run from, its own throw).
     */
    static boolean step(AiSoldier s, AiBot bot, ServerLevel level, long tick) {
        if (!medic(bot)) {
            // no medic now (the role taken away, the setting off): none it goes to
            if (bot.medic != null) {
                bot.medic.to = -1;
                bot.medic.treating = Long.MIN_VALUE;
            }
            return false;
        }
        State m = state(bot);
        if (kits(bot) <= 0) {
            m.to = -1;
            m.treating = Long.MIN_VALUE;
            return false;
        }
        // care under fire: one of its squad sees one of them, it fights; the run or the kit begun is left
        if (cover() && fight(bot) != null) {
            if (m.to >= 0 && m.to != bot.mob.getId() && m.treating == Long.MIN_VALUE) gaveUp++;
            if ((tick + bot.mob.getId()) % EVERY == 0) underFire++;
            m.to = -1;
            m.treating = Long.MIN_VALUE;
            return false;
        }
        LivingEntity to = m.to < 0 ? null : level.getEntity(m.to) instanceof LivingEntity le ? le : null;
        if (to == null) {
            m.to = -1;
            m.treating = Long.MIN_VALUE;
            if ((tick + bot.mob.getId()) % EVERY != 0 || tick < m.next) return false;
            if (!AiSupply.free(bot, tick)) {
                idle[bot.prone.pinned(tick) ? 0 : bot.prone.lying() ? 1 : 2]++;
                return false;
            }
            // itself first, where no one of them sees it; else the one hurt worst
            to = hurt(bot.mob) && !AiSupply.exposed(bot) ? bot.mob : pick(bot, level);
            if (to == null) return false;
            m.to = to.getId();
            m.at = tick;
            if (to != bot.mob) {
                runs++;
                s.note(bot, tick, "medic: goes to #" + to.getId());
            }
        }
        double dx = to.getX() - bot.mob.getX(), dz = to.getZ() - bot.mob.getZ(), dy = to.getY() - bot.mob.getY();
        double d2 = dx * dx + dz * dz;
        boolean onIt = m.treating != Long.MIN_VALUE;
        boolean by = to == bot.mob || d2 <= (onIt ? LEAVE * LEAVE : HAND * HAND) && Math.abs(dy) < 2;
        // the one it goes to is dead, whole again, no more of its side, the way too long, it is pinned down or one of
        // them near (itself too: it stops treating itself): given up
        if (!to.isAlive() || !hurt(to) || !by && tick - m.at > RUN_MAX || !AiSupply.free(bot, tick)
                || to instanceof Player p && (p.isCreative() || p.isSpectator() || !AiTeams.own(bot.mob, p)) || bot.mover.failed() && bot.mover.goalIs(cell(to))) {
            if (to != bot.mob && !onIt) gaveUp++;
            m.to = -1;
            m.treating = Long.MIN_VALUE;
            m.next = tick + GIVEN_UP;
            s.note(bot, tick, "medic: gives the run up");
            return false;
        }
        bot.gunner.fire(false);
        if (by) {
            // by them: down on its knees, still, a kit at work
            if (!onIt) m.treating = tick;
            if (bot.mover.goal() != Long.MIN_VALUE) bot.mover.stop();
            s.crouch(true);
            if (to != bot.mob) bot.gunner.aim(to.position().add(0, to.getBbHeight() * 0.5, 0));
            if (tick - m.treating >= TREAT) treat(s, bot, to, tick);
            return true;
        }
        // moved off while it was treating: after them again, the kit's time and the way's from the start
        if (onIt) m.at = tick;
        m.treating = Long.MIN_VALUE;
        long goal = bot.mover.goal();
        // a new way only when they have gone on from where it goes (a player on the move: not a new path each step)
        if (goal == Long.MIN_VALUE || to.distanceToSqr(BlockPos.getX(goal) + 0.5, BlockPos.getY(goal), BlockPos.getZ(goal) + 0.5) > REGOAL * REGOAL) {
            goal = cell(to);
            bot.mover.costs(FireLines.of(level, bot.mob));
            bot.mover.goTo(goal, 1.5, AiSoldier.RUN);
        }
        return true;
    }

    /** a kit: HEAL of their health back (to whole at most) */
    private static void treat(AiSoldier s, AiBot bot, LivingEntity to, long tick) {
        State m = state(bot);
        float before = to.getHealth();
        to.heal(HEAL * to.getMaxHealth());
        healed += to.getHealth() - before;
        m.kits--;
        keep(bot);
        if (to == bot.mob) self++;
        else treated++;
        s.note(bot, tick, String.format(java.util.Locale.ROOT, "medic: treated %s, %.0f to %.0f of %.0f; %d kits left", to == bot.mob ? "itself" : "#" + to.getId(),
                before, to.getHealth(), to.getMaxHealth(), m.kits));
        m.to = -1;
        m.treating = Long.MIN_VALUE;
        m.next = tick + AFTER;
    }

    private static long cell(Entity e) {
        return BlockPos.asLong(Mth.floor(e.getX()), Mth.floor(e.getY() + 0.2), Mth.floor(e.getZ()));
    }

    /**
     * The one hurt worst within reach, of its squad or a player of its side; none another medic goes to, none out in
     * sight of one of them (AiSupply.exposed); the nearer of two alike.
     */
    private static @Nullable LivingEntity pick(AiBot bot, ServerLevel level) {
        LivingEntity best = null;
        double worst = 1, bestD = Double.MAX_VALUE;
        AiSquads.Squad squad = AiSquads.of(bot);
        if (squad != null) for (AiBot b : squad.bots) {
            if (b == bot || !AiSquads.live(b) || !hurt(b.mob)) continue;
            double share = b.mob.getHealth() / b.mob.getMaxHealth(), d = b.mob.distanceToSqr(bot.mob);
            if (d > REACH * REACH || AiSupply.exposed(b) || served(level, bot, b.mob.getId())) continue;
            if (share < worst || share == worst && d < bestD) {
                worst = share;
                bestD = d;
                best = b.mob;
            }
        }
        for (Player p : level.players()) {
            if (p.isSpectator() || p.isCreative() || !hurt(p) || !AiTeams.own(bot.mob, p)) continue;
            double share = p.getHealth() / p.getMaxHealth(), d = p.distanceToSqr(bot.mob);
            // one hit by a mob a moment ago is out in sight of them, as far as it can tell
            if (d > REACH * REACH || p.getLastHurtByMob() != null && p.tickCount - p.getLastHurtByMobTimestamp() < PLAYER_HIT || served(level, bot, p.getId())) continue;
            if (share < worst || share == worst && d < bestD) {
                worst = share;
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    /** one of them that one of its squad sees now (itself first), null: none */
    private static TargetMemory.@Nullable Contact fight(AiBot bot) {
        TargetMemory.Contact own = bot.memory.focus(bot.mob.getX(), bot.mob.getZ());
        if (own != null && own.state == TargetMemory.State.VISIBLE) return own;
        AiSquads.Squad squad = AiSquads.of(bot);
        if (squad != null) for (AiBot b : squad.bots) {
            if (b == bot || !AiSquads.live(b)) continue;
            TargetMemory.Contact c = b.memory.focus(b.mob.getX(), b.mob.getZ());
            if (c != null && c.state == TargetMemory.State.VISIBLE) return c;
        }
        return null;
    }

    /** another medic goes to this one already */
    private static boolean served(ServerLevel level, AiBot bot, int id) {
        for (AiBot o : AiBots.bots(level)) if (o != bot && o.medic != null && o.medic.to == id && AiSquads.live(o) && medic(o)) return true;
        return false;
    }

    /** a medic down (AiBots.died): what it was at, for the stands */
    static void fell(AiBot bot) {
        if (!medic(bot) || bot.medic == null) return;
        down[bot.medic.to < 0 ? 2 : bot.medic.treating != Long.MIN_VALUE ? 1 : 0]++;
    }

    /** the kits as shown over a medic */
    static String shown(AiBot bot) {
        return kits(bot) + " kits";
    }

    /** /armsai info */
    public static String info() {
        return String.format(java.util.Locale.ROOT, "medics: %d runs (%d given up), %d kits on others, %d on themselves, %.0f health back;"
                + " the hurt off to cover %d; looks for none: %s, under fire %d; medics down on a run, treating, else: %s", runs, gaveUp, treated, self, healed, toCover,
                java.util.Arrays.toString(idle), underFire, java.util.Arrays.toString(down));
    }
}
