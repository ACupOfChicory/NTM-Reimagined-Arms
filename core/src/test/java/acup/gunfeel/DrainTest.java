package acup.gunfeel;

import static acup.gunfeel.host.HurtNow.PIERCE_DT;
import static acup.gunfeel.host.HurtNow.PIERCE_DR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import acup.gunfeel.EuphImmunityBase.Kit;
import acup.gunfeel.EuphImmunityBase.PoweredOf;
import acup.gunfeel.host.ArmorSuits;
import acup.gunfeel.host.Body;
import acup.gunfeel.host.FakeHurts;
import acup.gunfeel.host.HurtNow;
import acup.gunfeel.host.Hurts;

/** The old drain after scheme, against core: raw bits and every version read in order. */
class DrainTest {
    private static final boolean[] BOOL = {false, true};
    private static final float[] EXTRA = {1F, 1F, 0.35F, 0F};

    private record Input(Kit kit, String cat, float scheme, float amount, boolean head, boolean electric,
            boolean charged, float[] extra, float dt, float dr, float health, float power, boolean bullet) {
    }

    private static final class Reads implements HurtNow, ArmorSuits {
        @Override public float corrosion(Body b) { throw new UnsupportedOperationException(); }
        @Override public boolean gas() { throw new UnsupportedOperationException(); }
        @Override public int reach(Object hurt) { throw new UnsupportedOperationException(); }
        @Override public float schemeDrain(Body b, Object hurt) { throw new UnsupportedOperationException(); }
        final Input in;
        final FakeHurts hurts = new FakeHurts();
        final List<String> calls = hurts.calls;
        final PoweredOf powered = new PoweredOf(this);
        final Body body;
        final Object hurt;

        Reads(Input in) {
            this.in = in;
            hurt = new FakeHurts.Hurt(in.electric ? 1 << Hurts.NTM_ELECTRIC : 0, 0, in.cat, null);
            body = (Body) Proxy.newProxyInstance(Body.class.getClassLoader(), new Class<?>[] {Body.class}, (p, m, a) -> {
                return switch (m.getName()) {
                    case "maxHealth" -> {
                        calls.add("maxHealth");
                        yield in.health;
                    }
                    case "worn" -> {
                        calls.add("worn " + a[0]);
                        yield 17;
                    }
                    default -> throw new UnsupportedOperationException(m.getName());
                };
            });
        }

        @Override
        public boolean onHead(Body target) {
            calls.add("onHead");
            return in.head;
        }

        @Override
        public float[] floorExtra(Object hurt, boolean blast) {
            calls.add("floorExtra " + blast);
            return in.extra;
        }

        @Override
        public float ntmPierce(Body target, int which) {
            calls.add("pierce " + which);
            return switch (which) {
                case PIERCE_DT -> in.dt;
                case PIERCE_DR -> in.dr;
                default -> throw new IllegalArgumentException();
            };
        }

        @Override
        public boolean fullSet(Body target, int suit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean powered(int item) {
            calls.add("powered " + item);
            return in.charged;
        }
    }

    // The old drain after scheme, with the version's reads made the fakes'.
    private static float old(Reads r, Kit kit, Body player, Object source, float amount, String cat, float scheme) {
        if (!Float.isNaN(scheme)) {
            float click = ReaimTimes.number("damageCapClick", 0.6F);
            return Float.isFinite(click) && click > 0F && !r.onHead(player) ? Math.min(scheme, click * player.maxHealth()) : scheme;
        }
        float[] st = EuphImmunityBase.drainSet(kit);
        // NTM's accepted damage takes an electric hit on a powered chestplate five times (EntityDamageUtil)
        if (kit != Kit.EUPHEMIUM && r.hurts.is(source, Hurts.NTM_ELECTRIC) && r.powered.of(player.worn(Body.CHEST)))
            amount *= BalanceArmorBase.electricPowered(5F);
        float s = "EN".equals(cat) ? st[0] * Math.min(1F, ReaimTimes.number("balanceEnShare", 0.75F)) : st[0];
        boolean squeeze = !"EXPL".equals(cat) || amount <= ReaimTimes.number("balanceExplRawLimit", 60F) || BalanceArmorBase.bulletBlastNow();
        // a weapon's blast as BalanceArmor takes it (balanceBlastPower, set around the blast's hurt)
        float power = "EXPL".equals(cat) && squeeze ? BalanceArmorBase.BLAST_POWER.get() : 1F;
        // by the kit's tier (8, 9): the power itself, a rocket's direct hit too
        int tier = kit == Kit.EUPHEMIUM ? 9 : 8;
        if (power > 1F) power = BalanceArmorBase.pieceBlastPower(tier, amount, power);
        // the gun table's floor of the shot's pair, as BalanceArmor takes it
        boolean expl = "EXPL".equals(cat);
        float floor = !expl || BalanceArmorBase.bulletBlastNow() ? BalanceGunsBase.floorOf(r.floorExtra(source, expl), tier) : 1F;
        float drain = Math.min(amount, BalanceArmorRule.full(new BalanceArmorRule.Hit(amount * power, r.ntmPierce(player, 0), r.ntmPierce(player, 1), squeeze), s, st[1],
                BalanceArmorRule.Tune.DEFAULT, floor));
        float click = ReaimTimes.number("damageCapClick", 0.6F);
        if (Float.isFinite(click) && click > 0F) drain = Math.min(drain, click * player.maxHealth());
        return drain;
    }

