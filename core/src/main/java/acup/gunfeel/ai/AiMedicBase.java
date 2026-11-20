package acup.gunfeel.ai;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.ReaimTimes;
import acup.gunfeel.GameMath;
import acup.gunfeel.PosLong;
import acup.gunfeel.Vec3d;
import acup.gunfeel.host.Body;
import acup.gunfeel.host.EntityView;

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
 * rifleman and goes to none, itself neither ({@code tickMedic}); one hurt with a medic to come goes to cover by itself and
 * does not leave it for better ({@code awaitsCare}, AiSoldier). It treats them once none of its squad sees them. On the
 * stand (30 fights a mode) a medic running in a fight cost the side more than its kits gave back, with smoke or not.
 */
public abstract class AiMedicBase {
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

    /** The version's medic operations. */
    public interface Staff<B extends AiBotBase, S> {
        /** 26.2: persistent armsaiMedic getInt, null when absent. */
        @Nullable Integer keptKits(B bot);
        /** 26.2: persistent armsaiMedic putInt. */
        void keepKits(B bot, int kits);
        /** 26.2: LivingEntity.heal on the patient, including players. */
        void heal(Body patient, float amount);
        /** 26.2: AiSupply.free. */
        boolean free(B bot, long tick);
        /** 26.2: AiSupply.exposed. */
        boolean exposed(B bot);
        /** 26.2: FireLines.of. */
        PathSearch.Cost fireLines(Object level, B bot);
        /** 26.2: AiGunner.fire. */
        void fire(B bot, boolean fire);
        /** 26.2: AiGunner.aim, converting to Vec3. */
        void aim(B bot, @Nullable Vec3d at);
        /** 26.2: AiSoldier.crouch. */
        void crouch(S soldier, boolean crouch);
        /** 26.2: AiSoldier.note. */
        void note(S soldier, B bot, long tick, String text);
        /** 26.2: AiSoldier.RUN. */
        double run();
        /** 26.2: LevelEntities, in level.players order. */
        EntityView entities(Object level);
    }

    protected AiMedicBase() {
    }

    public static <B extends AiBotBase, S> boolean enabled(Staff<B, S> staff) {
        return on && ReaimTimes.flag(KEY, true);
    }

    /** care under fire: the medics on, and this */
    static <B extends AiBotBase, S> boolean careUnderFire(Staff<B, S> staff) {
        return cover && enabled(staff) && ReaimTimes.flag(COVER_KEY, true);
    }

    /**
     * Hurt and one to treat it: less than WOUNDED of its health, not a medic itself, and a medic of its squad with kits
     * left. Such a one goes to cover (AiSoldier) and waits there.
     */
    static <B extends AiBotBase, S> boolean awaitsCare(Staff<B, S> staff, B bot) {
        if (!wounded(staff, bot.body) || !careUnderFire(staff) || isMedic(staff, bot)) return false;
        AiSquadsBase.Squad<B> squad = AiSquadsBase.squadOf(bot);
        if (squad != null) for (B o : squad.bots) if (o != bot && AiSquadsBase.alive(o) && isMedic(staff, o) && kitCount(staff, o) > 0) return true;
        return false;
    }

    static <B extends AiBotBase, S> float medicShare(Staff<B, S> staff) {
        return Math.max(0F, Math.min(1F, ReaimTimes.number(SHARE_KEY, SHARE)));
    }

    /** a medic's own state: its kits; who it goes to (an id), since when; the tick its treating began; the next look */
    static final class State {
        int kits = -1, to = -1;
        long at, treating = Long.MIN_VALUE, next = Long.MIN_VALUE;
    }

    /** its kits: read off the mob, or what a new medic carries */
    static <B extends AiBotBase, S> int kitCount(Staff<B, S> staff, B bot) {
        State m = stateOf(staff, bot);
        if (m.kits < 0) {
            Integer kept = staff.keptKits(bot);
            m.kits = kept != null ? Math.max(0, kept) : Math.max(0, Math.min(64, Math.round(ReaimTimes.number(KITS_KEY, KITS))));
            keepStock(staff, bot);
        }
        return m.kits;
    }

    static <B extends AiBotBase, S> State stateOf(Staff<B, S> staff, B bot) {
        return bot.medic == null ? bot.medic = new State() : bot.medic;
    }

    static <B extends AiBotBase, S> void keepStock(Staff<B, S> staff, B bot) {
        if (bot.medic != null && bot.medic.kits >= 0) staff.keepKits(bot, bot.medic.kits);
    }

    /** a medic now: the setting on and the role */
    static <B extends AiBotBase, S> boolean isMedic(Staff<B, S> staff, AiBotBase bot) {
        return enabled(staff) && bot.role() == AiRole.MEDIC;
    }

    /** hurt enough to be treated: alive, less than WOUNDED of its health */
    static <B extends AiBotBase, S> boolean wounded(Staff<B, S> staff, Body e) {
        return e.alive() && e.health() < WOUNDED * e.maxHealth();
    }

