package acup.gunfeel;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.Receiver;
import com.hbm.items.weapon.sedna.mags.IMagazine;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;

// Dev (-Pscenario=aimlag, ShootScenario's flat world): how far a bullet's direction is behind the turn the player saw
// (clickAim, ClickAim; 26.2's notes/aim-lag stand). NTM builds a bullet from the server's turn of the player.
// - SPAS-12: four flicks of 40 degrees, each turned in a frame (as the mouse does) with the trigger pressed at the next
//   client tick, before that tick sends the turn. Off = the camera at the press minus the server's turn at the bullet.
// - M2: a 3 s burst while the camera turns 90 degrees a second, frame by frame. Behind = the camera when the bullet is
//   born minus the server's turn, in degrees and in ms (degrees / 90 per second).
// run/client/aimlag.txt gets each bullet and the summary; the game quits. No /tick here (1.7.10): 20 TPS only.
// port: the 1.12.2 port's AimLagScenario, on 1.7.10's names.
public final class AimLagScenario {
    private AimLagScenario() {
    }

    private static final int GIVE = 40, FLICK = 120, FLICK_GAP = 40, FLICKS = 4, SWITCH = FLICK + FLICK_GAP * FLICKS + 20,
            BURST = SWITCH + 60, BURST_TICKS = 60, DONE = BURST + BURST_TICKS + 60;
    private static final float FLICK_DEG = 40F, TURN_RATE = 90F;

    private static volatile float camera;
    private static volatile float pressYaw = Float.NaN;
    private static volatile String phase = "-";
    private static boolean flickNext, pressNext, turning;
    private static int flicks;
    private static long lastFrame;
    private static final List<String> lines = new ArrayList<String>();
    private static final List<double[]> flickOff = new ArrayList<double[]>(), burstBehind = new ArrayList<double[]>();
    private static int frames;
    private static long firstFrame;

    static void register() {
        AimLagScenario a = new AimLagScenario();
        FMLCommonHandler.instance().bus().register(a);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(a);
    }

    // the scenario's steps, at the end of each client tick (ShootScenario)
    static void step(Minecraft mc, int t) {
        if (t == 0) {
            mc.thePlayer.rotationPitch = mc.thePlayer.prevRotationPitch = 0F;
            mc.thePlayer.rotationYaw = mc.thePlayer.prevRotationYaw = 0F;
        } else if (t == GIVE) {
            ShootScenario.onServer(() -> {
                load(0, "gun_spas12");
                load(1, "gun_m2");
            });
            mc.thePlayer.inventory.currentItem = 0;
        } else if (t >= FLICK && t < FLICK + FLICK_GAP * FLICKS && (t - FLICK) % FLICK_GAP == 0) {
            phase = "flick";
            flickNext = true;
        } else if (t >= FLICK && t < FLICK + FLICK_GAP * FLICKS && (t - FLICK) % FLICK_GAP == 3) {
            ShootScenario.trigger(false, mc);
        } else if (t == SWITCH) {
            phase = "-";
            mc.thePlayer.inventory.currentItem = 1;
        } else if (t == BURST) {
            phase = "burst";
            turning = true;
            ShootScenario.trigger(true, mc);
        } else if (t == BURST + BURST_TICKS) {
            ShootScenario.trigger(false, mc);
            turning = false;
            phase = "-";
        } else if (t == DONE) {
            done();
            mc.shutdown();
        }
    }

    // a frame: the flick and the burst's turn, as the mouse turns the camera (before the frame is drawn)
    @SubscribeEvent
    public void frame(TickEvent.RenderTickEvent e) {
        if (e.phase != TickEvent.Phase.START) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;
        long now = System.nanoTime();
        if (firstFrame == 0) firstFrame = now;
        frames++;
        if (flickNext) {
            flickNext = false;
            float d = flicks++ % 2 == 0 ? FLICK_DEG : -FLICK_DEG;
            mc.thePlayer.rotationYaw += d;
            mc.thePlayer.prevRotationYaw += d;
            pressNext = true;
        }
        if (turning && lastFrame != 0) {
            float d = TURN_RATE * (now - lastFrame) / 1e9F;
            mc.thePlayer.rotationYaw += d;
            mc.thePlayer.prevRotationYaw += d;
        }
        lastFrame = now;
        camera = mc.thePlayer.rotationYaw;
    }

