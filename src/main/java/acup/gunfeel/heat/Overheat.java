package acup.gunfeel.heat;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;

import acup.gunfeel.compat.Feature;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/** A barrel's heat on the gun (OverheatBase: its numbers): which gun a stack is, the heat kept on it as NTM keeps its wear. */
public final class Overheat extends OverheatBase {
    private Overheat() {
    }

    // the item each gun is, looked up once (the registry is fixed by then), by its ordinal
    private static final net.minecraft.world.item.Item[] ITEMS = new net.minecraft.world.item.Item[Gun.values().length];

    public static Gun gun(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof ItemGunBaseNT)) return null;
        for (Gun g : Gun.values()) {
            if (ITEMS[g.ordinal()] == null) ITEMS[g.ordinal()] = BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(g.id));
            if (ITEMS[g.ordinal()] == stack.getItem()) return g;
        }
        return null;
    }

    public static boolean on() {
        return Feature.OVERHEAT.on();
    }

    // the gun's values, as NTM keeps its wear

    /** the heat now (the game's tick now: the server's, or the client's copy of it) */
    public static float heat(ItemStack stack, long now) {
        if (!on() || gun(stack) == null) return 0F;
        float h = ItemGunBaseNT.getValueFloat(stack, KEY_HEAT);
        if (h <= 0F) return 0F;
        return cooled(h, now - tick(stack, now), TAU);
    }

    /** the tick the heat was last set (shot or cooled faster): a gun's last shot near enough */
    public static long tick(ItemStack stack, long now) {
        // kept as the low 31 bits of the game's tick, put back as the one nearest now (a year and more of game ticks each
        // way); a client a tick or two behind the server sees its last shot a little ahead, as now
        return unwrap(ItemGunBaseNT.getValueInt(stack, KEY_TICK), now);
    }

    public static void set(ItemStack stack, float h, long now) {
        ItemGunBaseNT.setValueFloat(stack, KEY_HEAT, h < 1e-3F ? 0F : h);
        ItemGunBaseNT.setValueInt(stack, KEY_TICK, (int) (now & 0x7FFFFFFFL));
    }
}
