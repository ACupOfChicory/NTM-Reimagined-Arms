package acup.gunfeel.ai;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.LevelEntities;
import acup.gunfeel.Vec3d;
import acup.gunfeel.Vecs;
import acup.gunfeel.host.EntityView;
import net.minecraft.server.level.ServerLevel;

/** 26.2: the marksman's map, terrain and soldier actions for core. */
public final class AiMarksman extends AiMarksmanBase {
    private AiMarksman() {
    }

    private static final Staff<AiBot, AiSoldier> STAFF = new Staff<>() {
        @Override public CellSource map(Object level) { return TacticalMap.of((ServerLevel) level); }
        @Override public Sight terrain(Object level) {
            AiTerrain terrain = AiTerrain.ON ? AiTerrain.peek((ServerLevel) level) : null;
            return terrain == null ? null : terrain::openTowards;
        }
        @Override public EntityView entities(Object level) { return new LevelEntities((ServerLevel) level); }
        @Override public Vec3d middle(AiBot bot) {
            var middle = AiSquads.middle(bot);
            return middle == null ? null : Vecs.of(middle);
        }
        @Override public java.util.function.LongPredicate claims(AiBot bot) { return AiBots.heldByOthers(bot); }
        @Override public VoxelRay.Source voxels(Object level) { return AiBots.voxels((ServerLevel) level); }
        @Override public PathSearch.Cost fireLines(Object level, AiBot bot) { return FireLines.of((ServerLevel) level, bot.mob); }
        @Override public void crouch(AiSoldier soldier, boolean low) { soldier.crouch(low); }
        @Override public void note(AiSoldier soldier, AiBot bot, long tick, String text) { soldier.note(bot, tick, text); }
        @Override public double run() { return AiSoldier.RUN; }
    };

    public static boolean on() { return enabled(); }
    static boolean is(AiBot bot) { return usesPerches(STAFF, bot); }
    static boolean step(AiSoldier s, AiBot bot, ServerLevel level, TargetMemory.Contact c, long tick) { return fightFrom(STAFF, s, bot, level, c, tick); }
    private static void leave(State m, long tick, boolean wait) { abandonPerch(m, tick, wait); }
    static boolean perched(AiBot bot) { return hasPerch(STAFF, bot); }
    static boolean there(AiBot bot) { return atPerch(STAFF, bot); }
    private static long pick(AiBot bot, ServerLevel level, TargetMemory.Contact c, State m) { return choosePerch(STAFF, bot, level, c, m); }
    private static boolean near(State m, int x, int z) { return nearHistory(m, x, z); }
    public static String info() { return statistics(); }

}
