package acup.gunfeel;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import com.hbm.client.ClientRegistry;
import com.hbm.client.Keybinds;
import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.handler.HbmKeybinds.EnumKeybind;
import com.hbm.items.ModItems;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.factory.XFactory9mm;
import com.hbm.packet.PacketWire;
import com.hbm.packet.toserver.KeybindPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

// runCheckClient -PspectatorStand: may a player in spectator mode use an NTM gun (spectatorNoGuns, SpectatorGuns)?
// An Uzi with 9 mm AP in a flat world; each case holds the fire key for half a second at a zombie (lane +Z) or at a
// glass wall (lane -Z) and counts: bullets the server spawned, rounds gone from the magazine, the zombie's health
// lost, the glass, shots that reached the client (the gun's lastShot: recoil and the arms' animation follow it), the
// mod's corner HUD drawn, NTM's crosshair in place of the game's, the first-person hand drawn. Cases: creative (the
// control), spectator with the rule (the client's key; NTM's key packet sent straight, as a client without the mod
// would), spectator with the rule off (NTM's own behaviour), and the key held while the mode changes (counted from
// that tick). Then what goes on after the mode changes, each with the rule off for the control: a reload begun in
// creative (the magazine and the rounds in the inventory; with the rule, back in creative the gun fires again), the
// second hit of a power armor's swing (NCRPA's fist at a husk two blocks off), and the mod's ammo choice and lock
// packets sent by a spectator.
// Lines "[spectator] ..." in the log, PASS/FAIL in the report.
final class SpectatorStand {
    static final boolean ENABLED = Boolean.getBoolean("ntmgunfeel.spectatorStand");
    private static final BlockPos O = new BlockPos(8, 100, 8);
    // the eye (1.62 up) is at the husk's head
    private static final double EX = 8.5, EY = 100, EZ = 8.5;

    // KEY, PACKET: the fire key held; SWITCH: held while the mode changes; RELOAD, MELEE: a reload or a power armor's
    // swing begun in creative, then the mode changes; AMMO: the mod's ammo choice and lock packets
    private enum How { KEY, PACKET, SWITCH, RELOAD, MELEE, AMMO;
        boolean own() { return this == RELOAD || this == MELEE || this == AMMO; }
    }

    private record Case(String name, GameType mode, boolean rule, How how, boolean mob, boolean shoots) {
    }

    private static final List<Case> CASES = List.of(
            new Case("креатив, моб", GameType.CREATIVE, true, How.KEY, true, true),
            new Case("креатив, стекло", GameType.CREATIVE, true, How.KEY, false, true),
            new Case("наблюдатель, клавиша, моб", GameType.SPECTATOR, true, How.KEY, true, false),
            new Case("наблюдатель, клавиша, стекло", GameType.SPECTATOR, true, How.KEY, false, false),
            new Case("наблюдатель, пакет NTM, моб", GameType.SPECTATOR, true, How.PACKET, true, false),
            new Case("наблюдатель, пакет NTM, стекло", GameType.SPECTATOR, true, How.PACKET, false, false),
            new Case("наблюдатель без правила (как NTM), моб", GameType.SPECTATOR, false, How.KEY, true, true),
            new Case("наблюдатель без правила (как NTM), стекло", GameType.SPECTATOR, false, How.KEY, false, true),
            new Case("зажата в креативе, стал наблюдателем", GameType.CREATIVE, true, How.SWITCH, true, false),
            new Case("перезарядка начата в креативе, стал наблюдателем", GameType.CREATIVE, true, How.RELOAD, true, false),
            new Case("перезарядка начата в креативе, без правила (как NTM)", GameType.CREATIVE, false, How.RELOAD, true, true),
            new Case("удар силовой брони начат в креативе, стал наблюдателем", GameType.CREATIVE, true, How.MELEE, true, false),
            new Case("удар силовой брони начат в креативе, без правила (как NTM)", GameType.CREATIVE, false, How.MELEE, true, true),
            new Case("выбор и замок патронов", GameType.SPECTATOR, true, How.AMMO, true, false),
            new Case("выбор и замок патронов, без правила", GameType.SPECTATOR, false, How.AMMO, true, true));