    private static Reads compare(Input in) {
        Reads before = new Reads(in), after = new Reads(in);
        float want = old(before, in.kit, before.body, before.hurt, in.amount, in.cat, in.scheme);
        float got = EuphImmunityBase.drainOf(after, after.hurts, in.kit, after.body, after.hurt, in.amount, in.cat, in.scheme, after.powered);
        assertEquals(Float.floatToRawIntBits(want), Float.floatToRawIntBits(got), in::toString);
        assertEquals(before.calls, after.calls, in::toString);
        return after;
    }

    @Test
    void drainIsTheOldInBitsAndReads() {
        float wasPower = BalanceArmorBase.BLAST_POWER.get();
        boolean wasBullet = BalanceArmorBase.BULLET_BLAST.get();
        try {
            assertEquals(0.35F, BalanceGunsBase.floorOf(EXTRA, 8));
            for (Kit kit : Kit.values())
                for (String cat : new String[] {"PHYS", "EN", "EXPL", "FIRE"})
                    for (float scheme : new float[] {Float.NaN, 0F, 3.5F, 1e9F})
                        for (float amount : new float[] {0.5F, 12F, 70F, 1e6F})
                            for (boolean head : BOOL)
                                for (boolean electric : BOOL)
                                    for (boolean charged : BOOL)
                                        for (float[] extra : new float[][] {null, EXTRA})
                                            for (float[] pierce : new float[][] {{0F, 0F}, {5F, 0.3F}})
                                                for (float health : new float[] {20F, 1F})
                                                    for (float power : new float[] {1F, 2.5F})
                                                        for (boolean bullet : BOOL) {
                                                            BalanceArmorBase.BLAST_POWER.set(power);
                                                            BalanceArmorBase.BULLET_BLAST.set(bullet);
                                                            Reads r = compare(new Input(kit, cat, scheme, amount, head, electric, charged, extra,
                                                                    pierce[0], pierce[1], health, power, bullet));
                                                            if (Float.isNaN(scheme)) assertFalse(r.calls.contains("onHead"));
                                                        }
        } finally {
            BalanceArmorBase.BLAST_POWER.set(wasPower);
            BalanceArmorBase.BULLET_BLAST.set(wasBullet);
        }
    }

    @Test
    void clickShortCircuitsHeadAndHealth() {
        try {
            for (String click : new String[] {"NaN", "Infinity", "0", "-1", "0.6"}) {
                ServerSettings.useRemote(new ServerSettings.Snapshot("", Map.of("damageCapClick", click), Map.of(), ""));
                for (float scheme : new float[] {Float.NaN, 3.5F}) {
                    Reads r = compare(new Input(Kit.DNS, "PHYS", scheme, 12F, true, false, true, EXTRA, 5F, 0.3F, 20F, 1F, false));
                    if (Float.isNaN(scheme) || !click.equals("0.6")) assertFalse(r.calls.contains("onHead"));
                    if (!Float.isNaN(scheme)) assertFalse(r.calls.contains("maxHealth"));
                }
            }
        } finally {
            ServerSettings.useRemote(null);
        }
    }
}
