package acup.gunfeel;

import java.util.List;

import com.hbm.inventory.material.MaterialShapes;
import com.hbm.inventory.material.Mats;
import com.hbm.inventory.material.NTMMaterial;
import com.hbm.items.CreativeContents;
import com.hbm.items.special.Autogen;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * Five gun mechanisms NTM has the materials for but no item: steel, high-speed steel, desh, ferrouranium and technetium
 * steel. NTM makes its gun parts from the shapes a material lists (Autogen); MixinMechanismAutogen adds the mechanism
 * to these five just before NTM registers its parts, so NTM registers hbm:part_mechanism_&lt;material&gt; itself, as it does
 * gunmetal's. NTM finds every mechanism by its tag hbm:gun_mechanisms/&lt;material&gt; (data/hbm/tags, ours): the casting
 * mold, the crucible melting it back and JEI's casting page. The textures, models and tags are made by
 * tools/gun_mechanisms.py, the recipes that take them are GunMechanismRecipes.
 */
public final class GunMechanisms {
    private GunMechanisms() {
    }

    // in the order of their tiers: steel and high-speed steel cast in the crucible (tier 2), desh and ferrouranium
    // (tier 4), technetium steel (tier 5)
    static List<NTMMaterial> materials() {
        return List.of(Mats.MAT_STEEL, Mats.MAT_DURA, Mats.MAT_DESH, Mats.MAT_FERRO, Mats.MAT_TCALLOY);
    }

    private static boolean added;

    /** before NTM's Autogen.register("hbm"): the five materials get the mechanism shape, and NTM's parts tab the items */
    public static void addShapes() {
        if (added || !acup.gunfeel.compat.Feature.GUN_MECHANISMS.on()) return;
        added = true;
        for (NTMMaterial mat : materials()) mat.autogen.add(MaterialShapes.MECHANISM);
        // after saturnite's, the last of NTM's three
        Identifier anchor = Identifier.fromNamespaceAndPath("hbm", "part_mechanism_saturnite");
        for (NTMMaterial mat : materials()) CreativeContents.insertAfter(CreativeContents.Tab.PARTS, anchor, () -> new ItemStack(Autogen.require(MaterialShapes.MECHANISM, mat)));
    }
}
