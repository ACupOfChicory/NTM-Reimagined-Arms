package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.render.anim.AnimationEnums.GunAnimation;
import com.hbm.render.anim.HbmAnimations;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

// A trace scenario on the real client (runCheckClient -Ptrace -Pscenario=arms): each gun is put in hand by the
// server, then played on the client alone, frame by frame with the timer fixed (FixedStep): draw, rest, a turn of the
// view, aiming in and out, one shot, a burst of three, reload and inspect. NTM plays each animation as from the
// server's packet (HbmAnimationPayload.handleSedna, which also runs the gun's recoil); the mod draws arms, kick and
// camera as in a game. Only the client's lines while a part plays go into the trace, with the time since the part
// began: two runs give the same trace (tools/trace_diff.py), and a port's run the same numbers.
//   -Dntmgunfeel.scenario=arms            on (with -Dntmgunfeel.trace)
//   -Dntmgunfeel.scenarioGuns=<id,...>    the guns (default: GUNS); "all": every NTM gun of the game
final class TraceScenario {
    private static final String NAME = System.getProperty("ntmgunfeel.scenario");
    static final String GUNS = "hbm:gun_uzi,hbm:gun_g3,hbm:gun_henry,hbm:gun_light_revolver,hbm:gun_spas12,hbm:gun_maresleg";
    private static final String[] PARTS = {"equip", "idle", "turn", "ads", "shot", "burst", "reload", "inspect"};
    private static final int MAX_FRAMES = 1500, SETTLE_FRAMES = 250;

    private static final List<Item> guns = new ArrayList<>();
    private static int gun, part, frame, wait, next = -1;
    private static long origin;
    private static boolean given, done;

    private TraceScenario() {
    }

    static boolean enabled() {
        return Trace.ON && NAME != null && !NAME.isBlank();
    }

