package acup.gunfeel.gl54;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

// The window of a GL-54 magazine (a right click with it in hand): a row of 8 cells, the leftmost the magazine's mouth, the
// grenades pressed to the right with no gaps (MagWindow). One magazine is taken off the stack in hand when the window opens
// and goes back, with the grenades now in it, when it closes (merging into an identical stack, or the inventory, or the floor).
// A shift-click moves exactly one grenade each way and is the ordinary quick-move click, so mods that repeat it work on top.
public final class MagMenu extends AbstractContainerMenu {
    public static final int LABEL_INVENTORY_Y = 41;
    private final SimpleContainer cells = new SimpleContainer(MagWindow.CELLS);
    private final Inventory inventory;
    private final boolean server;
    private boolean fixing;

    // the client's: the server sends what the cells hold
    public MagMenu(int id, Inventory inventory) {
        this(id, inventory, List.of(), false);
    }

    public MagMenu(int id, Inventory inventory, List<String> remaining) {
        this(id, inventory, remaining, true);
    }

    private MagMenu(int id, Inventory inventory, List<String> remaining, boolean server) {
        super(Gl54Items.MAG_MENU.get(), id);
        this.inventory = inventory;
        this.server = server;
        List<String> cellKeys = MagWindow.cells(remaining);
        for (int i = 0; i < MagWindow.CELLS; i++) {
            if (!cellKeys.get(i).isEmpty()) cells.setItem(i, grenade(cellKeys.get(i)));
            addSlot(new Cell(cells, i, 17 + 18 * i, 18));
        }
        addStandardInventorySlots(inventory, 8, 54);
    }

    static ItemStack grenade(String key) {
        var h = Gl54Items.GRENADES.get(key);
        return h == null ? ItemStack.EMPTY : new ItemStack(h.get());
    }

    static String key(ItemStack s) {
        return s.getItem() instanceof GrenadeItem g ? g.kind : "";
    }

    // the grenades now in the cells, as the magazine's remaining list
    public List<String> remaining() {
        List<String> cellKeys = new ArrayList<>();
        for (int i = 0; i < MagWindow.CELLS; i++) cellKeys.add(key(cells.getItem(i)));
        return MagWindow.compact(cellKeys);
    }

    private void setCells(List<String> remaining) {
        fixing = true;
        List<String> cellKeys = MagWindow.cells(remaining);
        for (int i = 0; i < MagWindow.CELLS; i++) cells.setItem(i, cellKeys.get(i).isEmpty() ? ItemStack.EMPTY : grenade(cellKeys.get(i)));
        fixing = false;
        if (server) broadcastChanges();
    }

    // a grenade put in any empty cell slides to the stack (and so is the first to fire)
    @Override
    public void slotsChanged(Container container) {
        super.slotsChanged(container);
        if (container == cells && !fixing) setCells(remaining());
    }

    // a shift-click: one grenade, never the stack. Always returns empty, so the game does not repeat it for the rest.
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (index < MagWindow.CELLS) {
            List<String> now = remaining();
            if (index != MagWindow.takeCell(now) || !slot.hasItem()) return ItemStack.EMPTY;
            ItemStack one = slot.getItem().copyWithCount(1);
            moveItemStackTo(one, MagWindow.CELLS, slots.size(), true);
            if (one.isEmpty()) setCells(MagWindow.take(now));
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        String k = key(stack);
        List<String> now = remaining();
        if (!MagWindow.canInsert(now, k)) return ItemStack.EMPTY;
        stack.shrink(1);
        slot.setChanged();
        setCells(MagWindow.insert(now, k));
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    // closing: the magazine with its grenades back to the player (the one taken off the stack when the window opened)
    @Override
    public void removed(Player player) {
        super.removed(player);
        if (!server) return;
        ItemStack result = Gl54Items.magazine(new MagContents(remaining()));
        MagItem.give(player, result);
    }

    // a cell takes a grenade only while it is empty (the stack then slides), and gives only the leftmost one
    private final class Cell extends Slot {
        Cell(Container c, int i, int x, int y) {
            super(c, i, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack s) {
            return !hasItem() && s.getItem() instanceof GrenadeItem;
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public boolean mayPickup(Player player) {
            return getContainerSlot() == MagWindow.takeCell(remaining());
        }
    }
}
