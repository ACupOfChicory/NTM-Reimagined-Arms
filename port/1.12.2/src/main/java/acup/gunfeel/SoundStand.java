package acup.gunfeel;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.hbm.handler.HbmKeybinds;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.Receiver;
import com.hbm.items.weapon.sedna.mags.IMagazine;
import com.mojang.authlib.GameProfile;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

// Dev (runServer -PsoundStand): the sound stand. Two players: the first one to join (the dev client, recording what it
// hears: SoundRecord) listens; a second, a FakePlayer of the server's, shoots past him, over his head, into a wall by
// him, at walls and pillars from 4 to 45 blocks, at grazing angles off steel and stone (ricochets), and with a shotgun
// and a minigun into the wall by him. NTM CE fires on the server only (the client sends its gun keys:
// HbmKeybindsServer.onPressedServer -> handleKeybind), so the FakePlayer's handleKeybind is a second player's shot:
// the same NTM fire sound from the server, the same bullet, the same BulletServer and tracers' word to the players
// around. Last, the listener shoots himself (his own client holds the trigger), for what the shooter hears. Each case
// is a chat line "[soundstand] case ..." to the client's record; the shots go to logs/sound-stand.tsv. A flat world
// far from spawn (-3000, -3000); the listener stands at its middle looking along +z.
public final class SoundStand {
    public static final String PROPERTY = "ntmgunfeel.soundStand";
    private static final int OX = -3000, OZ = -3000, CASE = 80;

    private final PrintWriter out;
    private int tick, joined = -1;
    private EntityPlayerMP listener;
    private FakePlayer shooter;
    private ItemStack shooterGun, listenerGun;
    private double cx, cz;
    private int y0;
    private final List<Step> steps = new ArrayList<Step>();
    private int at;

    private static final class Step {
        final int tick;
        final Runnable run;

        Step(int tick, Runnable run) {
            this.tick = tick;
            this.run = run;
        }
    }

    private SoundStand(PrintWriter out) {
        this.out = out;
    }

