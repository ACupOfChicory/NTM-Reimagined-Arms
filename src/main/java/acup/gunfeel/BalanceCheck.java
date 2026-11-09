package acup.gunfeel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

import com.hbm.handler.HbmKeybinds.EnumKeybind;
import com.hbm.items.ModItems;
import com.hbm.items.armor.ModArmorItem;
import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.factory.XFactory12ga;
import com.hbm.items.weapon.sedna.factory.XFactory22lr;
import com.hbm.items.weapon.sedna.factory.XFactory357;
import com.hbm.items.weapon.sedna.factory.XFactory44;
import com.hbm.items.weapon.sedna.factory.XFactory50;
import com.hbm.items.weapon.sedna.factory.XFactory762mm;
import com.hbm.items.weapon.sedna.factory.XFactoryEnergy;
import com.hbm.items.weapon.sedna.factory.XFactoryRocket;
import com.mojang.authlib.GameProfile;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

// The balance's armour on a dedicated server (./gradlew runCheckBalance -PnoAssets): a mannequin fires NTM's real guns
// (handleKeybind, as an AI bot does) at a player 4 blocks off in a full NTM set, one click every 20 ticks (the damage
// cap's 14-tick share never in the way) until the player dies, three times: NTM's armour (balanceArmor off), the
// formula C, and the formula as the balance model counts it: the sets' vanilla armour off and NTM's shield emptied before each click (a full set's shield takes its share of a hit before the armour). One pair a tier,
// numbers from the model (variants.det_pellet with fit4's S, no gun multipliers): the damage a hit leaves after the
// armour and the clicks to kill. Then a player at an FBI agent in Security armour, and a Panzerschreck rocket at
// Security (the blast's path), before and after. A tsv of the cases and OK/FAIL lines go to the file.
public final class BalanceCheck {
    public static final String PROPERTY = "ntmgunfeel.checkBalance";
    private static final BlockPos O = new BlockPos(0, 120, 0);
    private static final double DIST = 4;
    private static final int SPACING = 20, MAX_CLICKS = 30;
    private static final Identifier FBI = Identifier.fromNamespaceAndPath("hbm", "entity_fbi");

    private BalanceCheck() {
    }

    /** a pair: the set, the gun and its ammo, the model's damage of one hit after the armour and clicks to kill */
    private record Pair(String set, String gunName, Supplier<Item> gun, BulletConfig ammo, float modelHit, int modelClicks) {
    }

    private static final List<Runnable> STEPS = new ArrayList<>();
    private static final List<String> LINES = new ArrayList<>();
    private static boolean ok = true;
    private static ServerLevel level;
    private static Mannequin shooter;
    private static FakePlayer target, gunner;
    private static Mob fbi;
    /** the steps of a case after its victim died are skipped up to this one */
    private static int skipTo;
    private static boolean noShield;

    // the case under way
    private static LivingEntity victim;
    private static int clicks, hits, deathClick;
    private static float lost, firstClick, incoming;
    private static String incomingNote = "";