    private static int index, step, until;
    private static volatile int bullets;
    private static int crosshair, hands, ammoBefore;
    private static long shotBefore;
    private static float healthBefore;
    private static int magBefore, invBefore, typeBefore;
    private static boolean lockBefore;
    // a husk: a zombie burns in the sun and loses health with no shot
    private static Husk zombie;
    private static Method gunChanged;
    private static String fileBefore;
    private static boolean fileExisted;

    private SpectatorStand() {
    }

    static void start() {
        Minecraft mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;
        try {
            gunChanged = Keybinds.class.getDeclaredMethod("gunChanged", int.class, boolean.class);
            gunChanged.setAccessible(true);
            Path f = file();
            fileExisted = Files.exists(f);
            fileBefore = fileExisted ? Files.readString(f) : null;
        } catch (Exception e) {
            fail("подготовка: " + e);
            return;
        }
        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, e -> {
            if (!e.getLevel().isClientSide() && e.getEntity() instanceof EntityBulletBaseMK4) bullets++;
        });
        NeoForge.EVENT_BUS.addListener(RenderHandEvent.class, e -> {
            if (e.getHand() == InteractionHand.MAIN_HAND) hands++;
        });
        var server = mc.getSingleplayerServer();
        server.submit(() -> {
            ServerLevel level = server.overworld();
            for (int x = -6; x <= 6; x++) for (int z = -10; z <= 14; z++) {
                level.setBlock(O.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 3);
                for (int y = 0; y <= 6; y++) level.setBlock(O.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
            }
            zombie = EntityTypes.HUSK.create(level, EntitySpawnReason.COMMAND);
            zombie.setNoAi(true);
            zombie.setPersistenceRequired();
            zombie.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
            zombie.setHealth(1000);
            zombie.snapTo(EX, O.getY(), EZ + 6, 180F, 0F);
            level.addFreshEntity(zombie);
        }).join();
        index = 0;
        step = 0;
    }

    private static Path file() {
        return net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve(ReaimTimes.FILE);
    }

