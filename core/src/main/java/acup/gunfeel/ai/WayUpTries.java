package acup.gunfeel.ai;

/**
 * A soldier's walks to the way up a shaft a foe was lost up (AiSoldier.search, AiHatch.wayUp), counted for one loss of the
 * foe: a walk that failed (no way to it found: the way taken by one of its own, the map not read yet) is begun again
 * after PAUSE ticks; TRIES failed and the way up is given up for that loss. From its first miss till it is CLIMBED blocks
 * up the ladder over where it missed it is stuck: no hunter of its side (AiSquads), the place goes to one waiting.
 * (one stood at a shaft's foot searching till it forgot the foe and held a hunter's place all that time, the stands)
 */
final class WayUpTries {
    static final int PAUSE = 40, TRIES = 3, CLIMBED = 2;

    private int target = -1, misses;
    private long lost = Long.MIN_VALUE, again = Long.MIN_VALUE;
    /** its height at its first miss */
    private double y;
    /** a miss counted on the last step (for the stand trace) */
    boolean missed;

    /** for this loss of this target (a new loss: counted afresh): whether the way up is tried still, not given up */
    boolean tried(int target, long lostTick) {
        if (target != this.target || lostTick != lost) {
            this.target = target;
            lost = lostTick;
            misses = 0;
            again = Long.MIN_VALUE;
        }
        return misses < TRIES;
    }

    /**
     * One tick of its going to the way up: goal, its walk's goal is that way up; failed, that walk failed; climbing and y,
     * on rungs and its height. True: a walk to it to be begun now (the failed one stopped first).
     */
    boolean step(long tick, boolean goal, boolean failed, boolean climbing, double y) {
        missed = false;
        // up the ladder a way since its first miss: on its way up (not at its foot, a rung's cell too, where the walk may fail still)
        if (misses > 0 && climbing && y > this.y + CLIMBED) {
            misses = 0;
            again = Long.MIN_VALUE;
        }
        if (goal && failed) {
            if (again == Long.MIN_VALUE) {
                if (misses == 0) this.y = y;
                misses++;
                again = tick + PAUSE;
                missed = true;
            }
            if (misses < TRIES && tick >= again) {
                again = Long.MIN_VALUE;
                return true;
            }
            return false;
        }
        if (goal) return false;
        // not its goal yet (or a way up another now: its pause over, a miss on it counted)
        again = Long.MIN_VALUE;
        return true;
    }

    boolean stuck() {
        return misses > 0;
    }

    int misses() {
        return misses;
    }
}