    static void start() {
        String list = System.getProperty("ntmgunfeel.scenarioGuns", GUNS).trim();
        if (list.equals("all")) {
            BuiltInRegistries.ITEM.keySet().stream().filter(k -> k.getNamespace().equals("hbm")).sorted()
                    .map(k -> BuiltInRegistries.ITEM.getValue(k)).filter(i -> i instanceof ItemGunBaseNT).forEach(guns::add);
        } else {
            for (String id : list.split(",")) {
                var item = BuiltInRegistries.ITEM.get(Identifier.parse(id.trim()));
                if (item.isPresent() && item.get().value() instanceof ItemGunBaseNT) guns.add(item.get().value());
                else ClientSmoke.check(false, "сценарий: нет пушки " + id);
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
        // NTM's animations run on the same frames: its clock (com.hbm.util.GameTime) from the fixed one, going on from
        // where it was; its tick clock on the ticks' frames
        long ntm = com.hbm.util.GameTime.now() - FixedStep.now();
        com.hbm.util.GameTime.CLIENT_NOW = () -> FixedStep.now() + ntm;
        com.hbm.util.GameTime.CLIENT_MILLIS = () -> FixedStep.now() + ntm - Math.floorMod(FixedStep.now() - origin, 50L);
        GunClock.use(() -> FixedStep.now() * 1_000_000L);
    }

    // the server puts the gun in hand, drawn and idle; the client waits for it and for the server's draw to end
    private static void give() {
        given = false;
        Item item = guns.get(gun);
        var server = Minecraft.getInstance().getSingleplayerServer();
        server.execute(() -> {
            ServerPlayer p = server.getPlayerList().getPlayers().getFirst();
            p.setGameMode(GameType.CREATIVE);
            p.getAbilities().flying = true;
            p.onUpdateAbilities();
            p.teleportTo(server.overworld(), 8.5, 110, 8.5, java.util.Set.of(), 0, 0, false);
            ItemStack stack = new ItemStack(item);
            ItemGunBaseNT.setIsDrawn(stack, true);
            ItemGunBaseNT.setIsEquipped(stack, true);
            ItemGunBaseNT.setState(stack, 0, ItemGunBaseNT.GunState.IDLE);
            ItemGunBaseNT.setTimer(stack, 0, 0);
            p.getInventory().setSelectedSlot(0);
            p.setItemInHand(InteractionHand.MAIN_HAND, stack);
            given = true;
        });
        wait = -1;
    }

    static void frame() {
        if (done) return;
        Minecraft mc = Minecraft.getInstance();
        var player = mc.player;
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
            if (wait < 0 && given && player.getMainHandItem().getItem() == guns.get(gun)) wait = SETTLE_FRAMES;
            if (wait > 0 && --wait == 0) next = 0;
            return;
        }
        frame++;
        play(mc, player);
        if (finished() || frame >= MAX_FRAMES) {
            if (frame >= MAX_FRAMES) ClientSmoke.check(false, "сценарий: " + id() + " " + PARTS[part] + " не кончился за " + MAX_FRAMES + " кадров");
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
        var player = Minecraft.getInstance().player;
        look(player, 0F, 0F);
        player.xBob = player.xBobO = player.yBob = player.yBobO = 0F;
        aim(0F);
        Rng.seed(Trace.DEFAULT_SEED + gun * 100L + p);
        origin = FixedStep.now();
        Trace.recording = true;
        TestLog.c(-1, "segment", "gun", id(), "part", PARTS[p]);
        play(Minecraft.getInstance(), player);
    }

    private static void play(Minecraft mc, net.minecraft.client.player.LocalPlayer player) {
        int f = frame;
        switch (PARTS[part]) {
            case "equip" -> { if (f == 0) sedna(player, GunAnimation.EQUIP, 0); }
            case "turn" -> {
                // 60 degrees right and 30 up over 30 frames, held, then back: the sway's input
                float k = f < 30 ? f / 30F : f < 60 ? 1F : Math.max(0F, 1F - (f - 60) / 30F);
                look(player, -30F * k, 60F * k);
            }
            case "ads" -> aim(f < 20 ? f / 20F : f < 40 ? 1F : Math.max(0F, 1F - (f - 40) / 20F));
            case "shot" -> { if (f == 0) sedna(player, GunAnimation.CYCLE, 0); }
            case "burst" -> { if (f == 0 || f == 10 || f == 20) sedna(player, GunAnimation.CYCLE, f); }
            case "reload" -> { if (f == 0) sedna(player, GunAnimation.RELOAD, 0); }
            case "inspect" -> { if (f == 0) sedna(player, GunAnimation.INSPECT, 0); }
            default -> { }
        }
    }

    private static boolean finished() {
        int f = frame;
        return switch (PARTS[part]) {
            case "idle" -> f >= 30;
            case "turn" -> f >= 100;
            case "ads" -> f >= 70;
            case "burst" -> f >= 30 && settled();
            default -> f >= 10 && settled();
        };
    }

    // NTM's animation over, the kick and the camera back
    private static boolean settled() {
        HbmAnimations.Animation anim = HbmAnimations.getRelevantAnim(0);
        boolean over = anim == null || anim.animation == null
                || com.hbm.util.GameTime.now() - anim.startMillis > anim.animation.getDuration();
        return over && ModelRecoil.idle() && CameraRecoil.idle();
    }

    // NTM's animation as from the server's packet; a shot's random draws (the gun's recoil, NTM's flash) seeded
    private static void sedna(net.minecraft.client.player.LocalPlayer player, GunAnimation type, int k) {
        long seed = Trace.DEFAULT_SEED + gun * 10_000L + part * 100L + k;
        player.getRandom().setSeed(seed);
        player.level().getRandom().setSeed(seed);
        TestLog.c(-1, "anim", "type", type);
        com.hbm.packet.toclient.HbmAnimationPayload.handleSedna(player, player.getMainHandItem(), player.getInventory().getSelectedSlot(), type, 0, 0);
    }

    private static void look(net.minecraft.client.player.LocalPlayer player, float pitch, float yaw) {
        player.setXRot(pitch);
        player.xRotO = pitch;
        player.setYRot(yaw);
        player.yRotO = yaw;
    }

    private static void aim(float k) {
        ItemGunBaseNT.prevAimingProgress = ItemGunBaseNT.aimingProgress = k;
    }

    private static String id() {
        return BuiltInRegistries.ITEM.getKey(guns.get(gun)).toString();
    }

    private static void end() {
        done = true;
        Trace.recording = false;
        FixedStep.stop();
        GunClock.use(null);
        com.hbm.client.ClientGameTime.install();
        ClientSmoke.check(!guns.isEmpty(), "сценарий " + NAME + ": пушек сыграно " + guns.size());
        ClientSmoke.finish();
    }
}
