package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.packet.toclient.HbmAnimationPacket;
import com.hbm.render.anim.AnimationEnums.GunAnimation;
import com.hbm.render.anim.HbmAnimations;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldSettings;

// A trace scenario on the real client (runCheckClient -Ptrace -Pscenario=arms): each gun is put in hand by the
// server, then played on the client alone, frame by frame with the timer fixed (FixedStep): draw, rest, a turn of the
// view, aiming in and out, one shot, a burst of three, reload and inspect. NTM plays each animation as from the
// server's packet (HbmAnimationPayload.handleSedna, which also runs the gun's recoil); the mod draws arms, kick and
// camera as in a game. Only the client's lines while a part plays go into the trace, with the time since the part
// began: two runs give the same trace (tools/trace_diff.py), and a port's run the same numbers.
//   -Dntmgunfeel.scenario=arms            on (with -Dntmgunfeel.trace)
//   -Dntmgunfeel.scenarioGuns=<id,...>    the guns (default: GUNS); "all": every NTM gun of the game
//
// port: copied from 26.2 (0762086, notes/ports/port-1.7.10-copied.md): the parts, frames, seeds and waits are 26.2's.
// The world comes from ShootScenario (26.2: ClientSmoke), start and frame are called from GunFeelClient's frame, a
// check that fails goes to the log and the end closes the client (26.2: ClientSmoke.check/finish). NTM 1.7.10's
// animation clock is com.hbm.util.Clock (MixinClock; 26.2: GameTime), its packet handler HbmAnimationPacket.Handler,
// and it stamps an animation's start with System.currentTimeMillis, so the start is set again from the fixed clock.
// The name "shoot" is the port's own scenario (ShootScenario), not this one.
public final class TraceScenario {
    private static final String NAME = System.getProperty("ntmgunfeel.scenario");
    static final String GUNS = "hbm:gun_uzi,hbm:gun_g3,hbm:gun_henry,hbm:gun_light_revolver,hbm:gun_spas12,hbm:gun_maresleg";
    private static final String[] PARTS = {"equip", "idle", "turn", "ads", "shot", "burst", "reload", "inspect"};
    private static final int MAX_FRAMES = 1500, SETTLE_FRAMES = 250;

    private static final List<Item> guns = new ArrayList<Item>();
    private static int gun, part, frame, wait, next = -1, failed;
    private static long origin, ntm;
    private static boolean given, done, started;

    private TraceScenario() {
    }

    static boolean enabled() {
        return Trace.ON && NAME != null && !NAME.trim().isEmpty() && !NAME.trim().equals("shoot");
    }

    static boolean started() {
        return started;
    }

    static void start() {
        started = true;
        String list = System.getProperty("ntmgunfeel.scenarioGuns", GUNS).trim();
        if (list.equals("all")) {
            // every NTM gun, in the order of its 26.2 id
            TreeMap<String, Item> all = new TreeMap<String, Item>();
            for (Object o : Item.itemRegistry) {
                Item i = (Item) o;
                String id = GunFeelClient.gunId(i);
                if (i instanceof ItemGunBaseNT && id != null && id.startsWith("hbm:")) all.put(id, i);
            }
            guns.addAll(all.values());
        } else {
            for (String id : list.split(",")) {
                String s = id.trim();
                Object item = Item.itemRegistry.getObject(s.replaceFirst("^hbm:", "hbm:item."));
                if (item instanceof ItemGunBaseNT) guns.add((Item) item);
                else check(false, "сценарий: нет пушки " + id);
            }
        }
        Trace.scenario = true;
        Trace.recording = false;
        FixedStep.start();
        gun = 0;
        wait = -2;
    }

