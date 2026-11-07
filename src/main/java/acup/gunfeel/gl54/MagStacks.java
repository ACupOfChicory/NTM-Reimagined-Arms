package acup.gunfeel.gl54;

import java.util.List;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

// MagSession on the game's stacks and a player's inventory
public final class MagStacks {
    private MagStacks() {
    }

    public static final MagSession.Ops<ItemStack> OPS = new MagSession.Ops<>() {
        @Override
        public boolean isEmpty(ItemStack s) {
            return s.isEmpty();
        }

        @Override
        public int count(ItemStack s) {
            return s.getCount();
        }

        @Override
        public int max(ItemStack s) {
            return s.getMaxStackSize();
        }

        @Override
        public ItemStack withCount(ItemStack s, int count) {
            return s.copyWithCount(count);
        }

        @Override
        public boolean same(ItemStack a, ItemStack b) {
            return ItemStack.isSameItemSameComponents(a, b);
        }

        @Override
        public boolean equal(ItemStack a, ItemStack b) {
            return ItemStack.matches(a, b);
        }

        @Override
        public ItemStack withRounds(ItemStack s, List<String> rounds) {
            if (rounds.isEmpty()) {
                ItemStack out = s.transmuteCopy(Gl54Items.MAG_EMPTY.get(), 1);
                out.remove(Gl54Items.MAG_CONTENTS.get());
                return out;
            }
            ItemStack out = s.transmuteCopy(Gl54Items.MAG.get(), 1);
            out.set(Gl54Items.MAG_CONTENTS.get(), new MagContents(rounds));
            return out;
        }

        @Override
        public List<String> rounds(ItemStack s) {
            return MagItem.contents(s).rounds();
        }

        @Override
        public ItemStack empty() {
            return ItemStack.EMPTY;
        }
    };

    public static MagSession.Slots<ItemStack> slots(Inventory inv) {
        return new MagSession.Slots<>() {
            @Override
            public ItemStack get(int slot) {
                return inv.getItem(slot).copy();
            }

            @Override
            public void set(int slot, ItemStack stack) {
                inv.setItem(slot, stack);
            }
        };
    }
}