    public static void registerIfAsked() {
        String file = System.getProperty(PROPERTY);
        if (file == null || file.isBlank()) return;
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, started -> {
            level = started.getServer().overworld();
            try {
                setup();
                plan();
            } catch (Throwable t) {
                LINES.add("FAIL\tsetup crashed: " + t);
                for (StackTraceElement s : t.getStackTrace()) LINES.add("    at " + s);
                BulletCheck.finish(file, LINES, 1);
            }
            int[] at = {0};
            NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, e -> {
                if (at[0] < 0) return;
                try {
                    if (target != null) target.invulnerableTime = 0;
                    if (fbi != null) fbi.invulnerableTime = 0;
                    if (skipTo > at[0]) at[0] = Math.min(skipTo, STEPS.size());
                    if (at[0] < STEPS.size()) {
                        STEPS.get(at[0]++).run();
                        return;
                    }
                    at[0] = -1;
                    keys();
                    LINES.add(ok ? "ALL OK" : "SOME FAILED");
                    BulletCheck.finish(file, LINES, ok ? 0 : 1);
                } catch (Throwable t) {
                    at[0] = -1;
                    LINES.add("FAIL\tcrashed: " + t);
                    for (StackTraceElement s : t.getStackTrace()) LINES.add("    at " + s);
                    keys();
                    BulletCheck.finish(file, LINES, 1);
                }
            });
        });
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGHEST, false, net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent.class, e -> {
            if (e.getEntity() == victim && victim != null) {
                incoming += e.getAmount();
                if (incomingNote.isEmpty()) incomingNote = e.getSource().getMsgId() + " " + f(e.getAmount())
                        + (victim instanceof net.minecraft.world.entity.player.Player p ? ", щит " + f(com.hbm.extprop.HbmPlayerProps.getData(p).shield) + "/"
                                + f(com.hbm.extprop.HbmPlayerProps.getData(p).getEffectiveMaxShield(p)) : "");
            }
        });
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST, false, LivingDamageEvent.Post.class, e -> {
            if (e.getEntity() != victim || victim == null) return;
            hits++;
            lost += e.getInflictedDamage();
        });
    }

    private static void setup() {
        for (int x = -4; x <= 6; x++) for (int z = -4; z <= 10; z++) level.setBlockAndUpdate(O.offset(x, -1, z), Blocks.STONE.defaultBlockState());
        shooter = EntityTypes.MANNEQUIN.create(level, EntitySpawnReason.COMMAND);
        shooter.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
        shooter.setHealth(1000);
        shooter.snapTo(O.getX() + 0.5, O.getY(), O.getZ() + 0.5, 0F, 0F);
        level.addFreshEntity(shooter);
        target = new FakePlayer(level, new GameProfile(UUID.nameUUIDFromBytes("balanceTarget".getBytes()), "balanceTarget")) {
            @Override
            public boolean canHarmPlayer(net.minecraft.world.entity.player.Player other) {
                return true;
            }
        };
        target.setGameMode(GameType.SURVIVAL);
        target.snapTo(O.getX() + 0.5, O.getY(), O.getZ() + 0.5 + DIST, 180F, 0F);
        level.addNewPlayer(target);
        target.connection.markClientLoaded();
        target.setInvulnerable(false);
        gunner = FakePlayerFactory.get(level, new GameProfile(UUID.nameUUIDFromBytes("balanceGunner".getBytes()), "balanceGunner"));
        gunner.setGameMode(GameType.SURVIVAL);
        gunner.snapTo(O.getX() + 4.5, O.getY(), O.getZ() + 0.5, 0F, 0F);
        level.addNewPlayer(gunner);
    }

    private static void wait(int ticks) {
        for (int i = 0; i < ticks; i++) STEPS.add(() -> {
        });
    }

    // the model's numbers: notes/balance/work, variants.det_pellet and kill2.simulate with fit4's S, the guns as in NTM
    private static final List<Pair> PAIRS = List.of(
            new Pair("steel", "light_revolver · m357_express", ModItems.GUN_LIGHT_REVOLVER::get, XFactory357.m357_express, 8.850F, 3),
            new Pair("dieselsuit", "henry · m44_express", ModItems.GUN_HENRY::get, XFactory44.m44_express, 9.557F, 3),
            new Pair("t51", "maresleg · g12_flechette", ModItems.GUN_MARESLEG::get, XFactory12ga.g12_flechette, 0.497F, 6),
            new Pair("hev", "star_f · p22_jhp", ModItems.GUN_STAR_F::get, XFactory22lr.p22_jhp, 3.594F, 6),
            new Pair("security", "carbine · r762_he", ModItems.GUN_CARBINE::get, XFactory762mm.r762_he, 8.429F, 4),
            new Pair("bj", "laser_pistol · capacitor (EN)", ModItems.GUN_LASER_PISTOL::get, XFactoryEnergy.energy_las, 2.738F, 8),
            new Pair("rpa", "mas36 · r762_du", ModItems.GUN_MAS36::get, XFactory762mm.r762_du, 4.890F, 5),
            new Pair("fau", "amat · bmg50_sm", ModItems.GUN_AMAT::get, XFactory50.bmg50_sm, 5.751F, 4),
            new Pair("hev", "amat · bmg50_sm (потолок)", ModItems.GUN_AMAT::get, XFactory50.bmg50_sm, 0F, 0),
            // NTM calls this hit off before it is dealt (its threshold 100 over 11.25 + 2): the formula leaves b of it
            new Pair("fau", "light_revolver · m357_express (слабая)", ModItems.GUN_LIGHT_REVOLVER::get, XFactory357.m357_express, 0.455F, 44));

    private static final String MODEL = "C как в модели (ваниль 0, щит 0)";
    private static final String[] MODES = {"NTM", "C", MODEL};

    private static void plan() {
        LINES.add("#\tрежим\tнабор\tпушка\tкликов до смерти\tснято первым кликом\tударов\tD за удар\tNTM за удар\tC за удар\tмодель: за удар / кликов");
        for (String mode : MODES) {
            STEPS.add(() -> keys("balanceArmor", mode.equals("NTM") ? "false" : "true", "damageCap", "true", "damageCapMobs", "false"));
            wait(30);
            for (Pair p : PAIRS) pair(p, mode);
            fbiCase(mode);
            rocket(mode);
        }
    }

    // ---------------------------------------------------------------- the cases

    private static void pair(Pair p, String mode) {
        boolean vanilla = !mode.equals(MODEL);
        STEPS.add(() -> {
            begin(target);
            noShield = !vanilla;
            dress(target, p.set(), vanilla);
            target.snapTo(O.getX() + 0.5, O.getY(), O.getZ() + 0.5 + DIST, 180F, 0F);
        });
        clicks(shooter, p.gun(), p.ammo(), EnumKeybind.GUN_PRIMARY);
        STEPS.add(() -> {
            String r = row(mode, p.set(), p.gunName()) + "\t" + f(p.modelHit()) + " / " + p.modelClicks();
            if (mode.equals("NTM")) return;
            if (p.modelHit() <= 0F) {
                check(firstClick <= 12.01F && firstClick > 11F, mode + ", " + p.set() + ", " + p.gunName() + ": клик снял " + f(firstClick) + " ≤ 12 (потолок)" + r);
                return;
            }
            float hit = BalanceArmor.seenHits > 0 ? BalanceArmor.seenOut / BalanceArmor.seenHits : 0F;
            float off = Math.abs(hit - p.modelHit()) / p.modelHit();
            check(BalanceArmor.seenHits > 0 && off <= 0.15F, mode + ", " + p.set() + ", " + p.gunName() + ": урон за удар после брони "
                    + f(hit) + " против модели " + f(p.modelHit()) + " (" + Math.round(off * 100) + " %)" + r);
            if (!vanilla) {
                if (deathClick > 0) LINES.add("#\t" + p.set() + ": кликов " + deathClick + " против модели " + p.modelClicks() + " ("
                        + Math.round(100F * (deathClick - p.modelClicks()) / p.modelClicks()) + " %)");
                else LINES.add("#\t" + p.set() + ": жив после " + clicks + " кликов, модель " + p.modelClicks());
            }
        });
    }

    private static void fbiCase(String mode) {
        STEPS.add(() -> {
            var created = BuiltInRegistries.ENTITY_TYPE.getValue(FBI).create(level, EntitySpawnReason.COMMAND);
            if (!(created instanceof Mob mob)) throw new IllegalStateException("no FBI agent");
            fbi = mob;
            fbi.setNoAi(true);
            fbi.snapTo(O.getX() + 4.5, O.getY(), O.getZ() + 0.5 + DIST, 180F, 0F);
            level.addFreshEntity(fbi);
            dress(fbi, "security", true);
            fbi.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            begin(fbi);
        });
        clicks(gunner, ModItems.GUN_HEAVY_REVOLVER::get, XFactory44.m44_express, EnumKeybind.GUN_PRIMARY);
        STEPS.add(() -> {
            LINES.add("#\tFBI: " + f(fbi.getMaxHealth()) + " HP, броня ванили " + f((float) fbi.getAttributeValue(Attributes.ARMOR)));
            row(mode, "FBI в security", "игрок: heavy_revolver · m44_express");
            if (!mode.equals("NTM")) check(BalanceArmor.seenHits > 0 && BalanceArmor.seenOut < BalanceArmor.seenIn,
                    mode + ", FBI: формула действует на моба (" + f(BalanceArmor.seenOut) + " из " + f(BalanceArmor.seenIn) + ")");
            fbi.discard();
            fbi = null;
        });
    }

    private static void rocket(String mode) {
        STEPS.add(() -> {
            begin(target);
            noShield = mode.equals(MODEL);
            dress(target, "security", !noShield);
            target.snapTo(O.getX() + 0.5, O.getY(), O.getZ() + 0.5 + DIST + 2, 180F, 0F);
        });
        clicks(shooter, ModItems.GUN_PANZERSCHRECK::get, XFactoryRocket.rocket_rpzb[0], EnumKeybind.GUN_PRIMARY);
        STEPS.add(() -> row(mode, "security", "panzerschreck · rocket_rpzb HE, прямое, 6 бл"));
    }

    /** clicks every SPACING ticks until the victim dies, MAX_CLICKS at most */
    private static void clicks(LivingEntity from, Supplier<Item> gun, BulletConfig ammo, EnumKeybind key) {
        int[] end = {0};
        for (int i = 0; i < MAX_CLICKS; i++) {
            STEPS.add(() -> {
                if (deathClick > 0 || victim.getHealth() <= 0F) return;
                // the model counts no shield: NTM's shield of a full set takes its share before the armour
                if (noShield && victim instanceof net.minecraft.world.entity.player.Player p) com.hbm.extprop.HbmPlayerProps.getData(p).shield = 0F;
                fire(from, gun.get(), ammo, key);
                clicks++;
            });
            for (int t = 0; t < SPACING; t++) STEPS.add(() -> {
                if (deathClick <= 0 && clicks > 0 && victim.getHealth() <= 0F) {
                    deathClick = clicks;
                    if (clicks > 1) skipTo = end[0];
                }
            });
            if (i == 0) STEPS.add(() -> {
                firstClick = lost;
                if (deathClick > 0) skipTo = end[0];
            });
        }
        end[0] = STEPS.size();
        wait(20);
    }

    // ---------------------------------------------------------------- the parts

    private static void begin(LivingEntity v) {
        victim = v;
        noShield = false;
        clicks = hits = deathClick = 0;
        lost = incoming = 0F;
        incomingNote = "";
        firstClick = -1F;
        BalanceArmor.watch = v;
        BalanceArmor.seenHits = 0;
        BalanceArmor.seenIn = BalanceArmor.seenNtm = BalanceArmor.seenOut = 0F;
        DamageCap.forget();
        v.setHealth(v.getMaxHealth());
        v.setAbsorptionAmount(0);
        v.setDeltaMovement(Vec3.ZERO);
    }

    private static final String[] SUFFIX = {"_helmet", "_plate", "_legs", "_boots"};
    private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    /** the full set, charged; {@code vanilla} off: its vanilla armour points taken off the pieces */
    private static void dress(LivingEntity v, String set, boolean vanilla) {
        for (int i = 0; i < 4; i++) {
            Item item = BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("hbm", set + SUFFIX[i]));
            ItemStack stack = new ItemStack(item);
            if (stack.isEmpty()) throw new IllegalStateException("no hbm:" + set + SUFFIX[i]);
            if (item instanceof ModArmorItem armor && armor.isPowered()) armor.setCharge(stack, armor.maxPower(stack));
            if (!vanilla) stack.set(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
            v.setItemSlot(SLOTS[i], stack);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ItemStack loaded(Item item, BulletConfig ammo) {
        ItemStack stack = new ItemStack(item);
        ItemGunBaseNT gun = (ItemGunBaseNT) stack.getItem();
        for (int i = 0; i < gun.getConfigCount(); i++) {
            for (var r : gun.getConfig(stack, i).getReceivers(stack)) {
                com.hbm.items.weapon.sedna.mags.IMagazine magazine = r.getMagazine(stack);
                magazine.setType(stack, ammo);
                magazine.setAmount(stack, magazine instanceof com.hbm.items.weapon.sedna.mags.MagazineSingleTypeBase m ? m.capacity : 30);
            }
            ItemGunBaseNT.setState(stack, i, ItemGunBaseNT.GunState.IDLE);
            ItemGunBaseNT.setTimer(stack, i, 0);
        }
        ItemGunBaseNT.setIsDrawn(stack, true);
        ItemGunBaseNT.setIsEquipped(stack, true);
        return stack;
    }

    private static void fire(LivingEntity from, Item item, BulletConfig ammo, EnumKeybind key) {
        ItemStack stack = loaded(item, ammo);
        from.setItemSlot(EquipmentSlot.MAINHAND, stack);
        aim(from, victim);
        var gun = (ItemGunBaseNT) stack.getItem();
        gun.handleKeybind(from, new SimpleContainer(1), stack, key, true);
        gun.handleKeybind(from, new SimpleContainer(1), stack, key, false);
    }

    private static void aim(LivingEntity from, LivingEntity at) {
        Vec3 eye = from.getEyePosition();
        Vec3 to = new Vec3(at.getX(), at.getY() + 0.9, at.getZ());
        Vec3 d = to.subtract(eye);
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        from.snapTo(from.getX(), from.getY(), from.getZ(), yaw, pitch);
        from.setYHeadRot(yaw);
        from.setYBodyRot(yaw);
    }

    private static String row(String mode, String set, String gun) {
        int n = BalanceArmor.seenHits;
        String r = "\t" + mode + "\t" + set + "\t" + gun + "\t" + (deathClick > 0 ? String.valueOf(deathClick) : "> " + clicks) + "\t"
                + f(Math.max(0F, firstClick)) + "\t" + n + "\t" + (n > 0 ? f(BalanceArmor.seenIn / n) : "-") + "\t"
                + (n > 0 ? f(BalanceArmor.seenNtm / n) : "-") + "\t" + (n > 0 ? f(BalanceArmor.seenOut / n) : "-") + "\tвход " + f(incoming / Math.max(1, hits))
                + " (" + incomingNote + ")";
        LINES.add("#" + r);
        return r;
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve(ReaimTimes.FILE);
    }

    private static final java.util.Map<String, String> KEYS = new java.util.TreeMap<>();

    private static void keys(String... kv) {
        for (int i = 0; i < kv.length; i += 2) KEYS.put(kv[i], kv[i + 1]);
        try {
            if (kv.length == 0) {
                Files.deleteIfExists(file());
                return;
            }
            StringBuilder s = new StringBuilder();
            KEYS.forEach((k, v) -> s.append(k).append(" = ").append(v).append('\n'));
            Files.writeString(file(), s);
            Files.setLastModifiedTime(file(), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static String f(float v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static void check(boolean pass, String what) {
        if (!pass) ok = false;
        LINES.add((pass ? "OK" : "FAIL") + "\t" + what);
    }
}