    // the timer's first fixed frame has run: the trace's, the mod's and NTM's clocks from it on
    private static void clocks() {
        origin = FixedStep.now();
        Trace.client(() -> (FixedStep.now() - origin) / 50L, () -> (FixedStep.now() - origin) % 50L / 50.0);
        // NTM's animations run on the same frames: its clock (com.hbm.util.Clock, MixinClock) from the fixed one, going
        // on from where it was (26.2: GameTime.CLIENT_NOW; NTM 1.7.10 has no tick clock of its own to set)
        ntm = System.currentTimeMillis() - FixedStep.now();
        GunClock.use(() -> FixedStep.now() * 1_000_000L);
    }

    // NTM's clock while the scenario runs (MixinClock)
    public static long ntmNow() {
        return FixedStep.now() + ntm;
    }

    // the server puts the gun in hand, drawn and idle; the client waits for it and for the server's draw to end
    private static void give() {
        given = false;
        final Item item = guns.get(gun);
        final MinecraftServer server = MinecraftServer.getServer();
        ShootScenario.onServer(() -> {
            EntityPlayerMP p = (EntityPlayerMP) server.getConfigurationManager().playerEntityList.get(0);
            p.setGameType(WorldSettings.GameType.CREATIVE);
            p.capabilities.isFlying = true;
            p.sendPlayerAbilities();
            p.playerNetServerHandler.setPlayerLocation(8.5, 110, 8.5, 0, 0);
            ItemStack stack = new ItemStack(item);
            ItemGunBaseNT.setIsDrawn(stack, true);
            ItemGunBaseNT.setIsEquipped(stack, true);
            ItemGunBaseNT.setState(stack, 0, ItemGunBaseNT.GunState.IDLE);
            ItemGunBaseNT.setTimer(stack, 0, 0);
            p.inventory.currentItem = 0;
            p.inventory.setInventorySlotContents(0, stack);
            p.inventoryContainer.detectAndSendChanges();
            given = true;
        });
        // port: the client picks its slot itself (26.2: the server's setSelectedSlot reaches it)
        Minecraft.getMinecraft().thePlayer.inventory.currentItem = 0;
        wait = -1;
    }

    static void frame() {
        if (done || !started) return;
        Minecraft mc = Minecraft.getMinecraft();
        EntityClientPlayerMP player = mc.thePlayer;
        if (player == null) return;
        // a part starts on a tick's first frame, so its ticks fall on the same frames in every run
        if (next >= 0) {
            if (FixedStep.aligned()) {
                int p = next;
                next = -1;
                begin(p);
            }
            return;
        }
        if (wait == -2) {
            if (FixedStep.fresh()) return;
            clocks();
            give();
            return;
        }
        if (wait != 0) {
            // setting up: nothing is written
            ItemStack held = player.getHeldItem();
            if (wait < 0 && given && held != null && held.getItem() == guns.get(gun)) wait = SETTLE_FRAMES;
            if (wait > 0 && --wait == 0) next = 0;
            return;
        }
        frame++;
        play(player);
        if (finished() || frame >= MAX_FRAMES) {
            if (frame >= MAX_FRAMES) check(false, "сценарий: " + id() + " " + PARTS[part] + " не кончился за " + MAX_FRAMES + " кадров");
            Trace.recording = false;
            if (part + 1 < PARTS.length) next = part + 1;
            else if (++gun < guns.size()) give();
            else end();
        }
    }

    // a part begins: the view straight, not aiming, the seeds and the trace's time from zero
    private static void begin(int p) {
        part = p;
        frame = 0;
        EntityClientPlayerMP player = Minecraft.getMinecraft().thePlayer;
        look(player, 0F, 0F);
        // port: 26.2's xBob/yBob are renderArmPitch/renderArmYaw here
        player.renderArmPitch = player.prevRenderArmPitch = player.renderArmYaw = player.prevRenderArmYaw = 0F;
        aim(0F);
        Rng.seed(Trace.DEFAULT_SEED + gun * 100L + p);
        origin = FixedStep.now();
        Trace.recording = true;
        TestLog.c(-1, "segment", "gun", id(), "part", PARTS[p]);
        play(player);
    }

