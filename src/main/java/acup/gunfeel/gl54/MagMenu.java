package acup.gunfeel.gl54;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

// The window of a GL-54 magazine (a right click with it in hand): a row of 8 cells, the leftmost the magazine's mouth, the
// grenades pressed to the right with no gaps (MagWindow). The magazine stays in its own inventory slot (MagSession): that
// slot is locked while the window is open (no move, swap, drop, number key or offhand swap) and every grenade that moves is
// written to the stack at once, so closing has nothing to give back. A stack of several magazines gives one to a free slot
// and edits that one. A shift-click moves exactly one grenade each way and is the ordinary quick-move click, so mods that
// repeat it work on top. If the stack in the slot is changed from outside the window closes (stillValid) and writes nothing.
public final class MagMenu extends AbstractContainerMenu {
    public static final int LABEL_INVENTORY_Y = 41;
    private final SimpleContainer cells = new SimpleContainer(MagWindow.CELLS);
    private final MagSession<ItemStack> session;
    // the locked inventory slot + 1 (0 none), told to the client so it does not offer a move the server refuses
    private final DataSlot locked = DataSlot.standalone();
    private boolean fixing;

    // the client's: the server sends what the cells hold
    public MagMenu(int id, Inventory inventory) {
        this(id, inventory, null);
    }

    public MagMenu(int id, Inventory inventory, MagSession<ItemStack> session) {
        super(Gl54Items.MAG_MENU.get(), id);
        this.session = session;
        List<String> cellKeys = MagWindow.cells(session == null ? List.of() : session.rounds());
        for (int i = 0; i < MagWindow.CELLS; i++) {
            if (!cellKeys.get(i).isEmpty()) cells.setItem(i, grenade(cellKeys.get(i)));
            addSlot(new Cell(cells, i, 17 + 18 * i, 18));
        }
        for (int y = 0; y < 3; y++) for (int x = 0; x < 9; x++) addSlot(new Held(inventory, x + (y + 1) * 9, 8 + x * 18, 54 + y * 18));
        for (int x = 0; x < 9; x++) addSlot(new Held(inventory, x, 8 + x * 18, 54 + 58));
        addDataSlot(locked);
        if (session != null) locked.set(session.edited() + 1);
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
        if (session != null) {
            session.write(remaining);
            broadcastChanges();
        }
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
        if (index >= MagWindow.CELLS && slot.getContainerSlot() == locked.get() - 1) return ItemStack.EMPTY;
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

    // the server closes the window when the magazine's slot no longer holds what the window wrote
    @Override
    public boolean stillValid(Player player) {
        return session == null || session.valid();
    }

    // no number key or offhand swap may reach the locked slot either (it is not the clicked slot then)
    @Override
    public void clicked(int slotIndex, int buttonNum, ContainerInput input, Player player) {
        if (input == ContainerInput.SWAP && locked.get() > 0 && buttonNum == locked.get() - 1) return;
        super.clicked(slotIndex, buttonNum, input, player);
    }

    // closing: the grenades are in the magazine already; it joins its stack again when they are identical
    @Override
    public void removed(Player player) {
        super.removed(player);
        if (session != null) session.close();
    }

    // an inventory slot, locked while it holds the magazine of the window
    private final class Held extends Slot {
        Held(Container c, int i, int x, int y) {
            super(c, i, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack s) {
            return getContainerSlot() != locked.get() - 1 && super.mayPlace(s);
        }

        @Override
        public boolean mayPickup(Player player) {
            return getContainerSlot() != locked.get() - 1 && super.mayPickup(player);
        }
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
