package acup.gunfeel;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.host.WorldView;

/**
 * Whether a bullet with this much damage goes through the block at (x, y, z) on a path of that length through it,
 * and with how much: the damage left, else -1 (it stops in it). A machine's window (glass) by the glass's material;
 * a machine (unless bulletPierceMachines); a break rule with pierce= by its own sum (with the block's destroy speed
 * there); else the block's material (Materials, as NTM's armour: its threshold and resistance over the path's length)
 * against the round's threshold negation and armour piercing. Less than 1 left stops it. What the version knows of
 * the block beyond the world (its rules' material and break rule, NTM's machines) it is asked in this order, each
 * only when needed ({@link Block}).
 */
public final class Pierce {
    /** the setting that lets bullets pierce machines */
    public static final String MACHINES_KEY = "bulletPierceMachines";

    private Pierce() {
    }

    /**
     * What the version knows of the block, each asked only when it is needed, in this order; {@code state} is the
     * world's number for it, read once by {@link #left}.
     */
    public interface Block {
        /** the material its rules give it (a machine's cell: its machine's) */
        Materials.Verdict material(int state);

        /** it or the block whose rules it takes is NTM's machine (a core or a cell of one) */
        boolean machine(int state);

        /** its break rule, if any */
        BulletRuleText.@Nullable Break breakRule(int state);
    }

    public static float left(WorldView w, int x, int y, int z, float damage, RoundSpec round, boolean glass, double path,
            Materials.Penetration penetration, Block block) {
        int state = w.state(x, y, z);
        if (w.air(state) || !(damage >= 1F)) return -1F;
        float negation = round.armorThresholdNegation;
        float piercing = round.armorPiercingPercent;
        // a machine's window is glass, not the machine's casing: bulletPierceMachines is not asked
        if (glass) {
            float left = penetration.left(MachineGlass.VERDICT, path, damage, negation, piercing);
            return left >= 1F ? left : -1F;
        }
        Materials.Verdict v = block.material(state);
        // Запрет относится к машине, даже если её часть определилась как none/stone.
        // Проверяем до правил пробития, чтобы они тоже не обходили общий выключатель.
        if ((block.machine(state) || v.material() == Materials.Material.MACHINE) && !ReaimTimes.flag(MACHINES_KEY, false)) return -1F;
        BulletRuleText.Break rule = block.breakRule(state);
        if (rule != null && rule.pierce() > 0F) return rule.pierced(damage, w.destroySpeed(x, y, z), path);
        float left = penetration.left(v, path, damage, negation, piercing);
        return left >= 1F ? left : -1F;
    }
}