    static void registerIfAsked() {
        String file = System.getProperty(PROPERTY);
        if (file == null) return;
        try {
            File f = new File(file);
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            PrintWriter w = new PrintWriter(f, StandardCharsets.UTF_8.name());
            w.println("tick\twtime\tcase\tevent\tx\ty\tz\tyaw\tpitch\tnote");
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new SoundStand(w));
            GunFeel.LOGGER.info("Sound stand: waiting for a player; shots to {}", f.getAbsolutePath());
        } catch (java.io.IOException e) {
            GunFeel.LOGGER.error("Sound stand: {}", file, e);
        }
    }

    private static String f(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    private WorldServer world() {
        return FMLCommonHandler.instance().getMinecraftServerInstance().getWorld(0);
    }

    private String caseName = "";

    private void log(String event, double x, double y, double z, float yaw, float pitch, String note) {
        out.println(tick + "\t" + world().getTotalWorldTime() + "\t" + caseName + "\t" + event + "\t" + f(x) + "\t" + f(y) + "\t" + f(z) + "\t"
                + f(yaw) + "\t" + f(pitch) + "\t" + note);
    }

    private void mark(String text) {
        listener.sendMessage(new TextComponentString("[soundstand] " + text));
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        tick++;
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (listener == null) {
            if (server.getPlayerList().getPlayers().isEmpty()) return;
            if (joined < 0) joined = tick;
            if (tick - joined < 100) return;
            try {
                setup(server.getPlayerList().getPlayers().get(0));
            } catch (RuntimeException ex) {
                GunFeel.LOGGER.error("Sound stand: setup", ex);
                server.initiateShutdown();
            }
            return;
        }
        try {
            if (shooterGun != null) {
                shooterGun.getItem().onUpdate(shooterGun, world(), shooter, 0, true);
                fill(shooterGun);
            }
            if (listenerGun != null) fill(listener.getHeldItemMainhand());
            while (at < steps.size() && steps.get(at).tick <= tick) steps.get(at++).run.run();
        } catch (RuntimeException ex) {
            GunFeel.LOGGER.error("Sound stand: step {}", at, ex);
            out.flush();
            server.initiateShutdown();
        }
        if (tick % 100 == 0) out.flush();
    }

    // ---- the place ----

    private void set(int dx, int y, int dz, Block b) {
        world().setBlockState(new BlockPos(OX + dx, y0 + y, OZ + dz), b.getDefaultState(), 2);
    }

    private void setup(EntityPlayerMP player) {
        listener = player;
        WorldServer w = world();
        w.getGameRules().setOrCreateGameRule("doMobSpawning", "false");
        w.getGameRules().setOrCreateGameRule("doDaylightCycle", "false");
        w.setWorldTime(6000);
        w.getChunk(new BlockPos(OX, 0, OZ));
        y0 = w.getHeight(OX, OZ);
        cx = OX + 0.5;
        cz = OZ + 0.5;
        Block steel = Block.REGISTRY.getObject(new net.minecraft.util.ResourceLocation("hbm", "block_steel"));
        if (steel == Blocks.AIR) throw new IllegalStateException("no hbm:block_steel");
        // the air over the place, then: a stone wall 3.5 ahead (z 3.5..4.5 from the listener's middle), steel 7.5 ahead
        // and stone 7.5 behind for the ricochets, stone pillars to the right (-x)
        for (int dx = -50; dx <= 40; dx++)
            for (int dz = -12; dz <= 12; dz++)
                for (int y = 0; y < 8; y++) set(dx, y, dz, Blocks.AIR);
        for (int dx = -6; dx <= 6; dx++)
            for (int y = 0; y < 4; y++) set(dx, y, 4, Blocks.STONE);
        for (int dx = -20; dx <= 30; dx++)
            for (int y = 0; y < 4; y++) {
                set(dx, y, 8, steel);
                set(dx, y, -8, Blocks.STONE);
            }
        int[][] pillars = {{-10, 0}, {-25, 3}, {-35, -3}, {-45, -6}};
        for (int[] p : pillars)
            for (int y = 0; y < 3; y++) set(p[0], y, p[1], Blocks.STONE);
        player.setGameType(GameType.CREATIVE);
        stand();
        shooter = FakePlayerFactory.get(w, new GameProfile(UUID.fromString("5a0e5a0e-0000-4000-8000-00000000a215"), "Shooter"));
        shooter.interactionManager.setGameType(GameType.SURVIVAL); // a FakePlayer has no connection to tell
        // the jar's rules only: BulletCheck, on the same dev server, leaves its own in the server's file (stone flown through)
        try {
            java.nio.file.Files.write(PortPaths.config().resolve(BulletRules.FILE),
                    "# the sound stand: the jar's rules only\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
        // the server's bullet log (hits, ricochets, where), to set against what the listener heard
        java.nio.file.Path bullets = TestLog.set(false, true);
        log("setup", cx, y0, cz, 0, 0, "listener " + player.getName() + " id " + player.getEntityId() + ", shooter id " + shooter.getEntityId()
                + ", steel " + steel.getRegistryName() + ", wall " + w.getBlockState(new BlockPos(OX, y0 + 1, OZ + 4)).getBlock().getRegistryName()
                + ", ground " + w.getBlockState(new BlockPos(OX, y0 - 1, OZ)).getBlock().getRegistryName() + ", bullets " + bullets);
        plan();
        mark("setup y0 " + y0);
    }

    private void stand() {
        listener.connection.setPlayerLocation(cx, y0, cz, 0F, 0F);
        listener.rotationYawHead = 0F;
    }

    // ---- the guns ----

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ItemStack gun(String name) {
        Item item = Item.REGISTRY.getObject(new net.minecraft.util.ResourceLocation("hbm", name));
        if (!(item instanceof ItemGunBaseNT)) throw new IllegalStateException("no NTM gun " + name);
        ItemStack stack = new ItemStack(item);
        ItemGunBaseNT g = (ItemGunBaseNT) item;
        for (int c = 0; c < g.getConfigCount(); c++)
            for (Receiver r : g.getConfig(stack, c).getReceivers(stack)) {
                IMagazine mag = r.getMagazine(stack);
                mag.setType(stack, mag.getType(stack, null));
            }
        fill(stack);
        return stack;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void fill(ItemStack stack) {
        if (stack == null || !(stack.getItem() instanceof ItemGunBaseNT)) return;
        ItemGunBaseNT g = (ItemGunBaseNT) stack.getItem();
        for (int c = 0; c < g.getConfigCount(); c++)
            for (Receiver r : g.getConfig(stack, c).getReceivers(stack)) {
                IMagazine mag = r.getMagazine(stack);
                mag.setAmount(stack, mag.getCapacity(stack));
            }
    }

    // the shooter's eyes at (dx, eye height, dz) from the listener's middle, looking at (tx, ty, tz) (ty from the ground)
    private void aim(double dx, double dz, double tx, double ty, double tz) {
        double ex = cx + dx, ey = y0 + shooter.getEyeHeight(), ez = cz + dz;
        double vx = cx + tx - ex, vy = y0 + ty - ey, vz = cz + tz - ez;
        float yaw = (float) Math.toDegrees(Math.atan2(-vx, vz));
        float pitch = (float) -Math.toDegrees(Math.atan2(vy, Math.sqrt(vx * vx + vz * vz)));
        shooter.setPositionAndRotation(ex, y0, ez, yaw, pitch);
        shooter.rotationYawHead = shooter.prevRotationYawHead = yaw;
        shooter.prevRotationYaw = yaw;
        shooter.prevRotationPitch = pitch;
        shooter.renderYawOffset = yaw;
        shooter.prevPosX = shooter.lastTickPosX = ex;
        shooter.prevPosY = shooter.lastTickPosY = y0;
        shooter.prevPosZ = shooter.lastTickPosZ = ez;
        // standing, as a player on the ground (else the mod spreads his shots as a jumping one's)
        shooter.onGround = true;
    }

    private void hold(String gunName) {
        shooterGun = gun(gunName);
        shooter.inventory.currentItem = 0;
        shooter.inventory.setInventorySlotContents(0, shooterGun);
        // a belt (the minigun's) feeds from the inventory, not the gun
        ItemGunBaseNT g = (ItemGunBaseNT) shooterGun.getItem();
        for (Receiver r : g.getConfig(shooterGun, 0).getReceivers(shooterGun))
            if (r.getMagazine(shooterGun) instanceof com.hbm.items.weapon.sedna.mags.MagazineBelt) {
                ItemStack ammo = ((com.hbm.items.weapon.sedna.mags.MagazineBelt) r.getMagazine(shooterGun)).acceptedBullets.get(0).ammo.toStack();
                for (int i = 1; i <= 4; i++) {
                    ItemStack a = ammo.copy();
                    a.setCount(a.getMaxStackSize());
                    shooter.inventory.setInventorySlotContents(i, a);
                }
            }
    }

    private void press(boolean down) {
        ((ItemGunBaseNT) shooterGun.getItem()).handleKeybind(shooter, shooterGun, HbmKeybinds.EnumKeybind.GUN_PRIMARY, down);
        log(down ? "press" : "release", shooter.posX, shooter.posY + shooter.getEyeHeight(), shooter.posZ, shooter.rotationYaw, shooter.rotationPitch, "");
    }

    // ---- the plan ----

    private int t;

    private void at(int dt, Runnable r) {
        steps.add(new Step(t + dt, r));
    }

    // a case: the shooter at (dx, dz) fires `shots` single shots (hold ticks each) of `gunName` at the point (tx, ty, tz)
    private void shoot(String name, String gunName, double dx, double dz, double tx, double ty, double tz, int shots, int holdTicks, int gap) {
        at(0, () -> {
            caseName = name;
            stand();
            hold(gunName);
            aim(dx, dz, tx, ty, tz);
            log("case", shooter.posX, shooter.posY + shooter.getEyeHeight(), shooter.posZ, shooter.rotationYaw, shooter.rotationPitch,
                    gunName + " at " + f(tx) + "," + f(ty) + "," + f(tz));
            mark("case " + name + " shooter " + f(dx) + "," + f(dz) + " target " + f(tx) + "," + f(ty) + "," + f(tz) + " " + gunName);
        });
        for (int i = 0; i < shots; i++) {
            int s = 10 + i * gap;
            at(s, () -> {
                aim(dx, dz, tx, ty, tz);
                press(true);
            });
            at(s + holdTicks, () -> press(false));
        }
        t += Math.max(CASE, 10 + shots * gap + holdTicks + 50);
    }

    // a case for the listener's own shots: he looks at (tx, ty, tz) and his client holds the trigger for holdTicks
    private void self(String name, String gunName, double tx, double ty, double tz, int shots, int holdTicks, int gap) {
        double ey = 1.62;
        float yaw = (float) Math.toDegrees(Math.atan2(-tx, tz));
        float pitch = (float) -Math.toDegrees(Math.atan2(ty - ey, Math.sqrt(tx * tx + tz * tz)));
        at(0, () -> {
            caseName = name;
            shooterGun = null;
            listenerGun = gun(gunName);
            listener.inventory.currentItem = 0;
            listener.inventory.setInventorySlotContents(0, listenerGun);
            listener.connection.sendPacket(new net.minecraft.network.play.server.SPacketHeldItemChange(0));
            listener.inventoryContainer.detectAndSendChanges();
            listener.connection.setPlayerLocation(cx, y0, cz, yaw, pitch);
            log("case", cx, y0 + ey, cz, yaw, pitch, "self " + gunName + " at " + f(tx) + "," + f(ty) + "," + f(tz));
            mark("case " + name + " self target " + f(tx) + "," + f(ty) + "," + f(tz) + " " + gunName);
        });
        for (int i = 0; i < shots; i++) {
            int s = 40 + i * gap;
            at(s, () -> {
                log("trigger", listener.posX, listener.posY + listener.getEyeHeight(), listener.posZ, listener.rotationYaw, listener.rotationPitch, "");
                mark("trigger 1");
            });
            at(s + holdTicks, () -> mark("trigger 0"));
        }
        t += Math.max(CASE, 40 + shots * gap + holdTicks + 50);
    }

    private void plan() {
        t = tick + 40;
        double eye = 1.62;
        String g = "gun_am180";
        // the shot's own sound by distance and side: into the sky
        shoot("fire_right_2", g, -2, 0, -2, 40, 3, 2, 0, 20);
        shoot("fire_left_2", g, 2, 0, 2, 40, 3, 2, 0, 20);
        for (int d : new int[] {5, 10, 15, 20, 30}) shoot("fire_left_" + d, g, d, 0, d, 40, 3, 2, 0, 20);
        // bullets past the listener from his right (-x) going left, and from his left going right
        shoot("past_front_1.5_from_right", g, -20, 1.5, 20, eye, 1.5, 2, 0, 20);
        shoot("past_behind_1.5_from_right", g, -20, -1.5, 20, eye, -1.5, 2, 0, 20);
        shoot("past_front_0.6_from_right", g, -20, 0.6, 20, eye, 0.6, 2, 0, 20);
        shoot("past_behind_4_from_right", g, -20, -4, 20, eye, -4, 2, 0, 20);
        shoot("past_front_1.5_from_left", g, 20, 1.5, -20, eye, 1.5, 2, 0, 20);
        shoot("past_behind_0.6_from_left", g, 20, -0.6, -20, eye, -0.6, 2, 0, 20);
        // over his head from the left, aimed at the point over him: from the right the pillar 10 blocks off stands in the line
        shoot("over_2_from_left", g, 20, 0, 0, eye + 2, 0, 2, 0, 20);
        shoot("over_1_from_left", g, 20, 0, 0, eye + 1, 0, 2, 0, 20);
        // into the wall by him, front left and front right, from aside (no whizz, no grazing angle)
        shoot("wall_front_left", g, 20, 1, 2, eye, 3.5, 2, 0, 20);
        shoot("wall_front_right", g, -20, 1, -2, eye, 3.5, 2, 0, 20);
        // into the pillars to his right, the shooter 3 blocks before each
        int[][] pillars = {{-10, 0}, {-25, 3}, {-35, -3}, {-45, -6}};
        for (int[] p : pillars) shoot("pillar_" + (-p[0]), g, p[0] + 3, p[1], p[0] + 0.5, eye, p[1], 2, 0, 20);
        // ricochets: 3 degrees to the steel face 7.5 ahead and the stone face 7.5 behind, hitting at x = rx
        for (int rx : new int[] {0, 12, 20}) {
            shoot("ricochet_steel_" + rx, g, rx - 9.5, 7.0, rx, eye, 7.5, 2, 0, 20);
            shoot("ricochet_stone_" + rx, g, rx - 9.5, -7.0, rx, eye, -7.5, 2, 0, 20);
        }
        // many sounds at once: a shotgun's pellets, a minigun's burst, into the wall by him
        shoot("spas12_wall", "gun_spas12", 20, 1, 1, eye, 3.5, 3, 0, 25);
        shoot("minigun_wall", "gun_minigun", 20, 1, 1, eye, 3.5, 1, 40, 20);
        // the listener's own shots
        self("self_wall", g, 0, eye, 3.5, 2, 1, 20);
        self("self_pillar_10", g, -10, eye, 0, 2, 1, 20);
        self("self_pillar_25", g, -25, eye, 3, 2, 1, 20);
        self("self_spas12_wall", "gun_spas12", 0, eye, 3.5, 2, 1, 25);
        at(0, () -> {
            caseName = "done";
            log("done", cx, y0, cz, 0, 0, "");
            mark("done");
            out.flush();
        });
        at(60, () -> {
            out.close();
            FMLCommonHandler.instance().getMinecraftServerInstance().initiateShutdown();
        });
    }
}
