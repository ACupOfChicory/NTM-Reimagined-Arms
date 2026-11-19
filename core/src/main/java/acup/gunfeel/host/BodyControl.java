package acup.gunfeel.host;

/**
 * What core does to a body in the game: one per version (26.2: EntityBodyControl), each method one call of the game's
 * on the entity behind the Body. Reads stay on {@link Body}; the caller keeps the order the game's code had.
 *
 * <p>A mob's own control: what its navigation and its MoveControl take and tell, written and read here; {@link Body}
 * has only what the entity itself has. The navigation and MoveControl methods are for mobs only.
 */
public interface BodyControl {
    /** where the version gives none (core's own tests): each method throws */
    BodyControl MISSING = new BodyControl() {
        private UnsupportedOperationException none() {
            return new UnsupportedOperationException("no BodyControl here");
        }

        @Override public void stance(Body b, byte stance) { throw none(); }
        @Override public void speed(Body b, double modifier) { throw none(); }
        @Override public void stop(Body b) { throw none(); }
        @Override public boolean done(Body b) { throw none(); }
        @Override public boolean walk(Body b, long[] cells, boolean reached, double modifier) { throw none(); }
        @Override public int pathNext(Body b) { throw none(); }
        @Override public void want(Body b, double x, double y, double z, double modifier) { throw none(); }
        @Override public boolean stuck(Body b) { throw none(); }
    };

    /**
     * Puts it in a stance ({@link acup.gunfeel.ai.Stance}: the game's pose STANDING, CROUCHING, or SWIMMING for PRONE,
     * the pose an AI mob lies down in); after it {@code b.stance()} is {@code stance}.
     */
    void stance(Body b, byte stance);

    /** the walk under way at this speed modifier (the game's PathNavigation.setSpeedModifier) */
    void speed(Body b, double modifier);

    /** the walk stopped (PathNavigation.stop): after it {@link #done} */
    void stop(Body b);

    /** no walk under way (PathNavigation.isDone) */
    boolean done(Body b);

    /**
     * Walks these cells (BlockPos longs, the whole path in order: a node a cell, walkable, its end the last cell;
     * reached: the path gets to the goal) at this speed modifier (PathNavigation.moveTo(path, modifier)); false where the
     * navigation would not take it.
     */
    boolean walk(Body b, long[] cells, boolean reached, double modifier);

    /** the index of the next node of the path walked, 0 with none (Path.getNextNodeIndex) */
    int pathNext(Body b);

    /** its MoveControl to step straight to this point at this speed modifier (setWantedPosition) */
    void want(Body b, double x, double y, double z, double modifier);

    /**
     * It jumped where it stood since asked last (the mod's own MoveControl, AiMoveControl; false for another): a read
     * that clears what it reads, so asked once a tick where the game's code asked it.
     */
    boolean stuck(Body b);
}
