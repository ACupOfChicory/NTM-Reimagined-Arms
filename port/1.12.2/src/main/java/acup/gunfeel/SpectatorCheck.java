package acup.gunfeel;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import com.hbm.entity.projectile.EntityBulletBase;
import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.handler.HbmKeybinds.EnumKeybind;
import com.hbm.handler.HbmKeybindsServer;
import com.hbm.items.ModItems;
import com.hbm.items.weapon.ItemGunBase;
import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.mags.MagazineSingleTypeBase;
import com.hbm.packet.toserver.GunButtonPacket;

import net.minecraft.entity.Entity;
import net.minecraft.entity.monster.EntityHusk;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;

// port: 26.2's SpectatorStand without a window, on a dev server started with a file "spectatorcheck" in its folder
// (run/server). May a player in spectator mode use an NTM gun (spectatorNoGuns, SpectatorGuns)? A fake player holds the
// Uzi, the power armor's fist or the vortex (a gun of NTM's old kind, ItemGunBase) and presses the gun keys
// through NTM's own server path (HbmKeybindsServer, GunButtonPacket's handler for the old gun), the gun
// steps each tick as in the hand; the counts are the bullets that came out, the rounds in the magazine and the ammo in
// the inventory, a husk's health and the gun's tag. Each case also runs with the rule off, where NTM's bug shows.
// "spectatorcheck: ..." lines and a summary to the log, then the server stops.
final class SpectatorCheck {
    private enum How { KEY, SWITCH, RELOAD, MELEE, MELEE_SWITCH, AMMO, OLD, OLD_SWITCH }

    private static final class Case {
        final String name;
        final How how;
        final boolean rule;
        final GameType mode; // at the press
        final boolean acts; // the gun does what it was told

        Case(String name, How how, boolean rule, GameType mode, boolean acts) {
            this.name = name;
            this.how = how;
            this.rule = rule;
            this.mode = mode;
            this.acts = acts;
        }
    }

    private static final Case[] CASES = {
            new Case("creative fires", How.KEY, true, GameType.CREATIVE, true),
            new Case("spectator does not fire", How.KEY, true, GameType.SPECTATOR, false),
            new Case("spectator fires with the rule off (NTM's bug)", How.KEY, false, GameType.SPECTATOR, true),
            new Case("a key held as the player becomes a spectator stops", How.SWITCH, true, GameType.CREATIVE, false),
            new Case("a key held as the player becomes a spectator, rule off: fires on", How.SWITCH, false, GameType.CREATIVE, true),
            new Case("a reload begun as the player becomes a spectator stops", How.RELOAD, true, GameType.CREATIVE, false),
            new Case("a reload begun as the player becomes a spectator, rule off: goes on", How.RELOAD, false, GameType.CREATIVE, true),
            new Case("power armor swing in creative hits", How.MELEE, true, GameType.CREATIVE, true),
            new Case("spectator's power armor swing does not hit", How.MELEE, true, GameType.SPECTATOR, false),
            new Case("spectator's power armor swing, rule off: hits", How.MELEE, false, GameType.SPECTATOR, true),
            new Case("a swing begun as the player becomes a spectator does not hit", How.MELEE_SWITCH, true, GameType.CREATIVE, false),
            new Case("a swing begun as the player becomes a spectator, rule off: hits", How.MELEE_SWITCH, false, GameType.CREATIVE, true),
            new Case("creative's ammo lock and choice change the gun", How.AMMO, true, GameType.CREATIVE, true),
            new Case("spectator's ammo lock and choice do nothing", How.AMMO, true, GameType.SPECTATOR, false),
            new Case("spectator's ammo lock and choice, rule off: change the gun", How.AMMO, false, GameType.SPECTATOR, true),
            new Case("old gun: creative fires", How.OLD, true, GameType.CREATIVE, true),
            new Case("old gun: spectator does not fire", How.OLD, true, GameType.SPECTATOR, false),
            new Case("old gun: spectator fires with the rule off (NTM's bug)", How.OLD, false, GameType.SPECTATOR, true),
            new Case("old gun: a button held as the player becomes a spectator is let go", How.OLD_SWITCH, true, GameType.CREATIVE, false),
            new Case("old gun: a button held as the player becomes a spectator, rule off: stays down and fires", How.OLD_SWITCH, false, GameType.CREATIVE, true),
    };

