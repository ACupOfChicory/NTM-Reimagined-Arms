package acup.gunfeel.ai.bench;

// What vanilla pathfinding does in a tick, counted by MixinPathCount and MixinPathSearchCount for the mob AI's stand
// (AiBench). Nothing is counted unless the stand turns it on; the server thread is the only one that writes.
public final class PathCount {
    public static boolean on;
    // PathNavigation.shouldRecomputePath: asked by ServerLevel.sendBlockUpdated, once per navigating mob per block
    // whose collision changed (with Lithium, once per mob that has a path)
    public static int asked;
    // PathNavigation.recomputePath: the mobs that said yes (a search now, or one put off to 20 ticks after the last)
    public static int recomputed;
    // PathFinder.findPath: every search for a path, by anything
    public static int searches;

    private PathCount() {
    }
}
