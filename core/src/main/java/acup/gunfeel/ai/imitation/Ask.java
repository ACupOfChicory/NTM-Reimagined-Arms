package acup.gunfeel.ai.imitation;

/** the places a soldier asks AiChoice at (its order is the arrays' in ImitationCore and the counts in Imitation's report) */
public enum Ask {
    /** in high cover in a fight, ready: out to its peek now? (formula: its hide is over) */
    PEEK_FIGHT,
    /** in cover from a target lost from sight: look out where they were now? */
    LOOK_LOST,
    /** in high cover from a noise: look out the way it came from now? */
    LOOK_NOISE,
    /** out at its peek: back into cover now? (formula: its burst is over) */
    BACK_IN,
    /** back from a peek: other cover? */
    SHIFT_AFTER_PEEK,
    /** shot at in cover with the shooter in sight: other cover? */
    SHIFT_ON_HIT,
    /** hit in cover from out of sight: other cover? */
    HIT_MOVE,
    /** in cover a while: look for a better place now? */
    BETTER_COVER,
    /** a target lost and looked for: go for them now? */
    PUSH_NOW
}