    private static int ok, failed, index, stage, t, waited;
    private static boolean done;
    private static String fileBefore;
    private static FakePlayer p;
    private static EntityHusk husk;
    private static BulletConfig ammo;
    private static final Set<Integer> SEEN = new HashSet<>();
    private static int shots, shotsAfter, magBefore, invBefore, oldDelay;
    private static float healthBefore;

    private SpectatorCheck() {
    }

    // GunFeelMod.serverStarted
    static void maybeRun() {
        if (!new File("spectatorcheck").exists()) return;
        try {
            Path f = file();
            fileBefore = Files.exists(f) ? new String(Files.readAllBytes(f), StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            GunFeel.LOGGER.error("spectatorcheck: could not read {}", ReaimTimes.FILE, e);
            return;
        }
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new SpectatorCheck.Ticks());
    }

    public static final class Ticks {
        @SubscribeEvent
        public void tick(TickEvent.ServerTickEvent e) {
            if (e.phase != TickEvent.Phase.END || done) return;
            try {
                step(FMLCommonHandler.instance().getMinecraftServerInstance().worlds[0]);
            } catch (RuntimeException | LinkageError x) {
                failed++;
                GunFeel.LOGGER.error("spectatorcheck: crashed in '{}'", index < CASES.length ? CASES[index].name : "-", x);
                finish();
            }
        }
    }

    private static Path file() {
        return PortPaths.config().resolve(ReaimTimes.FILE);
    }

    private static void check(String what, boolean pass, Object got) {
        if (pass) ok++;
        else failed++;
        GunFeel.LOGGER.info("spectatorcheck: {} {} (got {})", pass ? "ok  " : "FAIL", what, got);
    }