    private static void rule(boolean on) throws Exception {
        String base = fileBefore == null ? "" : fileBefore.replaceAll("(?m)^spectatorNoGuns\\s*=.*\\n?", "");
        Files.writeString(file(), base + (base.isEmpty() || base.endsWith("\n") ? "" : "\n") + SpectatorGuns.KEY + " = " + on + "\n");
        // a new modification time for ReaimTimes, which reads the file at most once a second
        Files.setLastModifiedTime(file(), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
    }

    private static void restore() {
        try {
            if (fileExisted) Files.writeString(file(), fileBefore);
            else Files.deleteIfExists(file());
        } catch (Exception e) {
            GunFeel.LOGGER.warn("[spectator] файл настроек не восстановлен", e);
        }
    }

    private static void fail(String text) {
        ClientSmoke.check(false, "наблюдатель: " + text);
        restore();
        ClientSmoke.finish();
    }

    private static int now() {
        return Minecraft.getInstance().getSingleplayerServer().getTickCount();
    }

    private static void press(boolean down) throws Exception {
        gunChanged.invoke(null, 0, down);
    }

    static void frame() {
        Minecraft mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        try {
            if (index == CASES.size()) {
                restore();
                ClientSmoke.finish();
                return;
            }
            Case c = CASES.get(index);
            if (step >= 3 && step <= 5) {
                if (mc.player.getMainHandItem().getItem() instanceof ItemGunBaseNT && ClientRegistry.replacesCrosshair()) crosshair++;
            }
            switch (step) {
                case 0 -> { // the case's rule, mode, gun, target
                    rule(c.rule);
                    float yaw = c.mob ? 0F : 180F;
                    server.submit(() -> {
                        ServerLevel level = server.overworld();
                        ServerPlayer p = server.getPlayerList().getPlayers().getFirst();
                        p.setGameMode(c.mode);
                        p.getAbilities().flying = true;
                        p.onUpdateAbilities();
                        p.teleportTo(level, EX, EY, EZ, Set.of(), yaw, 0F, false);
                        p.setDeltaMovement(0, 0, 0);
                        for (int x = -2; x <= 2; x++) for (int y = 0; y <= 4; y++)
                            level.setBlock(O.offset(x, y, -6), Blocks.GLASS.defaultBlockState(), 3);
                        zombie.setHealth(1000);
                        zombie.invulnerableTime = 0;
                        zombie.teleportTo(EX, O.getY(), EZ + (c.how == How.MELEE ? 2 : 6));
                        // a power armor's fist needs the whole suit on
                        var suit = List.of(ModItems.NCRPA_HELMET, ModItems.NCRPA_PLATE, ModItems.NCRPA_LEGS, ModItems.NCRPA_BOOTS);
                        var slots = List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);
                        for (int i = 0; i < 4; i++)
                            p.setItemSlot(slots.get(i), c.how == How.MELEE ? new ItemStack(suit.get(i).get()) : ItemStack.EMPTY);
                        // rounds in the inventory: the reload's, and a second type for the ammo choice
                        p.getInventory().setItem(5, c.how == How.RELOAD ? XFactory9mm.p9_ap.ammoStack().copyWithCount(64)
                                : c.how == How.AMMO ? XFactory9mm.p9_fmj.ammoStack().copyWithCount(64) : ItemStack.EMPTY);
                        ItemStack stack = new ItemStack(c.how == How.MELEE ? ModItems.GUN_PA_MELEE.get() : ModItems.GUN_UZI.get());
                        if (c.how != How.MELEE) {
                            ItemGunBaseNT gun = (ItemGunBaseNT) stack.getItem();
                            var magazine = gun.getConfig(stack, 0).getReceivers(stack)[0].getMagazine(stack);
                            magazine.setType(stack, XFactory9mm.p9_ap);
                            magazine.setAmount(stack, c.how == How.RELOAD ? 10 : 30);
                        }
                        ItemGunBaseNT.setIsDrawn(stack, true);
                        ItemGunBaseNT.setIsEquipped(stack, true);
                        ItemGunBaseNT.setState(stack, 0, ItemGunBaseNT.GunState.IDLE);
                        ItemGunBaseNT.setTimer(stack, 0, 0);
                        p.getInventory().setSelectedSlot(0);
                        p.setItemInHand(InteractionHand.MAIN_HAND, stack);
                    }).join();
                    until = now() + 30;
                    step = 1;
                }
                case 1 -> { // the client has the mode, the turn and the gun, and the file is read
                    if (now() < until) return;
                    boolean mode = mc.player.isSpectator() == (c.mode == GameType.SPECTATOR);
                    if (!mode || !(mc.player.getMainHandItem().getItem() instanceof ItemGunBaseNT)) return;
                    if (ReaimTimes.flag(SpectatorGuns.KEY, true) != c.rule) return;
                    // the server has the rule too, and the gun is in the hand and drawn (but for a spectator under the
                    // rule, whose gun is put away): a gun put away while the rule was on is drawn again once it is off
                    boolean[] ready = new boolean[1];
                    server.submit(() -> {
                        ItemStack stack = server.getPlayerList().getPlayers().getFirst().getMainHandItem();
                        boolean away = c.mode == GameType.SPECTATOR && c.rule;
                        ready[0] = ReaimTimes.flag(SpectatorGuns.KEY, true) == c.rule && (away || ItemGunBaseNT.getIsEquipped(stack)
                                && ItemGunBaseNT.getState(stack, 0) == ItemGunBaseNT.GunState.IDLE);
                    }).join();
                    if (!ready[0]) return;
                    mc.player.setYRot(c.mob ? 0F : 180F);
                    mc.player.yRotO = mc.player.getYRot();
                    mc.player.setXRot(0F);
                    mc.player.xRotO = 0F;
                    step = 2;
                }
                case 2 -> { // what it was
                    if (c.how.own()) {
                        step = 10;
                        return;
                    }
                    before(server);
                    shotBefore = lastShot(mc);
                    bullets = 0;
                    ClientSmoke.hud = crosshair = hands = 0;
                    if (c.how == How.PACKET) PacketWire.sendToServer(new KeybindPayload(EnumKeybind.GUN_PRIMARY, true));
                    else press(true);
                    until = now() + (c.how == How.SWITCH ? 6 : 10);
                    step = 3;
                }
                case 3 -> { // held
                    if (now() < until) return;
                    if (c.how == How.SWITCH) {
                        int fired = bullets;
                        GunFeel.LOGGER.info("[spectator] {}: до смены режима пуль {}", c.name, fired);
                        ClientSmoke.check(fired > 0, "наблюдатель, " + c.name + ": до смены режима стреляет (" + fired + ")");
                        // the bullets and rounds are counted from the tick the mode changes, on the server
                        server.submit(() -> {
                            ServerPlayer p = server.getPlayerList().getPlayers().getFirst();
                            ItemStack stack = p.getMainHandItem();
                            ammoBefore = ((ItemGunBaseNT) stack.getItem()).getConfig(stack, 0).getReceivers(stack)[0].getMagazine(stack).getAmount(stack, p.getInventory());
                            p.setGameMode(GameType.SPECTATOR);
                            bullets = 0;
                        }).join();
                        until = now() + 2;
                        step = 6;
                        return;
                    }
                    if (c.how == How.PACKET) PacketWire.sendToServer(new KeybindPayload(EnumKeybind.GUN_PRIMARY, false));
                    else press(false);
                    until = now() + 30;
                    step = 4;
                }
                case 6 -> { // the switch: the client's HUD and shots counted from two ticks after the mode changed,
                    // once the client knows it, and the husk's health too, once the bullets fired before have landed
                    if (now() < until) return;
                    ClientSmoke.hud = crosshair = hands = 0;
                    shotBefore = lastShot(mc);
                    server.submit(() -> healthBefore = zombie.getHealth()).join();
                    until = now() + 10;
                    step = 7;
                }
                case 7 -> {
                    if (now() < until) return;
                    press(false);
                    until = now() + 30;
                    step = 4;
                }
                case 4 -> { // the bullets have landed
                    if (now() < until) return;
                    measure(mc, c);
                    index++;
                    step = 0;
                }
                default -> own(mc, c);
            }
        } catch (Throwable t) {
            GunFeel.LOGGER.error("[spectator] failed", t);
            fail(t.toString());
        }
    }

