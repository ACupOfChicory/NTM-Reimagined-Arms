package acup.gunfeel;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.host.Body;
import acup.gunfeel.host.BulletBlocks;
import acup.gunfeel.host.ShotEffects;
import acup.gunfeel.host.WorldView;

/** Hit effects; the version retains its hit geometry and entity diagnostic. */
public class BulletEffectsBase {
    private final ShotEffects effects;
    private final BulletBlocks blocks;

    public BulletEffectsBase() {
        this(CoreServices.load(ShotEffects.class, ShotEffects.MISSING), CoreServices.load(BulletBlocks.class, BulletBlocks.MISSING));
    }

    BulletEffectsBase(ShotEffects effects, BulletBlocks blocks) {
        this.effects = effects;
        this.blocks = blocks;
    }

    BulletRuleText.Effect entityEffect(String kind, Body target) { return BulletRulesBase.entity(kind, target); }
    BulletRuleText.Effect hitEffect(int state) { return BulletRulesBase.hit(state); }

    public void entityHit(Object level, WorldView world, boolean server, @Nullable Body owner, Body target,
            boolean at, double x, double y, double z, boolean wasAlive) {
        if (!server || !wasAlive) return;
        boolean dead = target.living() && !target.alive();
        play(level, world, owner, target, at, x, y, z, entityEffect("entity", target));
        if (dead) play(level, world, owner, target, at, x, y, z, entityEffect("kill", target));
    }

    public void play(Object level, WorldView world, @Nullable Body owner, Body target,
            boolean at, double x, double y, double z, BulletRuleText.Effect fx) {
        if (fx.empty()) return;
        if (!at) {
            x = target.x();
            y = target.y() + target.bbHeight() / 2;
            z = target.z();
        }
        for (BulletRuleText.Particle part : fx.particles()) {
            Object particle = effects.particle(part.id(), -1);
            if (particle != null) effects.particles(level, particle, x, y, z, part.count());
        }
        BulletRuleText.Sound s = fx.sound();
        if (s == null) return;
        Object sound = effects.sound(s.id());
        if (sound == null) return;
        float pitch = s.pitch() * (0.95F + world.random().nextFloat() * 0.1F);
        if (fx.toAll()) {
            effects.playAll(level, sound, x, y, z, s.volume(), pitch);
        } else if (owner != null && owner.player()) {
            effects.playTo(owner, sound, x, y, z, s.volume(), pitch, world.random().nextLong());
        }
    }

    public boolean ricochetSound(Object level, WorldView world, boolean block, Object pos, int bx, int by, int bz,
            double x, double y, double z) {
        if (!block || world.client()) return false;
        int state = world.state(bx, by, bz);
        if (world.air(state)) return false;
        int rule = blocks.ruleState(level, pos, state);
        BulletRuleText.Sound s = hitEffect(rule).sound();
        boolean steel = Ricochet.steelSound(s == null ? null : s.id());
        if (TestLog.server) TestLog.s(TestLog.shot(), "ricochet", "block", blocks.stateObject(rule), "pos", pos,
                "material", blocks.material(level, pos), "result", "bounced", "sound", steel ? "ours" : "ntm");
        if (!steel) return false;
        effects.playAll(level, effects.ricochetSteel(), x, y, z, 1.0F, 0.9F + world.random().nextFloat() * 0.2F);
        return true;
    }
}