    /**
     * A medic, this tick: true while it runs to one hurt or treats one (it does nothing else then); false: it fights
     * as a rifleman. Called by its brain after what keeps it alive (a grenade to run from, its own throw).
     */
    static <B extends AiBotBase, S> boolean tickMedic(Staff<B, S> staff, S s, B bot, Object level, long tick) {
        if (!isMedic(staff, bot)) {
            // no medic now (the role taken away, the setting off): none it goes to
            if (bot.medic != null) {
                bot.medic.to = -1;
                bot.medic.treating = Long.MIN_VALUE;
            }
            return false;
        }
        State m = stateOf(staff, bot);
        if (kitCount(staff, bot) <= 0) {
            m.to = -1;
            m.treating = Long.MIN_VALUE;
            return false;
        }
        // care under fire: one of its squad sees one of them, it fights; the run or the kit begun is left
        if (careUnderFire(staff) && visibleFoe(staff, bot) != null) {
            if (m.to >= 0 && m.to != bot.body.id() && m.treating == Long.MIN_VALUE) gaveUp++;
            // off its way to them (the soldier's own moves stop it only out of IDLE)
            if (m.to >= 0 && bot.mover().goal() != Long.MIN_VALUE) bot.mover().stop();
            if ((tick + bot.body.id()) % EVERY == 0) underFire++;
            m.to = -1;
            m.treating = Long.MIN_VALUE;
            return false;
        }
        Body found = m.to < 0 ? null : staff.entities(level).byId(m.to);
        Body to = found != null && found.living() ? found : null;
        if (to == null) {
            m.to = -1;
            m.treating = Long.MIN_VALUE;
            if ((tick + bot.body.id()) % EVERY != 0 || tick < m.next) return false;
            if (!staff.free(bot, tick)) {
                idle[bot.prone().pinned(tick) ? 0 : bot.prone().lying() ? 1 : 2]++;
                return false;
            }
            // itself first, where no one of them sees it; else the one hurt worst
            to = wounded(staff, bot.body) && !staff.exposed(bot) ? bot.body : choosePatient(staff, bot, level);
            if (to == null) return false;
            m.to = to.id();
            m.at = tick;
            if (to != bot.body) {
                runs++;
                staff.note(s, bot, tick, "medic: goes to #" + to.id());
            }
        }
        double dx = to.x() - bot.body.x(), dz = to.z() - bot.body.z(), dy = to.y() - bot.body.y();
        double d2 = dx * dx + dz * dz;
        boolean onIt = m.treating != Long.MIN_VALUE;
        boolean by = to == bot.body || d2 <= (onIt ? LEAVE * LEAVE : HAND * HAND) && Math.abs(dy) < 2;
        // the one it goes to is dead, whole again, no more of its side, the way too long, it is pinned down or one of
        // them near (itself too: it stops treating itself): given up
        if (!to.alive() || !wounded(staff, to) || !by && tick - m.at > RUN_MAX || !staff.free(bot, tick)
                || to.player() && (to.creative() || to.spectator() || !AiTeamsBase.own(bot.body, to)) || bot.mover().failed() && bot.mover().goalIs(patientCell(staff, to))) {
            if (to != bot.body && !onIt) gaveUp++;
            m.to = -1;
            m.treating = Long.MIN_VALUE;
            m.next = tick + GIVEN_UP;
            staff.note(s, bot, tick, "medic: gives the run up");
            return false;
        }
        staff.fire(bot, false);
        if (by) {
            // by them: down on its knees, still, a kit at work
            if (!onIt) m.treating = tick;
            if (bot.mover().goal() != Long.MIN_VALUE) bot.mover().stop();
            staff.crouch(s, true);
            if (to != bot.body) staff.aim(bot, new Vec3d(to.x(), to.y(), to.z()).add(0, to.bbHeight() * 0.5, 0));
            if (tick - m.treating >= TREAT) useKit(staff, s, bot, to, tick);
            return true;
        }
        // moved off while it was treating: after them again, the kit's time and the way's from the start
        if (onIt) m.at = tick;
        m.treating = Long.MIN_VALUE;
        long goal = bot.mover().goal();
        // a new way only when they have gone on from where it goes (a player on the move: not a new path each step)
        if (goal == Long.MIN_VALUE || new Vec3d(to.x(), to.y(), to.z()).distanceToSqr(PosLong.blockX(goal) + 0.5, PosLong.blockY(goal), PosLong.blockZ(goal) + 0.5) > REGOAL * REGOAL) {
            goal = patientCell(staff, to);
            bot.mover().costs(staff.fireLines(level, bot));
            bot.mover().goTo(goal, 1.5, staff.run());
        }
        return true;
    }