    private static void play(EntityClientPlayerMP player) {
        int f = frame;
        String p = PARTS[part];
        if (p.equals("equip")) {
            if (f == 0) sedna(player, GunAnimation.EQUIP, 0);
        } else if (p.equals("turn")) {
            // 60 degrees right and 30 up over 30 frames, held, then back: the sway's input
            float k = f < 30 ? f / 30F : f < 60 ? 1F : Math.max(0F, 1F - (f - 60) / 30F);
            look(player, -30F * k, 60F * k);
        } else if (p.equals("ads")) {
            aim(f < 20 ? f / 20F : f < 40 ? 1F : Math.max(0F, 1F - (f - 40) / 20F));
        } else if (p.equals("shot")) {
            if (f == 0) sedna(player, GunAnimation.CYCLE, 0);
        } else if (p.equals("burst")) {
            if (f == 0 || f == 10 || f == 20) sedna(player, GunAnimation.CYCLE, f);
        } else if (p.equals("reload")) {
            if (f == 0) sedna(player, GunAnimation.RELOAD, 0);
        } else if (p.equals("inspect")) {
            if (f == 0) sedna(player, GunAnimation.INSPECT, 0);
        }
    }

    private static boolean finished() {
        int f = frame;
        String p = PARTS[part];
        if (p.equals("idle")) return f >= 30;
        if (p.equals("turn")) return f >= 100;
        if (p.equals("ads")) return f >= 70;
        if (p.equals("burst")) return f >= 30 && settled();
        return f >= 10 && settled();
    }

    // NTM's animation over, the kick and the camera back
    private static boolean settled() {
        HbmAnimations.Animation anim = HbmAnimations.getRelevantAnim(0);
        boolean over = anim == null || anim.animation == null || ntmNow() - anim.startMillis > anim.animation.getDuration();
        return over && ModelRecoil.idle() && CameraRecoil.idle();
    }

    // NTM's animation as from the server's packet; a shot's random draws (the gun's recoil, NTM's flash) seeded
    private static void sedna(EntityClientPlayerMP player, GunAnimation type, int k) {
        long seed = Trace.DEFAULT_SEED + gun * 10_000L + part * 100L + k;
        player.getRNG().setSeed(seed);
        player.worldObj.rand.setSeed(seed);
        TestLog.c(-1, "anim", "type", type);
        int slot = player.inventory.currentItem;
        HbmAnimationPacket.Handler.handleSedna(player, player.getHeldItem(), slot, type, 0, 0);
        // port: NTM 1.7.10 stamps the start with the wall clock; the fixed one instead, as 26.2's GameTime has it
        HbmAnimations.Animation anim = HbmAnimations.hotbar[slot][0];
        if (anim != null) anim.startMillis = ntmNow();
    }

    private static void look(EntityClientPlayerMP player, float pitch, float yaw) {
        player.rotationPitch = player.prevRotationPitch = pitch;
        player.rotationYaw = player.prevRotationYaw = yaw;
    }

    private static void aim(float k) {
        ItemGunBaseNT.prevAimingProgress = ItemGunBaseNT.aimingProgress = k;
    }

    private static String id() {
        return GunFeelClient.gunId(guns.get(gun));
    }

    // port: 26.2's ClientSmoke.check: a failed check goes to the log and is counted for the end
    private static void check(boolean ok, String what) {
        if (ok) GunFeel.LOGGER.info("OK {}", what);
        else {
            failed++;
            GunFeel.LOGGER.error("FAIL {}", what);
        }
    }

    private static void end() {
        done = true;
        Trace.recording = false;
        FixedStep.stop();
        GunClock.use(null);
        check(!guns.isEmpty(), "сценарий " + NAME + ": пушек сыграно " + guns.size());
        GunFeel.LOGGER.info("Scenario {} done: {} failed, trace {}", NAME, failed, TestLog.file());
        Minecraft.getMinecraft().shutdown();
    }
}
