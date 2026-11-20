package acup.gunfeel.host;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.BulletRuleText;
import acup.gunfeel.BulletServerBase.Shooter;

/** The version's block permissions, replacement parser and identity for bullet wear. */
public interface BulletBlocks {
    int ALLOW = 0, PROTECTED = 1, NO_MOB_GRIEFING = 2;

    /** Permission at the hit (26.2: mayBuild, BreakBlockEvent, or MOB_GRIEFING). */
    int mayBreak(Object level, @Nullable Body owner, Object pos, int state);
    /** Replacement state number, or -1 (26.2: cached BlockStateParser, then BlockStates.number outside its catch). */
    int parseState(Object level, String text);
    /** A block token (26.2: BlockStates.state(n).getBlock()): an opaque object of the version; core only compares it with ==. */
    Object blockToken(int state);
    /** The block unloaded or replaced (26.2: isLoaded, then getBlockState(at).getBlock() != token). */
    boolean gone(Object level, long pos, Object token);
    /** The sweep's game tick (26.2: Level.getGameTime). */
    long time(Object level);
    /** The packed position as the version keeps it (26.2: BlockPos.of). */
    Object position(long pos);
    /** The diagnostic's original state object (26.2: BlockStates.state). */
    Object stateObject(int state);
    /** The break rule (26.2: BulletRules.breakRule, initialising its file listener). */
    BulletRuleText.@Nullable Break rule(int state);
    /** The water share at the hit (26.2: WaterTick.k). */
    float water(Object bullet, Object hit);
    /** The wear's shooter, made inside wear (26.2: getId, getOwner, damage, EntityBody.of). */
    Shooter shooter(Object bullet);
    /** The diagnostic's shot id (26.2: MK4.getId). */
    int shotId(Object bullet);
    /** The diagnostic's hit point (26.2: BlockHitResult.getLocation). */
    Object location(Object hit);

    BulletBlocks MISSING = new BulletBlocks() {
        @Override public int mayBreak(Object level, @Nullable Body owner, Object pos, int state) { throw new UnsupportedOperationException("no BulletBlocks here"); }
        @Override public int parseState(Object level, String text) { throw new UnsupportedOperationException("no BulletBlocks here"); }
        @Override public Object blockToken(int state) { throw new UnsupportedOperationException("no BulletBlocks here"); }
        @Override public boolean gone(Object level, long pos, Object token) { throw new UnsupportedOperationException("no BulletBlocks here"); }
        @Override public long time(Object level) { throw new UnsupportedOperationException("no BulletBlocks here"); }
        @Override public Object position(long pos) { throw new UnsupportedOperationException("no BulletBlocks here"); }
        @Override public Object stateObject(int state) { throw new UnsupportedOperationException("no BulletBlocks here"); }
        @Override public BulletRuleText.@Nullable Break rule(int state) { throw new UnsupportedOperationException("no BulletBlocks here"); }
        @Override public float water(Object bullet, Object hit) { throw new UnsupportedOperationException("no BulletBlocks here"); }
        @Override public Shooter shooter(Object bullet) { throw new UnsupportedOperationException("no BulletBlocks here"); }
        @Override public int shotId(Object bullet) { throw new UnsupportedOperationException("no BulletBlocks here"); }
        @Override public Object location(Object hit) { throw new UnsupportedOperationException("no BulletBlocks here"); }
    };
}