    // RELOAD, MELEE, AMMO: steps 10 on
    private static void own(Minecraft mc, Case c) throws Exception {
        var server = mc.getSingleplayerServer();
        String name = "наблюдатель, " + c.name + ": ";
        switch (step) {
            case 10 -> {
                if (c.how == How.AMMO) {
                    server.submit(() -> {
                        ItemStack stack = server.getPlayerList().getPlayers().getFirst().getMainHandItem();
                        var magazine = magazine(stack);
                        magBefore = magazine.getAmount(stack, null);
                        typeBefore = com.hbm.items.weapon.sedna.BulletConfig.configs.indexOf(magazine.getType(stack, null));
                        lockBefore = AmmoLock.locked(stack);
                    }).join();
                    int fmj = com.hbm.items.weapon.sedna.BulletConfig.configs.indexOf(XFactory9mm.p9_fmj);
                    net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(new AmmoSelectPayload(fmj));
                    net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(AmmoLockPayload.INSTANCE);
                    until = now() + 10;
                    step = 14;
                    return;
                }
                server.submit(() -> {
                    ServerPlayer p = server.getPlayerList().getPlayers().getFirst();
                    ItemStack stack = p.getMainHandItem();
                    ItemGunBaseNT gun = (ItemGunBaseNT) stack.getItem();
                    healthBefore = zombie.getHealth();
                    EnumKeybind key = c.how == How.RELOAD ? EnumKeybind.RELOAD : EnumKeybind.GUN_PRIMARY;
                    gun.handleKeybind(p, p.getInventory(), stack, key, true);
                    gun.handleKeybind(p, p.getInventory(), stack, key, false);
                }).join();
                until = now() + (c.how == How.RELOAD ? 3 : 40);
                step = 11;
            }
            case 11 -> { // RELOAD: three ticks into the reload; MELEE: the swing's first hit has landed
                boolean[] ready = new boolean[1];
                server.submit(() -> {
                    ServerPlayer p = server.getPlayerList().getPlayers().getFirst();
                    ItemStack stack = p.getMainHandItem();
                    if (c.how == How.RELOAD) {
                        if (now() < until) return;
                        ready[0] = true;
                        var state = ItemGunBaseNT.getState(stack, 0);
                        ClientSmoke.check(state == ItemGunBaseNT.GunState.RELOADING, name + "перезарядка началась (" + state + ")");
                        magBefore = magazine(stack).getAmount(stack, null);
                        invBefore = AmmoLock.count(XFactory9mm.p9_ap, p.getInventory());
                    } else {
                        float hit = healthBefore - zombie.getHealth();
                        if (hit <= 0 && now() < until) return;
                        ready[0] = true;
                        ClientSmoke.check(hit > 0, name + "первый удар до смены режима прошёл (" + hit + ")");
                        healthBefore = zombie.getHealth();
                    }
                    p.setGameMode(GameType.SPECTATOR);
                }).join();
                if (!ready[0]) return;
                until = now() + (c.how == How.RELOAD ? 80 : 30);
                step = 12;
            }
            case 12 -> { // what went on in spectator mode
                if (now() < until) return;
                float[] hurt = new float[1];
                int[] mag = new int[2];
                server.submit(() -> {
                    ServerPlayer p = server.getPlayerList().getPlayers().getFirst();
                    ItemStack stack = p.getMainHandItem();
                    hurt[0] = healthBefore - zombie.getHealth();
                    if (c.how == How.RELOAD) {
                        mag[0] = magazine(stack).getAmount(stack, null);
                        mag[1] = AmmoLock.count(XFactory9mm.p9_ap, p.getInventory());
                    }
                }).join();
                if (c.how == How.RELOAD) {
                    GunFeel.LOGGER.info("[spectator] {}: магазин {}→{}, в инвентаре {}→{}", c.name, magBefore, mag[0], invBefore, mag[1]);
                    ClientSmoke.check(c.shoots == (mag[0] > magBefore), name + (c.shoots ? "перезарядка дошла" : "перезарядка не дошла")
                            + " (магазин " + magBefore + "→" + mag[0] + ", в инвентаре " + invBefore + "→" + mag[1] + ")");
                } else {
                    GunFeel.LOGGER.info("[spectator] {}: урон после смены режима {}", c.name, hurt[0]);
                    ClientSmoke.check(c.shoots == (hurt[0] > 0), name + (c.shoots ? "второй удар прошёл" : "второго удара нет") + " (" + hurt[0] + ")");
                }
                if (c.how == How.RELOAD && c.rule) { // back in creative the gun is drawn again and fires
                    server.submit(() -> server.getPlayerList().getPlayers().getFirst().setGameMode(GameType.CREATIVE)).join();
                    until = now() + 40;
                    step = 13;
                    return;
                }
                index++;
                step = 0;
            }
            case 13 -> {
                if (now() < until) return;
                bullets = 0;
                press(true);
                until = now() + 10;
                step = 15;
            }
            case 15 -> {
                if (now() < until) return;
                press(false);
                until = now() + 20;
                step = 16;
            }
            case 16 -> {
                if (now() < until) return;
                GunFeel.LOGGER.info("[spectator] {}: после возврата в креатив пуль {}", c.name, bullets);
                ClientSmoke.check(bullets > 0, name + "после возврата в креатив пушка стреляет (пуль " + bullets + ")");
                index++;
                step = 0;
            }
            case 14 -> { // AMMO
                if (now() < until) return;
                int[] after = new int[2];
                boolean[] lock = new boolean[1];
                server.submit(() -> {
                    ItemStack stack = server.getPlayerList().getPlayers().getFirst().getMainHandItem();
                    var magazine = magazine(stack);
                    after[0] = magazine.getAmount(stack, null);
                    after[1] = com.hbm.items.weapon.sedna.BulletConfig.configs.indexOf(magazine.getType(stack, null));
                    lock[0] = AmmoLock.locked(stack);
                }).join();
                GunFeel.LOGGER.info("[spectator] {}: магазин {}→{}, тип {}→{}, замок {}→{}", c.name, magBefore, after[0], typeBefore, after[1], lockBefore, lock[0]);
                boolean changed = after[0] != magBefore || after[1] != typeBefore;
                ClientSmoke.check(c.shoots == changed, name + (c.shoots ? "патроны сменились" : "магазин не тронут")
                        + " (" + magBefore + "→" + after[0] + ", тип " + typeBefore + "→" + after[1] + ")");
                ClientSmoke.check(c.shoots == (lock[0] != lockBefore), name + (c.shoots ? "замок переключился" : "замок не тронут")
                        + " (" + lockBefore + "→" + lock[0] + ")");
                index++;
                step = 0;
            }
            default -> {
            }
        }
    }

