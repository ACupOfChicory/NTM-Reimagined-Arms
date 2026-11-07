package acup.gunfeel.gl54;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

// The 20 mm magazine of the GL-54 (the ammunition itself: a magazine is a cartridge here). Its grenades are the data
// component MagContents; an empty one (gl54_mag_empty, stacks to 16) has none and is filled in NTM's ammo press. Name, bar
// and tooltip say the kind, what is left and for a mixed one the order.
public final class MagItem extends Item {
    private final boolean empty;

    public MagItem(Properties props, boolean empty) {
        super(props);
        this.empty = empty;
    }

    public static MagContents contents(ItemStack s) {
        MagContents c = s.get(Gl54Items.MAG_CONTENTS.get());
        return c == null ? MagContents.EMPTY : c;
    }

    // "Magazine 20 mm (HE)"; the kind's own name
    public static Component kindName(String kind) {
        return Component.translatable(GrenadeKind.MIXED.equals(kind) ? "item.ntmgunfeel.gl54_kind.mixed"
                : GrenadeKind.EMPTY.equals(kind) ? "item.ntmgunfeel.gl54_kind.empty"
                        : GrenadeKind.of(kind) != null ? GrenadeKind.of(kind).nameKey() : "item.ntmgunfeel.gl54_kind.unknown");
    }

    // a right click: the window of the magazine (one off the stack in hand); sneaking, its grenades go to the inventory
    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
        MagContents c = contents(held);
        held.shrink(1);
        if (player.isShiftKeyDown()) {
            // one magazine unloaded: its grenades to the inventory (what does not fit falls at the feet), the empty one back
            for (String k : c.rounds()) {
                var g = Gl54Items.GRENADES.get(k);
                if (g != null) give(player, new ItemStack(g.get()));
            }
            give(player, new ItemStack(Gl54Items.MAG_EMPTY.get()));
            return InteractionResult.SUCCESS;
        }
        List<String> remaining = new ArrayList<>(c.rounds());
        sp.openMenu(new SimpleMenuProvider((id, inv, p) -> new MagMenu(id, inv, remaining), Component.translatable("item.ntmgunfeel.gl54_mag.window")));
        return InteractionResult.SUCCESS;
    }

    // into the inventory (merging into an identical stack), what does not fit at the player's feet
    public static void give(Player player, ItemStack stack) {
        if (stack.isEmpty()) return;
        player.getInventory().add(stack);
        if (!stack.isEmpty()) player.drop(stack, false);
    }

    @Override
    public Component getName(ItemStack stack) {
        return Component.translatable("item.ntmgunfeel.gl54_mag", kindName(empty ? GrenadeKind.EMPTY : contents(stack).kind()));
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        int n = contents(stack).size();
        return !empty && n > 0 && n < MagContents.CAPACITY;
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        return Math.round(13F * contents(stack).size() / MagContents.CAPACITY);
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return Mth.hsvToRgb(Math.max(0F, (float) contents(stack).size() / MagContents.CAPACITY) / 3F, 1F, 1F);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void appendHoverText(ItemStack stack, TooltipContext ctx, TooltipDisplay display, Consumer<Component> out, TooltipFlag flag) {
        if (empty) {
            out.accept(Component.translatable("item.ntmgunfeel.gl54_mag.empty").withColor(0xAAAAAA));
            return;
        }
        MagContents c = contents(stack);
        String kind = c.kind();
        out.accept(Component.translatable("item.ntmgunfeel.gl54_mag.left", c.size(), MagContents.CAPACITY).withColor(0xAAAAAA));
        if (GrenadeKind.MIXED.equals(kind)) {
            out.accept(Component.translatable("item.ntmgunfeel.gl54_mag.order").withColor(0xAAAAAA));
            MutableComponent line = Component.empty();
            int i = 0;
            while (i < c.rounds().size()) {
                String key = c.rounds().get(i);
                int run = 1;
                while (i + run < c.rounds().size() && c.rounds().get(i + run).equals(key)) run++;
                if (i > 0) line.append(" ");
                line.append(Component.literal(GrenadeKind.codeOf(key) + (run > 1 ? "×" + run : "")).withColor(GrenadeKind.argbOf(key) & 0xFFFFFF));
                i += run;
            }
            out.accept(line);
        } else if (GrenadeKind.of(kind) != null) {
            out.accept(kindName(kind).copy().withColor(GrenadeKind.of(kind).rgb));
            if (c.size() < MagContents.CAPACITY) out.accept(Component.translatable("item.ntmgunfeel.gl54_mag.pours").withColor(0x777777));
        }
    }
}
