package acup.gunfeel;

import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.client.event.RenderSpecificHandEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

// Dev (-Pscenario=hands, ShootScenario's flat world): hideOffhand and smokeWidth, by numbers. The player holds
// gun_greasegun with a gold block in the off hand, looking at the sky.
// - hideOffhand on, then off: how many frames the off-hand item was drawn (the hand's event not cancelled at the
//   end) and how many hidden; screenshots hands-hide.png and hands-show.png to set against each other.
// - smokeWidth 1, then 2: a shot each, and the width NTM's renderSmokeNodes got (as NTM gives it, and after the mod);
//   screenshots hands-smoke-1.png and hands-smoke-2.png.
// Lines "Scenario hands: ..." in the log, then the game quits.
public final class HandsScenario {
    private HandsScenario() {
    }

    private static final int GIVE = 20, HIDE = 60, HIDE_SHOT = 80, SHOW = 81, SHOW_SHOT = 100, SMOKE1 = 110, SMOKE2 = 150, DONE = 200;
    // the screenshot a few ticks after the shot, with the smoke trailing from the muzzle
    private static final int SMOKE_SHOT = 6;

    private static int drawn, hidden, smokeCalls;
    private static double smokeIn = Double.NaN, smokeOut = Double.NaN;
    private static boolean registered;

    public static boolean on() {
        return "hands".equals(ShootScenario.NAME);
    }

    // after every other listener: whether the off-hand item is drawn this frame
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void offhand(RenderSpecificHandEvent e) {
        if (e.getHand() != net.minecraft.util.EnumHand.OFF_HAND || e.getItemStack().isEmpty()) return;
        if (e.isCanceled()) hidden++;
        else drawn++;
    }

    public static void smoke(double in, double out) {
        smokeCalls++;
        smokeIn = in;
        smokeOut = out;
    }

    private static void reset() {
        drawn = hidden = smokeCalls = 0;
        smokeIn = smokeOut = Double.NaN;
    }

    private static void report(String what) {
        GunFeel.LOGGER.info(String.format(Locale.ROOT, "Scenario hands: %s hideOffhand=%s smokeWidth=%.2f offhand drawn=%d hidden=%d smoke calls=%d in=%.3f out=%.3f",
                what, Config.hideOffhand, Config.smokeWidth, drawn, hidden, smokeCalls, smokeIn, smokeOut));
    }

    private static void screenshot(Minecraft mc, String name) {
        ScreenShotHelper.saveScreenshot(mc.gameDir, "hands-" + name + ".png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
    }

    static void step(Minecraft mc, int t) {
        if (!registered) {
            registered = true;
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new HandsScenario());
        }
        if (t == 0) {
            mc.player.rotationPitch = mc.player.prevRotationPitch = -30F;
            mc.player.rotationYaw = mc.player.prevRotationYaw = 0F;
        } else if (t == GIVE) {
            ShootScenario.onServer(() -> {
                ShootScenario.give(0, "gun_greasegun");
                EntityPlayerMP p = (EntityPlayerMP) net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance().getPlayerList().getPlayers().get(0);
                p.inventory.offHandInventory.set(0, new ItemStack(Blocks.GOLD_BLOCK));
                p.inventoryContainer.detectAndSendChanges();
            });
            mc.player.inventory.currentItem = 0;
        } else if (t == HIDE) {
            Config.hideOffhand = true;
            reset();
        } else if (t == HIDE_SHOT) {
            report("hide");
            screenshot(mc, "hide");
        } else if (t == SHOW) {
            Config.hideOffhand = false;
            reset();
        } else if (t == SHOW_SHOT) {
            report("show");
            screenshot(mc, "show");
            Config.hideOffhand = true;
        } else if (t == SMOKE1 || t == SMOKE2) {
            Config.smokeWidth = t == SMOKE1 ? 1F : 2F;
            reset();
            ShootScenario.trigger(true, mc);
        } else if (t == SMOKE1 + 2 || t == SMOKE2 + 2) {
            ShootScenario.trigger(false, mc);
        } else if (t == SMOKE1 + SMOKE_SHOT || t == SMOKE2 + SMOKE_SHOT) {
            String w = t == SMOKE1 + SMOKE_SHOT ? "1" : "2";
            report("smoke-" + w);
            screenshot(mc, "smoke-" + w);
        } else if (t == DONE) {
            Config.smokeWidth = 1F;
            GunFeel.LOGGER.info("Scenario hands: done");
            mc.shutdown();
        }
    }
}