    private static com.hbm.items.weapon.sedna.mags.MagazineSingleTypeBase magazine(ItemStack stack) {
        ItemGunBaseNT gun = (ItemGunBaseNT) stack.getItem();
        return (com.hbm.items.weapon.sedna.mags.MagazineSingleTypeBase) gun.getConfig(stack, 0).getReceivers(stack)[0].getMagazine(stack);
    }

    private static void before(net.minecraft.client.server.IntegratedServer server) {
        server.submit(() -> {
            ServerPlayer p = server.getPlayerList().getPlayers().getFirst();
            ItemStack stack = p.getMainHandItem();
            ItemGunBaseNT gun = (ItemGunBaseNT) stack.getItem();
            ammoBefore = gun.getConfig(stack, 0).getReceivers(stack)[0].getMagazine(stack).getAmount(stack, p.getInventory());
            healthBefore = zombie.getHealth();
        }).join();
    }

    private static long lastShot(Minecraft mc) {
        if (!(mc.player.getMainHandItem().getItem() instanceof ItemGunBaseNT gun) || gun.lastShot == null) return 0;
        long newest = 0;
        for (long t : gun.lastShot) newest = Math.max(newest, t);
        return newest;
    }

    private static void measure(Minecraft mc, Case c) {
        var server = mc.getSingleplayerServer();
        int[] ammo = new int[1];
        float[] health = new float[1];
        int[] glass = new int[1];
        server.submit(() -> {
            ServerPlayer p = server.getPlayerList().getPlayers().getFirst();
            ItemStack stack = p.getMainHandItem();
            ItemGunBaseNT gun = (ItemGunBaseNT) stack.getItem();
            ammo[0] = gun.getConfig(stack, 0).getReceivers(stack)[0].getMagazine(stack).getAmount(stack, p.getInventory());
            health[0] = zombie.getHealth();
            ServerLevel level = server.overworld();
            for (int x = -2; x <= 2; x++) for (int y = 0; y <= 4; y++)
                if (level.getBlockState(O.offset(x, y, -6)).is(Blocks.GLASS)) glass[0]++;
        }).join();
        int spent = ammoBefore - ammo[0];
        float hurt = healthBefore - health[0];
        boolean shot = lastShot(mc) != shotBefore;
        boolean spectator = c.mode == GameType.SPECTATOR || c.how == How.SWITCH;
        GunFeel.LOGGER.info("[spectator] {}: пуль {}, патронов {}, урон {}, стекла {}/25, выстрел на клиенте {}, HUD {}, прицел NTM {}, рука {}",
                c.name, bullets, spent, hurt, glass[0], shot, ClientSmoke.hud, crosshair, hands);
        String name = "наблюдатель, " + c.name + ": ";
        if (c.shoots) {
            ClientSmoke.check(bullets > 0 && spent > 0, name + "стреляет (пуль " + bullets + ", патронов " + spent + ")");
            ClientSmoke.check(shot, name + "выстрел дошёл до клиента (отдача, анимация)");
            if (c.mob) ClientSmoke.check(hurt > 0, name + "моб ранен (" + hurt + ")");
            // the mod's bullets break blocks for a player who may build (BulletServer.wear asks Player.mayBuild)
            else ClientSmoke.check(spectator ? glass[0] == 25 : glass[0] < 25, name + (spectator ? "стекло цело" : "стекло разбито") + " (" + glass[0] + "/25)");
        } else {
            ClientSmoke.check(bullets == 0 && spent == 0, name + "не стреляет (пуль " + bullets + ", патронов " + spent + ")");
            ClientSmoke.check(!shot, name + "выстрела на клиенте нет");
            if (c.mob) ClientSmoke.check(hurt == 0, name + "моб цел (" + hurt + ")");
            else ClientSmoke.check(glass[0] == 25, name + "стекло цело (" + glass[0] + "/25)");
        }
        boolean hudShown = !spectator || !c.rule;
        int hud = ClientSmoke.hud;
        ClientSmoke.check(hudShown == (hud > 0), name + (hudShown ? "HUD пушки есть" : "HUD пушки нет") + " (" + hud + ")");
        ClientSmoke.check(hudShown == (crosshair > 0), name + (hudShown ? "прицел NTM есть" : "прицела NTM нет") + " (" + crosshair + ")");
        // the game draws no hand in spectator mode, with or without the rule
        ClientSmoke.check(spectator == (hands == 0), name + (spectator ? "руки с пушкой нет" : "рука с пушкой есть") + " (" + hands + ")");
    }
}
