package acup.gunfeel;

import java.util.List;

import org.jspecify.annotations.Nullable;
import acup.gunfeel.BulletRuleText.Break;
import acup.gunfeel.BulletRuleText.Effect;
import acup.gunfeel.BulletRuleText.Sound;
import acup.gunfeel.BulletRuleText.Whizz;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;

// What bullets do to the blocks and creatures they hit, besides NTM's damage: break blocks, particles, sounds.
// Rules come from ntmgunfeel-bullets.txt in the mod jar, then config/ntm-gunfeel-bullets.txt (its lines win: of the
// rules that match, the last one counts). The server acts on break, material, path, ricochet, entity and kill lines; the
// client draws by hit, decal, hole, whizz and fly, and predicts the server's by the same lines. On another game's server
// the client takes the server's lines of the server's kinds instead of its own (ServerSettings). The answers are core's
// (BulletRulesBase, by the state's and the type's numbers); every entry the callers use is declared here, so that the
// first question of any kind initialises this class and sets up the file's listener below.
public final class BulletRules extends BulletRulesBase {
    private BulletRules() {
    }

    // the rules' file looked at again a second on, by the game's ticks (BulletRuleText.tick): every level's, both sides
    // (a client's level, a server's), from the first question to the rules on; the first one looks at the file at once
    static {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.tick.LevelTickEvent.Post.class,
                e -> tick(System.currentTimeMillis()));
    }

    // bullets pierce machines (material machine) only when the server says so (off by default)
    public static final String MACHINES_KEY = Pierce.MACHINES_KEY;

    // ---- matching

    // which blocks a rule's selectors take (BulletRulesBase.matches, by the state's number in BlockStates)
    public static boolean blockMatches(List<String> sel, BlockState state) {
        return matches(sel, BlockStates.number(state));
    }

    // %mod: a machine (a block entity, drawn by a renderer) or a block that is not a full cube; BlockStates keeps it
    // by the state's number (BlockTable.machineOrPartial)
    static boolean machineOrPartial(BlockState st) {
        if (st.hasBlockEntity() || st.getRenderShape() != net.minecraft.world.level.block.RenderShape.MODEL) return true;
        try {
            return !net.minecraft.world.level.block.Block.isShapeFullBlock(
                    st.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, net.minecraft.core.BlockPos.ZERO));
        } catch (RuntimeException e) {
            return true;
        }
    }

    // ---- answers: the state's number, then core's

    public static @Nullable Break breakRule(BlockState state) {
        return breakRule(BlockStates.number(state));
    }

    public static Materials.Verdict material(BlockState state) {
        return material(BlockStates.number(state));
    }

    public static boolean through(BlockState state) {
        return through(BlockStates.number(state));
    }

    public static boolean anyThrough() {
        return BulletRulesBase.anyThrough();
    }

    public static boolean ricochet(BlockState state) {
        return ricochet(BlockStates.number(state));
    }

    public static @Nullable String path(BlockState state) {
        return path(BlockStates.number(state));
    }

    public static String[] hole(BlockState state) {
        return hole(BlockStates.number(state));
    }

    public static int generation() {
        return BulletRulesBase.generation();
    }

    public static Decals.Set decal(BlockState state) {
        return decal(BlockStates.number(state));
    }

    public static Effect hit(BlockState state) {
        return hit(BlockStates.number(state));
    }

    // "entity" (hit) or "kill" by the creature's type (EntityTypeNumbers)
    public static Effect entity(String kind, Entity e) {
        return entity(kind, EntityTypeNumbers.number(e.getType()));
    }

    public static Whizz whizz() {
        return BulletRulesBase.whizz();
    }

    public static @Nullable Sound fly() {
        return BulletRulesBase.fly();
    }
}