    // the press after a flick: at the start of the next client tick, before the tick sends the turn (a real click is
    // read there too)
    @SubscribeEvent
    public void tickStart(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.START || !pressNext) return;
        pressNext = false;
        Minecraft mc = Minecraft.getMinecraft();
        pressYaw = mc.thePlayer.rotationYaw;
        ShootScenario.trigger(true, mc);
    }

    // a bullet born on the server: the turn it was built from against what the player saw
    @SubscribeEvent
    public void spawn(EntityJoinWorldEvent e) {
        if (e.world.isRemote || !(e.entity instanceof EntityBulletBaseMK4)) return;
        EntityPlayerMP p = player();
        if (p == null) return;
        EntityBulletBaseMK4 b = (EntityBulletBaseMK4) e.entity;
        float server = p.rotationYaw;
        double own = Math.toDegrees(Math.atan2(-b.motionX, b.motionZ));
        String ph = phase;
        if (ph.equals("flick")) {
            double off = wrap(pressYaw - server);
            flickOff.add(new double[] {off});
            line(ph, server, pressYaw, off, own);
        } else if (ph.equals("burst")) {
            double behind = wrap(camera - server);
            burstBehind.add(new double[] {behind, behind / TURN_RATE * 1000});
            line(ph, server, camera, behind, own);
        }
    }

    private static void line(String ph, float server, float seen, double off, double own) {
        synchronized (lines) {
            lines.add(String.format(Locale.ROOT, "%s\tserver %.2f\tseen %.2f\toff %.2f\tbullet %.2f", ph, server, seen, off, wrap(own - server)));
        }
    }

    private static double wrap(double d) {
        d %= 360;
        if (d > 180) d -= 360;
        if (d < -180) d += 360;
        return d;
    }

    private static EntityPlayerMP player() {
        net.minecraft.server.MinecraftServer s = net.minecraft.server.MinecraftServer.getServer();
        return s == null || s.getConfigurationManager().playerEntityList.isEmpty() ? null
                : (EntityPlayerMP) s.getConfigurationManager().playerEntityList.get(0);
    }

    // the gun with its magazines full, and a belt-fed gun's ammo in the inventory
    @SuppressWarnings({"rawtypes", "unchecked"})
    static void load(int slot, String name) {
        ShootScenario.give(slot, name);
        EntityPlayerMP p = player();
        ItemStack stack = p.inventory.getStackInSlot(slot);
        ItemGunBaseNT gun = (ItemGunBaseNT) stack.getItem();
        int free = 9;
        for (int c = 0; c < gun.getConfigCount(); c++) {
            for (Receiver r : gun.getConfig(stack, c).getReceivers(stack)) {
                IMagazine mag = r.getMagazine(stack);
                Object type = mag.getType(stack, p.inventory);
                if (!(type instanceof BulletConfig)) continue;
                ItemStack ammo = ((BulletConfig) type).ammo.toStack();
                ammo.stackSize = ammo.getMaxStackSize();
                for (int i = 0; i < 3; i++) p.inventory.setInventorySlotContents(free++, ammo.copy());
            }
        }
        p.inventoryContainer.detectAndSendChanges();
    }

    private static void done() {
        double secs = (System.nanoTime() - firstFrame) / 1e9;
        List<String> out = new ArrayList<String>();
        synchronized (lines) {
            out.addAll(lines);
        }
        out.add(String.format(Locale.ROOT, "frames %.1f a second", frames / secs));
        for (double[] x : flickOff) x[0] = Math.abs(x[0]);
        out.add(summary("flick 40 + click", flickOff, false));
        out.add(summary("burst 90 deg/s", burstBehind, true));
        out.add("AIMLAG DONE");
        try (PrintWriter w = new PrintWriter(new File("aimlag.txt"), StandardCharsets.UTF_8.name())) {
            for (String l : out) w.println(l);
        } catch (java.io.IOException ex) {
            GunFeel.LOGGER.warn("aimlag.txt: {}", ex.toString());
        }
        for (String l : out) if (!l.startsWith("flick\t") && !l.startsWith("burst\t")) GunFeel.LOGGER.info("Scenario aimlag: {}", l);
    }

    private static String summary(String name, List<double[]> v, boolean ms) {
        if (v.isEmpty()) return name + ": no bullets";
        double sum = 0, min = Double.MAX_VALUE, max = -Double.MAX_VALUE, sumMs = 0, maxMs = 0;
        for (double[] x : v) {
            sum += x[0];
            min = Math.min(min, x[0]);
            max = Math.max(max, x[0]);
            if (ms) {
                sumMs += x[1];
                maxMs = Math.max(maxMs, x[1]);
            }
        }
        return String.format(Locale.ROOT, "%s: %d bullets, off %.1f deg (min %.1f, max %.1f)%s", name, v.size(), sum / v.size(), min, max,
                ms ? String.format(Locale.ROOT, " = %.0f ms (max %.0f)", sumMs / v.size(), maxMs) : "");
    }
}
