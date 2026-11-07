package acup.gunfeel;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.mags.IMagazine;
import com.hbm.items.weapon.sedna.mags.MagazineSingleTypeBase;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

// runCheckClient -PhudShots: real frames of the mod's corner HUD (Hud) and the open ammo menu (RadialMenu) with
// guns in hand, for the GL-54 HUD mock-up. Per shot two screenshots of the same still scene: <name>.png with the
// HUD and <name>-clean.png with the GUI hidden (the difference is the HUD alone), and a line in meta.tsv.
// The silhouettes the mod draws are dumped as sil-<gun>.png (192 x 64, Silhouette.render); -PhudShotsGl54=<file> is
// quads of another model in the aligned frame (15 floats each, little-endian), run through the same Silhouette code
// into sil-gl54.png.
final class HudShots {
    static final boolean ENABLED = Boolean.getBoolean("ntmgunfeel.hudShots");
    private static final Path DIR = Path.of(System.getProperty("ntmgunfeel.hudShotsDir", "../notes/hud-shots"));
    private static final BlockPos O = new BlockPos(8, 100, 8);

    // scale: GUI scale (0 = auto); menu: the ammo menu open, hovering sector `hover`
    private record Shot(String name, String gun, int amount, int scale, boolean lock, boolean menu, int hover, int[] ammo) {
    }

    private static final List<Shot> SHOTS = List.of(
            new Shot("congolake-full", "hbm:gun_congolake", -1, 3, false, false, -1, new int[] {12, 5, 8, 3, 6}),
            new Shot("mk108-full", "hbm:gun_mk108", -1, 3, false, false, -1, new int[] {64, 20, 30}),
            new Shot("g3-full", "hbm:gun_g3", -1, 3, false, false, -1, new int[] {120, 40, 25, 60}),
            new Shot("g3-low", "hbm:gun_g3", 3, 3, false, false, -1, new int[] {120, 40, 25, 60}),
            new Shot("g3-lock", "hbm:gun_g3", -1, 3, true, false, -1, new int[] {120, 40, 25, 60}),
            new Shot("congolake-auto", "hbm:gun_congolake", -1, 0, false, false, -1, new int[] {12, 5, 8, 3, 6}),
            new Shot("menu-congolake", "hbm:gun_congolake", -1, 3, false, true, 1, new int[] {12, 5, 8, 3, 6}),
            new Shot("menu-g3", "hbm:gun_g3", -1, 3, false, true, 1, new int[] {120, 40, 25, 60}));

    private static final Deque<BooleanSupplier> TODO = new ArrayDeque<>();
    private static final List<String> META = new ArrayList<>();
    // what Hud leaves out for a shot: 1 the gun's picture, 2 the whole block, 3 the ammo menu's counts (the same scene without it)
    static volatile int skip;
    private static boolean started;
    private static int wait;
    private static volatile int pending;

    private HudShots() {
    }

