package acup.gunfeel.gl54;

import java.util.ArrayList;
import java.util.List;

import com.hbm.blockentity.machine.BlockEntityMachineAmmoPress;
import com.hbm.inventory.container.MenuMachineAmmoPress;
import com.hbm.inventory.recipes.AmmoPressRecipes;

import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;

// The ammo press's remembered layouts (PressLayouts) between the server and the client, and the rules that need NTM's recipe
// list: which layouts are presets, and what the press's selection index means.
public final class PressSync {
    private PressSync() {
    }

    // the layouts of NTM's own recipes with a GL-54 magazine as output (our presets): a layout equal to one is never stored
    public static List<List<String>> presets() {
        List<List<String>> out = new ArrayList<>();
        for (var r : AmmoPressRecipes.INSTANCE.recipes()) {
            var s = r.output();
            if (s.getItem() instanceof MagItem) out.add(MagItem.contents(s).rounds());
        }
        return out;
    }

    public static int recipes() {
        return AmmoPressRecipes.INSTANCE.recipes().size();
    }

    // the server: to one player (a window opening) ...
    public static void send(BlockEntityMachineAmmoPress press, ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, new PressLayoutsPayload(press.getBlockPos(), ((PressMemory) press).gunfeel$layouts(), press.selectedRecipe));
    }

    // ... and to everyone with this press's window open (a layout stored, moved, deleted)
    public static void broadcast(BlockEntityMachineAmmoPress press) {
        if (press.getLevel() == null || press.getLevel().isClientSide()) return;
        for (Player p : press.getLevel().players())
            if (p instanceof ServerPlayer sp && sp.containerMenu instanceof MenuMachineAmmoPress m && m.blockEntity() == press) send(press, sp);
    }

    // the client: the press's list and selection as the server has them
    public static void receive(PressLayoutsPayload p) {
        var level = Minecraft.getInstance().level;
        if (level == null || !(level.getBlockEntity(p.pos()) instanceof BlockEntityMachineAmmoPress press)) return;
        press.selectedRecipe = p.selected();
        ((PressMemory) press).gunfeel$layouts(List.copyOf(p.layouts()));
    }
}
