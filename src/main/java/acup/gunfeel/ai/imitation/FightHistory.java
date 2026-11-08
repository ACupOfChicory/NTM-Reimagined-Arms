package acup.gunfeel.ai.imitation;

/**
 * What a fighter's last moments add to a view: how long it has been in this cover and how many times it looked out of
 * it, how long since it saw an enemy, since it was hurt, how long it has been in an enemy's sight. Fed a view now and
 * then (a recorded player every few ticks, a bot when it asks); the times between are taken as they were. Plain
 * numbers, no world.
 */
public final class FightHistory {
    /** "long ago": every time is capped here, ticks */
    public static final int LONG = 400;
    /** within this of where it took cover it is still in that cover (a peek is a block or two aside), blocks */
    static final double SAME_COVER = 2.5;
    /** further than this from it, it has left that cover */
    static final double LEFT = 4;
    /** moved less than this between two views, blocks: standing still */
    static final double STILL = 0.3;

    private boolean anchored;
    private double ax, az;
    private long coverSince, seenTick = Long.MIN_VALUE, hurtTick = Long.MIN_VALUE, exposedSince = Long.MIN_VALUE, hiddenSince = Long.MIN_VALUE;
    private int peeks;
    private boolean wasHidden, wasExposed;
    private float lastHealth = Float.NaN;
    private double lastX = Double.NaN, lastZ;
    private long stillSince;
    private int lastLoaded = -1;

    /**
     * feeds the view's moment in and fills the view's history numbers; hurt: its health went down since the last one,
     * fired: its rounds did (a reload puts them up)
     */
    public void update(FightView v) {
        long t = v.tick;
        boolean hurt = !Float.isNaN(lastHealth) && v.health < lastHealth - 1e-4F;
        lastHealth = v.health;
        v.fired = lastLoaded >= 0 && v.loaded >= 0 && v.loaded < lastLoaded;
        lastLoaded = v.loaded;
        v.hurt = hurt;
        if (hurt) hurtTick = t;
        if (v.visibleCount() > 0) seenTick = t;
        boolean hidden = v.hidden(), exposed = v.exposed();
        double d = anchored ? Math.hypot(v.x - ax, v.z - az) : Double.MAX_VALUE;
        if (anchored && d > LEFT) anchored = false;
        if (hidden && (!anchored || d > SAME_COVER)) {
            anchored = true;
            ax = v.x;
            az = v.z;
            coverSince = t;
            peeks = 0;
            d = 0;
        }
        boolean near = anchored && d <= SAME_COVER;
        // out of that cover into an enemy's sight and still by it: a look out
        if (near && wasHidden && exposed) peeks++;
        if (exposed && !wasExposed) exposedSince = t;
        if (hidden && !wasHidden) hiddenSince = t;
        // standing still: moved no more than STILL since the last view
        if (Double.isNaN(lastX) || Math.hypot(v.x - lastX, v.z - lastZ) > STILL) stillSince = t;
        lastX = v.x;
        lastZ = v.z;
        wasHidden = hidden;
        wasExposed = exposed;
        v.ticksInCover = near ? cap(t - coverSince) : 0;
        v.peeksFromCover = near ? peeks : 0;
        // what the world knows is the same however often the view is taken (a bot is looked at more seldom than a player)
        v.ticksSinceSeen = v.seenAgo >= 0 ? cap(v.seenAgo) : since(t, seenTick);
        v.ticksSinceHurt = v.hurtAgo >= 0 ? cap(v.hurtAgo) : since(t, hurtTick);
        v.ticksExposed = exposed ? cap(t - exposedSince) : 0;
        v.ticksHidden = hidden ? cap(t - hiddenSince) : 0;
        v.ticksStill = cap(t - stillSince);
    }

    private static int since(long t, long at) {
        return at == Long.MIN_VALUE ? LONG : cap(t - at);
    }

    private static int cap(long dt) {
        return (int) Math.max(0, Math.min(LONG, dt));
    }
}