    /** a kit: HEAL of their health back (to whole at most) */
    static <B extends AiBotBase, S> void useKit(Staff<B, S> staff, S s, B bot, Body to, long tick) {
        State m = stateOf(staff, bot);
        float before = to.health();
        staff.heal(to, HEAL * to.maxHealth());
        healed += to.health() - before;
        m.kits--;
        keepStock(staff, bot);
        if (to == bot.body) self++;
        else treated++;
        staff.note(s, bot, tick, String.format(java.util.Locale.ROOT, "medic: treated %s, %.0f to %.0f of %.0f; %d kits left", to == bot.body ? "itself" : "#" + to.id(),
                before, to.health(), to.maxHealth(), m.kits));
        m.to = -1;
        m.treating = Long.MIN_VALUE;
        m.next = tick + AFTER;
    }

    static <B extends AiBotBase, S> long patientCell(Staff<B, S> staff, Body e) {
        return PosLong.block(GameMath.floor(e.x()), GameMath.floor(e.y() + 0.2), GameMath.floor(e.z()));
    }

    /**
     * The one hurt worst within reach, of its squad or a player of its side; none another medic goes to, none out in
     * sight of one of them (AiSupply.exposed); the nearer of two alike.
     */
    static <B extends AiBotBase, S> @Nullable Body choosePatient(Staff<B, S> staff, B bot, Object level) {
        Body best = null;
        double worst = 1, bestD = Double.MAX_VALUE;
        AiSquadsBase.Squad<B> squad = AiSquadsBase.squadOf(bot);
        if (squad != null) for (B b : squad.bots) {
            if (b == bot || !AiSquadsBase.alive(b) || !wounded(staff, b.body)) continue;
            double share = b.body.health() / b.body.maxHealth(), d = AiSquadsBase.distanceSqr(b, bot);
            if (d > REACH * REACH || staff.exposed(b) || patientTaken(staff, level, bot, b.body.id())) continue;
            if (share < worst || share == worst && d < bestD) {
                worst = share;
                bestD = d;
                best = b.body;
            }
        }
        EntityView entities = staff.entities(level);
        Body[] players = new Body[16];
        int n = entities.players(players);
        if (n < 0) {
            players = new Body[-n];
            n = entities.players(players);
        }
        for (int i = 0; i < n; i++) {
            Body p = players[i];
            if (p.spectator() || p.creative() || !wounded(staff, p) || !AiTeamsBase.own(bot.body, p)) continue;
            double share = p.health() / p.maxHealth(), d = new Vec3d(p.x(), p.y(), p.z()).distanceToSqr(bot.body.x(), bot.body.y(), bot.body.z());
            // one hit by a mob a moment ago is out in sight of them, as far as it can tell
            if (d > REACH * REACH || p.lastHurtBy() != null && p.age() - p.lastHurtTime() < PLAYER_HIT || patientTaken(staff, level, bot, p.id())) continue;
            if (share < worst || share == worst && d < bestD) {
                worst = share;
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    /** one of them that one of its squad sees now (itself first), null: none */
    static <B extends AiBotBase, S> TargetMemory.@Nullable Contact visibleFoe(Staff<B, S> staff, AiBotBase bot) {
        TargetMemory.Contact own = bot.memory.focus(bot.body.x(), bot.body.z());
        if (own != null && own.state == TargetMemory.State.VISIBLE) return own;
        AiSquadsBase.Squad<?> squad = AiSquadsBase.squadOf(bot);
        if (squad != null) for (AiBotBase b : squad.bots) {
            if (b == bot || !AiSquadsBase.alive(b)) continue;
            TargetMemory.Contact c = b.memory.focus(b.body.x(), b.body.z());
            if (c != null && c.state == TargetMemory.State.VISIBLE) return c;
        }
        return null;
    }

    /** another medic goes to this one already */
    static <B extends AiBotBase, S> boolean patientTaken(Staff<B, S> staff, Object level, B bot, int id) {
        for (AiBotBase o : AiBotsBase.bots(level)) if (o != bot && o.medic != null && o.medic.to == id && AiSquadsBase.alive(o) && isMedic(staff, o)) return true;
        return false;
    }

    /** a medic down (AiBots.died): what it was at, for the stands */
    static <B extends AiBotBase, S> void medicFell(Staff<B, S> staff, B bot) {
        if (!isMedic(staff, bot) || bot.medic == null) return;
        down[bot.medic.to < 0 ? 2 : bot.medic.treating != Long.MIN_VALUE ? 1 : 0]++;
    }

    /** the kits as shown over a medic */
    static <B extends AiBotBase, S> String kitLabel(Staff<B, S> staff, B bot) {
        return kitCount(staff, bot) + " kits";
    }

    /** /armsai info */
    public static <B extends AiBotBase, S> String medicInfo(Staff<B, S> staff) {
        return String.format(java.util.Locale.ROOT, "medics: %d runs (%d given up), %d kits on others, %d on themselves, %.0f health back;"
                + " the hurt off to cover %d; looks for none: %s, under fire %d; medics down on a run, treating, else: %s", runs, gaveUp, treated, self, healed, toCover,
                java.util.Arrays.toString(idle), underFire, java.util.Arrays.toString(down));
    }
}
