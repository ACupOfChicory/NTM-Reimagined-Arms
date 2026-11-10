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
import com.hbm.items.weapon.sedna.factory.XFactory10ga;
import com.hbm.items.weapon.sedna.factory.XFactory12ga;
import com.hbm.items.weapon.sedna.factory.XFactory22lr;
import com.hbm.items.weapon.sedna.factory.XFactory357;
import com.hbm.items.weapon.sedna.factory.XFactory44;
import com.hbm.items.weapon.sedna.factory.XFactory50;
import com.hbm.items.weapon.sedna.factory.XFactory556mm;
import com.hbm.items.weapon.sedna.factory.XFactory762mm;
import com.hbm.items.weapon.sedna.factory.XFactory9mm;
import com.hbm.items.weapon.sedna.factory.XFactoryAccelerator;
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
                    if (healing && target != null && target.tickCount == targetTicks) BalanceKit.tickHeal(target);
                    if (target != null) targetTicks = target.tickCount;
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
            if (e.getEntity() == target && DamageCap.last.contains(" head=true"))
                headHit = Math.max(headHit, DamageCap.last.contains(" sniper=true") ? 2 : 1);
            if (e.getEntity() != victim || victim == null) return;
            hits++;
            lost += e.getInflictedDamage();
            if (!e.getSource().is(net.minecraft.tags.DamageTypeTags.IS_FALL)) blastLost += e.getInflictedDamage();
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

    // ---------------------------------------------------------------- stage 1v: the recipes

    /** a recipe of stage 1v: its name (NTM's id), the items the balance's takes, the ones NTM's takes and it does not */
    private record Recipe1v(String name, List<String> balance, List<String> ntm) {
    }

    private static final List<Recipe1v> RECIPES = List.of(
            new Recipe1v("dieselsuit_helmet", List.of("circuit_basic"), List.of("circuit_analog")),
            new Recipe1v("dieselsuit_plate", List.of("circuit_basic", "plate_cast_dura_steel", "machine_diesel"), List.of("circuit_analog")),
            new Recipe1v("dieselsuit_legs", List.of("plate_cast_dura_steel", "motor"), List.of()),
            new Recipe1v("gun_tesla_cannon", List.of("circuit_quantum", "plate_welded_cmbsteel", "coil_copper"), List.of("circuit_advanced")),
            new Recipe1v("gun_lasrifle", List.of("circuit_quantum", "crystal_schraranium"), List.of("circuit_bismoid", "crystal_redstone")),
            new Recipe1v("gun_tau", List.of("circuit_quantum", "coil_magnetized_tungsten", "plate_dineutronium"), List.of("circuit_bismoid", "coil_copper_torus")));

    private static java.util.concurrent.CompletableFuture<Void> reload;

    private static void planRecipes() {
        for (boolean on : new boolean[] {true, false, true}) {
            STEPS.add(() -> {
                keys("balanceRecipes", Boolean.toString(on));
                ReaimTimes.inFile("balanceRecipes"); // the file read now, before the data packs ask
            });
            wait(30);
            STEPS.add(() -> {
                var server = level.getServer();
                reload = server.reloadResources(server.getPackRepository().getSelectedIds());
            });
            int[] after = {0};
            for (int i = 0; i < 1200; i++) STEPS.add(() -> {
                if (reload != null && reload.isDone()) skipTo = Math.max(skipTo, after[0]);
            });
            after[0] = STEPS.size();
            STEPS.add(() -> recipes(on));
        }
    }

    private static void recipes(boolean on) {
        check(reload != null && reload.isDone() && !reload.isCompletedExceptionally(), "1в: /reload с balanceRecipes = " + on);
        var manager = level.getServer().getRecipeManager();
        for (Recipe1v r : RECIPES) {
            var balance = manager.byKey(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.RECIPE, Identifier.fromNamespaceAndPath("hbm", r.name())));
            // Tesla Cannon's NTM recipe with the new mechanism is a file of its own (GunMechanismRecipes)
            String ntmPath = (r.name().equals("gun_tesla_cannon") && GunMechanismRecipes.on() ? "mechanisms/" : "ntm/") + r.name();
            var ntm = manager.byKey(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.RECIPE, Identifier.fromNamespaceAndPath("ntmgunfeel", ntmPath)));
            // every recipe making the item: one, whichever the setting
            var item = item(r.name());
            long making = manager.getRecipes().stream().filter(h -> makes(h.value(), item)).count();
            var held = on ? balance : ntm;
            List<String> takes = held.map(h -> takes(h.value())).orElse(List.of());
            boolean right = held.isPresent() && (on ? ntm : balance).isEmpty() && making == 1
                    && (on ? r.balance() : r.ntm()).stream().allMatch(takes::contains) && (on ? r.ntm().stream().noneMatch(takes::contains) : true);
            check(right, "1в: " + r.name() + (on ? " рецепт баланса hbm:" + r.name() : " рецепт NTM ntmgunfeel:" + ntmPath) + ", рецептов предмета " + making
                    + ", берёт " + takes);
        }
    }

    private static Item item(String name) {
        return BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("hbm", name));
    }

    private static boolean makes(net.minecraft.world.item.crafting.Recipe<?> recipe, Item item) {
        try {
            for (var d : recipe.display()) {
                var stacks = d.result().resolveForStacks(net.minecraft.world.item.crafting.display.SlotDisplayContext.fromLevel(level));
                for (ItemStack st : stacks) if (st.is(item)) return true;
            }
        } catch (RuntimeException e) {
            // a recipe that cannot show itself without a level of its own: not one of ours
        }
        return false;
    }

    /** the item names (hbm's path, or the full id) the recipe's ingredients accept */
    private static List<String> takes(net.minecraft.world.item.crafting.Recipe<?> recipe) {
        List<String> out = new ArrayList<>();
        for (var ing : recipe.placementInfo().ingredients()) ing.items().forEach(h -> {
            Identifier id = BuiltInRegistries.ITEM.getKey(h.value());
            String n = id.getNamespace().equals("hbm") ? id.getPath() : id.toString();
            if (!out.contains(n)) out.add(n);
        });
        return out;
    }

    /** the passes to run (ntmgunfeel.checkBalance.only, by commas: 2, 1g, 1v, 1a, 1b, dns, kit, db, hi, expl, esp, pool; none: all) */
    private static boolean only(String pass) {
        String only = System.getProperty("ntmgunfeel.checkBalance.only", "").trim();
        return only.isEmpty() || java.util.Arrays.asList(only.split("\\s*,\\s*")).contains(pass);
    }

    private static void plan() {
        String asked = System.getProperty("ntmgunfeel.checkBalance.only", "").trim();
        for (String p : asked.isEmpty() ? new String[0] : asked.split("\\s*,\\s*"))
            if (!List.of("2", "1g", "1v", "1a", "1b", "dns", "kit", "db", "hi", "expl", "esp", "pool", "pep", "hot").contains(p)) throw new IllegalArgumentException("no pass " + p);
        // the passes before the pool of DNS and FAU check their ratings: the pool off (X858) but in the pass "pool"
        STEPS.add(() -> keys("balance.immune.dns.mode", "formula", "balance.immune.fau.mode", "formula"));
        wait(30);
        if (only("2")) plan2();
        if (only("1g")) planAbilities();
        if (only("1v")) planRecipes();
        if (only("1a")) plan1a();
        if (only("1b")) plan1b();
        if (only("dns")) planDns();
        if (only("kit")) planKit();
        if (only("db")) planDb();
        if (only("hi")) planHigh();
        if (only("expl")) planExpl();
        if (only("esp")) planEsp();
        if (only("pool")) planPool();
        if (only("pep")) planPep();
        if (only("hot")) planHot();
    }

    // ---------------------------------------------------------------- melee and the flamethrower (hotfix X866)

    /** a target of the pass hot: an FBI agent, a zombie or the player, bare or in Security */
    private static LivingEntity hotTarget(String who, String set) {
        LivingEntity v;
        if (who.equals("player")) v = target;
        else {
            var created = who.equals("fbi") ? BuiltInRegistries.ENTITY_TYPE.getValue(FBI).create(level, EntitySpawnReason.COMMAND)
                    : EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
            if (!(created instanceof Mob mob)) throw new IllegalStateException("no " + who);
            mob.setNoAi(true);
            level.addFreshEntity(mob);
            v = mob;
            // the stand's player out of the way: it stood where the mob stands and took its shots
            for (EquipmentSlot slot : SLOTS) target.setItemSlot(slot, ItemStack.EMPTY);
            wearAttributes(target);
            target.snapTo(O.getX() + 6.5, O.getY(), O.getZ() + 0.5 + DIST, 180F, 0F);
        }
        for (EquipmentSlot slot : SLOTS) v.setItemSlot(slot, ItemStack.EMPTY);
        v.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        if (!set.isEmpty()) dress(v, set, true);
        if (v == target) wearAttributes(v);
        v.snapTo(O.getX() + 0.5, O.getY(), O.getZ() + 0.5 + DIST, 180F, 0F);
        v.setHealth(v.getMaxHealth());
        v.clearFire();
        com.hbm.extprop.HbmLivingProps.getData(v).fire = 0;
        return v;
    }

    /** the modifiers wearAttributes put on the stand's player, taken off before the next */
    private static final List<Runnable> WORN = new ArrayList<>();

    /**
     * The stand's player does not tick, so vanilla never puts its pieces' armour on it: the pieces' modifiers (after
     * balance.vanilla0's event) put on by hand, as a worn set has them in the game.
     */
    private static void wearAttributes(LivingEntity v) {
        WORN.forEach(Runnable::run);
        WORN.clear();
        for (EquipmentSlot slot : SLOTS) v.getItemBySlot(slot).forEachModifier(slot, (attr, mod) -> {
            var inst = v.getAttribute(attr);
            if (inst == null || inst.hasModifier(mod.id())) return;
            inst.addTransientModifier(mod);
            WORN.add(() -> inst.removeModifier(mod.id()));
        });
    }

    /** the hits of no shot the hot pass deals the player: the source's name and the damage */
    private static final String[][] HOT_HURTS = {{"удар_игрока", "10"}, {"crucible", "5001"}, {"удар_моба", "10"}, {"стрела", "9"}, {"лава", "4"},
            {"огонь", "1"}, {"горение", "1"}, {"падение", "10"}, {"tnt", "30"}, {"молния", "5"}, {"магия", "5"}, {"hbm:radiation", "5"},
            {"hbm:electricity", "5"}, {"hbm:electrified", "5"}, {"hbm:flamethrower", "3"}, {"hbm:acid_player", "3"}, {"hbm:monoxide", "3"},
            {"hbm:cloud", "3"}, {"hbm:physical_игрок", "10"}};

    private static net.minecraft.world.damagesource.DamageSource hotSource(String name) {
        var ds = level.damageSources();
        return switch (name) {
            case "удар_игрока", "crucible" -> ds.playerAttack(gunner);
            case "удар_моба" -> ds.mobAttack(shooter);
            case "стрела" -> {
                var arrow = (net.minecraft.world.entity.projectile.arrow.AbstractArrow) EntityTypes.ARROW.create(level, EntitySpawnReason.COMMAND);
                yield ds.arrow(arrow, shooter);
            }
            case "лава" -> ds.lava();
            case "огонь" -> ds.inFire();
            case "горение" -> ds.onFire();
            case "падение" -> ds.fall();
            case "tnt" -> ds.explosion(null, null);
            case "молния" -> ds.lightningBolt();
            case "магия" -> ds.magic();
            // a gun's own blow: NTM's physical from the player
            case "hbm:physical_игрок" -> new net.minecraft.world.damagesource.DamageSource(level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.DAMAGE_TYPE)
                    .getOrThrow(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DAMAGE_TYPE, Identifier.fromNamespaceAndPath("hbm", "physical"))), gunner, gunner);
            default -> ds.source(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DAMAGE_TYPE, Identifier.parse(name)));
        };
    }

    private static final String[] HOT_GUNS = {"огнемёт flame_diesel", "topaz flame_topaz_diesel", "daybreaker flame_daybreaker_diesel",
            "laser_pistol capacitor", "henry m44_express"};

    private static final String[][] HOT_MODES = {{"NTM", "balanceArmor", "false", "balanceGuns", "false", "damageCap", "false"}, {"баланс"},
            {"баланс без modelHitboxes", "modelHitboxes", "false"}};

    /**
     * The owner: the Crucible took one heart off a player in Security, the flamethrower nothing off an FBI agent. Melee
     * and a mob's blow by NTM's numbers (as with the balance off); the flamethrower, a laser and a bullet on an agent,
     * a zombie and the player, bare and in Security: what they lost and whether they burn.
     */
    private static void planHot() {
        LINES.add("#\t--- hot: ближний бой и огнемёт по наборам (баланс / NTM)");
        String[] sets = {"", "security", "t51", "bj", "dns"};
        float[] ntmHurt = new float[sets.length * HOT_HURTS.length];
        for (String[] mode : HOT_MODES) {
            boolean ntm = mode[0].equals("NTM");
            STEPS.add(() -> {
                keys("balanceArmor", "true", "balanceGuns", "true", "damageCap", "true", "damageCapMobs", "false", "modelHitboxes", "true");
                for (int i = 1; i < mode.length; i += 2) keys(mode[i], mode[i + 1]);
                shooter.setInvulnerable(true);
            });
            wait(30);
            // the hits of no shot (a blow, a mob's, an arrow, fire, a fall, gas...) on the player, by set: NTM's numbers
            for (int si = 0; si < sets.length; si++) {
                String set = sets[si];
                for (int ci = 0; ci < HOT_HURTS.length; ci++) {
                    int idx = si * HOT_HURTS.length + ci;
                    String[] hurt = HOT_HURTS[ci];
                    STEPS.add(() -> hotTarget("player", set));
                    wait(3);
                    STEPS.add(() -> {
                        LivingEntity v = target;
                        begin(v);
                        v.invulnerableTime = 0;
                        float hp = v.getHealth();
                        float amount = Float.parseFloat(hurt[1]);
                        v.hurtServer(level, hotSource(hurt[0]), amount);
                        float took = hp - v.getHealth();
                        String what = hurt[0] + " " + hurt[1] + " по игроку " + (set.isEmpty() ? "без брони" : "в " + set);
                        LINES.add("#\thot\t" + mode[0] + "\t" + what + "\t" + f(took) + "\tваниль " + v.getArmorValue());
                        if (ntm) ntmHurt[idx] = took;
                        // a big blast (TNT) is the formula's by the balance's notes (balanceArmorBlasts): logged only
                        else if (mode.length == 1 && !hurt[0].equals("tnt")) check(Math.abs(took - ntmHurt[idx]) <= 0.05F + 0.02F * ntmHurt[idx],
                                "hot: " + what + ": " + f(took) + " = NTM " + f(ntmHurt[idx]));
                        v.clearFire();
                    });
                    wait(2);
                }
            }
            for (String who : new String[] {"fbi", "zombie", "player"})
                for (String set : new String[] {"", "security"})
                    for (int g = 0; g < HOT_GUNS.length; g++) {
                        int gi = g;
                        LivingEntity[] v = {null};
                        float[] hp = {0F};
                        STEPS.add(() -> {
                            // Daybreaker's flames go off: the floor and the air back, the shooter home
                            for (int x = -6; x <= 8; x++) for (int z = -4; z <= 12; z++) {
                                level.setBlockAndUpdate(O.offset(x, -1, z), Blocks.BEDROCK.defaultBlockState());
                                for (int y = 0; y <= 3; y++) level.setBlockAndUpdate(O.offset(x, y, z), Blocks.AIR.defaultBlockState());
                            }
                            v[0] = hotTarget(who, set);
                            begin(v[0]);
                            hp[0] = v[0].getHealth();
                        });
                        wait(3);
                        for (int c = 0; c < 6; c++) {
                            STEPS.add(() -> {
                                shooter.snapTo(O.getX() + 0.5, O.getY(), O.getZ() + 0.5, 0F, 0F);
                                shooter.setDeltaMovement(Vec3.ZERO);
                                if (v[0] != target) v[0].snapTo(O.getX() + 0.5, O.getY(), O.getZ() + 0.5 + DIST, 180F, 0F);
                                v[0].setDeltaMovement(Vec3.ZERO);
                                switch (gi) {
                                    case 0 -> fire(shooter, ModItems.GUN_FLAMER.get(), com.hbm.items.weapon.sedna.factory.XFactoryFlamer.flame_diesel, EnumKeybind.GUN_PRIMARY);
                                    case 1 -> fire(shooter, ModItems.GUN_FLAMER_TOPAZ.get(), com.hbm.items.weapon.sedna.factory.XFactoryFlamer.flame_topaz_diesel, EnumKeybind.GUN_PRIMARY);
                                    case 2 -> fire(shooter, ModItems.GUN_FLAMER_DAYBREAKER.get(), com.hbm.items.weapon.sedna.factory.XFactoryFlamer.flame_daybreaker_diesel, EnumKeybind.GUN_PRIMARY);
                                    case 3 -> fire(shooter, ModItems.GUN_LASER_PISTOL.get(), XFactoryEnergy.energy_las, EnumKeybind.GUN_PRIMARY);
                                    default -> fire(shooter, ModItems.GUN_HENRY.get(), XFactory44.m44_express, EnumKeybind.GUN_PRIMARY);
                                }
                            });
                            wait(4);
                        }
                        wait(40);
                        STEPS.add(() -> {
                            String gun = HOT_GUNS[gi];
                            float took = hp[0] - Math.max(0F, v[0].getHealth());
                            int fire = com.hbm.extprop.HbmLivingProps.getData(v[0]).fire;
                            String what = gun + " ×6 по " + who + (set.isEmpty() ? " без брони" : " в " + set);
                            LINES.add("#\thot\t" + mode[0] + "\t" + what + "\tснято " + f(took) + (v[0].isAlive() ? "" : " (умер)") + ", огонь NTM " + fire
                                    + ", горит " + v[0].getRemainingFireTicks() + ", входящих " + f(incoming) + " (" + incomingNote + "), ваниль " + v[0].getArmorValue());
                            // the agent: NTM registers it fireImmune, a flame takes nothing off it there either
                            if (!mode[0].equals("NTM") && gi <= 2 && !who.equals("fbi")) check(took > 0F, "hot, " + mode[0] + ": " + what + ": урон есть (" + f(took) + ")");
                            if (v[0] != target) v[0].discard();
                            else target.clearFire();
                        });
                        wait(5);
                    }
        }
        STEPS.add(() -> {
            keys("modelHitboxes", "true");
            shooter.setInvulnerable(false);
            for (EquipmentSlot slot : SLOTS) target.setItemSlot(slot, ItemStack.EMPTY);
            wearAttributes(target);
            target.snapTo(O.getX() + 0.5, O.getY(), O.getZ() + 0.5 + DIST, 180F, 0F);
        });
        wait(20);
    }

    // ---------------------------------------------------------------- Pepperbox: 13th-century tech

    private static final String[] PEP_SETS = {"", "steel", "dieselsuit", "t51", "hev", "security"};
    private static final String[] PEP_AMMO = {"stone", "stone_ap", "stone_iron", "stone_shot"};
    private static float pepMaxClick;
    /** a click killed from full health */
    private static boolean pepFromFull;

    private static BulletConfig pepAmmo(String a) {
        return switch (a) {
            case "stone" -> com.hbm.items.weapon.sedna.factory.XFactoryBlackPowder.stone;
            case "stone_ap" -> com.hbm.items.weapon.sedna.factory.XFactoryBlackPowder.flint;
            case "stone_iron" -> com.hbm.items.weapon.sedna.factory.XFactoryBlackPowder.iron;
            default -> com.hbm.items.weapon.sedna.factory.XFactoryBlackPowder.shot;
        };
    }

    /**
     * Pepperbox against the low ladder, 4 blocks: clicks to death on the body, and on the head (the most a click took:
     * a pellet round has no sniper's head cap, so no click kills from full health). Seconds = the gun's cycle: a click
     * every 27 ticks, 6 to a cylinder, 67 to reload.
     */
    private static void planPep() {
        LINES.add("#\t--- Pepperbox: кликов до смерти по наборам (тело / голова), 4 бл; секунды — 27 тиков клик, 6 в барабане, 67 перезарядка");
        STEPS.add(() -> keys("balanceArmor", "true", "balanceGuns", "true", "damageCap", "true", "damageCapMobs", "false"));
        wait(30);
        for (String a : PEP_AMMO) for (String set : PEP_SETS) for (boolean head : new boolean[] {false, true}) {
            STEPS.add(() -> {
                begin(target);
                for (EquipmentSlot slot : SLOTS) target.setItemSlot(slot, ItemStack.EMPTY);
                if (!set.isEmpty()) dress(target, set, false);
                target.snapTo(O.getX() + 0.5, O.getY(), O.getZ() + 0.5 + DIST, 180F, 0F);
                aimHead = head;
                pepMaxClick = 0F;
                pepFromFull = false;
            });
            wait(3);
            int[] end = {0};
            for (int i = 0; i < 40; i++) {
                float[] hp = {0F};
                STEPS.add(() -> {
                    if (deathClick > 0 || target.getHealth() <= 0F) return;
                    hp[0] = target.getHealth();
                    fire(shooter, ModItems.GUN_PEPPERBOX.get(), pepAmmo(a), EnumKeybind.GUN_PRIMARY);
                    clicks++;
                });
                for (int t = 0; t < SPACING; t++) STEPS.add(() -> {
                    if (deathClick <= 0 && clicks > 0 && target.getHealth() <= 0F) {
                        // the killing click took all that was left
                        pepMaxClick = Math.max(pepMaxClick, hp[0]);
                        pepFromFull |= hp[0] >= target.getMaxHealth() - 0.01F;
                        deathClick = clicks;
                        skipTo = end[0];
                    }
                });
                STEPS.add(() -> pepMaxClick = Math.max(pepMaxClick, hp[0] - Math.max(0F, target.getHealth())));
            }
            end[0] = STEPS.size();
            STEPS.add(() -> {
                aimHead = false;
                int n = deathClick;
                String secs = n > 0 ? f(pepSeconds(n)) : "-";
                LINES.add("#\tpep\t" + a + "\t" + (set.isEmpty() ? "без брони" : set) + "\t" + (head ? "голова" : "тело") + "\t" + (n > 0 ? n : "> " + clicks)
                        + "\t" + secs + " с\tбольше всего за клик " + f(pepMaxClick));
                // no click from full health kills: a pellet round is no sniper's
                if (a.equals("stone_shot")) check(!pepFromFull && pepMaxClick <= 0.8F * target.getMaxHealth() + 0.05F, "pep: stone_shot " + (head ? "в голову" : "в тело") + ", "
                        + (set.isEmpty() ? "без брони" : set) + ": клик снял не больше 16 (взял " + f(pepMaxClick) + ")");
            });
            wait(10);
        }
    }

    /** seconds to the n-th click: a click every 27 ticks, after each 6 a reload of 67 */
    private static float pepSeconds(int n) {
        int t = 0;
        for (int i = 1; i < n; i++) t += i % 6 == 0 ? 67 : 27;
        return t / 20F;
    }

    // ---------------------------------------------------------------- stage 3: NTM's blasts on the ladder

    /** an explosive of stage 3: the model's numbers, and the hand grenade's filling or the launcher and its round */
    private record Blast3(BalanceBlasts.Boom boom, com.hbm.items.weapon.grenade.ItemGrenadeFilling.EnumGrenadeFilling filling, Supplier<Item> gun,
            Supplier<BulletConfig> ammo) {
    }

    private static final List<Blast3> BLASTS = List.of(
            new Blast3(new BalanceBlasts.Boom("граната порох", false, 10, 5, 5, 0), com.hbm.items.weapon.grenade.ItemGrenadeFilling.EnumGrenadeFilling.POWDER, null, null),
            new Blast3(new BalanceBlasts.Boom("граната HE", false, 25, 7.5F, 10, 0.1F), com.hbm.items.weapon.grenade.ItemGrenadeFilling.EnumGrenadeFilling.HE, null, null),
            new Blast3(new BalanceBlasts.Boom("граната DEMO", false, 10, 5, 0, 0), com.hbm.items.weapon.grenade.ItemGrenadeFilling.EnumGrenadeFilling.DEMO, null, null),
            new Blast3(new BalanceBlasts.Boom("40 мм HE Congo Lake", true, 20, 5, 0, 0), null, ModItems.GUN_CONGOLAKE::get,
                    () -> com.hbm.items.weapon.sedna.factory.XFactory40mm.g40_he),
            new Blast3(new BalanceBlasts.Boom("40 мм HE MK108", true, 25, 5, 0, 0), null, ModItems.GUN_MK108::get,
                    () -> com.hbm.items.weapon.sedna.factory.XFactory40mm.g40_he),
            new Blast3(new BalanceBlasts.Boom("ракета Panzerschreck", true, 25, 5, 0, 0), null, ModItems.GUN_PANZERSCHRECK::get, () -> XFactoryRocket.rocket_rpzb[0]),
            new Blast3(new BalanceBlasts.Boom("ракета Stinger", true, 35, 5, 0, 0), null, ModItems.GUN_STINGER::get, () -> XFactoryRocket.rocket_rpzb[0]),
            new Blast3(new BalanceBlasts.Boom("ракета Missile Launcher", true, 50, 5, 0, 0), null, ModItems.GUN_MISSILE_LAUNCHER::get, () -> XFactoryRocket.rocket_ml[0]));

    /** the sets of 10.4: no armour, then the ladder from tier 1 to 8 */
    private static final String[] SETS3 = {"", "steel", "dieselsuit", "t51", "steamsuit", "hev", "ajr", "security", "trenchmaster", "bj", "ncrpa",
            "schrabidium", "rpa", "fau", "dns"};
    /** where the target stands: straight in front of the mannequin, 6 blocks off */
    private static final double EXPL_DIST = 6;
    /** the damage of a launcher's round as the gun fired it (its first shot at the target), by its name */
    private static final java.util.Map<String, Float> SHOT_DAMAGE = new java.util.HashMap<>();
    /** what the target lost since ready3, but its falls (a big blast throws it up) */
    private static float blastLost;
    private static final List<Float> CELL = new ArrayList<>();
    private static final List<String> TSV3 = new ArrayList<>();

    private static Vec3 spot3() {
        return new Vec3(O.getX() + 0.5, O.getY(), O.getZ() + 0.5 + EXPL_DIST);
    }

    /** the target in a set (or bare), its shield emptied (the model counts none), standing on its spot */
    private static void ready3(String set) {
        begin(target);
        target.removeAllEffects();
        target.setForcedPose(null);
        for (EquipmentSlot slot : SLOTS) target.setItemSlot(slot, ItemStack.EMPTY);
        if (!set.isEmpty()) dress(target, set, true);
        var props = com.hbm.extprop.HbmPlayerProps.getData(target);
        props.maxShield = 0F;
        props.shield = 0F;
        Vec3 t = spot3();
        target.snapTo(t.x, t.y, t.z, 180F, 0F);
        target.setDeltaMovement(Vec3.ZERO);
        target.fallDistance = 0;
        shooter.setHealth(shooter.getMaxHealth());
        blastLost = 0F;
        pose3(false);
    }

    /**
     * The target's posed model, as a player's client sends it (ModelPosePayload → ModelHits): a dedicated server with
     * no client has none, and blastParts goes by the parts only with one. A player's parts in blocks (the model's 1/16):
     * standing, or lying along z (the crawling pose, the head to -z).
     */
    private static void pose3(boolean lying) {
        List<ModelBox> boxes = lying ? List.of(
                new ModelBox("head", -.25, 0, -.9, .5, 0, 0, 0, .5, 0, 0, 0, .5),
                new ModelBox("body", -.25, 0, -.4, .5, 0, 0, 0, .25, 0, 0, 0, .75),
                new ModelBox("right_arm", -.5, 0, -.4, .25, 0, 0, 0, .25, 0, 0, 0, .75),
                new ModelBox("left_arm", .25, 0, -.4, .25, 0, 0, 0, .25, 0, 0, 0, .75),
                new ModelBox("right_leg", -.25, 0, .35, .25, 0, 0, 0, .25, 0, 0, 0, .75),
                new ModelBox("left_leg", 0, 0, .35, .25, 0, 0, 0, .25, 0, 0, 0, .75))
                : List.of(
                new ModelBox("head", -.25, 1.5, -.25, .5, 0, 0, 0, .5, 0, 0, 0, .5),
                new ModelBox("body", -.25, .75, -.125, .5, 0, 0, 0, .75, 0, 0, 0, .25),
                new ModelBox("right_arm", -.5, .75, -.125, .25, 0, 0, 0, .75, 0, 0, 0, .25),
                new ModelBox("left_arm", .25, .75, -.125, .25, 0, 0, 0, .75, 0, 0, 0, .25),
                new ModelBox("right_leg", -.25, 0, -.125, .25, 0, 0, 0, .75, 0, 0, 0, .25),
                new ModelBox("left_leg", 0, 0, -.125, .25, 0, 0, 0, .75, 0, 0, 0, .25));
        ModelHits.put(level, target, 0F, boxes);
    }

    /** one blast of {@code b} at {@code r} blocks to the target's side ({@code r} &lt; 0: a launcher's round on it) */
    private static void boom3(Blast3 b, float r) {
        Vec3 t = spot3();
        if (!b.boom().launched()) {
            grenadeAt(b, t.x + r, t.z);
            return;
        }
        // the gun's own round, its damage as the gun's receiver gives it, with the mannequin its shooter: on the target
        // from 15 blocks (NTM's rockets pass through anyone in their first 3 ticks), or falling onto the ground near it
        // (its own impact, our contact and blast shares, NTM's blast). Some launchers will not fire for a stand
        // (Stinger's lock-on), so the round is the stand's, its path the game's.
        BulletConfig c = b.ammo().get();
        float dmg = roundDamage(b);
        com.hbm.entity.projectile.EntityBulletBaseMK4 round;
        if (r < 0F) {
            double dist = 15, drop = c.velocity > 0 ? c.gravity * Math.pow(dist / c.velocity, 2) / 2 : 0;
            Vec3 from = new Vec3(t.x, t.y + 1.2, t.z - dist), d = new Vec3(t.x, t.y + 1.0 + drop, t.z).subtract(from).normalize();
            round = new com.hbm.entity.projectile.EntityBulletBaseMK4(level, shooter, c, dmg, -c.spread, from.x, from.y, from.z, d.x, d.y, d.z);
        } else round = new com.hbm.entity.projectile.EntityBulletBaseMK4(level, shooter, c, dmg, -c.spread, t.x + r, t.y + 1.2, t.z, 0, -1, 0);
        level.addFreshEntity(round);
    }

    /** a launcher's round's damage: its receiver's base damage times the round's multiplier (Lego.calcDamage, new gun) */
    private static float roundDamage(Blast3 b) {
        ItemStack stack = loaded(b.gun().get(), b.ammo().get());
        var receiver = ((ItemGunBaseNT) stack.getItem()).getConfig(stack, 0).getReceivers(stack)[0];
        float d = receiver.getBaseDamage(stack) * b.ammo().get().damageMult;
        SHOT_DAMAGE.put(b.boom().name(), d);
        return d;
    }

    private static void planExpl() {
        LINES.add("#\t--- этап 3: взрывы NTM по лестнице брони, стоя, blastParts и blastPartsEven; урон за взрыв (среднее) и взрывов до смерти, стенд / модель");
        TSV3.add("взрывчатка\tгде\tнабор\tвзрывов\tблок\tурон за взрыв\tстенд, до смерти\tмодель, урон\tмодель, до смерти\tD снаряда");
        STEPS.add(() -> {
            kitKeys(true);
            keys("blastParts", "true", "blastPartsEven", "true", "damageCapBlasts", "true", "balanceArmorBlasts", "true", "balanceExplRawLimit", "60");
            // the mannequin (the rounds' and grenades' shooter) out of the rounds' way and the blasts' reach
            shooter.snapTo(O.getX() + 8.5, O.getY(), O.getZ() + 0.5, 0F, 0F);
            // DEMO breaks blocks: bedrock under the target's side
            for (int x = -6; x <= 6; x++) for (int z = 0; z <= 12; z++) level.setBlockAndUpdate(O.offset(x, -1, z), Blocks.BEDROCK.defaultBlockState());
        });
        wait(45);
        for (Blast3 b : BLASTS) {
            float[] where = b.boom().launched() ? new float[] {-1F, 1F, 3F} : new float[] {1F, 3F};
            for (float r : where) for (String set : SETS3) {
                boolean trench = set.equals("trenchmaster");
                int n = trench ? 9 : 2;
                int wait = !b.boom().launched() ? 3 : r < 0F ? 25 : 10;
                STEPS.add(CELL::clear);
                for (int i = 0; i < n; i++) {
                    STEPS.add(() -> {
                        ready3(set);
                        boom3(b, r);
                    });
                    wait(wait);
                    STEPS.add(() -> {
                        CELL.add(blastLost);
                        target.setHealth(target.getMaxHealth());
                    });
                }
                STEPS.add(() -> cell3(b, r, set));
            }
        }
        STEPS.add(BalanceCheck::writeTsv3);
        planExplRules();
    }

    private static String where3(float r) {
        return r < 0F ? "прямое" : Math.round(r) + " бл";
    }

    private static String hits3(int n) {
        return n == Integer.MAX_VALUE ? "∞" : String.valueOf(n);
    }

    private static void cell3(Blast3 b, float r, String set) {
        boolean trench = set.equals("trenchmaster");
        int blocked = 0;
        float sum = 0F;
        for (float l : CELL) {
            sum += l;
            if (trench && l <= 0F) blocked++;
        }
        float mean = CELL.isEmpty() ? 0F : sum / CELL.size();
        int game = BalanceBlasts.hits(mean);
        String name = set.isEmpty() ? "без брони" : set;
        BalanceArmor.SetOf def = set.isEmpty() ? null : BalanceArmor.SETS.get(set);
        float model = BalanceBlasts.damage(b.boom(), r, set.isEmpty() ? null : set, set.isEmpty() ? 0F : BalanceArmor.rating(set), def == null ? 0F : def.tau(),
                BalanceArmor.blastPower());
        int modelHits = BalanceBlasts.hits(model);
        Float shot = SHOT_DAMAGE.get(b.boom().name());
        TSV3.add(b.boom().name() + "\t" + where3(r) + "\t" + name + "\t" + CELL.size() + "\t" + blocked + "\t" + f(mean) + "\t" + hits3(game) + "\t" + f(model)
                + "\t" + hits3(modelHits) + "\t" + (b.boom().launched() ? (shot == null ? "-" : f(shot)) : f(b.boom().d())) + "\t" + CELL);
        String what = b.boom().name() + ", " + where3(r) + ", " + name + ": урон за взрыв " + f(mean) + ", до смерти " + hits3(game) + " (модель " + f(model) + " / "
                + hits3(modelHits) + (trench ? ", блок " + blocked + " из " + CELL.size() : "") + ")";
        // a blast that takes nothing where the model has a number: the hole of 10.2 again
        if (game == Integer.MAX_VALUE && modelHits != Integer.MAX_VALUE) {
            check(false, what + ": ∞ на стенде");
            return;
        }
        // the damage of a blast against the model's; the hits differ by more on a rounding edge (9.8 and 10.0: 3 and 2)
        float off = model > 0F ? Math.abs(mean - model) / model : 0F;
        String line = what + ", урон " + (mean >= model ? "+" : "−") + Math.round(off * 100) + " %";
        if (off > 0.15F) check(false, line + " (больше 15 %)");
        else LINES.add("#\t" + line);
    }

    private static void writeTsv3() {
        try {
            Path out = Path.of(System.getProperty(PROPERTY)).resolveSibling("explosions.tsv");
            Files.createDirectories(out.getParent());
            Files.write(out, TSV3);
            LINES.add("#\tтаблица взрывов: " + out);
        } catch (java.io.IOException e) {
            check(false, "запись explosions.tsv: " + e);
        }
    }

    /** a blast of NTM's EXPLOSIVE class from the mannequin at {@code r} blocks to the target's side, of {@code damage} */
    private static void plainBlast(float r, float damage) {
        Vec3 t = spot3();
        var v = new com.hbm.explosion.vanillant.ExplosionVNT(level, t.x + r, t.y + 0.15, t.z, 5F, shooter);
        v.setEntityProcessor(new com.hbm.explosion.vanillant.standard.EntityProcessorCrossSmooth(1, damage));
        v.setPlayerProcessor(new com.hbm.explosion.vanillant.standard.PlayerProcessorStandard());
        v.explode();
    }

    private static float took() {
        return blastLost;
    }

    /** a step of the rules: the target made ready in {@code set}, {@code act}, then after {@code ticks} {@code then} */
    private static void rule3(String set, Runnable act, int ticks, Runnable then) {
        STEPS.add(() -> {
            ready3(set);
            act.run();
        });
        wait(ticks);
        STEPS.add(() -> {
            then.run();
            target.setHealth(target.getMaxHealth());
        });
    }

    private static float stood, walled;
    private static final float[] LYING = new float[3];

    /** a hand grenade of {@code b} gone off on the ground at x, z, the mannequin its thrower */
    private static void grenadeAt(Blast3 b, double x, double z) {
        var grenade = new com.hbm.entity.grenade.EntityGrenadeUniversal(level, com.hbm.items.weapon.grenade.ItemGrenadeUniversal.make(
                com.hbm.items.weapon.grenade.ItemGrenadeShell.EnumGrenadeShell.STICK, b.filling(), com.hbm.items.weapon.grenade.ItemGrenadeFuze.EnumGrenadeFuze.IMPACT));
        grenade.setThrower(shooter);
        grenade.setPos(x, spot3().y + 0.15, z);
        grenade.explode();
    }

    /** the cap on blasts, the raw limit, the nuclear ones, lying and a wall; the keys off */
    private static void planExplRules() {
        LINES.add("#\t--- этап 3: правила взрывов (потолок, сырой урон, ядерные, лёжа, за стеной, ключи)");
        Blast3 he = BLASTS.get(1);
        // the click's cap on a blast: 60 % of 20
        rule3("", () -> boom3(he, 1F), 3, () -> check(took() > 11.9F && took() <= 12.01F,
                "потолок 60 % на EXPLOSIVE: граната HE в 1 бл, без брони (сырой ~23,8): снято " + f(took()) + " (ждали 12)"));
        // the head's cap: a blast's hurt through HeadDamage with the head's multiplier, as MixinExplosiveProcessor does it
        for (float m : new float[] {1.75F, 1F}) rule3("", () -> HeadDamage.attack(target, 30F, m,
                d -> com.hbm.util.EntityDamageUtil.attackEntityFromNT(target, level.damageSources().source(com.hbm.lib.ModDamageTypes.EXPLOSIVE, shooter), d, true,
                        false, 0, 0, 0)),
                3, () -> {
                    float want = m > 1F ? 16F : 12F;
                    check(Math.abs(took() - want) < 0.05F, "потолок на EXPLOSIVE" + (m > 1F ? " в голову 80 %" : " в тело 60 %") + ": удар взрыва 30 × " + f(m)
                            + " от стрелка, без брони: снято " + f(took()) + " (ждали " + f(want) + ")");
                });
        // the window: a rocket's contact and blast in two clicks, together at most 80 %
        // a round on the target: its contact and blast one hurt (ExplosiveDamage), one click; raw about 50 (the blast a hair off the box)
        rule3("", () -> boom3(BLASTS.get(7), -1F), 25, () -> check(took() > 11.9F && took() <= 12.01F,
                "потолок 60 %: ракета Missile Launcher прямое, без брони (контакт 30 + взрыв 20): снято " + f(took()) + " (ждали 12)"));
        rule3("hev", () -> boom3(BLASTS.get(7), -1F), 25, () -> check(BalanceArmor.seenHits == 1 && Math.abs(BalanceArmor.seenIn - 50F) < 2F,
                "ракета Missile Launcher прямое, HEV: контакт и взрыв одним ударом (ударов через броню " + BalanceArmor.seenHits + ", сырой "
                        + f(BalanceArmor.seenIn) + ", ждали 1 и ≈50)"));
        // a blast of more raw damage than balanceExplRawLimit: not capped (100 at 1 of 5: 80 raw)
        rule3("", () -> plainBlast(1F, 100F), 3, () -> check(took() > 12.5F,
                "сырой урон > 60 мимо потолка: взрыв 100 в 1 бл (сырой ~93), без брони: снято " + f(took()) + " (ждали больше 12)"));
        rule3("", () -> plainBlast(1F, 60F), 3, () -> check(took() <= 12.01F && took() > 11.9F,
                "сырой урон ≤ 60 под потолком: взрыв 60 в 1 бл (сырой ~56), без брони: снято " + f(took()) + " (ждали 12)"));
        // just over the limit raw, well under it after HEV: the cap looks at the raw damage
        rule3("hev", () -> plainBlast(1F, 70F), 3, () -> check(took() > 12.5F && BalanceArmor.seenIn > 60F && BalanceArmor.seenOut < 60F,
                "сырой урон > 60 мимо потолка: взрыв 70 в 1 бл, HEV: сырой " + f(BalanceArmor.seenIn) + ", после брони " + f(BalanceArmor.seenOut) + ", снято "
                        + f(took()) + " (ждали больше 12)"));
        // nuclear: NTM's ExplosionNukeGeneric.dealDamage (NUCLEAR_BLAST, no shooter), and a nuclear hit from the mannequin
        rule3("", () -> com.hbm.explosion.ExplosionNukeGeneric.dealDamage(level, spot3().x + 1, spot3().y, spot3().z, 6), 3,
                () -> check(took() > 12.5F, "ядерный взрыв (ExplosionNukeGeneric.dealDamage, 1 бл): снято " + f(took()) + " (ждали больше 12)"));
        rule3("", () -> target.hurtServer(level, level.damageSources().source(com.hbm.lib.ModDamageTypes.NUCLEAR_BLAST, shooter), 30F), 3,
                () -> check(took() > 12.5F, "ядерный удар 30 от стрелка мимо потолка: снято " + f(took()) + " (ждали больше 12)"));
        // standing, lying, behind a wall: HE at 2 blocks on HEV (no cap in the way)
        STEPS.add(() -> {
            ready3("hev");
            LINES.add("#\tпоза игрока-цели (как от клиента): " + (ModelHits.hasPose(target) ? "есть, взрыв по частям" : "нет (урон NTM целиком)"));
        });
        rule3("bj", () -> boom3(he, 2F), 3, () -> stood = took());
        // lying (the head to -z): the grenade 2 blocks from its side, from its head's end, from its feet's end
        double[][] lyingAt = {{2, 0}, {0, -0.9 - 2}, {0, 1.1 + 2}};
        String[] lyingName = {"боком", "головой к гранате", "ногами к гранате"};
        for (int k = 0; k < 3; k++) {
            int kk = k;
            rule3("bj", () -> {
                target.setForcedPose(net.minecraft.world.entity.Pose.SWIMMING);
                target.refreshDimensions();
                pose3(true);
                Vec3 t = spot3();
                grenadeAt(he, t.x + lyingAt[kk][0], t.z + lyingAt[kk][1]);
            }, 3, () -> {
                LYING[kk] = took();
                target.setForcedPose(null);
                target.refreshDimensions();
            });
        }
        rule3("bj", () -> {
            // a row of stone one high between the grenade (2 blocks to the side) and the target
            Vec3 t = spot3();
            for (int z = -3; z <= 3; z++) level.setBlockAndUpdate(BlockPos.containing(t.x + 1, t.y, t.z + z), Blocks.STONE.defaultBlockState());
            boom3(he, 2F);
        }, 3, () -> {
            walled = took();
            Vec3 t = spot3();
            for (int z = -3; z <= 3; z++) level.setBlockAndUpdate(BlockPos.containing(t.x + 1, t.y, t.z + z), Blocks.AIR.defaultBlockState());
            for (int k = 0; k < 3; k++) LINES.add("#\tграната HE в 2 бл, BJ, лёжа " + lyingName[k] + ": " + f(LYING[k]) + " (стоя " + f(stood) + ")");
            check(stood > 0F && LYING[1] < stood && LYING[2] < stood, "лёжа головой и ногами к гранате меньше, чем стоя: граната HE в 2 бл, BJ: стоя " + f(stood)
                    + ", лёжа головой " + f(LYING[1]) + ", ногами " + f(LYING[2]) + " (боком " + f(LYING[0]) + ")");
            check(stood > 0F && walled < stood, "за стеной меньше, чем стоя: граната HE в 2 бл, стена в 1 блок высотой, BJ: стоя " + f(stood) + ", за стеной " + f(walled));
        });
        // the keys off: NTM's
        STEPS.add(() -> keys("damageCapBlasts", "false"));
        wait(30);
        rule3("", () -> boom3(he, 1F), 3, () -> check(took() > 20F,
                "damageCapBlasts = false: граната HE в 1 бл без брони мимо потолка: снято " + f(took()) + " (сырой ~23,8)"));
        STEPS.add(() -> keys("damageCapBlasts", "true", "balanceArmorBlasts", "false"));
        wait(30);
        rule3("hev", () -> boom3(he, 1F), 3, () -> check(BalanceArmor.seenHits > 0 && BalanceArmor.seenOut == BalanceArmor.seenNtm,
                "balanceArmorBlasts = false: граната HE по HEV числами NTM (после брони " + f(BalanceArmor.seenOut) + " = NTM " + f(BalanceArmor.seenNtm)
                        + "), снято " + f(took())));
        STEPS.add(() -> keys("balanceArmorBlasts", "true"));
        wait(30);
        rule3("hev", () -> boom3(he, 1F), 3, () -> check(BalanceArmor.seenHits > 0 && BalanceArmor.seenOut != BalanceArmor.seenNtm,
                "balanceArmorBlasts = true: граната HE по HEV формулой C (после брони " + f(BalanceArmor.seenOut) + ", NTM " + f(BalanceArmor.seenNtm)
                        + "), снято " + f(took())));
        STEPS.add(() -> shooter.snapTo(O.getX() + 0.5, O.getY(), O.getZ() + 0.5, 0F, 0F));
    }

    // ---------------------------------------------------------------- stage 3: ESP (no players in VATS and thermal)

    /** NTM's suits whose bonus has VATS or thermal vision, and the item name of each */
    private static String espItems(com.hbm.items.armor.ModArmorItem.Suit s) {
        return switch (s.name()) {
            case "DIGAMMA" -> "fau";
            case "DIESEL" -> "dieselsuit";
            case "DESH" -> "steamsuit";
            default -> s.name().toLowerCase(Locale.ROOT);
        };
    }

    private static Mob espMob;

    /**
     * The viewer (a player) in each set with VATS or thermal vision asks the same decision as the client's renderer
     * (ArmorAbilities.thermalKeeps / vatsKeeps; MixinArmorEsp) about a player behind a wall 10 blocks off, in the open
     * 20 blocks off and in the open 10 blocks off, and about a zombie behind the wall; then AMAT Penance's sight, then
     * the keys off and all.
     */
    private static void planEsp() {
        LINES.add("#\t--- этап 3: ESP: VATS и термо не показывают игроков, кроме ступени 8 (16 бл, по прямой видимости)");
        BlockPos v = O.offset(-12, 0, 0);
        STEPS.add(() -> {
            keys("abilities.esp.players", "tier8", "abilities.esp.playerRange", "16");
            for (int x = -3; x <= 3; x++) for (int y = 0; y <= 3; y++) level.setBlockAndUpdate(v.offset(x, y, 5), Blocks.STONE.defaultBlockState());
            var zombie = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
            zombie.setNoAi(true);
            zombie.snapTo(v.getX() + 0.5, v.getY(), v.getZ() + 10.5, 180F, 0F);
            level.addFreshEntity(zombie);
            espMob = zombie;
        });
        wait(30);
        STEPS.add(() -> {
            List<String> seen = new ArrayList<>();
            for (var s : com.hbm.items.armor.ModArmorItem.Suit.values()) {
                var bonus = com.hbm.items.armor.ArmorFullSetBonus.get(s);
                if (bonus == null || !(bonus.vats() || bonus.thermal())) continue;
                String name = espItems(s);
                for (EquipmentSlot slot : SLOTS) gunner.setItemSlot(slot, ItemStack.EMPTY);
                try {
                    dress(gunner, name, true);
                } catch (IllegalStateException e) {
                    check(false, "ESP: набор " + s.name() + " (" + name + "): нет предметов");
                    continue;
                }
                int tier = ArmorAbilities.espTier(gunner);
                seen.add(s.name() + " " + tier);
                esp(s.name() + " (ступень " + tier + ", " + (bonus.thermal() ? "термо" : "") + (bonus.vats() ? " VATS" : "") + ")", tier, tier >= 8);
            }
            LINES.add("#\tESP: наборы с VATS/термо: " + seen);
            check(seen.stream().anyMatch(x -> x.startsWith("DIGAMMA 8")) && seen.stream().anyMatch(x -> x.startsWith("DNS 8")),
                    "ESP: FAU и DNS среди наборов с термо, ступень 8");
            for (EquipmentSlot slot : SLOTS) gunner.setItemSlot(slot, ItemStack.EMPTY);
            ItemStack penance = new ItemStack(ModItems.GUN_AMAT_PENANCE.get());
            var gun = (ItemGunBaseNT) penance.getItem();
            boolean sights = false;
            for (int i = 0; i < gun.getConfigCount(); i++) sights |= gun.getConfig(penance, i).hasThermalSights(penance);
            check(sights, "ESP: у AMAT Penance тепловой прицел (hasThermalSights)");
            esp("прицел AMAT Penance, без брони", ArmorAbilities.GUN_SIGHT_TIER, true);
            dress(gunner, "fau", true);
            keys("abilities.esp.players", "off");
        });
        wait(30);
        STEPS.add(() -> {
            String mode = ArmorAbilities.espModeNow();
            espCase("off, FAU", ArmorAbilities.espTier(gunner), mode, 10, false, false);
            keys("abilities.esp.players", "all");
            for (EquipmentSlot slot : SLOTS) gunner.setItemSlot(slot, ItemStack.EMPTY);
            dress(gunner, "t51", true);
        });
        wait(30);
        STEPS.add(() -> {
            String mode = ArmorAbilities.espModeNow();
            espCase("all (NTM), T-51b", ArmorAbilities.espTier(gunner), mode, 10, true, true);
            espCase("all (NTM), T-51b", ArmorAbilities.espTier(gunner), mode, 20, false, true);
            check(ArmorAbilities.vatsKeeps(mode, target), "ESP all (NTM): VATS показывает игрока");
            keys("abilities.esp.players", "tier8");
            for (EquipmentSlot slot : SLOTS) gunner.setItemSlot(slot, ItemStack.EMPTY);
            gunner.snapTo(O.getX() + 4.5, O.getY(), O.getZ() + 0.5, 0F, 0F);
            if (espMob != null) espMob.discard();
            for (int x = -3; x <= 3; x++) for (int y = 0; y <= 3; y++) level.setBlockAndUpdate(v.offset(x, y, 5), Blocks.AIR.defaultBlockState());
        });
        wait(30);
    }

    /** the three cases of stage 3 for a viewer of {@code tier}; {@code tier8}: only the open 10 blocks shows the player */
    private static void esp(String what, int tier, boolean tier8) {
        String mode = ArmorAbilities.espModeNow();
        espCase(what, tier, mode, 10, true, false);
        espCase(what, tier, mode, 20, false, false);
        espCase(what, tier, mode, 10, false, tier8);
        check(!ArmorAbilities.vatsKeeps(mode, target), "ESP " + what + ": VATS не показывает игрока");
        check(ArmorAbilities.vatsKeeps(mode, espMob) && ArmorAbilities.thermalKeeps(mode, ArmorAbilities.espRangeNow(), tier, gunner, espMob),
                "ESP " + what + ": зомби за стеной в 10 бл виден в термо и VATS, как в NTM");
    }

    /** the viewer at its spot, the target {@code dist} blocks in front ({@code wall}: behind the wall at 5) */
    private static void espCase(String what, int tier, String mode, int dist, boolean wall, boolean want) {
        BlockPos v = O.offset(-12, 0, 0);
        gunner.snapTo(v.getX() + 0.5, v.getY(), v.getZ() + 0.5, 0F, 0F);
        // behind the wall: straight on, the wall at +5; in the open: the other way (-z), nothing between
        double x = v.getX() + 0.5;
        double z = v.getZ() + 0.5 + (wall ? dist : -dist);
        target.snapTo(x, v.getY(), z, 180F, 0F);
        boolean keeps = ArmorAbilities.thermalKeeps(mode, ArmorAbilities.espRangeNow(), tier, gunner, target);
        check(keeps == want, "ESP " + what + ": игрок " + (wall ? "за стеной" : "на открытом месте") + " в " + f(gunner.distanceTo(target)) + " бл, видимость "
                + gunner.hasLineOfSight(target) + ": " + (keeps ? "виден" : "не виден") + " (ждали " + (want ? "виден" : "не виден") + ")");
    }

    // ---------------------------------------------------------------- big mults on weak ammo against low armour

    private record High(String gunName, Supplier<Item> gun, Supplier<BulletConfig> ammo, EnumKeybind key) {
    }

    // table v4: weak ammo raised x3 and more; one click on a player in lower armour must leave it alive (the cap 60 %)
    private static final List<High> HIGH = List.of(
            new High("double_barrel_sacred_dragon · g10 ×10,3, 1 ствол", ModItems.GUN_DOUBLE_BARREL_SACRED_DRAGON::get, () -> XFactory10ga.g10, EnumKeybind.GUN_SECONDARY),
            new High("double_barrel_sacred_dragon · g10_shrapnel ×10,3, 2 ствола", ModItems.GUN_DOUBLE_BARREL_SACRED_DRAGON::get, () -> XFactory10ga.g10_shrapnel, EnumKeybind.GUN_PRIMARY),
            new High("pepperbox · stone_shot ×2,5", ModItems.GUN_PEPPERBOX::get, () -> com.hbm.items.weapon.sedna.factory.XFactoryBlackPowder.shot, EnumKeybind.GUN_PRIMARY),
            new High("maresleg · g12_bp ×4,17", ModItems.GUN_MARESLEG::get, () -> XFactory12ga.g12_bp, EnumKeybind.GUN_PRIMARY),
            new High("light_revolver_atlas · m357_bp ×3,32", ModItems.GUN_LIGHT_REVOLVER_ATLAS::get, () -> XFactory357.m357_bp, EnumKeybind.GUN_PRIMARY));

    /** the hi pass: a hit of this click went to the head (1), a sniper's (2) */
    private static int headHit;

    private static void planHigh() {
        LINES.add("#\t--- большие множители слабых патронов против брони ниже: один клик не убивает (потолок 60 %, с головой 80 %)");
        STEPS.add(() -> {
            kitKeys(true);
            keys("balanceGuns", "true");
        });
        wait(45);
        for (High h : HIGH) for (String set : new String[] {"", "steel", "dieselsuit", "hev"}) {
            STEPS.add(() -> {
                begin(target);
                target.removeAllEffects();
                for (EquipmentSlot slot : SLOTS) target.setItemSlot(slot, ItemStack.EMPTY);
                if (!set.isEmpty()) dress(target, set, true);
                var props = com.hbm.extprop.HbmPlayerProps.getData(target);
                props.maxShield = 0F;
                props.shield = 0F;
                target.snapTo(O.getX() + 0.5, O.getY(), O.getZ() + 0.5 + 2, 180F, 0F);
                headHit = 0;
                DamageCap.last = "";
                fire(shooter, h.gun().get(), h.ammo().get(), h.key());
                clicks = 1;
            });
            wait(20);
            STEPS.add(() -> {
                float took = target.getMaxHealth() - Math.max(0F, target.getHealth());
                // a pellet in the head has the head's room of a click (0.8); a sniper's (a gun of 25 ticks and more) all of
                // it, so there a click may kill: written down, not checked (a question to the owner)
                String what = "один клик, 2 бл, " + (set.isEmpty() ? "без брони" : set) + " ← " + h.gunName() + ": снято " + f(took) + " HP из "
                        + f(target.getMaxHealth());
                if (headHit == 2) LINES.add("#\t" + what + ", дробина в голову, пушка снайперская по паузе (потолок 1,0): "
                        + (target.getHealth() > 0F ? "жив" : "убит") + ", ударов " + BalanceArmor.seenHits);
                else {
                    float cap = headHit == 1 ? 0.8F : 0.6F;
                    check(target.getHealth() > 0F && took <= cap * target.getMaxHealth() + 0.01F, what + " (ждали не больше " + f(cap * target.getMaxHealth())
                            + (headHit == 1 ? ", дробина в голову" : "") + ", жив), ударов " + BalanceArmor.seenHits);
                }
                target.setHealth(target.getMaxHealth());
            });
        }
        STEPS.add(() -> keys("balanceGuns", "false"));
        wait(30);
    }

    // ---------------------------------------------------------------- the double barrel (the owner: "too little damage")

    private static final String[] DB_AMMO = {"g10", "g10_shrapnel", "g10_slug", "g10_explosive"};
    /** the stand aims at the victim's eyes, not its chest */
    private static boolean aimHead;

    private static BulletConfig dbAmmo(String name) {
        return switch (name) {
            case "g10" -> XFactory10ga.g10;
            case "g10_shrapnel" -> XFactory10ga.g10_shrapnel;
            case "g10_slug" -> XFactory10ga.g10_slug;
            default -> XFactory10ga.g10_explosive;
        };
    }

    /** NTM, as main has it (every ammo x0.762 of g10_du's, the agent's vanilla 20), and after the owner's answers */
    private static void planDb() {
        LINES.add("#\t--- двустволка: игрок и FBI в Security, NTM / сейчас (main) / после");
        Path guns = FMLPaths.CONFIGDIR.get().resolve(BalanceGuns.FILE);
        // сейчас: main's table (every ammo of the double barrel took g10_du's x0.762), the agent's vanilla 20
        for (String mode : new String[] {"NTM", "сейчас", "после"}) {
            STEPS.add(() -> {
                try {
                    if (mode.equals("сейчас")) {
                        StringBuilder t = new StringBuilder(BalanceGuns.text());
                        for (String a : new String[] {"g10", "g10_shrapnel", "g10_slug", "g10_explosive"})
                            t.append("\ndouble_barrel\t").append(a).append("\t0.762\t0\t0\t3");
                        Files.writeString(guns, t.append('\n'));
                    } else Files.deleteIfExists(guns);
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
                kitKeys(!mode.equals("NTM"));
                boolean on = !mode.equals("NTM");
                keys("balanceArmor", Boolean.toString(on), "balanceGuns", Boolean.toString(on), "balance.fbiArmor", mode.equals("сейчас") ? "0" : "10");
            });
            wait(60);
            for (String a : DB_AMMO) for (EnumKeybind key : new EnumKeybind[] {EnumKeybind.GUN_PRIMARY, EnumKeybind.GUN_SECONDARY}) for (int kind = 0; kind < 3; kind++) {
                // NTM's primary fires both barrels (rounds 2), the secondary one; 0: the player, 1: the agent, 2: the agent at the head (shrapnel only: the owner's "one heart")
                if (kind == 2 && !a.equals("g10_shrapnel")) continue;
                boolean agent = kind > 0, head = kind == 2;
                String barrels = (key == EnumKeybind.GUN_PRIMARY ? "2 ствола" : "1 ствол") + (head ? ", в голову" : "");
                STEPS.add(() -> {
                    aimHead = head;
                    if (agent) {
                        var created = BuiltInRegistries.ENTITY_TYPE.getValue(FBI).create(level, EntitySpawnReason.COMMAND);
                        if (!(created instanceof Mob mob)) throw new IllegalStateException("no FBI agent");
                        fbi = mob;
                        fbi.setNoAi(true);
                        fbi.snapTo(O.getX() + 0.5, O.getY(), O.getZ() + 0.5 + DIST, 180F, 0F);
                        level.addFreshEntity(fbi);
                        dress(fbi, "security", true);
                        fbi.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                        begin(fbi);
                    } else {
                        begin(target);
                        target.removeAllEffects();
                        dress(target, "security", true);
                        var props = com.hbm.extprop.HbmPlayerProps.getData(target);
                        props.maxShield = 0F;
                        props.shield = 0F;
                        target.snapTo(O.getX() + 0.5, O.getY(), O.getZ() + 0.5 + DIST, 180F, 0F);
                    }
                });
                clicks(shooter, ModItems.GUN_DOUBLE_BARREL::get, dbAmmo(a), key);
                STEPS.add(() -> {
                    String who = agent ? "FBI в security (ваниль " + f((float) fbi.getAttributeValue(Attributes.ARMOR)) + ")" : "игрок в security";
                    row("двустволка " + mode, who, a + ", " + barrels);
                    LINES.add("#\tдвустволка\t" + mode + "\t" + who + "\t" + a + "\t" + barrels + "\tкликов " + (deathClick > 0 ? deathClick : "> " + clicks)
                            + "\tснято первым " + f(Math.max(0F, firstClick)) + "\tмножитель " + f(BalanceGuns.pair(ModItems.GUN_DOUBLE_BARREL.get(), dbAmmo(a)).mult()));
                    if (mode.equals("после")) check("table".equals(BalanceGuns.from(ModItems.GUN_DOUBLE_BARREL.get()).get(a).from()),
                            "двустволка после: " + a + " — своя строка таблицы v4 (×" + f(BalanceGuns.pair(ModItems.GUN_DOUBLE_BARREL.get(), dbAmmo(a)).mult()) + ")");
                    if (head) LINES.add("#\tдвустволка " + mode + ", шрапнель в голову FBI, " + barrels + ": первый клик снял " + f(Math.max(0F, firstClick))
                            + " HP, ударов по броне " + BalanceArmor.seenHits + " (до брони " + f(BalanceArmor.seenIn) + ", после " + f(BalanceArmor.seenOut) + ", NTM " + f(BalanceArmor.seenNtm) + ")");
                    aimHead = false;
                    if (agent) {
                        fbi.discard();
                        fbi = null;
                    }
                });
            }
        }
        STEPS.add(() -> {
            try {
                Files.deleteIfExists(guns);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            kitKeys(true);
            keys("balanceGuns", "false", "balance.fbiArmor", "10");
        });
        wait(60);
    }

    // ---------------------------------------------------------------- the kit in the tiers' pairs

    /** a pair of the kit's check: the clicks of the model (kit_shield.py) without the kit and with it, cap 30 and 16 */
    private record KitPair(String set, String gunName, Supplier<Item> gun, Supplier<BulletConfig> ammo, int base, int kit30, int kit16) {
    }

    // notes/balance/work/scripts/kit_pairs.py: the balance's guns at their own pause, the full kit = Med-X (Resistance
    // I), the shield at its cap (the mod and the flasks), a super stimpak taken at the first click
    private static final List<KitPair> KIT_PAIRS = List.of(
            new KitPair("dieselsuit", "henry · m44_express", ModItems.GUN_HENRY::get, () -> XFactory44.m44_express, 3, 7, 5),
            new KitPair("hev", "star_f · p22_jhp", ModItems.GUN_STAR_F::get, () -> XFactory22lr.p22_jhp, 6, 15, 12),
            new KitPair("hev", "carbine · r762_du", ModItems.GUN_CARBINE::get, () -> XFactory762mm.r762_du, 6, 15, 12),
            new KitPair("bj", "lasrifle · capacitor", ModItems.GUN_LASRIFLE::get, () -> XFactoryEnergy.energy_las, 4, 10, 8),
            new KitPair("fau", "tau · tau_uranium", ModItems.GUN_TAU::get, () -> XFactoryAccelerator.tau_uranium, 6, 16, 12));

    /** the super stimpak's heal goes on by the stand's tick (the fake player is not ticked by the level) */
    private static boolean healing;
    private static int targetTicks = Integer.MIN_VALUE;

    private static void planKit() {
        LINES.add("#\t--- комплект в парах ступеней: Med-X I + щит до потолка + супер-стимпак, клики с паузой пушки");
        for (int cap : new int[] {30, 16}) {
            String m = "потолок щита " + cap;
            STEPS.add(() -> {
                kitKeys(true);
                keys("balanceGuns", "true", "balance.shield.cap", Integer.toString(cap));
            });
            wait(45);
            for (KitPair p : KIT_PAIRS) {
                int[] base = {0};
                kitPairFight(p, false, m, base);
                kitPairFight(p, true, m, base);
                STEPS.add(() -> {
                    int want = cap == 30 ? p.kit30() : p.kit16();
                    float x = base[0] > 0 && deathClick > 0 ? (float) deathClick / base[0] : Float.NaN;
                    String what = m + ", " + p.set() + " ← " + p.gunName() + ": кликов " + base[0] + " → " + (deathClick > 0 ? deathClick : "> " + clicks)
                            + " (×" + f(x) + "; модель " + p.base() + " → " + want + ")";
                    boolean model = base[0] == p.base() && Math.abs(deathClick - want) <= 1;
                    check(model && (cap != 16 || x <= 2.0F), "комплект: " + what + (cap == 16 ? ", ждали не больше ×2" : ""));
                });
            }
        }
        STEPS.add(() -> {
            keys("balance.shield.cap", "16", "balanceGuns", "false");
            target.removeAllEffects();
            var props = com.hbm.extprop.HbmPlayerProps.getData(target);
            props.maxShield = 0F;
            props.shield = 0F;
        });
        wait(30);
    }

    private static void kitPairFight(KitPair p, boolean kit, String m, int[] base) {
        STEPS.add(() -> {
            begin(target);
            target.removeAllEffects();
            dress(target, p.set(), true);
            var props = com.hbm.extprop.HbmPlayerProps.getData(target);
            props.maxShield = 0F;
            props.shield = 0F;
            if (kit) {
                com.hbm.handler.ArmorModHandler.applyMod(target.getItemBySlot(EquipmentSlot.CHEST), hbm("australium_iii"));
                props.maxShield = 100F;
                // Med-X 5 s before the fight (its potion sickness over), the super stimpak at the first click
                for (String used : new String[] {"syringe_metal_medx", "syringe_metal_super"}) {
                    target.removeEffect(com.hbm.potion.HbmPotion.potionsickness());
                    ItemStack st = hbm(used);
                    target.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, st);
                    st.getItem().use(level, target, net.minecraft.world.InteractionHand.MAIN_HAND);
                }
                if (BalanceKit.healLeft(target) <= 0F || !target.hasEffect(net.minecraft.world.effect.MobEffects.RESISTANCE))
                    throw new IllegalStateException("the kit did not take: heal " + BalanceKit.healLeft(target));
                target.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                props.shield = props.getEffectiveMaxShield(target);
                healing = true;
            }
            target.snapTo(O.getX() + 0.5, O.getY(), O.getZ() + 0.5 + DIST, 180F, 0F);
        });
        int every = Math.max(2, delay(p.gun().get()));
        int[] end = {0};
        for (int i = 0; i < 60; i++) {
            STEPS.add(() -> {
                if (deathClick > 0 || victim.getHealth() <= 0F) return;
                fire(shooter, p.gun().get(), p.ammo().get(), EnumKeybind.GUN_PRIMARY);
                clicks++;
            });
            // a click every {@code every} ticks: the click's step, the empty ones, the check
            for (int t = 0; t < every - 2; t++) STEPS.add(() -> {
            });
            STEPS.add(() -> {
                if (deathClick <= 0 && clicks > 0 && victim.getHealth() <= 0F) {
                    deathClick = clicks;
                    skipTo = end[0];
                }
            });
        }
        end[0] = STEPS.size();
        STEPS.add(() -> {
            healing = false;
            BalanceKit.clearHeal(target);
            row(m, p.set() + (kit ? " + комплект" : ""), p.gunName());
            if (!kit) base[0] = deathClick;
        });
        wait(20);
    }

    private static void plan1a() {
        LINES.add("#\tрежим\tнабор\tпушка\tкликов до смерти\tснято первым кликом\tударов\tD за удар\tNTM за удар\tC за удар\tмодель: за удар / кликов");
        for (String mode : MODES) {
            STEPS.add(() -> keys("balanceArmor", mode.equals("NTM") ? "false" : "true", "balanceGuns", "false", "damageCap", "true", "damageCapMobs", "false"));
            wait(30);
            for (Pair p : PAIRS) pair(p, mode);
            fbiCase(mode);
            rocket(mode);
        }
    }

    // ---------------------------------------------------------------- stage 2: the kit, vanilla 0, euphemium

    private static final String[] KIT_KEYS = {"balance.shield", "balance.medx", "balance.superStimpak", "balance.inserts", "balance.vanilla0"};

    private static void kitKeys(boolean on) {
        List<String> kv = new ArrayList<>();
        for (String k : KIT_KEYS) {
            kv.add(k);
            kv.add(Boolean.toString(on));
        }
        kv.addAll(List.of("balance.immune.mode", on ? "pool" : "ntm", "balance.immune.pool", "2000", "balance.immune.window", "15", "balance.immune.recover", "60",
                "balanceArmor", "true", "balanceGuns", "false", "damageCap", "true", "damageCapMobs", "false"));
        keys(kv.toArray(new String[0]));
    }

    private static ItemStack hbm(String name) {
        ItemStack st = new ItemStack(item(name));
        if (st.isEmpty()) throw new IllegalStateException("no hbm:" + name);
        return st;
    }

    private static void plan2() {
        LINES.add("#\t--- этап 2: щит, Med-X, супер-стимпак, вставки, ваниль 0, евфемий");
        for (boolean on : new boolean[] {true, false}) {
            String m = on ? "баланс" : "NTM";
            STEPS.add(() -> kitKeys(on));
            wait(30);
            STEPS.add(() -> shield2(on, m));
            STEPS.add(() -> medicine2(on, m));
            STEPS.add(() -> inserts2(on, m));
            STEPS.add(() -> vanilla2(on, m));
            kitFight(on, 0);
            kitFight(on, 1);
            kitFight(on, 2);
        }
        STEPS.add(() -> kitKeys(true));
        wait(30);
        euph2();
        // the rest of the stand with the shipped keys
        STEPS.add(() -> {
            kitKeys(true);
            for (EquipmentSlot slot : SLOTS) target.setItemSlot(slot, ItemStack.EMPTY);
            target.removeAllEffects();
            var props = com.hbm.extprop.HbmPlayerProps.getData(target);
            props.maxShield = 0F;
            props.shield = 0F;
        });
        wait(30);
    }

    private static void shield2(boolean on, String m) {
        var props = com.hbm.extprop.HbmPlayerProps.getData(target);
        dress(target, "hev", true);
        ItemStack chest = target.getItemBySlot(EquipmentSlot.CHEST);
        com.hbm.handler.ArmorModHandler.applyMod(chest, hbm("australium_iii"));
        props.maxShield = 0F;
        float mod = props.getEffectiveMaxShield(target);
        props.maxShield = 100F;
        float all = props.getEffectiveMaxShield(target);
        check(on ? near(mod, 10F) && near(all, 16F) : near(mod, 25F) && near(all, 125F),
                "2: " + m + ": щит: мод " + f(mod) + ", мод и 20 фляг " + f(all) + (on ? " (ждали 10 и потолок 16)" : " (NTM: 25 и 125)"));
        // the shield comes back: 150 and 200 ticks after a hit, the mod alone
        props.maxShield = 0F;
        props.shield = 0F;
        props.lastDamage = target.tickCount;
        float at150 = 0F;
        for (int t = 1; t <= 200; t++) {
            target.tickCount++;
            com.hbm.handler.PlayerShield.tick(target);
            if (t == 150) at150 = props.shield;
        }
        check(on ? near(at150, 2.55F) && near(props.shield, 10F) : near(at150, 20.48F) && near(props.shield, 25F),
                "2: " + m + ": щит после удара: через 150 тиков " + f(at150) + ", через 200 " + f(props.shield)
                        + (on ? " (ждали 2,55 и 10: пауза 100, 0,002·t)" : " (NTM: 20,48 и 25: пауза 60, 0,005·t)"));
        // a flask: NTM's count +5, the shield's most +2 (NTM +5); no more once the flasks alone reach the cap
        dress(target, "hev", true);
        props.maxShield = 70F;
        props.shield = 0F;
        ItemStack flask = hbm("flask_infusion_shield");
        target.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, flask);
        flask.getItem().finishUsingItem(flask, level, target);
        float max = props.getEffectiveMaxShield(target), top = props.shield;
        ItemStack again = hbm("flask_infusion_shield");
        target.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, again);
        var use = again.getItem().use(level, target, net.minecraft.world.InteractionHand.MAIN_HAND);
        target.stopUsingItem();
        boolean refused = use == net.minecraft.world.InteractionResult.PASS;
        check(on ? near(props.maxShield, 75F) && near(max, 16F) && near(top, 2F) && refused : near(props.maxShield, 75F) && near(max, 75F) && near(top, 5F) && !refused,
                "2: " + m + ": фляга: счёт NTM " + f(props.maxShield) + ", щит до " + f(max) + ", сразу +" + f(top) + ", ещё одну " + (refused ? "нельзя" : "можно")
                        + (on ? " (ждали 75, 16, +2, нельзя)" : " (NTM: 75, 75, +5, можно)"));
        target.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        props.maxShield = 0F;
        props.shield = 0F;
    }

    private static boolean near(float a, float b) {
        return Math.abs(a - b) <= 0.02F + 0.002F * Math.abs(b);
    }

    private static void medicine2(boolean on, String m) {
        for (EquipmentSlot slot : SLOTS) target.setItemSlot(slot, ItemStack.EMPTY);
        target.removeAllEffects();
        ItemStack medx = hbm("syringe_metal_medx");
        target.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, medx);
        medx.getItem().use(level, target, net.minecraft.world.InteractionHand.MAIN_HAND);
        var res = target.getEffect(net.minecraft.world.effect.MobEffects.RESISTANCE);
        check(res != null && (on ? res.getAmplifier() == 0 && res.getDuration() == 1200 : res.getAmplifier() == 2 && res.getDuration() == 4800),
                "2: " + m + ": Med-X: " + (res == null ? "нет Сопротивления" : "Сопротивление " + (res.getAmplifier() + 1) + ", " + res.getDuration() / 20 + " с")
                        + (on ? " (ждали I, 60 с)" : " (NTM: III, 240 с)"));
        target.removeAllEffects();
        target.setHealth(5F);
        ItemStack sup = hbm("syringe_metal_super");
        target.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, sup);
        sup.getItem().use(level, target, net.minecraft.world.InteractionHand.MAIN_HAND);
        float now = target.getHealth();
        boolean slow = target.hasEffect(net.minecraft.world.effect.MobEffects.SLOWNESS);
        for (int t = 0; t < 120; t++) BalanceKit.tickHeal(target);
        float half = target.getHealth();
        for (int t = 0; t < 130; t++) BalanceKit.tickHeal(target);
        float end = target.getHealth();
        check(slow && (on ? near(now, 5F) && near(half, 17.5F) && near(end, 20F) && BalanceKit.healLeft(target) == 0F : near(now, 20F) && near(end, 20F)),
                "2: " + m + ": супер-стимпак с 5 HP: сразу " + f(now) + ", через 6 с " + f(half) + ", через 12,5 с " + f(end) + (slow ? ", Замедление есть" : ", НЕТ Замедления")
                        + (on ? " (ждали 5, 17,5, 20)" : " (NTM: 20 сразу)"));
        target.removeAllEffects();
        target.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        target.setHealth(target.getMaxHealth());
    }

    private static void inserts2(boolean on, String m) {
        var bullet = level.damageSources().source(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DAMAGE_TYPE, Identifier.fromNamespaceAndPath("hbm", "physical")));
        String[][] cases = {{"insert_kevlar", "9.00", "9.00"}, {"insert_sapi", "9.00", "8.50"}, {"insert_esapi", "8.55", "7.60"}, {"insert_xsapi", "8.10", "6.75"},
                {"insert_du", "8.10", "7.65"}, {"insert_ghiorsium", "7.20", "6.00"}, {"insert_steel", "9.50", "9.50"}};
        for (String[] c : cases) {
            dress(target, "hev", true);
            com.hbm.handler.ArmorModHandler.applyMod(target.getItemBySlot(EquipmentSlot.CHEST), hbm(c[0]));
            float out = com.hbm.handler.ArmorModHandler.modifyDamage(target, bullet, 10F);
            float want = Float.parseFloat(on ? c[1] : c[2]);
            check(near(out, want), "2: " + m + ": " + c[0] + ": пуля 10 → " + f(out) + " (ждали " + f(want) + ")");
        }
    }

    private static float vanillaArmor(ItemStack stack, EquipmentSlot slot) {
        float[] sum = {0F};
        stack.forEachModifier(slot, (attr, mod) -> {
            if (attr.is(Attributes.ARMOR) || attr.is(Attributes.ARMOR_TOUGHNESS)) sum[0] += (float) mod.amount();
        });
        return sum[0];
    }

    private static void vanilla2(boolean on, String m) {
        for (String set : new String[] {"steel", "dieselsuit", "hev", "bj", "fau", "dns", "euphemium", "starmetal", "dnt"}) {
            float sum = 0F;
            for (int i = 0; i < 4; i++) sum += vanillaArmor(hbm(set + SUFFIX[i]), SLOTS[i]);
            boolean combat = !set.equals("starmetal") && !set.equals("dnt");
            check(on && combat ? sum == 0F : sum > 0F, "2: " + m + ": ваниль-броня и прочность набора " + set + ": " + f(sum)
                    + (on && combat ? " (ждали 0)" : " (NTM: своя)"));
        }
    }

    /** the kit on HEV against Star-F JHP: clicks to kill without and with it (the shield mod, Med-X) */
    private static int kitBase;

    private static void kitFight(boolean on, int kind) {
        boolean kit = kind > 0;
        String m = on ? "баланс" : "NTM";
        STEPS.add(() -> {
            begin(target);
            target.removeAllEffects();
            dress(target, "hev", true);
            var props = com.hbm.extprop.HbmPlayerProps.getData(target);
            props.maxShield = 0F;
            props.shield = 0F;
            if (kit) {
                ItemStack chest = target.getItemBySlot(EquipmentSlot.CHEST);
                // 1: the shield mod; 2: the mod and every flask (NTM's count 100): the cap
                com.hbm.handler.ArmorModHandler.applyMod(chest, hbm("australium_iii"));
                if (kind == 2) props.maxShield = 100F;
                ItemStack medx = hbm("syringe_metal_medx");
                target.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, medx);
                medx.getItem().use(level, target, net.minecraft.world.InteractionHand.MAIN_HAND);
                target.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                props.shield = props.getEffectiveMaxShield(target);
            }
            target.snapTo(O.getX() + 0.5, O.getY(), O.getZ() + 0.5 + DIST, 180F, 0F);
        });
        // an insert takes the chestplate's socket 6, the shield mod's too: a kit has one of them, here the mod
        clicks(shooter, ModItems.GUN_STAR_F::get, XFactory22lr.p22_jhp, EnumKeybind.GUN_PRIMARY);
        STEPS.add(() -> {
            String what = kind == 0 ? "hev" : "hev + Med-X + щит " + f(com.hbm.extprop.HbmPlayerProps.getData(target).getEffectiveMaxShield(target));
            String r = row(m, what, "star_f · p22_jhp");
            if (!kit) {
                kitBase = deathClick;
                return;
            }
            float x = kitBase > 0 && deathClick > 0 ? (float) deathClick / kitBase : Float.NaN;
            // the numbers for the owner (the task: write them, not fit them); the balance's kit must kill at all
            LINES.add("#\t2: " + m + ": " + what + " против Star-F JHP: кликов " + kitBase + " → "
                    + (deathClick > 0 ? deathClick : "> " + clicks) + " (×" + (Float.isNaN(x) ? "?" : f(x)) + ")" + r);
            if (on) check(deathClick > 0, "2: баланс: " + what + ": убит за " + deathClick + " кликов (без комплекта " + kitBase + ", ×" + f(x) + ")");
            target.removeAllEffects();
        });
    }

    private static java.lang.reflect.Method activeBonus2, applyEffects2;

    /** what NTM's tick gives a player in the full set (the effects), as in 1g */
    private static void suitTick(net.minecraft.server.level.ServerPlayer p) {
        try {
            if (activeBonus2 == null) {
                activeBonus2 = com.hbm.items.armor.ArmorSuitEffects.class.getDeclaredMethod("activeBonus", net.minecraft.server.level.ServerPlayer.class);
                applyEffects2 = com.hbm.items.armor.ArmorSuitEffects.class.getDeclaredMethod("applyEffects", net.minecraft.server.level.ServerPlayer.class, com.hbm.items.armor.ArmorFullSetBonus.class);
                activeBonus2.setAccessible(true);
                applyEffects2.setAccessible(true);
            }
            Object bonus = activeBonus2.invoke(null, p);
            if (bonus != null) applyEffects2.invoke(null, p, bonus);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean has128(net.minecraft.server.level.ServerPlayer p) {
        var res = p.getEffect(net.minecraft.world.effect.MobEffects.RESISTANCE);
        var reg = p.getEffect(net.minecraft.world.effect.MobEffects.REGENERATION);
        return res != null && res.getAmplifier() >= 100 && reg != null && reg.getAmplifier() >= 100;
    }

    private static final java.util.List<String> EUPH_LOG = new ArrayList<>();
    private static float euphLostPool, euphLostWindow, euphLostRecover;
    private static boolean euph128Pool, euph128Window;

    /** one AMAT SM click on the euphemium wearer, the HP it took booked by the phase it met */
    private static void euphClick(String tag) {
        STEPS.add(() -> {
            pendingTag = null;
            if (target.getHealth() <= 0F || euphStop) return;
            String phase = EuphImmunity.describe(target);
            if (stopAtRecover && phase.startsWith("RECOVER")) euphStop = true;
            suitTick(target);
            boolean full128 = has128(target);
            if (phase.startsWith("POOL") || phase.equals("нет")) euph128Pool |= full128;
            if (phase.startsWith("WINDOW")) euph128Window |= full128;
            pendingHp = target.getHealth();
            pendingPhase = phase;
            pendingTag = tag;
            fire(shooter, ModItems.GUN_AMAT.get(), XFactory50.bmg50_sm, EnumKeybind.GUN_PRIMARY);
            clicks++;
        });
        wait(SPACING - 1);
        STEPS.add(() -> {
            if (pendingTag == null) return;
            float took = Math.max(0F, pendingHp - target.getHealth());
            if (pendingPhase.startsWith("WINDOW")) euphLostWindow += took;
            else if (pendingPhase.startsWith("RECOVER")) euphLostRecover += took;
            else euphLostPool += took;
            EUPH_LOG.add(pendingTag + ", " + pendingPhase + ": −" + f(took) + " HP → " + f(target.getHealth()));
            pendingTag = null;
        });
    }

    private static float pendingHp;
    private static String pendingPhase, pendingTag;
    private static boolean euphStop, stopAtRecover;

    private static void euph2() {
        // a small pool for the stand: 4 (AMAT SM takes about 1 of it a shot), a window of 3 s, a recovery of 3 s
        STEPS.add(() -> {
            keys("balance.immune.pool", "4", "balance.immune.window", "3", "balance.immune.recover", "3");
            begin(target);
            dress(target, "euphemium", true);
            target.removeAllEffects();
            EuphImmunity.forget(target);
            target.snapTo(O.getX() + 0.5, O.getY(), O.getZ() + 0.5 + DIST, 180F, 0F);
            EUPH_LOG.clear();
            euphLostPool = euphLostWindow = euphLostRecover = 0F;
            euph128Pool = euph128Window = false;
            euphStop = false;
            stopAtRecover = false;
        });
        wait(30);
        // through the pool and the window into the recovery (a shot in it), then no more
        for (int i = 0; i < 24; i++) {
            if (i == 0) STEPS.add(() -> stopAtRecover = true);
            euphClick("пул/окно/восст.");
        }
        STEPS.add(() -> {
            for (String l : EUPH_LOG) LINES.add("#\t2: евфемий: " + l);
            check(euphLostPool == 0F && euphLostRecover == 0F && euphLostWindow > 0F && euph128Pool && !euph128Window,
                    "2: евфемий, пул 4: в пуле снято " + f(euphLostPool) + " HP, в окне " + f(euphLostWindow) + ", в восстановлении " + f(euphLostRecover)
                            + "; эффекты 128 в пуле " + (euph128Pool ? "есть" : "НЕТ") + ", в окне " + (euph128Window ? "ЕСТЬ" : "нет")
                            + " (ждали: 0, > 0, 0; есть, нет)");
            target.setHealth(target.getMaxHealth());
            euphStop = false;
            stopAtRecover = false;
        });
        // the recovery (3 s) runs out: poll until the pool is the pool's again, 200 ticks at most
        int[] polled = {0};
        for (int t = 0; t < 200; t++) STEPS.add(() -> {
            if (polled[0] == 0 && EuphImmunity.describe(target).startsWith("POOL")) polled[0] = 1;
        });
        STEPS.add(() -> check(EuphImmunity.describe(target).equals("POOL 4"), "2: евфемий: после восстановления пул полон: " + EuphImmunity.describe(target) + " (ждали POOL 4)"));
        // the tomb's radon: invulnerable no more while in it, the full pool notwithstanding
        STEPS.add(() -> {
            keys("balance.immune.pool", "2000", "balance.immune.window", "15", "balance.immune.recover", "60");
            EuphImmunity.forget(target);
            begin(target);
            EUPH_LOG.clear();
            euphLostPool = euphLostWindow = euphLostRecover = 0F;
        });
        wait(30);
        for (int i = 0; i < 2; i++) {
            STEPS.add(() -> {
                var gas = BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath("hbm", "gas_radon_tomb")).defaultBlockState();
                net.minecraft.core.BlockPos at = target.blockPosition();
                gas.entityInside(level, at, target, net.minecraft.world.entity.InsideBlockEffectApplier.NOOP, false);
            });
            euphClickInGas();
        }
        STEPS.add(() -> {
            float inGas = euphLostPool;
            String pool = EuphImmunity.describe(target);
            check(inGas > 0F && (pool.equals("нет") || pool.equals("POOL 2000")), "2: евфемий в могильном газе (пул 2000 полон): снято " + f(inGas)
                    + " HP за 2 выстрела AMAT, пул " + pool + " (ждали > 0, пул не тронут)");
            target.setHealth(target.getMaxHealth());
            euphLostPool = 0F;
        });
        wait(5);
        for (int i = 0; i < 2; i++) euphClick("после газа");
        STEPS.add(() -> {
            String pool = EuphImmunity.describe(target);
            check(euphLostPool == 0F && pool.startsWith("POOL ") && !pool.equals("POOL 2000"),
                    "2: евфемий вне газа снова неуязвим, пул тратится: снято " + f(euphLostPool) + " HP, " + pool + " (ждали 0, пул меньше 2000)");
            for (String l : EUPH_LOG) LINES.add("#\t2: евфемий: " + l);
        });
        // NTM's mode: nothing gets through, nothing counted
        STEPS.add(() -> {
            keys("balance.immune.mode", "ntm");
            EuphImmunity.forget(target);
            begin(target);
            euphLostPool = euphLostWindow = euphLostRecover = 0F;
        });
        wait(30);
        for (int i = 0; i < 3; i++) euphClick("ntm");
        STEPS.add(() -> check(euphLostPool + euphLostWindow + euphLostRecover == 0F && EuphImmunity.describe(target).equals("нет"),
                "2: евфемий, balance.immune.mode = ntm: снято " + f(euphLostPool) + " HP, пул " + EuphImmunity.describe(target) + " (ждали 0, нет)"));
    }

    /** a click while the gas marks the wearer (the mark lasts a tick: put it again just before the shot) */
    private static void euphClickInGas() {
        STEPS.add(() -> {
            float hp = target.getHealth();
            var gas = BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath("hbm", "gas_radon_tomb")).defaultBlockState();
            gas.entityInside(level, target.blockPosition(), target, net.minecraft.world.entity.InsideBlockEffectApplier.NOOP, false);
            fire(shooter, ModItems.GUN_AMAT.get(), XFactory50.bmg50_sm, EnumKeybind.GUN_PRIMARY);
            pendingHp = hp;
        });
        // the bullet flies a tick or two: keep the mark on meanwhile
        for (int t = 0; t < 6; t++) STEPS.add(() -> {
            var gas = BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath("hbm", "gas_radon_tomb")).defaultBlockState();
            gas.entityInside(level, target.blockPosition(), target, net.minecraft.world.entity.InsideBlockEffectApplier.NOOP, false);
        });
        STEPS.add(() -> {
            euphLostPool += Math.max(0F, pendingHp - target.getHealth());
            EUPH_LOG.add("в газе: −" + f(Math.max(0F, pendingHp - target.getHealth())) + " HP");
        });
        wait(14);
    }

    // ---------------------------------------------------------------- stage 1g: the sets' abilities

    /** a set: its items' name, NTM's effect levels and the balance's (balance-by-progression.md 6.3-6.5), its dashes */
    private record Ab(String set, String ntm, String balance, int dashesNtm, int dashes) {
    }

    private static final List<Ab> ABILITIES = List.of(
            new Ab("dieselsuit", "jump_boost 3, speed 3", "jump_boost 1, speed 2", 0, 0),
            new Ab("taurun", "strength 1", "strength 1", 0, 0),
            new Ab("t51", "strength 1", "strength 1", 0, 0),
            new Ab("steamsuit", "haste 5", "haste 5", 0, 0),
            new Ab("hev", "jump_boost 1, speed 2", "jump_boost 1, speed 2", 0, 0),
            new Ab("envsuit", "jump_boost 1, speed 2", "jump_boost 1, speed 2", 0, 0),
            new Ab("ajr", "jump_boost 1, strength 1", "jump_boost 1, strength 1", 0, 0),
            new Ab("ajro", "jump_boost 1, strength 1", "jump_boost 1, strength 1", 0, 0),
            new Ab("trenchmaster", "haste 2, jump_boost 2, speed 1, strength 3", "haste 2, jump_boost 1, speed 1, strength 2", 0, 0),
            new Ab("bj", "jump_boost 1, speed 2", "jump_boost 1, speed 2", 0, 0),
            new Ab("ncrpa", "strength 4", "strength 2", 0, 0),
            new Ab("bismuth", "jump_boost 7, night_vision 1, regeneration 2, speed 7", "jump_boost 2, night_vision 1, regeneration 1, speed 3", 3, 2),
            new Ab("schrabidium", "haste 3, jump_boost 2, speed 3, strength 3", "haste 3, jump_boost 2, speed 3, strength 2", 0, 0),
            new Ab("cmb", "haste 3, speed 3, strength 5", "haste 3, speed 3, strength 2", 0, 0),
            new Ab("rpa", "strength 4", "strength 2", 0, 0),
            new Ab("fau", "jump_boost 2", "jump_boost 2", 0, 0),
            new Ab("dns", "haste 8, jump_boost 3, strength 10", "haste 8, jump_boost 2, strength 2", 0, 0),
            new Ab("euphemium", "fire_resistance 128, haste 128, regeneration 128, resistance 128", "fire_resistance 128, haste 128, regeneration 128, resistance 128", 0, 0),
            new Ab("steel", "", "", 0, 0));

    private static void planAbilities() {
        LINES.add("#\t--- этап 1г: способности наборов (уровни эффектов полного набора, рывки)");
        for (boolean on : new boolean[] {true, false}) {
            STEPS.add(() -> keys("abilities.mobilityCap", Boolean.toString(on), "abilities.strengthCap", on ? "2" : "0", "abilities.bismuth", Boolean.toString(on)));
            wait(30);
            for (Ab a : ABILITIES) STEPS.add(() -> abilities(a, on));
            STEPS.add(() -> {
                for (EquipmentSlot slot : SLOTS) target.setItemSlot(slot, ItemStack.EMPTY);
                target.removeAllEffects();
            });
        }
        // the rest of the stand with the shipped abilities
        STEPS.add(() -> keys("abilities.mobilityCap", "true", "abilities.strengthCap", "2", "abilities.bismuth", "true"));
    }

    private static java.lang.reflect.Method activeBonus, applyEffects;

    private static void abilities(Ab a, boolean on) {
        try {
            if (activeBonus == null) {
                activeBonus = com.hbm.items.armor.ArmorSuitEffects.class.getDeclaredMethod("activeBonus", net.minecraft.server.level.ServerPlayer.class);
                applyEffects = com.hbm.items.armor.ArmorSuitEffects.class.getDeclaredMethod("applyEffects", net.minecraft.server.level.ServerPlayer.class, com.hbm.items.armor.ArmorFullSetBonus.class);
                activeBonus.setAccessible(true);
                applyEffects.setAccessible(true);
            }
            target.removeAllEffects();
            dress(target, a.set(), true);
            // what NTM's tick does for a player in a full set
            Object bonus = activeBonus.invoke(null, target);
            if (bonus != null) applyEffects.invoke(null, target, bonus);
            com.hbm.items.armor.ArmorDash.tick(target, 0F, 0F);
            java.util.TreeMap<String, Integer> got = new java.util.TreeMap<>();
            for (var e : target.getActiveEffects()) got.put(BuiltInRegistries.MOB_EFFECT.getKey(e.getEffect().value()).getPath(), e.getAmplifier() + 1);
            StringBuilder s = new StringBuilder();
            got.forEach((k, v) -> s.append(s.isEmpty() ? "" : ", ").append(k).append(' ').append(v));
            int dashes = com.hbm.extprop.HbmPlayerProps.getData(target).dashCount;
            String want = on ? a.balance() : a.ntm();
            int wantDashes = on ? a.dashes() : a.dashesNtm();
            check(s.toString().equals(want) && dashes == wantDashes && (bonus != null || a.ntm().isEmpty()),
                    "1г: " + (on ? "баланс" : "NTM") + " " + a.set() + ": " + (s.isEmpty() ? "нет эффектов" : s) + (dashes > 0 ? ", рывков " + dashes : "")
                            + (s.toString().equals(want) ? "" : " (ждали " + want + ")") + (dashes == wantDashes ? "" : " (рывков ждали " + wantDashes + ")"));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- stage 1b: the guns' damage

    /**
     * a pair of stage 1b: the model's hit after the armour and clicks to kill with the armour of 1a and NTM's gun
     * ({@code hit1a}), then with the gun's mult ({@code hit1b}); {@code d1b} the hit's damage before the armour (0: a
     * blast, not checked), {@code ntmD} NTM's (0: a blast); {@code fbi}: the FBI agent fires it; {@code info}: no check
     */
    private record Pair1b(String set, String gunName, Supplier<Item> gun, Supplier<BulletConfig> ammo, float hit1a, int clicks1a,
            float hit1b, int clicks1b, float d1b, float ntmD, boolean fbi, boolean info) {
    }

    // notes/balance/work: variants.det_pellet with fit4's S, the pair's mult/neg/pen as BalanceGuns has them (the
    // script work/scripts/pairs1b.py, the numbers in notes/balance/stage1b-guns.md)
    private static final List<Pair1b> PAIRS_1B = List.of(
            new Pair1b("steel", "henry · m44_express ×0,955", ModItems.GUN_HENRY::get, () -> XFactory44.m44_express, 10.873F, 2, 10.614F, 2, 14.33F, 15F, false, false),
            new Pair1b("dieselsuit", "greasegun · p9_sp ×2,65", ModItems.GUN_GREASEGUN::get, () -> XFactory9mm.p9_sp, 0.775F, 26, 3.009F, 7, 7.95F, 3F, false, false),
            new Pair1b("dieselsuit", "am180 · p22_sp ×3,5 (A2)", ModItems.GUN_AM180::get, () -> XFactory22lr.p22_sp, 0.505F, 40, 2.402F, 9, 7.0F, 2F, false, false),
            // NTM's blast of 116 is over balanceExplRawLimit (60): 1a does not squeeze it (the formula's m alone, 0.917)
            new Pair1b("t51", "autoshotgun · g12_explosive ×0,175", ModItems.GUN_AUTOSHOTGUN::get, () -> XFactory12ga.g12_explosive, 106.8F, 2, 8.429F, 3, 0F, 0F, false, false),
            new Pair1b("hev", "carbine · r762_he ×0,542", ModItems.GUN_CARBINE::get, () -> XFactory762mm.r762_he, 7.451F, 3, 3.272F, 7, 0F, 0F, false, false),
            new Pair1b("hev", "autoshotgun_heretic · g10_du ×0,2 (H2)", ModItems.GUN_AUTOSHOTGUN_HERETIC::get, () -> XFactory10ga.g10_du, 10.511F, 2, 1.769F, 2, 5.0F, 25F, false, false),
            new Pair1b("security", "star_f · p22_jhp ×0,955", ModItems.GUN_STAR_F::get, () -> XFactory22lr.p22_jhp, 3.685F, 6, 3.434F, 6, 17.91F, 18.75F, false, false),
            new Pair1b("bj", "hangman · m44_express ×2 (пьед.)", ModItems.GUN_HANGMAN::get, () -> XFactory44.m44_express, 4.247F, 5, 9.231F, 3, 75F, 37.5F, false, false),
            new Pair1b("rpa", "g3_zebra · r556_inc_ap ×1,07", ModItems.GUN_G3_ZEBRA::get, () -> XFactory556mm.r556_inc_ap, 0.663F, 31, 0.710F, 29, 10.03F, 9.375F, false, false),
            new Pair1b("fau", "tau · tau_uranium ×2 +30", ModItems.GUN_TAU::get, () -> XFactoryAccelerator.tau_uranium, 0.700F, 29, 3.575F, 6, 50F, 25F, false, false),
            // N.I.4.N.I.'s arc has no ammo item: the shot must go on (no check of numbers)
            new Pair1b("hev", "ni4ni · arc (нет предмета патрона)", ModItems.GUN_N_I_4_N_I::get, () -> XFactoryAccelerator.ni4ni_arc, 0F, 0, 0F, 0, 0F, 0F, false, true),
            new Pair1b("security", "FBI → игрок: greasegun · p9_sp ×2,65", ModItems.GUN_GREASEGUN::get, () -> XFactory9mm.p9_sp, 0.396F, 51, 1.297F, 16, 7.95F, 3F, true, false));

    private static final String[] MODES_1B = {"NTM", "1а", "1а+1б"};

    // ---------------------------------------------------------------- DNS without its invulnerability

    // the model (variants.det_pellet, fit4's S, the pairs' mult and additions) against DNS (S 186). Tesla and Lacunae
    // with no model (info): the model has Lacunae's laser as PHYS (the game: EN, S x 0.75) and Tesla's overcharge at a
    // damage of 52.5 (the game: its beam and the chained ones); they are checked to get through
    private static final List<Pair1b> PAIRS_DNS = List.of(
            new Pair1b("dns", "tau · tau_uranium ×2 +30", ModItems.GUN_TAU::get, () -> XFactoryAccelerator.tau_uranium, 0F, 0, 2.143F, 10, 50F, 0F, false, false),
            new Pair1b("dns", "tesla_cannon · capacitor_overcharge ×2 +30", ModItems.GUN_TESLA_CANNON::get, () -> XFactoryEnergy.energy_tesla_overcharge, 0F, 0, 0F, 0, 0F, 0F, false, true),
            new Pair1b("dns", "minigun_lacunae · capacitor ×2 +50/+0,15", ModItems.GUN_MINIGUN_LACUNAE::get, () -> XFactory762mm.energy_lacunae, 0F, 0, 0F, 0, 0F, 0F, false, true),
            new Pair1b("dns", "amat · bmg50_sm ×0,853", ModItems.GUN_AMAT::get, () -> XFactory50.bmg50_sm, 0F, 0, 3.039F, 7, 63.98F, 0F, false, false));

    /**
     * DNS by its rating (balance.immune.dns = formula): the pairs against a charged full set as the model has them;
     * with ntm what NTM's invulnerability lets through (a bullet nothing; beams come with ignoreEarlyCancellation)
     */
    private static void planDns() {
        LINES.add("#\t--- DNS: неуязвимость снята (balance.immune.dns), формула C по S 186");
        for (String key : new String[] {"formula", "ntm"}) {
            String mode = key.equals("formula") ? "DNS формула" : "DNS как NTM";
            STEPS.add(() -> keys("balanceArmor", "true", "balanceGuns", "true", "damageCap", "true", "damageCapMobs", "false", "balance.immune.dns.mode", key));
            wait(45);
            for (Pair1b p : PAIRS_DNS) {
                Pair1b q = key.equals("formula") ? p : new Pair1b(p.set(), p.gunName(), p.gun(), p.ammo(), 0F, 0, 0F, 0, 0F, 0F, false, true);
                pair1b(q, mode, true);
                if (key.equals("formula")) STEPS.add(() -> check(lost > 0F && deathClick > 0,
                        mode + ": " + p.gunName() + " по заряженному DNS проходит: снято " + f(lost) + ", кликов " + (deathClick > 0 ? deathClick : "> " + clicks)));
                if (key.equals("ntm") && p.gunName().startsWith("amat")) STEPS.add(() -> check(lost == 0F && deathClick == 0,
                        mode + ": пуля AMAT по заряженному DNS: снято " + f(lost) + " за " + clicks + " кликов (NTM: неуязвим, ждали 0)"));
            }
        }
        STEPS.add(() -> keys("balance.immune.dns.mode", "formula"));
    }

    // ---------------------------------------------------------------- the pool of DNS and FAU

    /** a gun of the pool's pass: its name, the gun, the round */
    private record PoolGun(String name, Supplier<Item> gun, Supplier<BulletConfig> ammo) {
    }

    private static final List<PoolGun> POOL_GUNS = List.of(
            new PoolGun("amat · bmg50_sm", ModItems.GUN_AMAT::get, () -> XFactory50.bmg50_sm),
            new PoolGun("tau · tau_uranium", ModItems.GUN_TAU::get, () -> XFactoryAccelerator.tau_uranium),
            new PoolGun("tesla_cannon · capacitor_overcharge", ModItems.GUN_TESLA_CANNON::get, () -> XFactoryEnergy.energy_tesla_overcharge),
            new PoolGun("minigun_lacunae · capacitor", ModItems.GUN_MINIGUN_LACUNAE::get, () -> XFactory762mm.energy_lacunae),
            new PoolGun("double_barrel · g10", ModItems.GUN_DOUBLE_BARREL::get, () -> XFactory10ga.g10),
            new PoolGun("missile_launcher · rocket_ml", ModItems.GUN_MISSILE_LAUNCHER::get, () -> XFactoryRocket.rocket_ml[0]));

    /** what one click took in HP (formula) and from the pool (pool), by kit and gun */
    private static final java.util.Map<String, Float> POOL_HP = new java.util.HashMap<>(), POOL_DRAIN = new java.util.HashMap<>();
    private static final java.util.Map<String, Integer> POOL_HITS = new java.util.HashMap<>();
    private static float poolBefore;

    private static float poolNow(EuphImmunity.Kit kit) {
        return EuphImmunity.poolOf(kit, target);
    }

    /**
     * DNS and FAU with euphemium's round: (1) each gun's click by the set's rating (formula, X858) and on the full pool
     * (nothing through, the pool takes about the same: beams and blasts too, they come with ignoreEarlyCancellation);
     * (2) a small pool: pool → window → recovery → full pool, by AMAT; (3) the radon gas takes the pool off.
     */
    private static void planPool() {
        LINES.add("#\t--- пул DNS и FAU (balance.immune.dns.*, balance.immune.fau.*)");
        // the rocket bursts 4 blocks off: the mannequin must not die of it
        STEPS.add(() -> {
            shooter.setInvulnerable(true);
            for (int x = -6; x <= 6; x++) for (int z = -4; z <= 12; z++) level.setBlockAndUpdate(O.offset(x, -1, z), Blocks.BEDROCK.defaultBlockState());
        });
        for (EuphImmunity.Kit kit : new EuphImmunity.Kit[] {EuphImmunity.Kit.DNS, EuphImmunity.Kit.FAU}) {
            String set = kit.set, k = kit.key;
            float full = kit.size;
            for (String mode : new String[] {"formula", "pool"}) {
                STEPS.add(() -> keys("balanceArmor", "true", "balanceGuns", "true", "damageCap", "true", "damageCapMobs", "false", k + ".mode", mode,
                        k + ".pool", String.valueOf(full), k + ".window", "15", k + ".recover", "60"));
                wait(30);
                for (PoolGun g : POOL_GUNS) {
                    String id = set + " " + g.name();
                    STEPS.add(() -> {
                        begin(target);
                        dress(target, set, false);
                        EuphImmunity.forget(target);
                        homePool();
                    });
                    wait(5);
                    for (int c = 0; c < 3; c++) {
                        STEPS.add(() -> {
                            target.setHealth(target.getMaxHealth());
                            homePool();
                            float p = poolNow(kit);
                            poolBefore = Float.isNaN(p) ? full : p;
                            fire(shooter, g.gun().get(), g.ammo().get(), EnumKeybind.GUN_PRIMARY);
                            clicks++;
                        });
                        wait(SPACING + 10);
                        STEPS.add(() -> {
                            float p = poolNow(kit);
                            if (mode.equals("pool")) POOL_DRAIN.merge(id, poolBefore - (Float.isNaN(p) ? full : p), Float::sum);
                        });
                    }
                    STEPS.add(() -> {
                        if (mode.equals("formula")) {
                            POOL_HP.put(id, lost / 3F);
                            POOL_HITS.put(id, BalanceArmor.seenHits);
                            LINES.add("#\tпул: " + id + ": формула (X858): по " + f(lost / 3F) + " HP за клик, ударов " + BalanceArmor.seenHits + " за 3 клика");
                            check(lost > 0F, "пул: " + id + ": по рейтингу набора проходит (снято " + f(lost) + " за 3 клика)");
                            return;
                        }
                        float drain = POOL_DRAIN.getOrDefault(id, 0F) / 3F, hp = POOL_HP.getOrDefault(id, 0F);
                        LINES.add("#\tпул: " + id + ": пул " + f(full) + ": снято " + f(lost) + " HP за 3 клика, из пула по " + f(drain) + " за клик (по рейтингу "
                                + f(hp) + " HP), " + EuphImmunity.describe(kit, target));
                        // a click of several hits (pellets; Tesla's chained beams, 3 or 4 a click): the damage cap takes the
                        // click whole, the pool each hit on its own; a pellet's head multiplier (after the armour) not at all
                        float tol = g.name().startsWith("double_barrel") || g.name().startsWith("tesla") ? 0.3F : 0.15F;
                        check(lost == 0F && drain > 0F && Math.abs(drain - hp) <= Math.max(tol * hp, 0.3F), "пул: " + id + ": набор неуязвим (снято " + f(lost)
                                + "), пул берёт за клик " + f(drain) + " ≈ урону по рейтингу " + f(hp));
                    });
                }
            }
            poolRound(kit);
        }
        STEPS.add(() -> keys("balance.immune.dns.mode", "formula", "balance.immune.fau.mode", "formula", "balance.immune.dns.pool", "500",
                "balance.immune.fau.pool", "450", "balance.immune.dns.window", "15", "balance.immune.fau.window", "15", "balance.immune.dns.recover", "60",
                "balance.immune.fau.recover", "60"));
        STEPS.add(() -> shooter.setInvulnerable(false));
        wait(30);
    }

    /** the mannequin and the target back in their places (a rocket throws both), the air between them clear */
    private static void homePool() {
        for (int x = -2; x <= 2; x++) for (int y = 0; y <= 3; y++) for (int z = -1; z <= 6; z++) level.setBlockAndUpdate(O.offset(x, y, z), Blocks.AIR.defaultBlockState());
        shooter.setHealth(shooter.getMaxHealth());
        shooter.setDeltaMovement(Vec3.ZERO);
        shooter.snapTo(O.getX() + 0.5, O.getY(), O.getZ() + 0.5, 0F, 0F);
        target.setDeltaMovement(Vec3.ZERO);
        target.snapTo(O.getX() + 0.5, O.getY(), O.getZ() + 0.5 + DIST, 180F, 0F);
    }

    private static float roundPool, roundWindow, roundRecover;
    private static final java.util.List<String> ROUND_LOG = new ArrayList<>();

    /** a small pool (4; AMAT takes about 3 of it a shot), a window of 3 s, a recovery of 3 s; then the radon gas */
    private static void poolRound(EuphImmunity.Kit kit) {
        String set = kit.set, k = kit.key;
        STEPS.add(() -> {
            keys(k + ".mode", "pool", k + ".pool", "4", k + ".window", "3", k + ".recover", "3");
            begin(target);
            dress(target, set, false);
            EuphImmunity.forget(target);
            homePool();
            roundPool = roundWindow = roundRecover = 0F;
            ROUND_LOG.clear();
            euphStop = false;
        });
        wait(30);
        for (int i = 0; i < 24; i++) {
            STEPS.add(() -> {
                pendingTag = null;
                if (target.getHealth() <= 0F || euphStop) return;
                String phase = EuphImmunity.describe(kit, target);
                if (phase.startsWith("RECOVER")) euphStop = true;
                // the window must not kill: what it takes is booked, the health put back
                target.setHealth(target.getMaxHealth());
                pendingHp = target.getHealth();
                pendingPhase = phase;
                pendingTag = "клик";
                fire(shooter, ModItems.GUN_AMAT.get(), XFactory50.bmg50_sm, EnumKeybind.GUN_PRIMARY);
            });
            wait(SPACING - 1);
            STEPS.add(() -> {
                if (pendingTag == null) return;
                float took = Math.max(0F, pendingHp - target.getHealth());
                if (pendingPhase.startsWith("WINDOW")) roundWindow += took;
                else if (pendingPhase.startsWith("RECOVER")) roundRecover += took;
                else roundPool += took;
                ROUND_LOG.add(pendingPhase + ": −" + f(took));
                pendingTag = null;
            });
        }
        STEPS.add(() -> {
            LINES.add("#\tпул: " + set + ", пул 4: " + String.join("; ", ROUND_LOG));
            check(roundPool == 0F && roundRecover == 0F && roundWindow > 0F && euphStop, "пул: " + set + ", пул 4 / окно 3 с / восст. 3 с: в пуле снято " + f(roundPool)
                    + ", в окне " + f(roundWindow) + ", в восстановлении " + f(roundRecover) + " (ждали 0, > 0, 0; дошли до восстановления: " + euphStop + ")");
            euphStop = false;
        });
        int[] seen = {0};
        for (int t = 0; t < 120; t++) STEPS.add(() -> {
            if (seen[0] == 0 && EuphImmunity.describe(kit, target).startsWith("POOL")) seen[0] = 1;
        });
        STEPS.add(() -> check(EuphImmunity.describe(kit, target).equals("POOL 4"), "пул: " + set + ": после восстановления пул полон: " + EuphImmunity.describe(kit, target)
                + " (ждали POOL 4)"));
        // the radon gas: the pool takes nothing, the hits go through
        STEPS.add(() -> {
            keys(k + ".pool", "450", k + ".window", "15", k + ".recover", "60");
            EuphImmunity.forget(target);
            begin(target);
            euphLostPool = 0F;
        });
        wait(30);
        for (int i = 0; i < 2; i++) euphClickInGas();
        STEPS.add(() -> {
            String pool = EuphImmunity.describe(kit, target);
            check(euphLostPool > 0F && (pool.equals("нет") || pool.equals("POOL 450")), "пул: " + set + " в могильном газе: снято " + f(euphLostPool)
                    + " HP за 2 выстрела AMAT, пул " + pool + " (ждали > 0, пул не тронут)");
            euphLostPool = 0F;
            target.setHealth(target.getMaxHealth());
        });
    }

    private static void plan1b() {
        LINES.add("#	--- этап 1б: урон пушек; секунды = (кликов − 1) × пауза ствола / 20");
        for (String mode : MODES_1B) {
            boolean guns = mode.equals("1а+1б");
            STEPS.add(() -> keys("balanceArmor", mode.equals("NTM") ? "false" : "true", "balanceGuns", guns ? "true" : "false"));
            wait(45);
            STEPS.add(() -> receivers(mode, guns));
            for (Pair1b p : PAIRS_1B) pair1b(p, mode, guns);
            fbiCase(mode);
        }
    }

    private static int delay(Item item) {
        ItemStack stack = new ItemStack(item);
        return ((ItemGunBaseNT) item).getConfig(stack, 0).getReceivers(stack)[0].getDelayAfterFire(stack);
    }

    /** the gun lines' receivers: changed with the setting on, NTM's with it off */
    private static void receivers(String mode, boolean on) {
        ItemStack h = new ItemStack(ModItems.GUN_AUTOSHOTGUN_HERETIC.get()), a = new ItemStack(ModItems.GUN_AM180.get()), g = new ItemStack(ModItems.GUN_LASER_PISTOL_MORNING_GLORY.get());
        var hr = ((ItemGunBaseNT) h.getItem()).getConfig(h, 0).getReceivers(h)[0];
        var ar = ((ItemGunBaseNT) a.getItem()).getConfig(a, 0).getReceivers(a)[0];
        var gr = ((ItemGunBaseNT) g.getItem()).getConfig(g, 0).getReceivers(g)[0];
        int hm = ((com.hbm.items.weapon.sedna.mags.MagazineSingleTypeBase) hr.getMagazine(h)).capacity;
        int am = ((com.hbm.items.weapon.sedna.mags.MagazineSingleTypeBase) ar.getMagazine(a)).capacity;
        String seen = "Heretic пауза " + hr.getDelayAfterFire(h) + ", авто " + hr.getRefireOnHold(h) + ", магазин " + hm + "; AM180 пауза "
                + ar.getDelayAfterFire(a) + ", магазин " + am + "; Penance " + delay(ModItems.GUN_AMAT_PENANCE.get()) + ", Subtlety "
                + delay(ModItems.GUN_AMAT_SUBTLETY.get()) + ", MAS-36 " + delay(ModItems.GUN_MAS36.get()) + "; Morning Glory база " + f(gr.getBaseDamage(g));
        boolean want = on
                ? hr.getDelayAfterFire(h) == 12 && !hr.getRefireOnHold(h) && hm == 24 && ar.getDelayAfterFire(a) == 3 && am == 60
                        && delay(ModItems.GUN_AMAT_PENANCE.get()) == 15 && delay(ModItems.GUN_AMAT_SUBTLETY.get()) == 17 && delay(ModItems.GUN_MAS36.get()) == 17
                        && gr.getBaseDamage(g) == 40F
                : hr.getDelayAfterFire(h) == 3 && hr.getRefireOnHold(h) && hm == 250 && ar.getDelayAfterFire(a) == 1 && am == 177
                        && delay(ModItems.GUN_AMAT_PENANCE.get()) == 25 && delay(ModItems.GUN_AMAT_SUBTLETY.get()) == 25 && delay(ModItems.GUN_MAS36.get()) == 25
                        && gr.getBaseDamage(g) == 20F;
        check(want, mode + ", стволы " + (on ? "по таблице" : "как в NTM") + ": " + seen);
    }

    private static void pair1b(Pair1b p, String mode, boolean guns) {
        STEPS.add(() -> {
            begin(target);
            dress(target, p.set(), true);
            target.snapTo(O.getX() + 0.5, O.getY(), O.getZ() + 0.5 + DIST, 180F, 0F);
            if (p.fbi()) {
                var created = BuiltInRegistries.ENTITY_TYPE.getValue(FBI).create(level, EntitySpawnReason.COMMAND);
                if (!(created instanceof Mob mob)) throw new IllegalStateException("no FBI agent");
                fbi = mob;
                fbi.setNoAi(true);
                fbi.snapTo(O.getX() + 0.5, O.getY(), O.getZ() + 0.5, 0F, 0F);
                level.addFreshEntity(fbi);
                fbiShooter = fbi;
                shooter.snapTo(O.getX() + 2.5, O.getY(), O.getZ() - 3.5, 0F, 0F);
            }
        });
        clicks1b(p);
        STEPS.add(() -> {
            float modelHit = guns ? p.hit1b() : p.hit1a();
            int modelClicks = guns ? p.clicks1b() : p.clicks1a();
            int dl = delay(p.gun().get());
            String secs = deathClick > 0 ? f((deathClick - 1) * dl / 20F) : "-";
            String r = row(mode, p.set(), p.gunName()) + "	с до смерти " + secs + "	модель: " + f(modelHit) + " / " + modelClicks + " кл., "
                    + f((modelClicks - 1) * dl / 20F) + " с";
            if (p.fbi()) {
                fbi.discard();
                fbi = null;
                fbiShooter = null;
                shooter.snapTo(O.getX() + 0.5, O.getY(), O.getZ() + 0.5, 0F, 0F);
            }
            if (mode.equals("NTM")) return;
            if (p.info()) {
                LINES.add("#	" + p.set() + ": " + p.gunName() + ": ударов " + BalanceArmor.seenHits + ", снято " + f(lost) + ", кликов "
                        + (deathClick > 0 ? deathClick : "> " + clicks) + " (" + mode + ", без сверки с моделью)");
                return;
            }
            float hit = BalanceArmor.seenHits > 0 ? BalanceArmor.seenOut / BalanceArmor.seenHits : 0F;
            float off = Math.abs(hit - modelHit) / modelHit;
            check(BalanceArmor.seenHits > 0 && off <= 0.15F, mode + ", " + p.set() + ", " + p.gunName() + ": урон за удар после брони "
                    + f(hit) + " против модели " + f(modelHit) + " (" + Math.round(off * 100) + " %)" + r);
            if (!guns && p.ntmD() > 0F && BalanceArmor.seenHits > 0) {
                float d = BalanceArmor.seenIn / BalanceArmor.seenHits, offD = Math.abs(d - p.ntmD()) / p.ntmD();
                check(offD <= 0.02F, mode + ", " + p.gunName() + ": урон удара до брони " + f(d) + " = NTM " + f(p.ntmD()) + " (balanceGuns выкл)");
            }
            if (guns && p.d1b() > 0F && BalanceArmor.seenHits > 0) {
                float d = BalanceArmor.seenIn / BalanceArmor.seenHits, offD = Math.abs(d - p.d1b()) / p.d1b();
                check(offD <= 0.02F, mode + ", " + p.gunName() + ": урон удара до брони " + f(d) + " = NTM × множитель " + f(p.d1b()));
            }
            if (deathClick > 0) LINES.add("#	" + p.set() + ", " + p.gunName() + ": кликов " + deathClick + " против модели " + modelClicks + " ("
                    + Math.round(100F * (deathClick - modelClicks) / modelClicks) + " %)");
            else LINES.add("#	" + p.set() + ", " + p.gunName() + ": жив после " + clicks + " кликов, модель " + modelClicks);
        });
    }

    /** the FBI agent firing in a pair of 1b (null: the mannequin) */
    private static LivingEntity fbiShooter;

    private static void clicks1b(Pair1b p) {
        int[] end = {0};
        for (int i = 0; i < MAX_CLICKS + 25; i++) {
            STEPS.add(() -> {
                if (deathClick > 0 || victim.getHealth() <= 0F) return;
                fire(fbiShooter != null ? fbiShooter : shooter, p.gun().get(), p.ammo().get(), EnumKeybind.GUN_PRIMARY);
                clicks++;
            });
            for (int t = 0; t < SPACING; t++) STEPS.add(() -> {
                if (deathClick <= 0 && clicks > 0 && victim.getHealth() <= 0F) {
                    deathClick = clicks;
                    skipTo = end[0];
                }
            });
            if (i == 0) STEPS.add(() -> firstClick = lost);
        }
        end[0] = STEPS.size();
        wait(20);
    }

    // ---------------------------------------------------------------- the cases

    private static void pair(Pair p, String mode) {
        boolean vanilla = !mode.equals(MODEL);
        STEPS.add(() -> {
            begin(target);
            noShield = !vanilla;
            dress(target, p.set(), vanilla);
            headHit = 0;
            DamageCap.last = "";
            target.snapTo(O.getX() + 0.5, O.getY(), O.getZ() + 0.5 + DIST, 180F, 0F);
        });
        clicks(shooter, p.gun(), p.ammo(), EnumKeybind.GUN_PRIMARY);
        STEPS.add(() -> {
            String r = row(mode, p.set(), p.gunName()) + "\t" + f(p.modelHit()) + " / " + p.modelClicks();
            if (mode.equals("NTM")) return;
            if (p.modelHit() <= 0F) {
                // a sniper's bullet in the head has all of a click (cap 1.0): written down, not checked
                if (headHit == 2) {
                    LINES.add("#\t" + mode + ", " + p.set() + ", " + p.gunName() + ": клик снял " + f(firstClick) + ", пуля в голову, пушка снайперская (потолок 1,0)" + r);
                    return;
                }
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
            float vanilla = (float) fbi.getAttributeValue(Attributes.ARMOR);
            LINES.add("#\tFBI: " + f(fbi.getMaxHealth()) + " HP, броня ванили " + f(vanilla));
            row(mode, "FBI в security", "игрок: heavy_revolver · m44_express");
            // balance.vanilla0 takes Security's 10 off, balance.fbiArmor gives the agent 10 of its own: 30 as before
            check(vanilla == 30F, mode + ", FBI: ваниль " + f(vanilla) + " (ждали 30: своя 20 + Security 10 или balance.fbiArmor 10)");
            if (mode.equals("1а+1б")) check(deathClick >= 9 && deathClick <= 12,
                    mode + ", игрок → FBI в Security, heavy_revolver m44_express ×1,2: кликов " + (deathClick > 0 ? deathClick : "> " + clicks) + " (ждали 10–11, как до этапа 2)");
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
        // a belt-fed gun (the Tau, Lacunae) takes its rounds from the inventory
        SimpleContainer inv = new SimpleContainer(1);
        if (ammo.ammoItem != null) inv.setItem(0, new ItemStack(ammo.ammoItem.get(), 64));
        gun.handleKeybind(from, inv, stack, key, true);
        gun.handleKeybind(from, inv, stack, key, false);
    }

    private static void aim(LivingEntity from, LivingEntity at) {
        Vec3 eye = from.getEyePosition();
        Vec3 to = new Vec3(at.getX(), aimHead ? at.getEyeY() : at.getY() + 0.9, at.getZ());
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
