package acup.gunfeel;

// port: copy of 26.2's InstantSwitch.java (0762086) for NTM 1.7.10, with 26.2's MixinItemInHandRenderer folded in
// (take()). The wait for the server's draw moved into SwitchPrediction. NTM 1.7.10: the draw comes from
// HbmAnimationPacket.Handler.handleSedna, which also makes ItemRenderer draw that stack (itemToRender) at once; NTM draws
// its guns from RenderHandEvent with ItemRenderer.itemToRender, which vanilla changes only once the old item is
// lowered (updateEquippedItem). Akimbo guns are one item with two configs: there is no ghost stack to leave out.
// Called once a frame from RenderTickEvent START (HudEvents): 1.7.10 changes the slot in the tick, before the frame.

import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.packet.toclient.HbmAnimationPacket;
import com.hbm.render.anim.AnimationEnums.GunAnimation;

import acup.gunfeel.mixins.early.ItemRendererAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

// instantSwitch: NTM starts a gun's draw animation from the server (onUpdate sees the new slot, then a packet),
// so until the next server tick and the packet the new gun is drawn at rest, fully up. The client starts the draw
// itself the frame the slot changes, and the server's draw for that slot and gun is then not started again.
public final class InstantSwitch {
    private InstantSwitch() {
    }

    // the thread in HbmAnimationPacket.Handler.onMessage: the animation comes from the server (port: 26.2 a boolean;
    // here the thread, so the client's own handleSedna in frame() is never taken for the server's)
    public static volatile Thread serverPacket;
    private static int lastSlot = -1;
    private static Item lastItem;
    static final SwitchPrediction PREDICTED = new SwitchPrediction();

    public static boolean isGun(ItemStack stack) {
        return stack != null && stack.getItem() instanceof ItemGunBaseNT;
    }

    // each frame, before anything is drawn
    public static void frame(EntityPlayer player) {
        if (player == null) {
            lastSlot = -1;
            lastItem = null;
            return;
        }
        int slot = player.inventory.currentItem;
        ItemStack stack = player.getHeldItem();
        Item item = stack == null ? null : stack.getItem();
        if (slot != lastSlot || item != lastItem) {
            lastSlot = slot;
            lastItem = item;
            PREDICTED.clear();
            if (Config.instantSwitch && isGun(stack) && slot >= 0 && slot <= 8) predict(player, stack, slot);
        }
        if (Config.instantSwitch) take(stack, slot);
    }

    private static void predict(EntityPlayer player, ItemStack stack, int slot) {
        int n = ((ItemGunBaseNT) stack.getItem()).getConfigCount();
        try {
            for (int i = 0; i < n; i++) HbmAnimationPacket.Handler.handleSedna(player, stack, slot, GunAnimation.EQUIP, 0, i);
        } catch (RuntimeException e) {
            GunFeel.LOGGER.debug("Could not start the draw animation", e);
            return;
        }
        PREDICTED.start(slot, stack.getItem(), n, GunClock.millis());
    }

    // port: 26.2's MixinItemInHandRenderer. Vanilla keeps drawing the old item until the next ticks have lowered it;
    // NTM's guns ignore the height, so a gun stayed fully up on a slot that no longer has it. To or from a gun the new
    // item is taken at once: a gun as it is (its draw animation lifts it), anything else from below as vanilla raises it.
    private static void take(ItemStack next, int slot) {
        ItemRendererAccess r = (ItemRendererAccess) Minecraft.getMinecraft().entityRenderer.itemRenderer;
        ItemStack shown = r.gunfeel$getItemToRender();
        if (next == shown) return;
        boolean nextGun = isGun(next);
        if (!nextGun && !isGun(shown)) return;
        r.gunfeel$setItemToRender(next);
        r.gunfeel$setEquippedItemSlot(slot);
        if (!nextGun) {
            r.gunfeel$setEquippedProgress(0F);
            r.gunfeel$setPrevEquippedProgress(0F);
        }
    }

    // the server's animation for the held gun: false = skip it (the client already started this draw)
    public static boolean accept(int slot, Item item, GunAnimation type, int index) {
        return PREDICTED.accept(slot, item, type == GunAnimation.EQUIP, index, GunClock.millis());
    }
}
