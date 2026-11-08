package acup.gunfeel.ai;

import java.util.List;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.smoke.SmokeClouds;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/**
 * One AI mob's mind: its profile, what it knows ({@link TargetMemory}) and the search cells it has looked into. Its
 * senses look around every profile.period ticks, spread over the ticks by the mob's id. Driven by the mob's own tick
 * (the Goal), so a mob in a chunk that does not tick thinks nothing.
 */
public final class AiBot {
    public final Mob mob;
    public final AiProfile profile;
    public final TargetMemory memory;
    /** search cells looked into, of the target lost last */
    public final LongOpenHashSet cleared = new LongOpenHashSet();
    private int clearedFor = Integer.MIN_VALUE;
    private long clearedLost = Long.MIN_VALUE;
    public final AiMover mover;
    public final AiGunner gunner;
    public final AiProne prone;
    /** running low past low cover (its brain asks it each tick) */
    public final AiStoop stoop = new AiStoop();
    /** what it does with what it knows; null: it only senses (a stand drives it) */
    public @Nullable AiBrain brain;
    /** grenades it has left (AiGrenades) */
    public int grenades;
    /** a player within aiRadius: it thinks; else it stands still */
    boolean active = true;

    AiBot(Mob mob, AiProfile profile) {
        this.mob = mob;
        this.profile = profile;
        this.memory = new TargetMemory(profile);
        this.eyes = profile;
        this.mover = new AiMover(mob);
        this.gunner = new AiGunner(mob, new AiAmmo(0));
        this.prone = new AiProne(mob);
        // each its own place in the list, so a hundred do not all look at the same twelve
        this.scanFrom = mob.getId();
    }

    public boolean active() {
        return active;
    }

    /** a look around now and then, the memory's clock always */
    public void tick(VoxelRay.Source voxels, @Nullable SmokeClouds smoke, List<LivingEntity> targets, long tick) {
        if ((tick + mob.getId()) % profile.period() == 0) perceive(voxels, smoke, targets, tick);
        memory.update(tick);
    }

    private @Nullable AiSkill eyesFor;
    private AiProfile eyes;

    /** its profile as its eyes notice with it: the skill (aiSkill) moves the time to notice from a regular's */
    private AiProfile eyes() {
        AiSkill skill = AiSkill.current();
        if (skill != eyesFor) {
            eyesFor = skill;
            eyes = profile.withReaction(Math.max(1, profile.reaction() + skill.notice() - AiSkill.REGULAR.notice()));
        }
        return eyes;
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
            int notice = Senses.look(voxels, smoke, mob, t, eyes(), focus);
            // of one it does not aim at it saw one part, not which ones: all of it, as far as aiming goes (the chest,
            // with the wide band), till it deals with them and sees them whole
            if (notice >= 0) memory.seen(t.getId(), t.getX(), t.getY(), t.getZ(), tick, notice).parts = focus ? Senses.parts : Senses.WHOLE;
        }
        memory.endLook(tick);
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
}
