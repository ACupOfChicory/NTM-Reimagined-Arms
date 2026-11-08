package acup.gunfeel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

// runCheckClient -PgasStand=<dir> -PgunWeight: the heavy guns' slowing in numbers (<dir>/weight.txt). The player in
// survival walks and then runs 2 s down a concrete strip with nothing, a light, a medium, a heavy and a super-heavy gun
// in the main hand, two super-heavy ones (both hands), a heavy one in the off hand alone, and a super-heavy one in
// creative; the speed on the client (blocks a second, by the way it went) and the attribute on the server, each against
// the empty hand. Then the slot changed from the minigun to an empty one: the modifier gone on both sides within a few
// ticks. Then every gun of the data file: its tooltip has the line with its kind and weight (and the slowing for the heavy).
final class GunWeightStand {
    static final boolean ON = Boolean.getBoolean("ntmgunfeel.gunWeight");
    private static final int Y = 100;
    private static final List<String> OUT = new ArrayList<>();

    private record Case(String name, String main, String off, boolean creative, float want) {
    }

    private static final List<Case> CASES = List.of(
            new Case("empty", null, null, false, 1F),
            new Case("light uzi", "hbm:gun_uzi", null, false, 1F),
            new Case("medium g3", "hbm:gun_g3", null, false, 1F),
            new Case("heavy drill", "hbm:gun_drill", null, false, GunWeight.HEAVY),
            new Case("super-heavy minigun", "hbm:gun_minigun", null, false, GunWeight.SUPER_HEAVY),
            new Case("two super-heavy", "hbm:gun_minigun", "hbm:gun_tesla_cannon", false, GunWeight.SUPER_HEAVY),
            new Case("heavy in the off hand", null, "hbm:gun_missile_launcher", false, GunWeight.HEAVY),
            new Case("super-heavy in creative", "hbm:gun_minigun", null, true, 1F));

    private static int c, phase, run;
    private static long at, startTick;
    private static double startZ;
    private static final double[][] SPEED = new double[CASES.size()][2];
    private static final double[] ATTR = new double[CASES.size()], FOV = new double[CASES.size()];
    private static boolean done;

    private GunWeightStand() {
    }

    private static MinecraftServer server() {
        return Minecraft.getInstance().getSingleplayerServer();
    }

    private static void line(String s) {
        OUT.add(s);
        GunFeel.LOGGER.info("[gunweight] {}", s);
    }

    private static long now() {
        var l = Minecraft.getInstance().level;
        return l == null ? 0 : l.getGameTime();
    }

    private static ItemStack stack(String id) {
        if (id == null) return ItemStack.EMPTY;
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
        return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
    }

