package acup.gunfeel;

import java.util.IdentityHashMap;
import java.util.Map;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.render.item.weapon.sedna.ItemRenderWeaponBase;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

// Client-side state and hooks (26.2: GunFeel.init).
public final class GunFeelClient {
    // renderer drawing the first-person gun right now (null outside of it)
    public static ItemRenderWeaponBase current;
    public static boolean currentAkimbo;
    // the frame's partial tick, from RenderTickEvent (26.2: DeltaTracker), for a trace's time
    public static volatile float partial;
    private static final Map<Item, Long> SEEN_SHOT = new IdentityHashMap<Item, Long>();

    private GunFeelClient() {
    }

    static void init() {
        Config.poll();
        FMLCommonHandler.instance().bus().register(new GunFeelClient());
    }

    // dev runs (runClient): a file "autostop" in the run folder closes the client a few seconds after the main menu
    // shows, with the mod list's entry in the log
    private static int autostop = -1;

    @SubscribeEvent
    public void clientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END || autostop == -2) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (autostop < 0) {
            if (!(mc.currentScreen instanceof net.minecraft.client.gui.GuiMainMenu)) return;
            if (!new java.io.File("autostop").exists()) {
                autostop = -2;
                return;
            }
            cpw.mods.fml.common.ModContainer mod = cpw.mods.fml.common.Loader.instance().getIndexedModList().get(GunFeelMod.MODID);
            GunFeel.LOGGER.info("autostop: main menu; mod list entry \"{}\" version {}; closing in 5 s", mod.getName(), mod.getVersion());
            MixinCheck.report();
            autostop = 100;
        } else if (--autostop == 0) {
            GunFeel.LOGGER.info("autostop: closing the client");
            mc.shutdown();
        }
    }

    // once a frame before the world is drawn (26.2: RenderFrameEvent.Pre)
    @SubscribeEvent
    public void renderTick(TickEvent.RenderTickEvent e) {
        if (e.phase != TickEvent.Phase.START) return;
        partial = e.renderTickTime;
        Config.poll();
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        ItemStack held = player == null ? null : player.getHeldItem();
        Config.select(held != null && held.getItem() instanceof ItemGunBaseNT ? gunId(held.getItem()) : null);
        if (Config.enabled) CameraRecoil.frame();
    }

    // The gun's id as 26.2 writes it ("hbm:gun_maresleg"): NTM 1.7.10 registers its items under their unlocalized
    // name, "hbm:item.gun_maresleg". The settings files and the 26.2 tables use the 26.2 form.
    public static String gunId(Item item) {
        Object name = Item.itemRegistry.getNameForObject(item);
        if (name == null) return null;
        String s = name.toString();
        int colon = s.indexOf(':');
        return s.startsWith("item.", colon + 1) ? s.substring(0, colon + 1) + s.substring(colon + 6) : s;
    }

    // NTM stamps gun.lastShot[i] when the shot animation arrives; a newer stamp is a new shot.
    public static void checkShot(ItemStack stack) {
        if (stack == null || !(stack.getItem() instanceof ItemGunBaseNT)) return;
        ItemGunBaseNT gun = (ItemGunBaseNT) stack.getItem();
        if (gun.lastShot == null) return;
        long newest = 0;
        for (long t : gun.lastShot) newest = Math.max(newest, t);
        Long seen = SEEN_SHOT.put(gun, newest);
        if (seen == null || newest <= seen) return;
        ModelRecoil.shot(ItemGunBaseNT.getIsAiming(stack));
    }
}
