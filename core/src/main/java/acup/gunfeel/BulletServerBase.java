package acup.gunfeel;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.host.Body;
import acup.gunfeel.host.BulletBlocks;
import acup.gunfeel.host.WorldEdit;
import acup.gunfeel.host.WorldView;

/** Bullet block wear and its entry decisions; the version keeps its bullet and hit reads lazy. */
public final class BulletServerBase {
    public record Shooter(int id, @Nullable Body owner, float damage) {}

    private final BlockWear<Object> WEAR = new BlockWear<>();
    private final WorldEdit edit;
    private final BulletBlocks blocks;
    private final BlockWear.Cracks<Object> CRACKS;
    private final BlockWear.Gone<Object> GONE;

    public BulletServerBase() {
        this(CoreServices.load(WorldEdit.class, WorldEdit.MISSING), CoreServices.load(BulletBlocks.class, BulletBlocks.MISSING));
    }

    BulletServerBase(WorldEdit edit, BulletBlocks blocks) {
        this.edit = edit;
        this.blocks = blocks;
        CRACKS = (level, pos, stage) -> edit.cracks(level,
                crackId(PosLong.blockX(pos), PosLong.blockY(pos), PosLong.blockZ(pos)), blocks.position(pos), stage);
        GONE = new BlockWear.Gone<>() {
            @Override public boolean gone(Object level, long pos, Object token) { return blocks.gone(level, pos, token); }
            @Override public long time(Object level) { return blocks.time(level); }
        };
    }

    public void forgetWear() { WEAR.clear(); }
    public Object wearNow() { return WEAR.now(); }
    public void wearBack(Object was) { WEAR.back(was); }
    public void tick() { WEAR.tick(GONE, CRACKS); }

    public int blockHit(Object level, WorldView world, Object pos, int x, int y, int z, @Nullable Object bullet,
            int id, @Nullable Body owner, float damage, Object hit) {
        int state = world.state(x, y, z);
        if (world.air(state)) return 0;
        BulletRuleText.Break rule = blocks.rule(state);
        if (rule == null || rule.mode() == 0) return 0;
        float k = bullet == null ? 1F : blocks.water(bullet, hit);
        boolean broke = bullet == null ? wear(level, world, new Shooter(id, owner, damage), pos, x, y, z, state, rule, k)
                : wearShot(level, world, bullet, pos, x, y, z, state, rule, k);
        if (!broke) return 0;
        if (TestLog.server) TestLog.s(bullet == null ? id : blocks.shotId(bullet), "end", "by", rule.pass() ? "broke_pass" : "broke", "block", blocks.stateObject(state), "pos", pos, "at", blocks.location(hit));
        return rule.pass() ? 2 : 1;
    }

    public void wearBy(Object level, WorldView world, Object pos, int x, int y, int z, @Nullable Object bullet,
            int id, @Nullable Body owner, float damage, Object hit) {
        int state = world.state(x, y, z);
        if (world.air(state)) return;
        BulletRuleText.Break rule = blocks.rule(state);
        if (rule == null || rule.mode() == 0) return;
        if (bullet == null) wear(level, world, new Shooter(id, owner, damage), pos, x, y, z, state, rule, 1F);
        else wearShot(level, world, bullet, pos, x, y, z, state, rule, 1F);
    }

    public void passed(Object level, WorldView world, Object pos, int x, int y, int z, @Nullable Object bullet,
            int id, @Nullable Body owner, float damage, Object hit) {
        int state = world.state(x, y, z);
        if (TestLog.server) TestLog.s(bullet == null ? id : blocks.shotId(bullet), "through", "block", blocks.stateObject(state), "pos", pos, "at", blocks.location(hit));
        BulletRuleText.Break rule = blocks.rule(state);
        if (rule == null || rule.mode() == 0) return;
        float k = bullet == null ? 1F : blocks.water(bullet, hit);
        if (bullet == null) wear(level, world, new Shooter(id, owner, damage), pos, x, y, z, state, rule, k);
        else wearShot(level, world, bullet, pos, x, y, z, state, rule, k);
    }

    public boolean wearShot(Object level, WorldView world, Object bullet, Object pos, int x, int y, int z,
            int state, BulletRuleText.Break rule, float k) {
        return wear(level, world, blocks.shooter(bullet), pos, x, y, z, state, rule, k);
    }

    private void worn(Shooter bullet, Object pos, int state, String result, Object... more) {
        if (!TestLog.server) return;
        Object[] kv = new Object[8 + more.length];
        Object[] head = {"block", blocks.stateObject(state), "pos", pos, "result", result, "damage", bullet.damage()};
        System.arraycopy(head, 0, kv, 0, 8);
        System.arraycopy(more, 0, kv, 8, more.length);
        TestLog.s(bullet.id(), "wear", kv);
    }

    public boolean wear(Object level, WorldView world, Shooter bullet, Object pos, int x, int y, int z,
            int state, BulletRuleText.Break rule, float k) {
        float damage = bullet.damage() * k;
        float hardness = world.destroySpeed(state, x, y, z);
        if (hardness < 0F) {
            worn(bullet, pos, state, "unbreakable");
            return false;
        }
        int permission = blocks.mayBreak(level, bullet.owner(), pos, state);
        if (permission == BulletBlocks.PROTECTED) {
            worn(bullet, pos, state, "protected");
            return false;
        } else if (permission == BulletBlocks.NO_MOB_GRIEFING) {
            worn(bullet, pos, state, "no_mob_griefing");
            return false;
        }
        if (rule.tooWeak(damage)) {
            worn(bullet, pos, state, "too_weak", "min", rule.min());
            return false;
        }
        float[] worn = new float[2];
        int stage = WEAR.hit(level, PosLong.block(x, y, z), blocks.blockToken(state), damage, hardness, rule, world.gameTime(), CRACKS, worn);
        if (stage != BlockWear.BREAKS) {
            worn(bullet, pos, state, "wear", "done", worn[0], "need", worn[1], "min", rule.min());
            edit.cracks(level, crackId(x, y, z), pos, stage);
            return false;
        }
        worn(bullet, pos, state, "broke", "min", rule.min());
        edit.destroy(level, pos, rule.drop(), bullet.owner());
        if (rule.replace() != null) {
            int with = blocks.parseState(level, rule.replace());
            if (with != -1) edit.set(level, pos, with, 3);
        }
        return true;
    }

    static int crackId(int x, int y, int z) {
        return -1_000_000 - (((y + z * 31) * 31 + x) & 0x3fffffff);
    }
}