    static void start() {
        Minecraft mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;
        server().submit(() -> {
            var level = server().overworld();
            var rules = level.getGameRules();
            rules.set(net.minecraft.world.level.gamerules.GameRules.SPAWN_MOBS, false, server());
            rules.set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME, false, server());
            for (int x = -4; x <= 4; x++) for (int z = -10; z <= 60; z++) {
                level.setBlock(new BlockPos(x, Y - 1, z), Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.GRAY).defaultBlockState(), 2);
                for (int y = Y; y <= Y + 3; y++) level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
            }
        }).join();
        phase = 0;
        at = now() + 20;
    }

    // the player at the strip's start, this case's guns in hand
    private static void place(Case k) {
        server().submit(() -> {
            ServerPlayer p = server().getPlayerList().getPlayers().getFirst();
            p.setGameMode(k.creative ? GameType.CREATIVE : GameType.SURVIVAL);
            p.getAbilities().flying = false;
            p.onUpdateAbilities();
            p.getInventory().setSelectedSlot(0);
            p.setItemInHand(InteractionHand.MAIN_HAND, stack(k.main));
            p.setItemInHand(InteractionHand.OFF_HAND, stack(k.off));
            p.getFoodData().setFoodLevel(20);
            p.teleportTo(server().overworld(), 0.5, Y, -8.5, java.util.Set.of(), 0F, 0F, false);
            p.setDeltaMovement(0, 0, 0);
        }).join();
    }

    private static double serverSpeed() {
        return server().submit(() -> server().getPlayerList().getPlayers().getFirst().getAttributeValue(Attributes.MOVEMENT_SPEED)).join();
    }

    static void frame() {
        if (done) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || now() < at) return;
        try {
            switch (phase) {
                case 0 -> {
                    // a case, walking (run 0) or running (run 1)
                    place(CASES.get(c));
                    mc.player.setYRot(0F);
                    mc.player.setXRot(0F);
                    at = now() + 15;
                    phase = 1;
                }
                case 1 -> {
                    if (!mc.player.onGround()) return;
                    if (run == 0) {
                        ATTR[c] = serverSpeed();
                        // standing: the view as wide as with nothing in hand (GunWeight.fov)
                        FOV[c] = mc.player.getFieldOfViewModifier(true, 1F);
                    }
                    mc.player.setYRot(0F);
                    mc.options.keyUp.setDown(true);
                    mc.options.keySprint.setDown(run == 1);
                    at = now() + 15;
                    phase = 2;
                }
                case 2 -> {
                    startZ = mc.player.getZ();
                    startTick = now();
                    at = now() + 40;
                    phase = 3;
                }
                case 3 -> {
                    SPEED[c][run] = (mc.player.getZ() - startZ) / ((now() - startTick) / 20.0);
                    mc.options.keyUp.setDown(false);
                    mc.options.keySprint.setDown(false);
                    at = now() + 5;
                    if (run == 0) run = 1;
                    else {
                        run = 0;
                        c++;
                    }
                    phase = c < CASES.size() ? 0 : 4;
                }
                case 4 -> {
                    report();
                    // the slot: the minigun in slot 0, nothing in slot 1
                    place(CASES.get(4));
                    at = now() + 10;
                    phase = 5;
                }
                case 5 -> {
                    boolean[] h = has(mc);
                    boolean before = h[0] && h[1];
                    mc.player.getInventory().setSelectedSlot(1);
                    startTick = now();
                    line("slot: minigun in hand, modifier on server and client " + before);
                    ClientSmoke.check(before, "тяжесть: с миниганом в руке замедление есть");
                    phase = 6;
                }
                case 6 -> {
                    boolean[] h = has(mc);
                    if ((h[0] || h[1]) && now() - startTick < 40) return;
                    long took = now() - startTick;
                    line("slot: changed to an empty one, the modifier gone after " + took + " ticks (server " + h[0] + ", client " + h[1] + ")");
                    ClientSmoke.check(took <= 5 && !h[0] && !h[1], "тяжесть: замедление снялось при смене слота за " + took + " тиков");
                    phase = 7;
                }
                case 7 -> {
                    tooltips(mc);
                    finish();
                }
                default -> finish();
            }
        } catch (RuntimeException e) {
            GunFeel.LOGGER.error("[gunweight]", e);
            ClientSmoke.check(false, "тяжесть: " + e);
            finish();
        }
    }

    // the modifier on the server's player and on the client's
    private static boolean[] has(Minecraft mc) {
        boolean s = server().submit(() -> server().getPlayerList().getPlayers().getFirst().getAttribute(Attributes.MOVEMENT_SPEED).getModifier(GunWeight.MODIFIER) != null).join();
        var a = mc.player.getAttribute(Attributes.MOVEMENT_SPEED);
        return new boolean[] {s, a != null && a.getModifier(GunWeight.MODIFIER) != null};
    }

    private static void report() {
        double walk0 = SPEED[0][0], run0 = SPEED[0][1], attr0 = ATTR[0];
        for (int i = 0; i < CASES.size(); i++) {
            Case k = CASES.get(i);
            double w = SPEED[i][0] / walk0, r = SPEED[i][1] / run0, a = ATTR[i] / attr0;
            line(String.format(Locale.ROOT, "%s: walk %.3f b/s (%.3f), run %.3f b/s (%.3f), attribute %.4f (%.3f); want %.2f; view standing %.4f",
                    k.name, SPEED[i][0], w, SPEED[i][1], r, ATTR[i], a, k.want, FOV[i]));
            if (i == 0) continue;
            ClientSmoke.check(Math.abs(FOV[i] - FOV[0]) < 0.002, "тяжесть: " + k.name + " вид не сузился " + FOV[i] + " / " + FOV[0]);
            ClientSmoke.check(Math.abs(a - k.want) < 0.002, "тяжесть: " + k.name + " атрибут " + a);
            ClientSmoke.check(Math.abs(w - k.want) < 0.02 && Math.abs(r - k.want) < 0.02, "тяжесть: " + k.name + " ходьба " + w + ", бег " + r);
        }
    }

    private static void tooltips(Minecraft mc) {
        int shown = 0, missing = 0, bad = 0;
        StringBuilder wrong = new StringBuilder(), absent = new StringBuilder();
        for (var e : GunWeight.all().entrySet()) {
            ItemStack s = stack(e.getKey());
            if (s.isEmpty()) {
                missing++;
                absent.append(e.getKey()).append(' ');
                continue;
            }
            // the very line, kind, class and slowing, among the tooltip's lines
            List<String> lines = s.getTooltipLines(Item.TooltipContext.of(mc.level), mc.player, TooltipFlag.Default.NORMAL).stream().map(Component::getString).toList();
            String want = GunWeight.text(e.getValue()).getString();
            boolean ok = lines.contains(want) && (GunWeight.speed(e.getValue().weight()) == 1F || want.contains("%"));
            if (shown == 0 && ok) line("tooltip of " + e.getKey() + ": " + want);
            if (ok) shown++;
            else {
                bad++;
                if (wrong.length() < 400) wrong.append(e.getKey()).append(' ');
            }
        }
        line("tooltips: " + shown + " with their line, " + bad + " without " + wrong + "; " + missing + " not in this NTM: " + absent);
        // the file may list guns this NTM has not got (other versions): those are named, not counted against it
        ClientSmoke.check(bad == 0 && shown > 0, "тяжесть: подсказка у всех пушек из файла, что есть в этой NTM " + shown + "/" + bad + " " + wrong);
    }

    private static void finish() {
        if (done) return;
        done = true;
        try {
            Files.write(Path.of(GasStand.DIR, "weight.txt"), OUT);
        } catch (Exception e) {
            ClientSmoke.check(false, "тяжесть: запись " + e);
        }
        ClientSmoke.finish();
    }
}
