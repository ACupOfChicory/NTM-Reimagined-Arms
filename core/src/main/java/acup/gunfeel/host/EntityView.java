package acup.gunfeel.host;

import org.jspecify.annotations.Nullable;

/**
 * A level's entities as core asks for them: by the game's number where the code looks one up by number, and those in
 * a box, in the game's order (the AI picks by it). Each entity is its one {@link Body}. Read only: what is done to an
 * entity is another port's.
 */
public interface EntityView {
    /** the kinds near asks for: living ones, mobs, projectiles (the classes the AI asks the game for) */
    int LIVING = 0, MOB = 1, PROJECTILE = 2;

    /** the entity with that number in the level now (any kind, see {@link Body#living}); null when there is none */
    @Nullable Body byId(int id);

    /**
     * The entities of a kind whose boxes meet the box, players watching only (spectators) left out, in the game's
     * order, into out from 0; how many. When out is too short nothing is cut: it is -(how many), and the asker asks
     * again with room for them.
     */
    int near(int kind, double x0, double y0, double z0, double x1, double y1, double z1, Body[] out);
}