    static void start() {
        Minecraft mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;
        var server = mc.getSingleplayerServer();
        server.submit(() -> {
            ServerLevel level = server.overworld();
            for (int x = -12; x <= 12; x++) for (int z = -4; z <= 14; z++) {
                level.setBlock(O.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 3);
                for (int y = 0; y <= 12; y++) level.setBlock(O.offset(x, y, z), z == 10 ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
            }
        }).join();
        try {
            Files.createDirectories(DIR);
        } catch (java.io.IOException e) {
            ClientSmoke.check(false, "hudshots: папка " + e);
        }
    }

    static void frame() {
        if (!started) {
            started = true;
            // the sun and the light settle before the first shot
            TODO.add(() -> {
                noon(Minecraft.getInstance());
                return true;
            });
            sleep(600);
            for (Shot s : SHOTS) plan(s);
            TODO.add(HudShots::done);
        }
        if (wait > 0) {
            wait--;
            return;
        }
        var next = TODO.peek();
        if (next != null && next.getAsBoolean()) TODO.poll();
    }

    private static void sleep(int frames) {
        TODO.add(() -> {
            wait = frames;
            return true;
        });
    }

    private static void plan(Shot s) {
        var mc = Minecraft.getInstance();
        TODO.add(() -> {
            hide(mc, false);
            RadialMenu.close();
            mc.options.guiScale().set(s.scale);
            noon(mc);
            giveGun(mc, s);
            return true;
        });
        // the gun is in the hand, drawn, and its silhouette is made (it is, after the first frame in first person)
        TODO.add(() -> mc.player.getMainHandItem().getItem() instanceof ItemGunBaseNT
                && Silhouette.get(mc.player.getMainHandItem().getItem()) != null
                && mc.getWindow().getGuiScale() == (s.scale == 0 ? autoScale(mc) : s.scale));
        sleep(150); // the draw animation is over
        if (s.menu) {
            TODO.add(() -> {
                ItemStack stack = mc.player.getMainHandItem();
                ClientSmoke.check(RadialMenu.open(stack), s.name + ": меню открылось");
                int n = RadialMenu.choices(stack).size();
                double a = Math.PI * 2 * s.hover / n;
                RadialMenu.move(Math.sin(a) * 400, -Math.cos(a) * 400);
                META.add("#" + s.name + " sectors " + n);
                return true;
            });
            sleep(60);
        }
        TODO.add(() -> {
            shot(s.name + ".png");
            return true;
        });
        TODO.add(() -> pending == 0);
        for (int k = 1; k <= (s.menu ? 3 : 2); k++) {
            int mode = k;
            TODO.add(() -> {
                skip = mode;
                return true;
            });
            sleep(4);
            TODO.add(() -> {
                shot(s.name + (mode == 1 ? "-nosil.png" : mode == 2 ? "-bare.png" : "-nonum.png"));
                return true;
            });
            TODO.add(() -> pending == 0);
        }
        TODO.add(() -> {
            skip = 0;
            hide(mc, true);
            return true;
        });
        sleep(6);
        TODO.add(() -> {
            shot(s.name + "-clean.png");
            return true;
        });
        TODO.add(() -> {
            if (pending != 0) return false;
            hide(mc, false);
            meta(s);
            return true;
        });
    }

    private static void noon(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        server.submit(() -> {
            for (String c : new String[] {"time set noon", "weather clear"}) {
                try {
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), c);
                } catch (RuntimeException e) {
                    GunFeel.LOGGER.warn("[hudshots] {}: {}", c, e.toString());
                }
            }
        }).join();
    }

    // F1: the game's whole HUD (and this mod's, drawn in its event) hidden
    private static void hide(Minecraft mc, boolean hidden) {
        if (mc.gui.hud.isHidden() != hidden) mc.gui.hud.toggle();
    }

    private static int autoScale(Minecraft mc) {
        int w = mc.getWindow().getWidth(), h = mc.getWindow().getHeight();
        int k = 1;
        while (k < 64 && w / (k + 1) >= 320 && h / (k + 1) >= 240) k++;
        return k;
    }

    private static void giveGun(Minecraft mc, Shot s) {
        var server = mc.getSingleplayerServer();
        server.submit(() -> {
            ServerLevel level = server.overworld();
            ServerPlayer p = server.getPlayerList().getPlayers().getFirst();
            p.setGameMode(GameType.CREATIVE);
            p.getAbilities().flying = true;
            p.onUpdateAbilities();
            p.teleportTo(level, O.getX() + 0.5, O.getY(), O.getZ() + 0.5, Set.of(), 0F, 0F, false);
            p.setDeltaMovement(0, 0, 0);
            p.getInventory().clearContent();
            var item = BuiltInRegistries.ITEM.get(Identifier.parse(s.gun)).orElseThrow().value();
            ItemStack stack = new ItemStack(item);
            ItemGunBaseNT gun = (ItemGunBaseNT) item;
            IMagazine<?> mag = AmmoLock.primary(stack);
            var types = AmmoLock.accepted(mag);
            StringBuilder ids = new StringBuilder();
            for (int i = 0; i < types.size(); i++) {
                BulletConfig t = types.get(i);
                ids.append(t.id).append(':').append(t.ammoStack().getHoverName().getString()).append(' ');
                if (i < s.ammo.length) p.getInventory().setItem(9 + i, t.ammoStack().copyWithCount(s.ammo[i]));
            }
            GunFeel.LOGGER.info("[hudshots] {} accepted: {}", s.name, ids);
            if (mag instanceof MagazineSingleTypeBase single) {
                single.setType(stack, types.get(0));
                single.setAmount(stack, s.amount >= 0 ? s.amount : single.getCapacity(stack));
            }
            if (s.lock) ItemGunBaseNT.setValueInt(stack, AmmoLock.KEY, 1);
            ItemGunBaseNT.setIsDrawn(stack, true);
            ItemGunBaseNT.setIsEquipped(stack, true);
            ItemGunBaseNT.setState(stack, 0, ItemGunBaseNT.GunState.IDLE);
            ItemGunBaseNT.setTimer(stack, 0, 0);
            p.getInventory().setSelectedSlot(0);
            p.setItemInHand(InteractionHand.MAIN_HAND, stack);
        }).join();
    }

    private static void meta(Shot s) {
        var mc = Minecraft.getInstance();
        ItemStack stack = mc.player.getMainHandItem();
        IMagazine<?> mag = AmmoLock.primary(stack);
        int amount = mag.getAmount(stack, mc.player.getInventory());
        int cap = mag instanceof MagazineSingleTypeBase single ? single.getCapacity(stack) : -1;
        META.add(String.join("\t", s.name, s.gun, "window", mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight(),
                "guiScale", String.valueOf(mc.getWindow().getGuiScale()), "gui", mc.getWindow().getGuiScaledWidth() + "x" + mc.getWindow().getGuiScaledHeight(),
                "amount", String.valueOf(amount), "capacity", String.valueOf(cap), "locked", String.valueOf(AmmoLock.locked(stack)),
                "menu", String.valueOf(RadialMenu.isOpen())));
    }

    private static void shot(String name) {
        var mc = Minecraft.getInstance();
        pending++;
        net.minecraft.client.Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                image.writeToFile(DIR.resolve(name));
                META.add("#file " + name + " " + image.getWidth() + "x" + image.getHeight());
            } catch (java.io.IOException e) {
                GunFeel.LOGGER.warn("[hudshots] кадр не записан", e);
            } finally {
                pending--;
            }
        });
    }

    // the silhouettes as the mod makes them, and the model of -PhudShotsGl54 through the same code
    private static void silhouettes() throws Exception {
        for (String gun : new String[] {"gun_congolake", "gun_mk108", "gun_g3"}) {
            var item = BuiltInRegistries.ITEM.get(Identifier.parse("hbm:" + gun)).orElseThrow().value();
            GunGeometry geo = Arms.geometry(item);
            if (geo == null) {
                ClientSmoke.check(false, "hudshots: нет геометрии " + gun);
                continue;
            }
            try (var image = Silhouette.render(geo)) {
                image.writeToFile(DIR.resolve("sil-" + gun.substring(4) + ".png"));
            }
        }
        String tx = System.getProperty("ntmgunfeel.hudShotsGl54", "");
        if (!tx.isEmpty()) {
            ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(Path.of(tx))).order(ByteOrder.LITTLE_ENDIAN);
            float[] q = new float[b.remaining() / 4];
            b.asFloatBuffer().get(q);
            try (var image = Silhouette.rasterize(q)) {
                image.writeToFile(DIR.resolve("sil-gl54.png"));
            }
            GunFeel.LOGGER.info("[hudshots] GL-54: {} четырёхугольников", q.length / 15);
        }
    }

    private static boolean done() {
        try {
            silhouettes();
            Files.write(DIR.resolve("meta.tsv"), META);
        } catch (Exception e) {
            ClientSmoke.check(false, "hudshots: " + e);
        }
        ClientSmoke.check(true, "hudshots: кадры в " + DIR);
        ClientSmoke.finish();
        return true;
    }
}