    private static void rule(boolean on) {
        String base = fileBefore == null ? "" : fileBefore.replaceAll("(?m)^\\s*" + SpectatorGuns.KEY + "\\s*[=:].*\\n?", "");
        if (!base.isEmpty() && !base.endsWith("\n")) base += "\n";
        try {
            Path f = file();
            Files.write(f, (on ? base : base + SpectatorGuns.KEY + " = false\n").getBytes(StandardCharsets.UTF_8));
            f.toFile().setLastModified(System.currentTimeMillis() + 1000L * (waited + 1)); // a new stamp for ReaimTimes.poll
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ItemStack gun() {
        return p.inventory.getStackInSlot(0);
    }

    private static void mode(GameType g) {
        p.interactionManager.setGameType(g); // a FakePlayer has no connection to tell
    }

    private static void press(EnumKeybind key, boolean down) {
        HbmKeybindsServer.onPressedServer(p, key, down);
    }

    // the gun steps as in the hand; the bullets that came out are counted
    private static void stepGun(WorldServer w) {
        ItemStack stack = gun();
        if (!stack.isEmpty()) stack.getItem().onUpdate(stack, w, p, 0, true);
        // an old gun's shot (the vortex's is no bullet entity) sets its delay to the rate of fire
        if (stack.getItem() instanceof ItemGunBase) {
            int delay = ItemGunBase.getDelay(stack);
            if (delay > oldDelay) {
                shots++;
                shotsAfter++;
            }
            oldDelay = delay;
        }
        for (Entity e : w.loadedEntityList) {
            if ((e instanceof EntityBulletBaseMK4 || e instanceof EntityBulletBase) && SEEN.add(e.getEntityId())) {
                shots++;
                shotsAfter++;
            }
        }
    }

    private static MagazineSingleTypeBase mag() {
        return (MagazineSingleTypeBase) AmmoLock.primary(gun());
    }

    private static int inInventory() {
        return AmmoLock.count(ammo, p.inventory);
    }

    private static void step(WorldServer w) {
        if (index >= CASES.length) {
            finish();
            return;
        }
        Case c = CASES[index];
        switch (stage) {
            case 0: // the rule as the case wants it
                if (ReaimTimes.flag(SpectatorGuns.KEY, true) != c.rule) {
                    if (waited % 20 == 0) rule(c.rule);
                    if (++waited > 200) {
                        check(c.name + ": the server took the rule", false, ReaimTimes.flag(SpectatorGuns.KEY, true));
                        next();
                    }
                    return;
                }
                waited = 0;
                setUp(w, c);
                stage = 1;
                t = 0;
                return;
            case 1: // drawn in creative
                stepGun(w);
                if (!(gun().getItem() instanceof ItemGunBaseNT)) {
                    if (++t >= 3) {
                        stage = 2;
                        t = 0;
                    }
                    return;
                }
                if (++t >= 3 && ItemGunBaseNT.getState(gun(), 0) == ItemGunBaseNT.GunState.IDLE) {
                    stage = 2;
                    t = 0;
                    return;
                }
                if (t > 100) {
                    check(c.name + ": the gun is drawn", false, ItemGunBaseNT.getState(gun(), 0));
                    next();
                }
                return;
            default:
                run(w, c);
        }
    }

    private static void setUp(WorldServer w, Case c) {
        if (p == null) p = FakePlayerFactory.getMinecraft(w);
        BlockPos at = w.getTopSolidOrLiquidBlock(w.getSpawnPoint()).up(3);
        p.setPositionAndRotation(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0F, 0F);
        p.rotationYawHead = 0F;
        p.inventory.clear();
        p.inventory.currentItem = 0;
        mode(GameType.CREATIVE);
        for (Entity e : w.loadedEntityList) if (e instanceof EntityBulletBaseMK4 || e instanceof EntityBulletBase) e.setDead();
        SEEN.clear();
        boolean melee = c.how == How.MELEE || c.how == How.MELEE_SWITCH;
        if (c.how == How.OLD || c.how == How.OLD_SWITCH) {
            // the vortex (automatic, a full magazine); the super shotgun's 12 gauge rounds of the old kind are no longer
            // in NTM CE 2.6, so it does not fire for anyone
            ItemStack old = new ItemStack(ModItems.gun_vortex);
            p.inventory.setInventorySlotContents(0, old);
            ItemGunBase.setMag(old, ((ItemGunBase) old.getItem()).mainConfig.ammoCap);
            return;
        }
        ItemStack stack = new ItemStack(melee ? ModItems.gun_pa_melee : ModItems.gun_uzi);
        p.inventory.setInventorySlotContents(0, stack);
        if (melee) {
            p.setItemStackToSlot(EntityEquipmentSlot.HEAD, new ItemStack(ModItems.ncrpa_helmet));
            p.setItemStackToSlot(EntityEquipmentSlot.CHEST, new ItemStack(ModItems.ncrpa_plate));
            p.setItemStackToSlot(EntityEquipmentSlot.LEGS, new ItemStack(ModItems.ncrpa_legs));
            p.setItemStackToSlot(EntityEquipmentSlot.FEET, new ItemStack(ModItems.ncrpa_boots));
            husk = new EntityHusk(w);
            husk.setPosition(p.posX, p.posY, p.posZ + 2);
            husk.setNoAI(true);
            husk.setNoGravity(true);
            w.spawnEntity(husk);
        } else {
            MagazineSingleTypeBase mag = mag();
            BulletConfig[] two = new BulletConfig[2];
            for (BulletConfig b : mag.acceptedBullets) {
                if (b.ammo == null || b.ammo.item == null) continue;
                if (two[0] == null) two[0] = b;
                else if (two[1] == null && (b.ammo.item != two[0].ammo.item || b.ammo.meta != two[0].ammo.meta)) two[1] = b;
            }
            ammo = two[0];
            mag.setType(stack, ammo);
            mag.setAmount(stack, c.how == How.RELOAD ? 2 : mag.getCapacity(stack));
            p.inventory.setInventorySlotContents(5, stack(ammo, 64));
            if (c.how == How.AMMO && two[1] != null) p.inventory.setInventorySlotContents(6, stack(two[1], 64));
        }
    }

    private static ItemStack stack(BulletConfig b, int n) {
        ItemStack s = b.ammo.toStack();
        s.setCount(n);
        return s;
    }

    private static void baseline() {
        shots = 0;
        shotsAfter = 0;
        if (gun().getItem() instanceof ItemGunBase) {
            magBefore = ItemGunBase.getMag(gun());
            oldDelay = ItemGunBase.getDelay(gun());
        }
        else if (gun().getItem() != ModItems.gun_pa_melee) {
            magBefore = mag().getAmount(gun(), p.inventory);
            invBefore = inInventory();
        }
        if (husk != null) healthBefore = husk.getHealth();
    }

    private static void run(WorldServer w, Case c) {
        switch (c.how) {
            case KEY:
                if (t == 0) {
                    mode(c.mode);
                    baseline();
                    press(EnumKeybind.GUN_PRIMARY, true);
                }
                if (t == 20) press(EnumKeybind.GUN_PRIMARY, false);
                stepGun(w);
                if (t == 40) {
                    int mag = mag().getAmount(gun(), p.inventory);
                    if (c.acts) check(c.name, shots > 0 && mag < magBefore, shots + " bullets, magazine " + magBefore + " -> " + mag);
                    else check(c.name, shots == 0 && mag == magBefore, shots + " bullets, magazine " + magBefore + " -> " + mag);
                    next();
                    return;
                }
                break;
            case SWITCH:
                if (t == 0) {
                    baseline();
                    press(EnumKeybind.GUN_PRIMARY, true);
                }
                if (t == 2) {
                    mode(GameType.SPECTATOR);
                    shotsAfter = 0;
                }
                stepGun(w);
                if (t == 42) {
                    boolean held = ItemGunBaseNT.getPrimary(gun(), 0);
                    press(EnumKeybind.GUN_PRIMARY, false);
                    if (c.acts) check(c.name, shotsAfter > 0, shots + " bullets, " + shotsAfter + " after the switch");
                    else check(c.name, shots > 0 && shotsAfter == 0 && !held, shots + " bullets, " + shotsAfter + " after the switch, key held " + held);
                    next();
                    return;
                }
                break;
            case RELOAD:
                if (stage == 2) {
                    if (t == 0) {
                        baseline();
                        press(EnumKeybind.RELOAD, true);
                    }
                    if (t == 1) press(EnumKeybind.RELOAD, false);
                    if (t == 3) {
                        check(c.name + ": the reload began", ItemGunBaseNT.getState(gun(), 0) == ItemGunBaseNT.GunState.RELOADING, ItemGunBaseNT.getState(gun(), 0));
                        mode(GameType.SPECTATOR);
                    }
                    stepGun(w);
                    if (t == 103) {
                        int mag = mag().getAmount(gun(), p.inventory), inv = inInventory();
                        String got = "magazine " + magBefore + " -> " + mag + ", inventory " + invBefore + " -> " + inv + ", " + ItemGunBaseNT.getState(gun(), 0);
                        check(c.name, c.acts ? mag > magBefore : mag == magBefore && inv == invBefore, got);
                        if (c.acts) {
                            next();
                            return;
                        }
                        mode(GameType.CREATIVE); // back: the gun is drawn again and works
                        stage = 3;
                        t = 0;
                        return;
                    }
                } else {
                    stepGun(w);
                    if (waited == 0 && t >= 1 && ItemGunBaseNT.getState(gun(), 0) == ItemGunBaseNT.GunState.IDLE) {
                        check(c.name + ": back in creative the gun is drawn again", true, t + " ticks");
                        shots = 0;
                        press(EnumKeybind.GUN_PRIMARY, true);
                        press(EnumKeybind.GUN_PRIMARY, false);
                        waited = t;
                    }
                    if (waited > 0 && t == waited + 10) {
                        check(c.name + ": back in creative the gun fires", shots > 0, shots + " bullets");
                        next();
                        return;
                    }
                    if (t > 200) {
                        check(c.name + ": back in creative the gun is drawn again", false, ItemGunBaseNT.getState(gun(), 0));
                        next();
                        return;
                    }
                }
                break;
            case MELEE:
            case MELEE_SWITCH:
                if (t == 0) {
                    mode(c.mode);
                    baseline();
                    press(EnumKeybind.GUN_PRIMARY, true);
                }
                if (t == 1) {
                    if (c.how == How.MELEE_SWITCH) mode(GameType.SPECTATOR);
                    press(EnumKeybind.GUN_PRIMARY, false);
                }
                stepGun(w);
                if (t == 60) {
                    float h = husk.getHealth();
                    String got = "husk " + healthBefore + " -> " + h + ", " + (husk.isDead ? "dead" : "alive");
                    check(c.name, c.acts == (h < healthBefore || husk.isDead), got);
                    next();
                    return;
                }
                break;
            case OLD:
            case OLD_SWITCH:
                if (t == 0) {
                    mode(c.mode);
                    baseline();
                    button(true);
                }
                if (t == 2 && c.how == How.OLD_SWITCH) {
                    mode(GameType.SPECTATOR);
                    shotsAfter = 0;
                }
                stepGun(w);
                if (t == 30) {
                    boolean down = ItemGunBase.getIsMouseDown(gun());
                    int mag = ItemGunBase.getMag(gun());
                    button(false);
                    String got = shots + " shots, " + shotsAfter + " after the switch, magazine " + magBefore + " -> " + mag + ", button down " + down;
                    if (c.how == How.OLD) check(c.name, c.acts ? shots > 0 : shots == 0 && mag == magBefore, got);
                    else check(c.name, c.acts ? down && shotsAfter > 0 : !down && shotsAfter == 0, got);
                    next();
                    return;
                }
                break;
            case AMMO: {
                mode(c.mode);
                ItemStack stack = gun();
                String before = String.valueOf(stack.getTagCompound());
                MessageContext ctx = context();
                try {
                    new AmmoLockPayload.Handler().onMessage(new AmmoLockPayload(), ctx);
                    int other = -1;
                    ItemStack second = p.inventory.getStackInSlot(6);
                    for (BulletConfig b : mag().acceptedBullets)
                        if (b != ammo && b.ammo != null && !second.isEmpty() && b.ammo.item == second.getItem() && b.ammo.meta == second.getMetadata()) other = BulletConfig.configs.indexOf(b);
                    if (other >= 0) new AmmoSelectPayload.Handler().onMessage(new AmmoSelectPayload(other), ctx);
                    String after = String.valueOf(stack.getTagCompound());
                    check(c.name, c.acts != before.equals(after), (before.equals(after) ? "the tag the same" : "the tag changed") + (other >= 0 ? ", lock and choice" : ", lock only"));
                } finally {
                    p.connection = null;
                }
                next();
                return;
            }
        }
        t++;
    }

    // the mouse button as the client of an old gun sends it
    private static void button(boolean down) {
        try {
            new GunButtonPacket.Handler().onMessage(new GunButtonPacket(down, (byte) 0, EnumHand.MAIN_HAND), context());
        } finally {
            p.connection = null;
        }
    }

    // what a packet's handler gets: the fake player behind a connection that sends nowhere
    private static MessageContext context() {
        NetHandlerPlayServer h = new NetHandlerPlayServer(FMLCommonHandler.instance().getMinecraftServerInstance(), new NetworkManager(EnumPacketDirection.SERVERBOUND), p);
        try { // its constructor is package-private
            java.lang.reflect.Constructor<MessageContext> k = MessageContext.class.getDeclaredConstructor(net.minecraft.network.INetHandler.class, Side.class);
            k.setAccessible(true);
            return k.newInstance(h, Side.SERVER);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void next() {
        if (husk != null) {
            husk.setDead();
            husk = null;
        }
        if (p != null) {
            for (EntityEquipmentSlot s : new EntityEquipmentSlot[] { EntityEquipmentSlot.HEAD, EntityEquipmentSlot.CHEST, EntityEquipmentSlot.LEGS, EntityEquipmentSlot.FEET })
                p.setItemStackToSlot(s, ItemStack.EMPTY);
            mode(GameType.CREATIVE);
        }
        index++;
        stage = 0;
        t = 0;
        waited = 0;
    }

    private static void finish() {
        done = true;
        try {
            Path f = file();
            if (fileBefore == null) Files.deleteIfExists(f);
            else Files.write(f, fileBefore.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            GunFeel.LOGGER.warn("spectatorcheck: could not put back {}", ReaimTimes.FILE, e);
        }
        GunFeel.LOGGER.info("spectatorcheck: {} ok, {} failed", ok, failed);
        FMLCommonHandler.instance().getMinecraftServerInstance().initiateShutdown();
    }
}
