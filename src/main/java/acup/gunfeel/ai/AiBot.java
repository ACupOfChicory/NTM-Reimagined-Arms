package acup.gunfeel.ai;

import java.util.List;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.smoke.SmokeClouds;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/**
 * One AI mob's mind: its profile, what it knows ({@link TargetMemory}) and the search cells it has looked into. Its
 * senses look around every profile.period ticks, spread over the ticks by the mob's id. Driven by the mob's own tick
 * (the Goal), so a mob in a chunk that does not tick thinks nothing.
 */
public final class AiBot extends AiBotBase {
    public final Mob mob;
    public final AiMover mover;
    public final AiGunner gunner;
    public final AiProne prone;
    /** running low past low cover (its brain asks it each tick) */
    public final AiStoop stoop = new AiStoop();
    /** a slide into cover, a roll under fire */
    public final AiDash dash = new AiDash();
    /** a supplier: the one it runs to with supplies, since when; no look for one in need before this tick */
    @Nullable AiBot supplyTo;
    /** its side's commander's order: an area to go to and look through (AiCommander), null: none */
    AiCommander.@Nullable Order order;
    /** the fallen supplier's stock it goes for (AiSupply) */
    AiSupply.@Nullable Left supplyFetch;
    /** the gun in its hands its role was worked out for (AiRoles.of) */
    net.minecraft.world.item.@Nullable Item roleFor;
    /** what it does with what it knows; null: it only senses (a stand drives it) */
    public @Nullable AiBrain brain;

    AiBot(Mob mob, AiProfile profile) {
        super(acup.gunfeel.EntityBody.of(mob), profile);
        this.mob = mob;
        this.mover = new AiMover(mob);
        this.gunner = new AiGunner(mob, new AiAmmo(0));
        this.prone = new AiProne(mob);
        // each its own place in the list, so a hundred do not all look at the same twelve
        this.scanFrom = mob.getId();
    }

    /** a look around now and then, the memory's clock always */
    public void tick(VoxelRay.Source voxels, @Nullable SmokeClouds smoke, List<LivingEntity> targets, long tick) {
        if ((tick + mob.getId()) % profile.period() == 0) perceive(voxels, smoke, targets, tick);
        memory.update(tick);
    }

    /**
     * Foes it knows nothing of yet that one look takes in, in turn from look to look (those it knows of, at most
     * {@link TargetMemory#MAX_CONTACTS}, it looks at every time): a person does not watch fifty at once, and fifty foes
     * a look, three rays each, cost a hundred AI mobs 4 ms a tick (KT5). Fewer foes than this, nothing changes.
     */
    static final int SCAN = 12;
    /** where in the list of targets the next look's foes it knows nothing of start (each mob its own place) */
    private int scanFrom;

    public void perceive(VoxelRay.Source voxels, @Nullable SmokeClouds smoke, List<LivingEntity> targets, long tick) {
        memory.look();
        int n = targets.size(), start = n == 0 ? 0 : Math.floorMod(scanFrom, n), scanned = 0;
        double range = eyes().viewRange();
        AiEntry.Primed primed = n == 0 ? null : AiEntry.primed(this, tick);
        for (int k = 0; k < n; k++) {
            LivingEntity t = targets.get((start + k) % n);
            if (t == mob || !t.isAlive() || !AiTeams.enemy(mob, t)) continue;
            if (memory.contact(t.getId()) == null) {
                // one out of its sight's reach is no look (and takes no place among the twelve)
                if (scanned == SCAN || t.distanceToSqr(mob) > range * range) continue;
                // the next look's new ones start after the last one this look took
                if (++scanned == SCAN) scanFrom = start + k + 1;
            }
            boolean focus = t.getId() == memory.focusId;
            boolean looked = primed != null && primed.in(t);
            int notice = Senses.look(voxels, smoke, mob, t, eyes(), focus, looked);
            TargetMemory.Contact was = looked ? memory.contact(t.getId()) : null;
            // (one it knew of is noticed again in half the time: one looked for, its least kept even so)
            if (looked && notice >= 0 && was != null && was.state != TargetMemory.State.GLIMPSED) notice = Math.max(notice, 2 * Senses.PRIMED_MIN);
            // of one it does not aim at it saw one part, not which ones: all of it, as far as aiming goes (the chest,
            // with the wide band), till it deals with them and sees them whole
            if (notice >= 0) {
                boolean before = was != null && was.state == TargetMemory.State.VISIBLE;
                TargetMemory.Contact c = memory.seen(t.getId(), t.getX(), t.getY(), t.getZ(), tick, notice);
                c.parts = focus ? Senses.parts : Senses.WHOLE;
                if (looked && !before && c.state == TargetMemory.State.VISIBLE) AiEntry.primedNoticed++;
            }
        }
        memory.endLook(tick);
    }

    @Override
    public AiRole role() {
        return AiRoles.of(this);
    }

    @Override
    public boolean soldier() {
        return brain instanceof AiSoldier;
    }

    @Override
    public boolean onTheMove() {
        if (!(brain instanceof AiSoldier s)) return false;
        AiSoldier.Mode m = s.mode();
        return m == AiSoldier.Mode.TO_COVER || m == AiSoldier.Mode.SEARCH;
    }

    @Override
    public boolean inCover() {
        if (!(brain instanceof AiSoldier s)) return false;
        AiSoldier.Mode m = s.mode();
        return (m == AiSoldier.Mode.COVER || m == AiSoldier.Mode.PEEK || m == AiSoldier.Mode.HOLD) && s.spot() != null;
    }

    @Override
    public boolean searching() {
        return brain instanceof AiSoldier o && o.mode() == AiSoldier.Mode.SEARCH && !o.wayUpStuck();
    }

    @Override
    public boolean fightsFallen(int target) {
        return AiSoldier.fights(this, target);
    }

    @Override
    public boolean fallingBack() {
        return brain instanceof AiSoldier o && o.fallingBack();
    }

    @Override
    public boolean marksman() {
        return AiMarksman.is(this);
    }

    @Override
    public boolean perched() {
        return AiMarksman.perched(this);
    }

    @Override
    public boolean atPerch() {
        return AiMarksman.there(this);
    }

    @Override
    public boolean grenadier() {
        return AiGrenadier.is(this);
    }

    @Override
    public @Nullable Object serverLevel() {
        return mob.level() instanceof net.minecraft.server.level.ServerLevel level ? level : null;
    }

    /** its level for core, made again only when its level is another */
    private acup.gunfeel.host.@Nullable WorldView world;
    private net.minecraft.world.level.@Nullable Level worldOf;

    @Override
    public acup.gunfeel.host.@Nullable WorldView world() {
        net.minecraft.world.level.Level level = mob.level();
        if (!(level instanceof net.minecraft.server.level.ServerLevel)) return null;
        if (level != worldOf) {
            world = acup.gunfeel.host.Worlds.of(level);
            worldOf = level;
        }
        return world;
    }

    @Override
    public AiMover mover() {
        return mover;
    }

    @Override
    public AiGunner gunner() {
        return gunner;
    }

    @Override
    public AiProne prone() {
        return prone;
    }

    @Override
    public AiStoop stoop() {
        return stoop;
    }

    @Override
    public AiDash dash() {
        return dash;
    }
}
