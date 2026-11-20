package acup.gunfeel.ai;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.host.Body;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * One AI mob's mind as core sees it (26.2's AiBot): the mob as a {@link Body}, its profile, what it knows
 * ({@link TargetMemory}), its confidence and the plain state its staff, squad and roles keep on it; its mover, gunner
 * and the rest by their core bases. What is still the version's (the mob itself, its brain, role, the staff's order, a
 * role's state) the version's AiBot keeps.
 */
public abstract class AiBotBase {
    /** the mob, for core */
    public final Body body;
    public final AiProfile profile;
    public final TargetMemory memory;
    /** search cells looked into, of the target lost last */
    public final LongOpenHashSet cleared = new LongOpenHashSet();
    private int clearedFor = Integer.MIN_VALUE;
    private long clearedLost = Long.MIN_VALUE;
    public final AiConfidence confidence = new AiConfidence();
    /** what it is to its squad, worked out for the gun in its hands (the version's AiRoles.of) */
    AiRole role = AiRole.RIFLE;
    /** the role it was given by word (/armsai spawn … role, /armsai kit … role), null: by its gun */
    @Nullable AiRole assigned;
    /** a medic's kits and run (AiMedic); null till it is one */
    AiMedicBase.@Nullable State medic;
    /** a supplier's stock to hand out {magazines, grenades, smoke grenades} (AiSupply), null till it is one */
    int @Nullable [] supply;
    /** the group of enemies it gave up looking for (its members' ids), not again before this tick */
    int @Nullable [] orderFailed;
    long orderFailedUntil = Long.MIN_VALUE;
    /** at red (AiCommander.red) since, as its staff last looked; Long.MIN_VALUE: not */
    long redFrom = Long.MIN_VALUE;
    long supplyAt, supplyNext = Long.MIN_VALUE;
    /** when it was last handed supplies */
    long suppliedAt = Long.MIN_VALUE / 2;
    /** grenades it has left (AiGrenades) */
    public int grenades;
    /**
     * where a foe it (or its squad) knew of fell in a building, for AiSoldier to go in and through its rooms while it
     * knows of no one (a room fought in is cleared all the same: there may be more in it): a place, not a target; null none
     */
    public TargetMemory.@Nullable Contact fought;
    /** smoke grenades it has left (AiSmoke) */
    public int smokes;
    /** when it last called out (AiVoice) */
    public long saidAt = Long.MIN_VALUE / 2;
    /** a sign with its free hand or a hand to a door (AiPose's action), since when and how long; shown while it does nothing else with its hands */
    public byte gesture;
    public long gestureAt = Long.MIN_VALUE / 2;
    public short gestureTicks;
    /** waiting beside a way in (AiEntry) till this tick: its gun held up close (AiPose.STACK) */
    public long stackUntil = Long.MIN_VALUE;

    /** the server setting: AI soldiers point, wave on and halt with a hand as they call out, put a hand to a door, hold the gun close beside a doorway */
    public static final String GESTURES_KEY = "aiGestures";

    static boolean gestures() {
        return acup.gunfeel.ReaimTimes.flag(GESTURES_KEY, true);
    }

    void gesture(byte kind, int ticks, long tick) {
        if (!gestures()) return;
        gesture = kind;
        gestureAt = tick;
        gestureTicks = (short) ticks;
    }

    /** a player within aiRadius: it thinks; else it stands still */
    boolean active = true;

    /** holding its place in its squad's bounds (C3): the tick it last held, and since when it holds (AiHold) */
    long holdAt = Long.MIN_VALUE, holdSince = Long.MIN_VALUE;

    /** the body: the version's one for the mob (EntityBody.of on 26.2) */
    protected AiBotBase(Body body, AiProfile profile) {
        this.body = body;
        this.profile = profile;
        this.memory = new TargetMemory(profile);
        this.eyes = profile;
    }

    public boolean active() {
        return active;
    }

    private @Nullable AiSkill eyesFor;
    private AiProfile eyes;

    /** its profile as its eyes notice with it: the skill (aiSkill) moves the time to notice from a regular's */
    AiProfile eyes() {
        AiSkill skill = AiSkill.current();
        if (skill != eyesFor) {
            eyesFor = skill;
            eyes = profile.withReaction(Math.max(1, profile.reaction() + skill.notice() - AiSkill.REGULAR.notice()));
        }
        return eyes;
    }

    /** the cleared set belongs to one loss of one target: a new loss starts it over */
    public LongOpenHashSet clearedFor(TargetMemory.Contact c) {
        if (c.id != clearedFor || c.lostTick != clearedLost) {
            cleared.clear();
            clearedFor = c.id;
            clearedLost = c.lostTick;
        }
        return cleared;
    }

    /** what it is to its squad: the role given by word, else the one its gun makes it (the version's AiRoles.of) */
    public abstract AiRole role();

    /** it fights as a soldier (its brain the version's AiSoldier) */
    public abstract boolean soldier();

    /** a soldier on its way to cover or after a target (TO_COVER, SEARCH); its mode asked once */
    public abstract boolean onTheMove();

    /** a soldier in its cover, out at its peek or holding (COVER, PEEK, HOLD), with a spot; its mode asked once */
    public abstract boolean inCover();

    /** a soldier searching for a lost target itself (SEARCH), not one short of the way up a shaft (wayUpStuck) */
    public abstract boolean searching();

    /** a soldier going through the building this target fell in (AiSoldier.fights) */
    public abstract boolean fightsFallen(int target);

    /** a soldier falling back now (AiSoldier.fallingBack) */
    public abstract boolean fallingBack();

    /** a marksman that fights from perches (AiMarksman.is) */
    public abstract boolean marksman();

    /** a marksman with a perch it goes to or fires from (AiMarksman.perched) */
    public abstract boolean perched();

    /** a marksman at its perch (AiMarksman.there) */
    public abstract boolean atPerch();

    /** a grenadier that lobs (AiGrenadier.is) */
    public abstract boolean grenadier();

    /** its level, the version's object, where it is a server's; null elsewhere */
    public abstract @Nullable Object serverLevel();

    /** its level for core where it is a server's (Wiring.world on 26.2); null elsewhere */
    public abstract acup.gunfeel.host.@Nullable WorldView world();

    /** where it goes (the version's AiMover) */
    public abstract AiMoverBase mover();

    /** its gun (the version's AiGunner) */
    public abstract AiGunnerBase gunner();

    /** lying down (the version's AiProne) */
    public abstract AiProneBase prone();

    /** running low past low cover (the version's AiStoop) */
    public abstract AiStoopBase stoop();

    /** a slide into cover, a roll under fire (the version's AiDash) */
    public abstract AiDashBase dash();
}
