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
    private static final double DIST = 4;
    private static final int SPACING = 20, MAX_CLICKS = 30;
    private static final Identifier FBI = Identifier.fromNamespaceAndPath("hbm", "entity_fbi");

    private BalanceCheck() {
    }

    /** a pair: the set, the gun and its ammo, the model's damage of one hit after the armour and clicks to kill */
    private record Pair(String set, String gunName, Supplier<Item> gun, BulletConfig ammo, float modelHit, int modelClicks) {
    }

    private static final List<String> LINES = new ArrayList<>();
    private static boolean ok = true;
    private static ServerLevel level;

    // ---------------------------------------------------------------- the lanes

    /**
     * A lane: a mannequin, a player to shoot at and one more (the gunner), with its own queue of steps, one a tick, and
     * the case under way. The cases of a pass go to the lanes (the one with the fewest steps of the pass so far), each
     * lane LANE_GAP blocks along x from the last; what the whole server shares (the keys, the gun table, a reload) is
     * set between two passes, every lane waiting at the barrier (Barrier) for the last. Lane 0 stands where the stand
     * stood before the lanes (0, 120, 0) and plays the serial parts.
     */
    private static final class Lane {
        final int index;
        final BlockPos o;
        final List<Runnable> steps = new Queue();
        int at;
        /** the steps of a case after its victim died are skipped up to this one */
        int skipTo;
        /** the step asks to run again next tick (a wait in real time: a reload) */
        boolean hold;
        Mannequin shooter;
        /** the pass team: more shooters beside the mannequin, each its own damage cap on the target */
        final List<Mannequin> team = new ArrayList<>();
        FakePlayer target, gunner;
        Mob fbi;
        /** the FBI agent firing in a pair of 1b (null: the mannequin) */
        LivingEntity fbiShooter;
        // the case under way
        LivingEntity victim;
        BalanceArmor.Seen seen = new BalanceArmor.Seen();
        boolean noShield;
        int clicks, hits, deathClick;
        float lost, firstClick, incoming;
        String incomingNote = "";
        /** the pass mx: the hits of the cell being played (null: not logged) */
        StringBuilder mxHits;
        float pepMaxClick;
        /** a click killed from full health */
        boolean pepFromFull;
        /** what the target lost since ready3, but its falls (a big blast throws it up) */
        float blastLost;
        final List<Float> blastCell = new ArrayList<>();
        /** the hi pass: a hit of this click went to the head (1), a sniper's (2) */
        int headHit;
        /** the hits on the victim's head and what they took (the pass rg: the body's hits apart) */
        int headHits;
        float headLost;
        /** the stand aims at the victim's eyes, not its chest */
        boolean aimHead;
        /** the pass rf: a fight under way; the game tick the hit that killed was fired at, the last projectile's flight */
        boolean fightOn;
        long fightKill = Long.MIN_VALUE, fightFlight;
        /** the super stimpak's heal goes on by the stand's tick (the fake player is not ticked by the level) */
        boolean healing;
        int targetTicks = Integer.MIN_VALUE;
        float poolBefore;
        /** the modifiers wearAttributes put on the lane's player, taken off before the next */
        final List<Runnable> worn = new ArrayList<>();
        /** the lines of the case running now, written out in the plan's order at the next barrier */
        List<Out> buf;

        Lane(int index) {
            this.index = index;
            o = new BlockPos(index * LANE_GAP, 120, 0);
        }

        /** every tick, before the lane's step: what the level does not do for the fake player and the agent */
        void pre() {
            if (target != null) target.invulnerableTime = 0;
            if (healing && target != null && target.tickCount == targetTicks) BalanceKit.tickHeal(target);
            if (target != null) targetTicks = target.tickCount;
            if (fbi != null) fbi.invulnerableTime = 0;
        }
    }

    /**
     * Blocks between two lanes along x. The farthest the stand reaches from a lane's origin: ESP's viewer 12 to -x, its
     * target 20 along z; a blast's hurt (the Missile Launcher's, ExplosionNukeGeneric 6) under 10. Shots go along +z.
     */
    private static final int LANE_GAP = 64;
    private static Lane[] LANES;
    /** the lane whose step runs now (or whose steps the plan writes, or whose creature an event is about) */
    private static Lane L;
    /** where the plan writes its steps now: L's queue */
    private static List<Runnable> STEPS;

    /** a line of a case: the list it goes to (LINES, TSV3) and the line */
    private record Out(List<String> to, String s) {
    }

    /** every case's lines, in the plan's order; a barrier writes out all those before it */
    private static final List<List<Out>> BUFS = new ArrayList<>();
    private static int flushed;
    /** the lines of the case being planned (null: a serial part, its lines written as they come) */
    private static List<Out> planBuf;

    /** all lanes wait here for the last; then the cases' lines before it are written out */
    private static final class Barrier implements Runnable {
        final int upTo;

        Barrier(int upTo) {
            this.upTo = upTo;
        }

        @Override
        public void run() {
        }
    }

    /** a lane's steps: each step of a case keeps the case's lines apart while it runs */
    private static final class Queue extends ArrayList<Runnable> {
        @Override
        public boolean add(Runnable r) {
            List<Out> b = planBuf;
            if (b == null || r instanceof Barrier) return super.add(r);
            return super.add(() -> {
                L.buf = b;
                try {
                    r.run();
                } finally {
                    L.buf = null;
                }
            });
        }
    }

    private static void line(String s) {
        out(LINES, s);
    }

    private static void out(List<String> to, String s) {
        if (L != null && L.buf != null) L.buf.add(new Out(to, s));
        else to.add(s);
    }

    private static void flush(int upTo) {
        for (; flushed < upTo; flushed++) for (Out o : BUFS.get(flushed)) o.to().add(o.s());
    }

    private static void barrier() {
        Barrier b = new Barrier(BUFS.size());
        for (Lane l : LANES) l.steps.add(b);
    }

    /**
     * The cases of a pass, each planned on a lane (the one with the fewest steps of this pass so far); the keys are the
     * pass's, set before by the serial part. A case's steps read the lane's creatures as they run (L).
     */
    private static void parallel(List<Runnable> cases) {
        barrier();
        int[] start = new int[LANES.length];
        for (Lane l : LANES) start[l.index] = l.steps.size();
        for (Runnable c : cases) {
            Lane best = LANES[0];
            for (Lane l : LANES) if (l.steps.size() - start[l.index] < best.steps.size() - start[best.index]) best = l;
            List<Out> buf = new ArrayList<>();
            BUFS.add(buf);
            L = best;
            STEPS = best.steps;
            planBuf = buf;
            c.run();
        }
        planBuf = null;
        L = LANES[0];
        STEPS = L.steps;
        barrier();
    }

    /** in a serial step (the other lanes at the barrier): {@code r} on every lane, each its own L */
    private static void eachLane(Runnable r) {
        Lane was = L;
        try {
            for (Lane l : LANES) {
                L = l;
                r.run();
            }
        } finally {
            L = was;
        }
    }

    /** the lane whose victim or target {@code e} is */
    private static Lane laneOf(net.minecraft.world.entity.Entity e) {
        if (LANES == null || e == null) return null;
        for (Lane l : LANES) if (l.victim == e || l.target == e) return l;
        return null;
    }

    /**
     * One tick: each lane's step; lanes at a barrier wait, and when all are there they go on at once (the lanes that
     * have not run a step this tick run their next). False: all done.
     */
    private static boolean tick() {
        boolean[] ran = new boolean[LANES.length];
        for (Lane l : LANES) {
            L = l;
            l.pre();
        }
        while (true) {
            for (Lane l : LANES) {
                if (ran[l.index]) continue;
                L = l;
                if (l.skipTo > l.at) l.at = Math.min(l.skipTo, l.steps.size());
                if (l.at < l.steps.size() && !(l.steps.get(l.at) instanceof Barrier)) {
                    ran[l.index] = true;
                    l.steps.get(l.at++).run();
                    if (l.hold) {
                        l.hold = false;
                        l.at--;
                    }
                }
            }
            Barrier b = null;
            boolean done = true;
            for (Lane l : LANES) {
                if (l.at >= l.steps.size()) continue;
                done = false;
                if (!(l.steps.get(l.at) instanceof Barrier at)) return true;
                b = at;
            }
            if (done) return false;
            flush(b.upTo);
            for (Lane l : LANES) if (l.at < l.steps.size()) l.at++;
        }
    }

    /** -PbalanceLanes (ntmgunfeel.checkBalance.lanes): 8 by default, 1 as the stand was before the lanes */
    static int lanes() {
        return Math.max(1, Math.min(32, Integer.getInteger(PROPERTY + ".lanes", 8)));
    }

    public static void registerIfAsked() {
        String file = System.getProperty(PROPERTY);
        if (file == null || file.isBlank()) return;
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, started -> {
            var server = started.getServer();
            level = server.overworld();
            long began = System.nanoTime();
            try {
                setup();
                plan();
                line("#\tдорожек " + LANES.length + ", ускорение тактов " + (StandSprint.on() ? "да" : "нет") + ", шагов по дорожкам " + java.util.Arrays.stream(LANES)
                        .map(l -> String.valueOf(l.steps.size())).toList());
            } catch (Throwable t) {
                line("FAIL\tsetup crashed: " + t);
                for (StackTraceElement s : t.getStackTrace()) line("    at " + s);
                BulletCheck.finish(file, LINES, 1);
                return;
            }
            StandSprint.start(server);
            boolean[] over = {false};
            long[] ticks = {0};
            NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, e -> {
                if (over[0]) return;
                try {
                    ticks[0]++;
                    if (tick()) return;
                    over[0] = true;
                    end(server, file, ok, ticks[0], began);
                } catch (Throwable t) {
                    over[0] = true;
                    if (L != null) L.buf = null;
                    line("FAIL\tcrashed: " + t);
                    for (StackTraceElement s : t.getStackTrace()) line("    at " + s);
                    end(server, file, false, ticks[0], began);
                }
            });
        });
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGHEST, false, net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent.class, e -> {
            Lane was = L, l = laneOf(e.getEntity());
            if (l == null) return;
            L = l;
            try {
                if (e.getEntity() == L.victim && L.victim != null) {
                    L.incoming += e.getAmount();
                    if (L.incomingNote.isEmpty()) L.incomingNote = e.getSource().getMsgId() + " " + f(e.getAmount())
                            + (L.victim instanceof net.minecraft.world.entity.player.Player p ? ", щит " + f(com.hbm.extprop.HbmPlayerProps.getData(p).shield) + "/"
                                    + f(com.hbm.extprop.HbmPlayerProps.getData(p).getEffectiveMaxShield(p)) : "");
                }
            } finally {
                L = was;
            }
        });
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST, false, LivingDamageEvent.Post.class, e -> {
            Lane was = L, l = laneOf(e.getEntity());
            if (l == null) return;
            L = l;
            try {
                // the pass rf: when the hit that killed was fired (a projectile by its age; a blast without one, by the
                // last projectile's flight; the shooter's own beam, now)
                if (e.getEntity() == L.target && L.fightOn && L.fightKill == Long.MIN_VALUE) {
                    var d = e.getSource().getDirectEntity();
                    long now = level.getGameTime();
                    if (d != null && d != L.shooter) L.fightFlight = d.tickCount;
                    if (L.target.getHealth() <= 0F || L.target.isDeadOrDying()) L.fightKill = now - (d == L.shooter ? 0 : L.fightFlight);
                }
                if (e.getEntity() == L.target && DamageCap.last.contains(" head=true"))
                    L.headHit = Math.max(L.headHit, DamageCap.last.contains(" sniper=true") ? 2 : 1);
                if (e.getEntity() != L.victim || L.victim == null) return;
                L.hits++;
                L.lost += e.getInflictedDamage();
                if (DamageCap.last.contains(" head=true")) {
                    L.headHits++;
                    L.headLost += e.getInflictedDamage();
                }
                // the pass mx: each hit of a cell, its type, what it took and the cap's ruling
                if (L.mxHits != null) L.mxHits.append(e.getSource().getMsgId()).append(' ').append(f(e.getInflictedDamage())).append(" {")
                        .append(e.getSource().getDirectEntity() == null ? "-" : e.getSource().getDirectEntity().getClass().getSimpleName()).append(' ')
                        .append(BalanceFinal.pairOf(e.getSource().getDirectEntity())).append(BalanceArmor.vntBlastNow() ? " blast" : "").append("} [").append(DamageCap.last).append("]; ");
                if (!e.getSource().is(net.minecraft.tags.DamageTypeTags.IS_FALL)) L.blastLost += e.getInflictedDamage();
            } finally {
                L = was;
            }
        });
    }

    /** the keys back, the sprint stopped, the lines written; the run's ticks and its real time in the file */
    private static void end(net.minecraft.server.MinecraftServer server, String file, boolean pass, long ticks, long began) {
        StandSprint.stop(server);
        if (L != null) L.buf = null;
        flush(BUFS.size());
        L = null;
        keys();
        line("#\tтактов " + ticks + ", " + String.format(Locale.ROOT, "%.1f", (System.nanoTime() - began) / 1e9) + " с");
        line(pass ? "ALL OK" : "SOME FAILED");
        BulletCheck.finish(file, LINES, pass ? 0 : 1);
    }

    private static void setup() {
        LANES = new Lane[lanes()];
        for (int i = 0; i < LANES.length; i++) {
            LANES[i] = new Lane(i);
            L = LANES[i];
            setupLane();
        }
        L = LANES[0];
        STEPS = L.steps;
    }

    private static void setupLane() {
        // the lane's ground kept loaded: the stand's reach (LANE_GAP) about the origin
        for (int cx = (L.o.getX() - 24) >> 4; cx <= (L.o.getX() + 24) >> 4; cx++)
            for (int cz = (L.o.getZ() - 24) >> 4; cz <= (L.o.getZ() + 24) >> 4; cz++) level.setChunkForced(cx, cz, true);
        String suffix = L.index == 0 ? "" : String.valueOf(L.index);
        for (int x = -4; x <= 6; x++) for (int z = -4; z <= 10; z++) level.setBlockAndUpdate(L.o.offset(x, -1, z), Blocks.STONE.defaultBlockState());
        L.shooter = EntityTypes.MANNEQUIN.create(level, EntitySpawnReason.COMMAND);
        L.shooter.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
        L.shooter.setHealth(1000);
        L.shooter.snapTo(L.o.getX() + 0.5, L.o.getY(), L.o.getZ() + 0.5, 0F, 0F);
        level.addFreshEntity(L.shooter);
        L.target = new FakePlayer(level, new GameProfile(UUID.nameUUIDFromBytes(("balanceTarget" + suffix).getBytes()), "balanceTarget" + suffix)) {
            @Override
            public boolean canHarmPlayer(net.minecraft.world.entity.player.Player other) {
                return true;
            }
        };
        L.target.setGameMode(GameType.SURVIVAL);
        L.target.snapTo(L.o.getX() + 0.5, L.o.getY(), L.o.getZ() + 0.5 + DIST, 180F, 0F);
        level.addNewPlayer(L.target);
        L.target.connection.markClientLoaded();
        L.target.setInvulnerable(false);
        L.gunner = FakePlayerFactory.get(level, new GameProfile(UUID.nameUUIDFromBytes(("balanceGunner" + suffix).getBytes()), "balanceGunner" + suffix));
        L.gunner.setGameMode(GameType.SURVIVAL);
        L.gunner.snapTo(L.o.getX() + 4.5, L.o.getY(), L.o.getZ() + 0.5, 0F, 0F);
        level.addNewPlayer(L.gunner);
    }

    private static void wait(int ticks) {
        for (int i = 0; i < ticks; i++) STEPS.add(() -> {
        });
    }

    // the model's numbers: notes/balance/work, review_model.after_rows (the sets' S and tau of the balance review), the guns as in NTM;
    // HEV and Security by work/scripts/r3.py (the third review: HEV 60 / 4, Security tau 4)
    private static final List<Pair> PAIRS = List.of(
            new Pair("steel", "light_revolver · m357_express", ModItems.GUN_LIGHT_REVOLVER::get, XFactory357.m357_express, 8.826F, 3),
            new Pair("dieselsuit", "henry · m44_express", ModItems.GUN_HENRY::get, XFactory44.m44_express, 8.858F, 3),
            new Pair("t51", "maresleg · g12_flechette", ModItems.GUN_MARESLEG::get, XFactory12ga.g12_flechette, 0.272F, 10),
            new Pair("hev", "star_f · p22_jhp", ModItems.GUN_STAR_F::get, XFactory22lr.p22_jhp, 1.429F, 14),
            new Pair("security", "carbine · r762_he", ModItems.GUN_CARBINE::get, XFactory762mm.r762_he, 2.619F, 8),
            new Pair("bj", "laser_pistol · capacitor (EN)", ModItems.GUN_LASER_PISTOL::get, XFactoryEnergy.energy_las, 1.182F, 17),
            new Pair("rpa", "mas36 · r762_du", ModItems.GUN_MAS36::get, XFactory762mm.r762_du, 1.767F, 12),
            new Pair("fau", "amat · bmg50_sm", ModItems.GUN_AMAT::get, XFactory50.bmg50_sm, 4.614F, 5),
            new Pair("hev", "amat · bmg50_sm (потолок)", ModItems.GUN_AMAT::get, XFactory50.bmg50_sm, 0F, 0),
            // NTM calls this hit off before it is dealt (its threshold 100 over 11.25 + 2): the formula leaves b of it
            new Pair("fau", "light_revolver · m357_express (слабая)", ModItems.GUN_LIGHT_REVOLVER::get, XFactory357.m357_express, 0.373F, 54));

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
            // cheese 2026-11-11 §3.4: the T-51 parts take the helmet's circuit (tier 3), so three of them are not tier-1 work
            new Recipe1v("t51_plate", List.of("circuit_basic", "motor", "gas_tank"), List.of()),
            new Recipe1v("t51_legs", List.of("circuit_basic", "motor"), List.of()),
            new Recipe1v("t51_boots", List.of("circuit_basic", "titanium_boots"), List.of()),
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
            // the reload in real time (a minute at most), however fast the ticks go
            long[] since = {0};
            STEPS.add(() -> {
                if (since[0] == 0) since[0] = System.nanoTime();
                if ((reload == null || !reload.isDone()) && System.nanoTime() - since[0] < 60_000_000_000L) L.hold = true;
            });
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

    /** the passes to run (ntmgunfeel.checkBalance.only, by commas: 2, 1g, 1v, 1a, 1b, dns, kit, db, hi, expl, esp, pool, pep, hot, mx, team, cheese; none: all) */
    private static boolean only(String pass) {
        String only = System.getProperty("ntmgunfeel.checkBalance.only", "").trim();
        return only.isEmpty() || java.util.Arrays.asList(only.split("\\s*,\\s*")).contains(pass);
    }

    private static void plan() {
        // -PbalanceTestLog=true: the server's test log on (each shot's spread, TestLog), its file in the result
        if (Boolean.getBoolean(PROPERTY + ".testLog")) line("#\ttest log " + TestLog.set(false, true));
        String asked = System.getProperty("ntmgunfeel.checkBalance.only", "").trim();
        for (String p : asked.isEmpty() ? new String[0] : asked.split("\\s*,\\s*"))
            if (!List.of("2", "1g", "1v", "1a", "1b", "dns", "kit", "db", "hi", "expl", "esp", "pool", "pep", "hot", "mx", "team", "cheese", "frag", "bx", "rg", "lg", "ps", "tb", "g4", "aim", "rf", "bot").contains(p)) throw new IllegalArgumentException("no pass " + p);
        // the passes before the pool of DNS and FAU check their ratings: the pool off (X858) but in the pass "pool"
        // the passes check the machine of X908 (formula C, the gun table): the final scheme off, its cells and team lines
        // turn it on by a key line (balance.final true)
        STEPS.add(() -> keys("balance.final", String.valueOf(Boolean.getBoolean(PROPERTY + ".final")), "balance.immune.dns.mode", "formula", "balance.immune.fau.mode", "formula"));
        wait(30);
        if (only("2")) pass("2", BalanceCheck::plan2);
        if (only("1g")) pass("1g", BalanceCheck::planAbilities);
        if (only("1v")) pass("1v", BalanceCheck::planRecipes);
        if (only("1a")) pass("1a", BalanceCheck::plan1a);
        if (only("1b")) pass("1b", BalanceCheck::plan1b);
        if (only("dns")) pass("dns", BalanceCheck::planDns);
        if (only("kit")) pass("kit", BalanceCheck::planKit);
        if (only("db")) pass("db", BalanceCheck::planDb);
        if (only("hi")) pass("hi", BalanceCheck::planHigh);
        if (only("expl")) pass("expl", BalanceCheck::planExpl);
        if (only("esp")) pass("esp", BalanceCheck::planEsp);
        if (only("pool")) pass("pool", BalanceCheck::planPool);
        if (only("pep")) pass("pep", BalanceCheck::planPep);
        if (only("hot")) pass("hot", BalanceCheck::planHot);
        if (only("mx")) pass("mx", BalanceCheck::planMatrix);
        if (only("team")) pass("team", BalanceCheck::planTeam);
        if (only("cheese")) pass("cheese", BalanceCheck::planCheese);
        if (only("frag")) pass("frag", BalanceCheck::planFrag);
        if (only("bx")) pass("bx", BalanceCheck::planBlastLadder);
        if (only("rg")) pass("rg", BalanceCheck::planRange);
        if (only("lg")) pass("lg", BalanceCheck::planLog);
        if (only("ps")) pass("ps", BalanceCheck::planShare);
        if (only("tb")) pass("tb", BalanceCheck::planTrenchBlock);
        if (only("bot")) pass("bot", BalanceCheck::planBot);
        // asked by name only: the final scheme on in it whatever the run's
        if (asked.contains("g4")) pass("g4", BalanceCheck::planAcidGas);
        // Fable №3: NTM's aim of the guns (0.1), fights by distance (stage 4); asked by name only
        if (List.of(asked.split("\\s*,\\s*")).contains("aim")) pass("aim", BalanceCheck::planAim);
        if (List.of(asked.split("\\s*,\\s*")).contains("rf")) pass("rf", BalanceCheck::planFights);
    }

    /** a pass planned, its start marked in the test log (-PbalanceTestLog: which pass a shot's spread is of) */
    private static void pass(String name, Runnable plan) {
        if (Boolean.getBoolean(PROPERTY + ".testLog")) STEPS.add(() -> TestLog.at(level, "pass", "name", name));
        plan.run();
    }

    // ---------------------------------------------------------------- melee and the flamethrower (hotfix X867)

    /** a target of the pass hot: an FBI agent, a zombie or the player, bare or in Security */
    private static LivingEntity hotTarget(String who, String set) {
        LivingEntity v;
        if (who.equals("player")) v = L.target;
        else {
            var created = who.equals("fbi") ? BuiltInRegistries.ENTITY_TYPE.getValue(FBI).create(level, EntitySpawnReason.COMMAND)
                    : EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
            if (!(created instanceof Mob mob)) throw new IllegalStateException("no " + who);
            mob.setNoAi(true);
            level.addFreshEntity(mob);
            v = mob;
            // the stand's player out of the way: it stood where the mob stands and took its shots
            for (EquipmentSlot slot : SLOTS) L.target.setItemSlot(slot, ItemStack.EMPTY);
            wearAttributes(L.target);
            L.target.snapTo(L.o.getX() + 6.5, L.o.getY(), L.o.getZ() + 0.5 + DIST, 180F, 0F);
        }
        for (EquipmentSlot slot : SLOTS) v.setItemSlot(slot, ItemStack.EMPTY);
        v.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        if (!set.isEmpty()) dress(v, set, true);
        if (v == L.target) wearAttributes(v);
        v.snapTo(L.o.getX() + 0.5, L.o.getY(), L.o.getZ() + 0.5 + DIST, 180F, 0F);
        v.setHealth(v.getMaxHealth());
        v.clearFire();
        com.hbm.extprop.HbmLivingProps.getData(v).fire = 0;
        return v;
    }

    /**
     * The stand's player does not tick, so vanilla never puts its pieces' armour on it: the pieces' modifiers (after
     * balance.vanilla0's event) put on by hand, as a worn set has them in the game.
     */
    private static void wearAttributes(LivingEntity v) {
        L.worn.forEach(Runnable::run);
        L.worn.clear();
        for (EquipmentSlot slot : SLOTS) v.getItemBySlot(slot).forEachModifier(slot, (attr, mod) -> {
            var inst = v.getAttribute(attr);
            if (inst == null || inst.hasModifier(mod.id())) return;
            inst.addTransientModifier(mod);
            L.worn.add(() -> inst.removeModifier(mod.id()));
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
            case "удар_игрока", "crucible" -> ds.playerAttack(L.gunner);
            case "удар_моба" -> ds.mobAttack(L.shooter);
            case "стрела" -> {
                var arrow = (net.minecraft.world.entity.projectile.arrow.AbstractArrow) EntityTypes.ARROW.create(level, EntitySpawnReason.COMMAND);
                yield ds.arrow(arrow, L.shooter);
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
                    .getOrThrow(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DAMAGE_TYPE, Identifier.fromNamespaceAndPath("hbm", "physical"))), L.gunner, L.gunner);
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
        line("#\t--- hot: ближний бой и огнемёт по наборам (баланс / NTM)");
        String[] sets = {"", "security", "t51", "bj", "dns"};
        float[] ntmHurt = new float[sets.length * HOT_HURTS.length];
        for (String[] mode : HOT_MODES) {
            boolean ntm = mode[0].equals("NTM");
            STEPS.add(() -> {
                keys("balanceArmor", "true", "balanceGuns", "true", "damageCap", "true", "damageCapMobs", "false", "modelHitboxes", "true");
                for (int i = 1; i < mode.length; i += 2) keys(mode[i], mode[i + 1]);
                eachLane(() -> L.shooter.setInvulnerable(true));
            });
            wait(30);
            // the hits of no shot (a blow, a mob's, an arrow, fire, a fall, gas...) on the player, by set: NTM's numbers
            List<Runnable> cases = new ArrayList<>();
            for (int si = 0; si < sets.length; si++) {
                String set = sets[si];
                int sj = si;
                cases.add(() -> {
                for (int ci = 0; ci < HOT_HURTS.length; ci++) {
                    int idx = sj * HOT_HURTS.length + ci;
                    String[] hurt = HOT_HURTS[ci];
                    STEPS.add(() -> hotTarget("player", set));
                    wait(3);
                    STEPS.add(() -> {
                        LivingEntity v = L.target;
                        begin(v);
                        v.invulnerableTime = 0;
                        float hp = v.getHealth();
                        float amount = Float.parseFloat(hurt[1]);
                        v.hurtServer(level, hotSource(hurt[0]), amount);
                        float took = hp - v.getHealth();
                        String what = hurt[0] + " " + hurt[1] + " по игроку " + (set.isEmpty() ? "без брони" : "в " + set);
                        line("#\thot\t" + mode[0] + "\t" + what + "\t" + f(took) + "\tваниль " + v.getArmorValue());
                        if (ntm) ntmHurt[idx] = took;
                        // a big blast (TNT) is the formula's by the balance's notes (balanceArmorBlasts): logged only
                        else if (mode.length == 1 && !hurt[0].equals("tnt")) check(Math.abs(took - ntmHurt[idx]) <= 0.05F + 0.02F * ntmHurt[idx],
                                "hot: " + what + ": " + f(took) + " = NTM " + f(ntmHurt[idx]));
                        v.clearFire();
                    });
                    wait(2);
                }
                });
            }
            for (String who : new String[] {"fbi", "zombie", "player"})
                for (String set : new String[] {"", "security"})
                    for (int g = 0; g < HOT_GUNS.length; g++) {
                        int gi = g;
                        cases.add(() -> {
                        LivingEntity[] v = {null};
                        float[] hp = {0F};
                        STEPS.add(() -> {
                            // Daybreaker's flames go off: the floor and the air back, the shooter home
                            for (int x = -6; x <= 8; x++) for (int z = -4; z <= 12; z++) {
                                level.setBlockAndUpdate(L.o.offset(x, -1, z), Blocks.BEDROCK.defaultBlockState());
                                for (int y = 0; y <= 3; y++) level.setBlockAndUpdate(L.o.offset(x, y, z), Blocks.AIR.defaultBlockState());
                            }
                            v[0] = hotTarget(who, set);
                            begin(v[0]);
                            hp[0] = v[0].getHealth();
                        });
                        wait(3);
                        for (int c = 0; c < 6; c++) {
                            STEPS.add(() -> {
                                L.shooter.snapTo(L.o.getX() + 0.5, L.o.getY(), L.o.getZ() + 0.5, 0F, 0F);
                                L.shooter.setDeltaMovement(Vec3.ZERO);
                                if (v[0] != L.target) v[0].snapTo(L.o.getX() + 0.5, L.o.getY(), L.o.getZ() + 0.5 + DIST, 180F, 0F);
                                v[0].setDeltaMovement(Vec3.ZERO);
                                switch (gi) {
                                    case 0 -> fire(L.shooter, ModItems.GUN_FLAMER.get(), com.hbm.items.weapon.sedna.factory.XFactoryFlamer.flame_diesel, EnumKeybind.GUN_PRIMARY);
                                    case 1 -> fire(L.shooter, ModItems.GUN_FLAMER_TOPAZ.get(), com.hbm.items.weapon.sedna.factory.XFactoryFlamer.flame_topaz_diesel, EnumKeybind.GUN_PRIMARY);
                                    case 2 -> fire(L.shooter, ModItems.GUN_FLAMER_DAYBREAKER.get(), com.hbm.items.weapon.sedna.factory.XFactoryFlamer.flame_daybreaker_diesel, EnumKeybind.GUN_PRIMARY);
                                    case 3 -> fire(L.shooter, ModItems.GUN_LASER_PISTOL.get(), XFactoryEnergy.energy_las, EnumKeybind.GUN_PRIMARY);
                                    default -> fire(L.shooter, ModItems.GUN_HENRY.get(), XFactory44.m44_express, EnumKeybind.GUN_PRIMARY);
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
                            line("#\thot\t" + mode[0] + "\t" + what + "\tснято " + f(took) + (v[0].isAlive() ? "" : " (умер)") + ", огонь NTM " + fire
                                    + ", горит " + v[0].getRemainingFireTicks() + ", входящих " + f(L.incoming) + " (" + L.incomingNote + "), ваниль " + v[0].getArmorValue());
                            // the agent: NTM registers it fireImmune, a flame takes nothing off it there either
                            if (!mode[0].equals("NTM") && gi <= 2 && !who.equals("fbi")) check(took > 0F, "hot, " + mode[0] + ": " + what + ": урон есть (" + f(took) + ")");
                            if (v[0] != L.target) v[0].discard();
                            else L.target.clearFire();
                        });
                        wait(5);
                        });
                    }
            parallel(cases);
        }
        STEPS.add(() -> {
            keys("modelHitboxes", "true");
            eachLane(() -> {
                L.shooter.setInvulnerable(false);
                for (EquipmentSlot slot : SLOTS) L.target.setItemSlot(slot, ItemStack.EMPTY);
                wearAttributes(L.target);
                L.target.snapTo(L.o.getX() + 0.5, L.o.getY(), L.o.getZ() + 0.5 + DIST, 180F, 0F);
            });
        });
        wait(20);
    }

    // ---------------------------------------------------------------- the model's cells, one by one

    /** a cell of the model: the set (empty: none), the gun (no gun_), its ammo (BalanceGuns.ammoName), the head or not */
    private record Cell(String set, String gun, String ammo, boolean head) {
    }

    /** the gun's ammo of that name, from every receiver's magazine */
    private static BulletConfig ammoNamed(Item item, String name) {
        if (!(item instanceof ItemGunBaseNT gun)) return null;
        ItemStack stack = new ItemStack(item);
        for (int c = 0; c < gun.getConfigCount(); c++)
            for (com.hbm.items.weapon.sedna.Receiver rec : gun.getConfig(stack, c).getReceivers(stack))
                for (BulletConfig b : AmmoLock.accepted(rec.getMagazine(stack))) if (BalanceGuns.ammoName(b).equals(name)) return b;
        return null;
    }

    /**
     * The model's cells (ntmgunfeel.checkBalance.cells: a file of lines "set gun ammo body|head", tabs; set "-" for no
     * armour): three primary clicks, each from full health and an empty damage cap, what a click took and how many hits
     * it was. DNS and FAU by their rating (formula); euphemium in its window (a pool of 0.01 emptied by a first click).
     * No checks: the lines "mx" go to the model (notes/balance/review-fixes-2026-11-10.md).
     */
    /** -PbalanceCellHits: each hit of the cells logged */
    private static boolean mxLog;
    /**
     * -PbalanceCellsHome=true (Fable №2, stage 5): the pass mx at the role's working distance (homeOf), aimed, the range
     * rule on, the clicks at the gun's own pace (matrixHomeCell); -PbalanceCellsDist=<blocks> one distance for all
     */
    private static boolean mxHome;

    private static void planMatrix() {
        String file = System.getProperty("ntmgunfeel.checkBalance.cells", "").trim();
        mxLog = Boolean.getBoolean("ntmgunfeel.checkBalance.cellHits");
        mxHome = Boolean.getBoolean("ntmgunfeel.checkBalance.cellsHome");
        if (file.isEmpty()) return;
        List<Cell> cells = new ArrayList<>();
        // lines "key name value": server keys for the cells after it (a cell "key" line in place of a cell)
        try {
            for (String l : Files.readAllLines(Path.of(file))) {
                String[] p = l.trim().split("\t");
                if (p.length == 3 && p[0].equals("key")) {
                    cells.add(new Cell("\u0000key", p[1], p[2], false));
                    continue;
                }
                if (p.length < 4 || l.startsWith("#")) continue;
                cells.add(new Cell(p[0].equals("-") ? "" : p[0], p[1], p[2], p[3].equals("head")));
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        line("#\t--- матрица модели: " + cells.size() + " клеток, по 3 клика с полного здоровья, 4 бл");
        STEPS.add(() -> {
            keys("balanceArmor", "true", "balanceGuns", "true", "damageCap", "true", "damageCapMobs", "false", "balance.immune.dns.mode", "pool",
                    "balance.immune.fau.mode", "pool", "balance.immune.mode", "pool", "balance.immune.pool", "0.0001", "balance.immune.window", "600",
                    "balance.immune.recover", "1", "balance.immune.dns.pool", "0.0001", "balance.immune.dns.window", "600", "balance.immune.dns.recover", "1",
                    "balance.immune.fau.pool", "0.0001", "balance.immune.fau.window", "600", "balance.immune.fau.recover", "1",
                    // the model's clicks are at the role's own distance, the stand's at 4 blocks: no range rule (pass rg has it);
                    // at the role's distance (mxHome) the rule on
                    "balance.final.range", mxHome ? BalanceRangeRule.LINES : "off");
            airClicks = 0;
            eachLane(() -> {
                L.shooter.setInvulnerable(true);
                // the chunks out past the target (-PbalanceCellsDist), or a bullet stops at the edge of the loaded ones
                int far = Math.max(40, Integer.getInteger(PROPERTY + ".cellsDist", 0) + 16);
                if (mxHome) for (int cx = (L.o.getX() - 8) >> 4; cx <= (L.o.getX() + 8) >> 4; cx++)
                    for (int cz = (L.o.getZ() - 8) >> 4; cz <= (L.o.getZ() + far) >> 4; cz++) level.setChunkForced(cx, cz, true);
            });
        });
        wait(30);
        // the cells between two key lines go to the lanes; a key line is the server's: all lanes wait for it
        List<Runnable> run = new ArrayList<>();
        for (Cell cell : cells) {
            if (cell.set().equals("\u0000key")) {
                parallel(run);
                run = new ArrayList<>();
                STEPS.add(() -> {
                    keys(cell.gun(), cell.ammo());
                    line("#\tmx-key\t" + cell.gun() + "\t" + cell.ammo());
                });
                wait(10);
                continue;
            }
            Item gun = BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("hbm", "gun_" + cell.gun()));
            BulletConfig ammo = ammoNamed(gun, cell.ammo());
            String id = (cell.set().isEmpty() ? "-" : cell.set()) + "\t" + cell.gun() + "\t" + cell.ammo() + "\t" + (cell.head() ? "head" : "body");
            if (ammo == null) {
                line("mx\t" + id + "\tнет патрона");
                continue;
            }
            var pr = mxHome ? BalanceFinal.pairs().get(cell.gun() + "." + cell.ammo()) : null;
            if (pr != null) run.add(() -> matrixHomeCell(cell, gun, ammo, id, pr));
            else run.add(() -> matrixCell(cell, gun, ammo, id));
        }
        parallel(run);
        STEPS.add(() -> {
            if (mxHome) check(airClicks == 0, "mx: стрелок на земле на каждом клике (в воздухе " + airClicks + ")");
            keys("balance.immune.pool", "150", "balance.immune.window", "15", "balance.immune.recover", "60", "balance.immune.dns.mode", "formula",
                    "balance.immune.fau.mode", "formula", "balance.immune.dns.pool", "40", "balance.immune.dns.window", "15", "balance.immune.dns.recover", "60",
                    "balance.immune.fau.pool", "45", "balance.immune.fau.window", "15", "balance.immune.fau.recover", "60", "balance.final.range",
                    BalanceRangeRule.LINES);
            eachLane(() -> {
                L.shooter.setInvulnerable(false);
                for (EquipmentSlot slot : SLOTS) L.target.setItemSlot(slot, ItemStack.EMPTY);
                wearAttributes(L.target);
            });
        });
        wait(30);
    }

    /** the pass mx at the role's distance: the target {@code d} blocks off on a floor (at 4 blocks: homePool's place) */
    private static void mxPlace(int d) {
        if (d == (int) DIST) return;
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) level.setBlockAndUpdate(L.o.offset(x, -1, d + z), Blocks.STONE.defaultBlockState());
        L.target.setDeltaMovement(Vec3.ZERO);
        L.target.snapTo(L.o.getX() + 0.5, L.o.getY(), L.o.getZ() + 0.5 + d, 180F, 0F);
    }

    /**
     * A cell of the pass mx at the role's working distance (mxHome): the shooter aimed, standing, both guns of akimbo;
     * the clicks at the pair's interval, as held in a fight (an automatic's bloom as in the pass ps): at least 3, an
     * automatic at least 3 s, at least 30 projectiles, a pair of a share of hits under 0.97 at least 100, Trenchmaster at
     * least 9 clicks; the target's health put back every tick and what it lost summed. A set with the pool (euphemium, DNS, FAU): a click
     * first that empties it (not counted). A line: what a click took, its hits, the clicks, the distance.
     */
    private static void matrixHomeCell(Cell cell, Item gun, BulletConfig ammo, String id, BalanceFinalRule.Pair pr) {
        float[] took = {0F};
        int[] hitsSeen = {0};
        // a set with the pool (euphemium, DNS, FAU): a click first that empties it, or the cell's first hit goes into it
        boolean pool = java.util.Arrays.stream(EuphImmunity.Kit.values()).anyMatch(k -> k.set.equals(cell.set()));
        // -PbalanceCellsDist=<blocks>: one distance for all in place of the role's
        int d = Integer.getInteger(PROPERTY + ".cellsDist", homeOf(pr)), iv = Math.max(1, BalanceFinalRule.intervalTicks(pr));
        int n = 3;
        if (pr.auto()) n = Math.max(n, (int) Math.ceil(60.0 / iv));
        // a share of 0.90 and above is written as 1.00: a miss in 3 clicks would be a third of the cell
        n = Math.max(n, (int) Math.ceil(30 / pr.projectiles()));
        if (pr.p() < 0.97) n = Math.max(n, (int) Math.ceil(100 / pr.projectiles()));
        if (cell.set().equals("trenchmaster")) n = Math.max(n, 9);
        // -PbalanceCellsClicks=<n>: this many clicks a cell (a held burst's length)
        int clicks = Integer.getInteger(PROPERTY + ".cellsClicks", n);
        STEPS.add(() -> {
            begin(L.target);
            for (EquipmentSlot slot : SLOTS) L.target.setItemSlot(slot, ItemStack.EMPTY);
            if (!cell.set().isEmpty()) dress(L.target, cell.set(), true);
            wearAttributes(L.target);
            EuphImmunity.forget(L.target);
            homePool();
            mxPlace(d);
            L.aimHead = cell.head();
        });
        // the bloom of the cell before gone (it halves every 5 ticks)
        wait(40);
        if (pool) {
            STEPS.add(() -> {
                mxPlace(d);
                fireAimed(L.shooter, gun, ammo, pr.hands() > 1);
            });
            wait(SPACING + 10);
        }
        STEPS.add(() -> {
            L.target.setHealth(L.target.getMaxHealth());
            L.target.setAbsorptionAmount(0F);
            DamageCap.forget(L.target);
            took[0] = 0F;
            hitsSeen[0] = L.seen.hits;
        });
        int ticks = clicks * iv + SPACING + 10;
        for (int t = 0; t < ticks; t++) {
            boolean fire = t % iv == 0 && t / iv < clicks;
            STEPS.add(() -> {
                took[0] += Math.max(0F, L.target.getMaxHealth() - Math.max(0F, L.target.getHealth()));
                L.target.setHealth(L.target.getMaxHealth());
                L.target.setAbsorptionAmount(0F);
                DamageCap.forget(L.target);
                mxPlace(d);
                if (fire && Spread.inAir(L.shooter)) airClicks++;
                if (fire) fireAimed(L.shooter, gun, ammo, pr.hands() > 1);
            });
        }
        STEPS.add(() -> {
            took[0] += Math.max(0F, L.target.getMaxHealth() - Math.max(0F, L.target.getHealth()));
            L.target.setHealth(L.target.getMaxHealth());
            L.aimHead = false;
            line("mx\t" + id + "\t" + String.format(Locale.ROOT, "%.5f", took[0] / clicks) + "\t" + f((L.seen.hits - hitsSeen[0]) / (float) clicks) + "\t" + clicks + "\t" + d);
        });
    }

    /** a cell of the pass mx: three clicks from full health, what a click took and its hits */
    private static void matrixCell(Cell cell, Item gun, BulletConfig ammo, String id) {
        float[] took = {0F};
        int[] hitsSeen = {0};
        boolean euph = cell.set().equals("euphemium");
        STEPS.add(() -> {
            begin(L.target);
            for (EquipmentSlot slot : SLOTS) L.target.setItemSlot(slot, ItemStack.EMPTY);
            // as worn in the game: the pieces' vanilla armour by balance.vanilla0 (the joke sets keep theirs)
            if (!cell.set().isEmpty()) dress(L.target, cell.set(), true);
            wearAttributes(L.target);
            EuphImmunity.forget(L.target);
            homePool();
            L.aimHead = cell.head();
            took[0] = 0F;
            hitsSeen[0] = 0;
            L.mxHits = new StringBuilder();
        });
        wait(5);
        // euphemium: the first click empties the pool of 0.01, the window is open from the next tick
        for (int c = euph ? -1 : 0; c < 3; c++) {
            boolean count = c >= 0;
            float[] hp = {0F};
            int[] seen = {0};
            STEPS.add(() -> {
                L.target.setHealth(L.target.getMaxHealth());
                L.target.setAbsorptionAmount(0F);
                DamageCap.forget(L.target);
                homePool();
                hp[0] = L.target.getHealth();
                seen[0] = L.seen.hits;
                fire(L.shooter, gun, ammo, EnumKeybind.GUN_PRIMARY);
            });
            wait(SPACING + 10);
            STEPS.add(() -> {
                if (!count) return;
                took[0] += hp[0] - Math.max(0F, L.target.getHealth());
                hitsSeen[0] += L.seen.hits - seen[0];
            });
        }
        STEPS.add(() -> {
            L.aimHead = false;
            line("mx\t" + id + "\t" + String.format(Locale.ROOT, "%.5f", took[0] / 3F) + "\t" + f(hitsSeen[0] / 3F));
            if (mxLog) line("#\tmx-hits\t" + id + "\t" + L.mxHits);
            L.mxHits = null;
        });
    }

    // ---------------------------------------------------------------- the cheese review: several on one (item A, B)

    /** a cell of the pass team: n shooters of one pair on the target in the set, a click each {@code iv} ticks, {@code mag} to a reload of {@code reload} */
    private record TeamCell(int n, String set, String gun, String ammo, int iv, int mag, int reload, int limit) {
    }

    /** the lane's mannequin and {@code n - 1} more beside it, out of the line of fire, each a shooter of its own */
    private static List<LivingEntity> teamOf(int n) {
        while (L.team.size() < n - 1) {
            Mannequin m = EntityTypes.MANNEQUIN.create(level, EntitySpawnReason.COMMAND);
            m.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
            m.setHealth(1000);
            m.setInvulnerable(true);
            level.addFreshEntity(m);
            L.team.add(m);
        }
        List<LivingEntity> out = new ArrayList<>();
        out.add(L.shooter);
        for (int i = 0; i < n - 1; i++) {
            Mannequin m = L.team.get(i);
            // two to a row, a block and a half apart, behind the lane's mannequin
            double x = L.o.getX() + 0.5 + (i % 2 == 0 ? -1.5 : 1.5), z = L.o.getZ() + 0.5 - 1.5 * (i / 2);
            m.setDeltaMovement(Vec3.ZERO);
            m.snapTo(x, L.o.getY(), z, 0F, 0F);
            out.add(m);
        }
        return out;
    }

    /**
     * The pass team (ntmgunfeel.checkBalance.team: a file of lines "n set gun ammo iv mag reload [limit]", tabs, or
     * "key name value"): n shooters of the same pair on the target from full health, each firing on the model's
     * schedule (a click each iv ticks, after mag clicks a reload of reload ticks; all start together), until it dies or
     * the limit (ticks, 2400 when not given). Each shooter has its own damage cap on the target (DamageCap). The pools
     * of DNS, FAU and euphemium as in the game (pool, window 15 s, recovery 60, hold 10). No checks: the lines "team"
     * go to the model (notes/balance/cheese-fixes-2026-11-11.md).
     */
    private static void planTeam() {
        String file = System.getProperty(PROPERTY + ".team", "").trim();
        if (file.isEmpty()) return;
        List<Object> cells = new ArrayList<>();
        try {
            for (String l : Files.readAllLines(Path.of(file))) {
                if (l.startsWith("#") || l.isBlank()) continue;
                String[] p = l.trim().split("\t");
                if (p.length == 3 && p[0].equals("key")) {
                    cells.add(new String[] {p[1], p[2]});
                    continue;
                }
                if (p.length < 7) continue;
                cells.add(new TeamCell(Integer.parseInt(p[0]), p[1].equals("-") ? "" : p[1], p[2], p[3], Math.max(1, Integer.parseInt(p[4])),
                        Math.max(1, Integer.parseInt(p[5])), Math.max(0, Integer.parseInt(p[6])), p.length > 7 ? Integer.parseInt(p[7]) : 2400));
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        line("#\t--- команда: " + cells.size() + " строк, n стрелков одной парой по одной цели, 4 бл");
        STEPS.add(() -> {
            keys("balanceArmor", "true", "balanceGuns", "true", "damageCap", "true", "damageCapMobs", "false", "balance.immune.mode", "pool",
                    "balance.immune.dns.mode", "pool", "balance.immune.fau.mode", "pool", "balance.immune.pool", "150", "balance.immune.dns.pool", "40",
                    "balance.immune.fau.pool", "45", "balance.immune.window", "15", "balance.immune.dns.window", "15", "balance.immune.fau.window", "15",
                    "balance.immune.recover", "60", "balance.immune.dns.recover", "60", "balance.immune.fau.recover", "60", "balance.immune.hold", "10",
                    "balance.immune.refillPause", "5");
            eachLane(() -> L.shooter.setInvulnerable(true));
        });
        wait(30);
        List<Runnable> run = new ArrayList<>();
        for (Object o : cells) {
            if (o instanceof String[] kv) {
                parallel(run);
                run = new ArrayList<>();
                STEPS.add(() -> {
                    keys(kv[0], kv[1]);
                    line("#\tteam-key\t" + kv[0] + "\t" + kv[1]);
                });
                wait(10);
                continue;
            }
            TeamCell c = (TeamCell) o;
            Item gun = BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("hbm", "gun_" + c.gun()));
            BulletConfig ammo = ammoNamed(gun, c.ammo());
            String id = c.n() + "\t" + (c.set().isEmpty() ? "-" : c.set()) + "\t" + c.gun() + "\t" + c.ammo();
            if (ammo == null) {
                line("team\t" + id + "\tнет патрона");
                continue;
            }
            run.add(() -> teamCell(c, gun, ammo, id));
        }
        parallel(run);
        STEPS.add(() -> eachLane(() -> {
            for (Mannequin m : L.team) m.discard();
            L.team.clear();
            L.shooter.setInvulnerable(false);
            for (EquipmentSlot slot : SLOTS) L.target.setItemSlot(slot, ItemStack.EMPTY);
            wearAttributes(L.target);
        }));
        wait(10);
    }

    private static void teamCell(TeamCell c, Item gun, BulletConfig ammo, String id) {
        int[] next = new int[c.n()], mag = new int[c.n()], clicks = {0}, died = {-1};
        @SuppressWarnings("unchecked")
        List<LivingEntity>[] who = new List[1];
        int[] end = {0};
        STEPS.add(() -> {
            begin(L.target);
            for (EquipmentSlot slot : SLOTS) L.target.setItemSlot(slot, ItemStack.EMPTY);
            if (!c.set().isEmpty()) dress(L.target, c.set(), true);
            wearAttributes(L.target);
            EuphImmunity.forget(L.target);
            homePool();
            L.aimHead = false;
            who[0] = teamOf(c.n());
            java.util.Arrays.fill(next, 0);
            java.util.Arrays.fill(mag, c.mag());
        });
        wait(5);
        for (int t = 0; t < c.limit(); t++) {
            int tick = t;
            STEPS.add(() -> {
                if (L.target.getHealth() <= 0F || L.target.isDeadOrDying()) {
                    died[0] = tick;
                    L.skipTo = end[0];
                    return;
                }
                for (int i = 0; i < c.n(); i++) {
                    if (tick < next[i]) continue;
                    if (mag[i] < 1) {
                        next[i] = tick + c.reload();
                        mag[i] = c.mag();
                        if (c.reload() > 0) continue;
                    }
                    fire(who[0].get(i), gun, ammo, EnumKeybind.GUN_PRIMARY);
                    clicks[0]++;
                    mag[i]--;
                    next[i] = tick + c.iv();
                }
            });
        }
        end[0] = STEPS.size();
        STEPS.add(() -> {
            if (died[0] < 0 && (L.target.getHealth() <= 0F || L.target.isDeadOrDying())) died[0] = c.limit();
            // the tick of the killing click: death is seen the tick after
            line("team\t" + id + "\t" + (died[0] >= 0 ? String.valueOf(Math.max(0, died[0] - 1)) : "> " + c.limit()) + "\t" + clicks[0] + "\t" + L.seen.hits
                    + "\t" + f(L.lost) + "\t" + EuphImmunity.describe(L.target));
            for (EquipmentSlot slot : SLOTS) L.target.setItemSlot(slot, ItemStack.EMPTY);
        });
        wait(5);
    }

    /**
     * The cheese review's checks: (1) DNS emptied to its window, then FAU put on: with balance.immune.shared the window
     * goes on (the hit gets through), without it a fresh pool calls it off; (2) two super stimpaks at once: 25 HP over
     * 12 s, not 50 (balance.healStack off), 50 with it on.
     */
    private static void planCheese() {
        line("#\t--- разбор cheese: смена DNS на FAU, два супер-стимпака");
        for (boolean shared : new boolean[] {true, false}) {
            STEPS.add(() -> {
                keys("balanceArmor", "true", "balanceGuns", "true", "damageCap", "true", "damageCapMobs", "false", "balance.immune.dns.mode", "pool",
                        "balance.immune.fau.mode", "pool", "balance.immune.dns.pool", "4", "balance.immune.fau.pool", "45", "balance.immune.dns.window", "15",
                        "balance.immune.fau.window", "15", "balance.immune.dns.recover", "60", "balance.immune.fau.recover", "60", "balance.immune.hold", "10",
                        "balance.immune.shared", Boolean.toString(shared));
                L.shooter.setInvulnerable(true);
                begin(L.target);
                dress(L.target, "dns", false);
                EuphImmunity.forget(L.target);
                homePool();
            });
            wait(10);
            // AMAT takes about 3 of the pool a shot: two empty DNS's 4
            for (int i = 0; i < 2; i++) {
                STEPS.add(() -> {
                    L.target.setHealth(L.target.getMaxHealth());
                    homePool();
                    fire(L.shooter, ModItems.GUN_AMAT.get(), XFactory50.bmg50_sm, EnumKeybind.GUN_PRIMARY);
                });
                wait(SPACING + 10);
            }
            float[] lost = {0F};
            String[] dns = {""};
            STEPS.add(() -> {
                dns[0] = EuphImmunity.describe(EuphImmunity.Kit.DNS, L.target);
                dress(L.target, "fau", false);
                L.target.setHealth(L.target.getMaxHealth());
                homePool();
                lost[0] = L.target.getHealth();
                fire(L.shooter, ModItems.GUN_LIGHT_REVOLVER.get(), XFactory357.m357_express, EnumKeybind.GUN_PRIMARY);
            });
            wait(SPACING + 10);
            STEPS.add(() -> {
                float took = lost[0] - L.target.getHealth();
                String fau = EuphImmunity.describe(EuphImmunity.Kit.FAU, L.target);
                line("#\tcheese: смена dns→fau, shared " + shared + ": DNS " + dns[0] + ", FAU " + fau + ", снято " + f(took));
                check(dns[0].startsWith("WINDOW") && (shared ? took > 0F && fau.startsWith("WINDOW") : took == 0F && fau.startsWith("POOL")),
                        "cheese: DNS опустошён, надет FAU, balance.immune.shared = " + shared + ": " + (shared ? "окно идёт дальше" : "свежий запас FAU") + " (снято "
                                + f(took) + ", FAU " + fau + ")");
                for (EquipmentSlot slot : SLOTS) L.target.setItemSlot(slot, ItemStack.EMPTY);
                EuphImmunity.forget(L.target);
            });
        }
        for (boolean stack : new boolean[] {false, true}) {
            float[] hp = {0F};
            STEPS.add(() -> {
                keys("balance.healStack", Boolean.toString(stack));
                begin(L.target);
                L.target.setHealth(1F);
                hp[0] = L.target.getHealth();
                BalanceKit.healOverTime(L.target, 25F);
                BalanceKit.healOverTime(L.target, 25F);
                L.healing = true;
            });
            wait(60);
            STEPS.add(() -> {
                float got = L.target.getHealth() - hp[0];
                // 60 of 240 ticks: a quarter of 25 or of 50
                float want = (stack ? 50F : 25F) * 60F / BalanceKit.STIMPAK_TICKS;
                line("#\tcheese: два супер-стимпака, healStack " + stack + ": за 3 с +" + f(got));
                check(Math.abs(got - want) <= 1F, "cheese: два супер-стимпака сразу, balance.healStack = " + stack + ": за 3 с +" + f(got) + " ≈ " + f(want));
            });
            // the heal runs out before the next case
            wait(BalanceKit.STIMPAK_TICKS);
            STEPS.add(() -> {
                L.healing = false;
                L.target.setHealth(L.target.getMaxHealth());
            });
        }
        STEPS.add(() -> {
            keys("balance.immune.dns.pool", "40", "balance.immune.shared", "true", "balance.healStack", "false");
            L.shooter.setInvulnerable(false);
        });
        wait(10);
    }

    // ---------------------------------------------------------------- GL-54: the fragment grenades by set (cheese §2.4)

    private static final String[] FRAG_SETS = {"", "steel", "t51", "hev", "bj", "rpa", "fau", "dns"};
    private static final acup.gunfeel.gl54.frag.FragSpec[] FRAG_SPECS = {acup.gunfeel.gl54.frag.FragSpec.AIRBURST, acup.gunfeel.gl54.frag.FragSpec.AP,
            acup.gunfeel.gl54.frag.FragSpec.INCENDIARY};
    private static final int FRAG_N = 20, FRAG_LIMIT = 20 * 60;

    // the burst `d` blocks before the target's chest on the line from the shooter, flying at it
    private static void fragAt(acup.gunfeel.gl54.frag.FragSpec spec, double d, long seed) {
        Vec3 chest = new Vec3(L.target.getX(), L.target.getY() + 1.2, L.target.getZ());
        L.target.invulnerableTime = 0;
        acup.gunfeel.gl54.frag.FragBurst.burst(spec, level, L.shooter, null, chest.add(0, 0, -d), new Vec3(0, 0, 1), seed);
    }

    // the incendiary's burning patches and the target's fire out, so the next case starts clean
    private static void fragFireOff() {
        for (var e : level.getEntitiesOfClass(com.hbm.entity.effect.EntityFireLingering.class, new net.minecraft.world.phys.AABB(L.o).inflate(16))) e.discard();
        L.target.clearFire();
    }

    /** the GL-54's airburst, armour-piercing and incendiary grenades on each set: the damage of one burst from a fresh
     * target (2, 4 and 6 blocks, FRAG_N seeds) and the time to kill at 3 blocks, a burst every Gl54Gun.DELAY ticks and
     * the empty reload after 8. Numbers only (the formula's lot; no rule of the owner's for it yet). */
    private static void planFrag() {
        line("#\t--- frag: GL-54 по наборам: урон выстрела на 2/4/6 бл (среднее " + FRAG_N + "), время убийства на 3 бл");
        STEPS.add(() -> {
            keys("balanceArmor", "true", "balanceGuns", "true", "damageCap", "true", "damageCapMobs", "false", "balance.immune.dns.mode", "pool",
                    "balance.immune.fau.mode", "pool");
            L.shooter.setInvulnerable(true);
        });
        wait(10);
        int empty = acup.gunfeel.gl54.Gl54Gun.RELOAD_BEGIN + acup.gunfeel.gl54.Gl54Gun.RELOAD_END + acup.gunfeel.gl54.Gl54Gun.RELOAD_EMPTY_POST;
        for (String set : FRAG_SETS)
            for (var spec : FRAG_SPECS) {
                String name = (set.isEmpty() ? "голый" : set) + " · " + spec.kind().key;
                StringBuilder per = new StringBuilder();
                STEPS.add(() -> {
                    homePool();
                    for (EquipmentSlot slot : SLOTS) L.target.setItemSlot(slot, ItemStack.EMPTY);
                    if (!set.isEmpty()) dress(L.target, set, false);
                    for (double d : new double[] {2, 4, 6}) {
                        double sum = 0;
                        for (int i = 0; i < FRAG_N; i++) {
                            begin(L.target);
                            EuphImmunity.forget(L.target);
                            float hp = L.target.getHealth();
                            fragAt(spec, d, 0x5EED_0000L + i * 7919L);
                            sum += hp - L.target.getHealth();
                            L.target.clearFire();
                        }
                        per.append(' ').append(f((float) (sum / FRAG_N)));
                    }
                    fragFireOff();
                    begin(L.target);
                    EuphImmunity.forget(L.target);
                });
                int[] t = {0}, shots = {0}, next = {0};
                STEPS.add(() -> {
                    if (t[0] >= next[0] && L.target.isAlive() && L.target.getHealth() > 0F) {
                        fragAt(spec, 3, 0xF4A6_0000L + shots[0] * 104729L);
                        shots[0]++;
                        next[0] = t[0] + (shots[0] % 8 == 0 ? empty : acup.gunfeel.gl54.Gl54Gun.DELAY);
                    }
                    boolean dead = !L.target.isAlive() || L.target.getHealth() <= 0F;
                    if (!dead && t[0] < FRAG_LIMIT) {
                        t[0]++;
                        L.hold = true;
                        return;
                    }
                    String kill = dead ? f(t[0] / 20F) + " с, выстрелов " + shots[0] : "не убил за " + FRAG_LIMIT / 20 + " с (" + shots[0] + " выстрелов, осталось "
                            + f(L.target.getHealth()) + ")";
                    line("#\tfrag\t" + name + "\tурон 2/4/6 бл" + per + "\tна 3 бл: " + kill);
                    check(true, "frag: " + name);
                    L.target.clearFire();
                    fragFireOff();
                });
                wait(5);
            }
        STEPS.add(() -> {
            for (EquipmentSlot slot : SLOTS) L.target.setItemSlot(slot, ItemStack.EMPTY);
            L.shooter.setInvulnerable(false);
            begin(L.target);
        });
        wait(10);
    }

    // ---------------------------------------------------------------- Fable's stage 3: blasts on the ladder (pass bx)

    /** a blast of the pass bx: its pair ("gun.ammo" of a launcher, "grenade.<filling>"), the blast of planExpl it is shot as */
    private record Bx(String pair, Blast3 b) {
    }

    private static Blast3 launcher3(String name, Supplier<Item> gun, Supplier<BulletConfig> ammo) {
        return new Blast3(new BalanceBlasts.Boom(name, true, 0, 0, 0, 0), null, gun, ammo);
    }

    private static List<Bx> bxBlasts() {
        return List.of(new Bx("grenade.powder", BLASTS.get(0)), new Bx("grenade.he", BLASTS.get(1)), new Bx("grenade.demo", BLASTS.get(2)),
                new Bx("congolake.g40_he", BLASTS.get(3)), new Bx("mk108.g40_he", BLASTS.get(4)),
                new Bx("mk108.g40_demo", launcher3("mk108 demo", ModItems.GUN_MK108::get, () -> com.hbm.items.weapon.sedna.factory.XFactory40mm.g40_demo)),
                new Bx("panzerschreck.rocket_he", BLASTS.get(5)),
                new Bx("panzerschreck.rocket_demo", launcher3("panzerschreck demo", ModItems.GUN_PANZERSCHRECK::get, () -> XFactoryRocket.rocket_rpzb[2])),
                new Bx("stinger.rocket_he", BLASTS.get(6)), new Bx("missile_launcher.rocket_he", BLASTS.get(7)),
                new Bx("missile_launcher.rocket_demo", launcher3("ml demo", ModItems.GUN_MISSILE_LAUNCHER::get, () -> XFactoryRocket.rocket_ml[2])),
                new Bx("gl54.he", launcher3("gl54 he", () -> acup.gunfeel.gl54.Gl54Items.GUN.get(), acup.gunfeel.gl54.Gl54Ammo::he)));
    }

    private static final String[] BX_SETS = {"", "dieselsuit", "t51", "security", "hev", "bj", "cmb", "rpa", "fau", "dns"};
    private static final String[] BX_FRAG_SETS = {"", "dieselsuit", "t51", "hev", "bj", "cmb", "fau", "dns"};
    private static final int BX_N = 2, BX_FRAG_N = 10;

    /** a blast of the pass bx: a hand grenade, or a launcher's round with its pair (BalanceFinal.fired), the GL-54's from 4 blocks up so it arms */
    private static void bxBoom(Bx x, float r, LivingEntity thrower) {
        Vec3 t = spot3();
        if (!x.b().boom().launched()) {
            var grenade = new com.hbm.entity.grenade.EntityGrenadeUniversal(level, com.hbm.items.weapon.grenade.ItemGrenadeUniversal.make(
                    com.hbm.items.weapon.grenade.ItemGrenadeShell.EnumGrenadeShell.STICK, x.b().filling(),
                    com.hbm.items.weapon.grenade.ItemGrenadeFuze.EnumGrenadeFuze.IMPACT));
            grenade.setThrower(thrower);
            grenade.setPos(t.x + r, t.y + 0.15, t.z);
            grenade.explode();
            return;
        }
        BulletConfig c = x.b().ammo().get();
        boolean gl54 = x.pair().startsWith("gl54.");
        float dmg = gl54 ? acup.gunfeel.gl54.Gl54Grenade.BASE_DAMAGE : roundDamage(x.b());
        com.hbm.entity.projectile.EntityBulletBaseMK4 round;
        if (r < 0F) {
            double dist = 15, drop = c.velocity > 0 ? c.gravity * Math.pow(dist / c.velocity, 2) / 2 : 0;
            Vec3 from = new Vec3(t.x, t.y + 1.2, t.z - dist), d = new Vec3(t.x, t.y + 1.0 + drop, t.z).subtract(from).normalize();
            round = new com.hbm.entity.projectile.EntityBulletBaseMK4(level, L.shooter, c, dmg, -c.spread, from.x, from.y, from.z, d.x, d.y, d.z);
        } else if (gl54) {
            // the GL-54's grenade arms after 3 blocks of flight: from 15 blocks back onto the ground beside the target
            Vec3 from = new Vec3(t.x + r, t.y + 1.2, t.z - 15), d = new Vec3(t.x + r, t.y - 0.05, t.z).subtract(from).normalize();
            round = new com.hbm.entity.projectile.EntityBulletBaseMK4(level, L.shooter, c, dmg, -c.spread, from.x, from.y, from.z, d.x, d.y, d.z);
        } else round = new com.hbm.entity.projectile.EntityBulletBaseMK4(level, L.shooter, c, dmg, -c.spread, t.x + r, t.y + 1.2, t.z, 0, -1, 0);
        BalanceFinal.fired(round, L.shooter, x.b().gun().get(), c);
        level.addFreshEntity(round);
    }

    /**
     * Fable 2026-11-12, 11.2 and 12.3 (stage 3): a launcher's round's or a hand grenade's blast on each set takes the
     * ladder's share (direct, 1 and 3 blocks off); the GL-54's fragment grenades a burst's share at 1.5 and 3 blocks.
     * Lines "bx" and "bxf" for notes/balance/fable/stand/bx.py (the model's numbers); checks only of the rules: no
     * parts' multiplier, one's own blast half, balance.final.blast=false back to formula C (planExpl after it).
     */
    private static void planBlastLadder() {
        line("#	--- bx: взрывы по лестнице (Fable 12.3), урон за взрыв; осколки GL-54 за подрыв");
        STEPS.add(() -> {
            keys("balanceArmor", "true", "balanceGuns", "true", "damageCap", "true", "damageCapMobs", "false", "blastParts", "true", "blastPartsEven", "true",
                    "balance.immune.dns.mode", "pool", "balance.immune.fau.mode", "pool", "balance.immune.mode", "pool", "balance.immune.pool", "0.0001",
                    "balance.immune.window", "600", "balance.immune.recover", "1", "balance.immune.dns.pool", "0.0001", "balance.immune.dns.window", "600",
                    "balance.immune.dns.recover", "1", "balance.immune.fau.pool", "0.0001", "balance.immune.fau.window", "600", "balance.immune.fau.recover", "1",
                    "balance.final.blast", "true", "gl54", "true");
            eachLane(() -> {
                L.shooter.setInvulnerable(true);
                L.shooter.snapTo(L.o.getX() + 8.5, L.o.getY(), L.o.getZ() + 0.5, 0F, 0F);
                for (int x = -6; x <= 6; x++) for (int z = 0; z <= 12; z++) level.setBlockAndUpdate(L.o.offset(x, -1, z), Blocks.BEDROCK.defaultBlockState());
            });
        });
        wait(30);
        List<Runnable> cases = new ArrayList<>();
        for (Bx x : bxBlasts()) {
            float[] where = x.b().boom().launched() ? new float[] {-1F, 1F, 3F} : new float[] {1F, 3F};
            for (float r : where) for (String set : BX_SETS) cases.add(() -> bxCell(x, r, set, null, "bx"));
        }
        parallel(cases);
        // the rules: no parts' multiplier (blastParts off: the same), one's own grenade half
        List<Runnable> rules = new ArrayList<>();
        Bx he = bxBlasts().get(1);
        rules.add(() -> bxCell(he, 1F, "hev", null, "bx-parts-on"));
        rules.add(() -> bxCell(he, 1F, "", null, "bx-self-other"));
        rules.add(() -> bxCell(he, 1F, "", "self", "bx-self"));
        parallel(rules);
        STEPS.add(() -> keys("blastParts", "false"));
        wait(5);
        parallel(List.of(() -> bxCell(he, 1F, "hev", null, "bx-parts-off")));
        STEPS.add(() -> keys("blastParts", "true"));
        wait(5);
        // the GL-54's fragments: a burst 1.5 and 3 blocks before the chest, flying at it
        List<Runnable> frags = new ArrayList<>();
        for (var spec : FRAG_SPECS) for (double d : new double[] {1.5, 3}) for (String set : BX_FRAG_SETS) frags.add(() -> bxFrag(spec, d, set));
        parallel(frags);
        STEPS.add(() -> {
            keys("balance.immune.pool", "150", "balance.immune.window", "15", "balance.immune.recover", "60", "balance.immune.dns.mode", "formula",
                    "balance.immune.fau.mode", "formula", "balance.immune.dns.pool", "40", "balance.immune.dns.window", "15", "balance.immune.dns.recover", "60",
                    "balance.immune.fau.pool", "45", "balance.immune.fau.window", "15", "balance.immune.fau.recover", "60", "balance.final.blast", "false");
            eachLane(() -> {
                L.shooter.setInvulnerable(false);
                for (EquipmentSlot slot : SLOTS) L.target.setItemSlot(slot, ItemStack.EMPTY);
                wearAttributes(L.target);
            });
        });
        wait(30);
    }

    /** a cell of bx: BX_N blasts on the target in {@code set} from full health, what each took ({@code who} "self": the target throws it) */
    private static void bxCell(Bx x, float r, String set, String who, String tag) {
        List<Float> lost = new ArrayList<>();
        int wait = !x.b().boom().launched() ? 3 : r < 0F ? 25 : 12;
        bxPrime(set);
        for (int i = 0; i < BX_N; i++) {
            STEPS.add(() -> {
                ready3(set);
                bxBoom(x, r, "self".equals(who) ? L.target : L.gunner);
            });
            wait(wait);
            STEPS.add(() -> {
                lost.add(L.blastLost);
                L.target.setHealth(L.target.getMaxHealth());
            });
        }
        STEPS.add(() -> {
            float sum = 0F;
            for (float l : lost) sum += l;
            line(tag + "\t" + x.pair() + "\t" + (set.isEmpty() ? "-" : set) + "\t" + where3(r) + "\t" + String.format(Locale.ROOT, "%.4f", sum / Math.max(1, lost.size()))
                    + "\t" + lost.size() + "\t" + lost);
        });
    }

    /**
     * the target in {@code set} with its pool's window open: a full pool calls its emptying hit off (the window from the
     * next tick), so FAU and DNS take a hand grenade first; the pool's share is the scheme's all the same (balance.final.pools)
     */
    private static void bxPrime(String set) {
        STEPS.add(() -> {
            ready3(set);
            EuphImmunity.forget(L.target);
            if (set.equals("fau") || set.equals("dns")) bxBoom(bxBlasts().get(1), 1F, L.gunner);
        });
        wait(3);
    }

    /** a cell of bx: BX_FRAG_N bursts of {@code spec} {@code d} blocks before the chest of the target in {@code set}, each from full health */
    private static void bxFrag(acup.gunfeel.gl54.frag.FragSpec spec, double d, String set) {
        bxPrime(set);
        STEPS.add(() -> {
            ready3(set);
            var type = acup.gunfeel.gl54.Gl54Ammo.type(spec.kind().key);
            double sum = 0;
            int[] rays = {0};
            int seen0 = 0;
            float out0 = 0F;
            var was = acup.gunfeel.gl54.frag.FragBurst.watch;
            acup.gunfeel.gl54.frag.FragBurst.watch = o -> {
                var near = (LivingEntity[]) o[3];
                var hits = (int[]) o[4];
                for (int k = 0; k < near.length; k++) if (near[k] == L.target) rays[0] += hits[k];
            };
            for (int i = 0; i < BX_FRAG_N; i++) {
                begin(L.target);
                L.target.invulnerableTime = 0;
                float hp = L.target.getHealth();
                Vec3 chest = new Vec3(L.target.getX(), L.target.getY() + 1.2, L.target.getZ());
                var proj = new com.hbm.entity.projectile.EntityBulletBaseMK4(level, L.shooter, type, 45F, 0F, chest.x, chest.y, chest.z - d - 1, 0, 0, 1);
                BalanceFinal.fired(proj, L.shooter, acup.gunfeel.gl54.Gl54Items.GUN.get(), type);
                acup.gunfeel.gl54.frag.FragBurst.burst(spec, level, L.shooter, proj, chest.add(0, 0, -d), new Vec3(0, 0, 1), 0xB0A5_0000L + i * 7919L);
                sum += hp - L.target.getHealth();
                seen0 += L.seen.hits;
                out0 += L.seen.out;
                L.target.clearFire();
            }
            acup.gunfeel.gl54.frag.FragBurst.watch = was;
            fragFireOff();
            line("bxf\t" + spec.kind().key + "\t" + (set.isEmpty() ? "-" : set) + "\t" + f((float) d) + "\t" + String.format(Locale.ROOT, "%.4f", sum / BX_FRAG_N) + "\t" + BX_FRAG_N
                    + "\t" + f(rays[0] / (float) BX_FRAG_N) + "\t" + f(seen0 / (float) BX_FRAG_N) + "\t" + String.format(Locale.ROOT, "%.4f", out0 / BX_FRAG_N));
            L.target.setHealth(L.target.getMaxHealth());
        });
        wait(3);
    }

    // ---------------------------------------------------------------- Fable's stage 4: damage by range (pass rg)

    /** the pairs of Fable's table 10.4 and of review №2's stage 2 (gun, ammo, set) and the distances, blocks */
    private static final String[][] RG_PAIRS = {{"autoshotgun", "g12", "hev"}, {"spas12", "g12_slug", "hev"}, {"uzi", "p9_fmj", "hev"}, {"star_f", "p22_fmj", "hev"},
            {"heavy_revolver", "m44_ap", "hev"}, {"g3", "r556_fmj", "hev"}, {"carbine", "r762_fmj", "hev"}, {"m2", "bmg50_fmj", "hev"}, {"amat", "bmg50_ap", "hev"},
            {"amat", "bmg50_fmj", "hev"}, {"laser_pistol", "capacitor", "bj"}, {"stg77", "r556_fmj", "bj"}, {"mas36", "r762_du", "bj"},
            // Fable №2, stage 2: the shotgun's line, the slug's, the beam's, an explosive shell's direct hit by its role's
            {"autoshotgun", "g12_flechette", "hev"}, {"autoshotgun", "g12_magnum", "hev"}, {"autoshotgun", "g12_slug", "hev"},
            {"autoshotgun", "g12_explosive", "hev"}, {"autoshotgun_shredder", "g12", "hev"}, {"lasrifle", "capacitor", "bj"},
            {"tau", "tau_uranium", "bj"}, {"tesla_cannon", "capacitor", "bj"}};
    private static final int[] RG_DIST = {4, 7, 8, 12, 16, 20, 24, 30, 32, 48, 64, 96, 128, 200};
    private static final int RG_CLICKS = 12, RG_FAR = 216;

    /**
     * Fable 2026-11-12, 10.4 (stage 4): the pairs of table 10.4 aimed standing from 4 to 200 blocks, RG_CLICKS clicks
     * from full health each: what a click took and its hits (lines "rg" for notes/balance/fable/stand/rg.py: the
     * model's multiplier and spread). The rule on (the default); the chunks along the lanes kept loaded.
     */
    private static void planRange() {
        // -PbalanceRangePairs=<gun:ammo:set,...>, -PbalanceRangeDist=<blocks,...>, -PbalanceRangeClicks=<n>: these in place
        // of RG_PAIRS, RG_DIST, RG_CLICKS; -PbalanceRangeHip=true: from the hip (Fable №3)
        String[][] pairs = rangePairs();
        int[] dists = shareDists(PROPERTY + ".rangeDist", -1);
        if (dists.length == 1 && dists[0] < 0) dists = RG_DIST;
        int clicks = Integer.getInteger(PROPERTY + ".rangeClicks", RG_CLICKS);
        boolean hip = Boolean.getBoolean(PROPERTY + ".rangeHip");
        line("#	--- rg: дальность (Fable 10.4), по " + clicks + " кликов с полного здоровья, стрелок " + (hip ? "от бедра" : "прицельно") + " стоя");
        STEPS.add(() -> {
            keys("balanceArmor", "true", "balanceGuns", "true", "damageCap", "true", "damageCapMobs", "false", "balance.final.range", BalanceRangeRule.LINES);
            airClicks = 0;
            eachLane(() -> {
                L.shooter.setInvulnerable(true);
                for (int cx = (L.o.getX() - 8) >> 4; cx <= (L.o.getX() + 8) >> 4; cx++)
                    for (int cz = (L.o.getZ() - 8) >> 4; cz <= (L.o.getZ() + RG_FAR) >> 4; cz++) level.setChunkForced(cx, cz, true);
            });
        });
        wait(60);
        List<Runnable> run = new ArrayList<>();
        for (String[] p : pairs) {
            Item gun = BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("hbm", "gun_" + p[0]));
            BulletConfig ammo = ammoNamed(gun, p[1]);
            if (ammo == null) {
                line("rg\t" + p[0] + "\t" + p[1] + "\tнет патрона");
                continue;
            }
            for (int d : dists) run.add(() -> rangeCell(p, gun, ammo, d, clicks, hip));
        }
        parallel(run);
        STEPS.add(() -> check(airClicks == 0, "rg: стрелок на земле на каждом клике (в воздухе " + airClicks + ")"));
        STEPS.add(() -> eachLane(() -> {
            L.shooter.setInvulnerable(false);
            for (EquipmentSlot slot : SLOTS) L.target.setItemSlot(slot, ItemStack.EMPTY);
            wearAttributes(L.target);
            L.target.snapTo(L.o.getX() + 0.5, L.o.getY(), L.o.getZ() + 0.5 + DIST, 180F, 0F);
            for (int cx = (L.o.getX() - 8) >> 4; cx <= (L.o.getX() + 8) >> 4; cx++)
                for (int cz = ((L.o.getZ() + 24) >> 4) + 1; cz <= (L.o.getZ() + RG_FAR) >> 4; cz++) level.setChunkForced(cx, cz, false);
        }));
        wait(10);
    }

    /** the pairs of -PbalanceRangePairs (gun:ammo:set by commas), or RG_PAIRS */
    private static String[][] rangePairs() {
        String v = System.getProperty(PROPERTY + ".rangePairs", "").trim();
        if (v.isEmpty()) return RG_PAIRS;
        return java.util.Arrays.stream(v.split("\\s*,\\s*")).map(e -> e.split(":")).filter(e -> e.length == 3).toArray(String[][]::new);
    }

    private static void rangeCell(String[] p, Item gun, BulletConfig ammo, int d, int clicks, boolean hip) {
        float[] took = {0F}, headLost = {0F};
        int[] hits = {0}, headHits = {0};
        int wait = d >= 64 ? 45 : SPACING + 10;
        STEPS.add(() -> {
            begin(L.target);
            for (EquipmentSlot slot : SLOTS) L.target.setItemSlot(slot, ItemStack.EMPTY);
            dress(L.target, p[2], true);
            wearAttributes(L.target);
            EuphImmunity.forget(L.target);
            L.shooter.setDeltaMovement(Vec3.ZERO);
            L.shooter.snapTo(L.o.getX() + 0.5, L.o.getY(), L.o.getZ() + 0.5, 0F, 0F);
            for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) level.setBlockAndUpdate(L.o.offset(x, -1, d + z), Blocks.STONE.defaultBlockState());
            L.target.setDeltaMovement(Vec3.ZERO);
            L.target.snapTo(L.o.getX() + 0.5, L.o.getY(), L.o.getZ() + 0.5 + d, 180F, 0F);
            L.aimHead = false;
        });
        wait(3);
        for (int c = 0; c < clicks; c++) {
            float[] hp = {0F};
            int[] seen = {0};
            STEPS.add(() -> {
                L.target.setHealth(L.target.getMaxHealth());
                L.target.setAbsorptionAmount(0F);
                L.target.setDeltaMovement(Vec3.ZERO);
                L.target.snapTo(L.o.getX() + 0.5, L.o.getY(), L.o.getZ() + 0.5 + d, 180F, 0F);
                DamageCap.forget(L.target);
                hp[0] = L.target.getHealth();
                seen[0] = L.seen.hits;
                L.headHits = 0;
                L.headLost = 0F;
                L.shooter.setOnGround(true);
                if (Spread.inAir(L.shooter)) airClicks++;
                if (hip) fireBoth(L.shooter, gun, ammo, false);
                else fireAimed(L.shooter, gun, ammo);
            });
            wait(wait);
            STEPS.add(() -> {
                took[0] += hp[0] - Math.max(0F, L.target.getHealth());
                hits[0] += L.seen.hits - seen[0];
                headHits[0] += L.headHits;
                headLost[0] += L.headLost;
            });
        }
        STEPS.add(() -> line("rg\t" + p[0] + "\t" + p[1] + "\t" + p[2] + "\t" + d + "\t" + String.format(Locale.ROOT, "%.5f", took[0] / clicks) + "\t"
                + f(hits[0] / (float) clicks) + "\t" + String.format(Locale.ROOT, "%.4f", BalanceFinal.hitClicks(p[0] + "." + p[1], p[2]))
                // the head's hits a click and what they took a click (the body's: the rest)
                + "\t" + f(headHits[0] / (float) clicks) + "\t" + String.format(Locale.ROOT, "%.5f", headLost[0] / clicks)));
    }

    // ---------------------------------------------------------------- Fable №3: NTM's aim (pass aim), fights by distance (pass rf)

    /** the guns and an ammo of theirs: does NTM aim them (Fable №3, 0.1) */
    private static final String[][] AIM_GUNS = {{"maresleg", "g12"}, {"maresleg_akimbo", "g12"}, {"double_barrel", "g10"}, {"double_barrel_sacred_dragon", "g10"},
            {"spas12", "g12"}, {"autoshotgun", "g12"}, {"light_revolver_dani", "m357_fmj"}, {"uzi_akimbo", "p9_fmj"}, {"star_f_akimbo", "p22_fmj"},
            {"minigun_dual", "r762_fmj"}, {"aberrator_eott", "secret_p35_800"}, {"minigun", "r762_fmj"}};

    /**
     * The pass aim: the aim key (GUN_TERTIARY) pressed, as a player aims; NTM's doStandardFire hands Lego.calcSpread the
     * stack's isAiming as its aim. A line: the gun, isAiming with the key held, NTM's spread then (as calcSpread works it
     * out, before the mod's), aimed and from the hip.
     */
    private static void planAim() {
        // -PbalanceAimAll=true: every gun of the scheme's pairs with its first ammo
        String[][] guns = AIM_GUNS;
        if (Boolean.getBoolean(PROPERTY + ".aimAll")) {
            java.util.Map<String, String> first = new java.util.TreeMap<>();
            for (var pr : BalanceFinal.pairs().values()) first.putIfAbsent(pr.gun(), pr.ammo());
            guns = first.entrySet().stream().map(e -> new String[] {e.getKey(), e.getValue()}).toArray(String[][]::new);
        }
        line("#\t--- aim: прицел NTM (Fable №3, 0.1): клавиша прицела зажата; aim в Lego.calcSpread = isAiming ствола");
        line("#\taim\tgun\tammo\tisAiming\tразброс при нём\tприцельно\tот бедра\tконфигов\tклавиша прицела (TOGGLE, OWN, NONE: стенд стреляет от бедра)");
        for (String[] g : guns) {
            STEPS.add(() -> {
                Item item = BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("hbm", "gun_" + g[0]));
                BulletConfig ammo = ammoNamed(item, g[1]);
                if (ammo == null) {
                    line("aim\t" + g[0] + "\t" + g[1] + "\tнет патрона");
                    return;
                }
                ItemStack stack = loaded(item, ammo);
                L.shooter.setItemSlot(EquipmentSlot.MAINHAND, stack);
                var gun = (ItemGunBaseNT) stack.getItem();
                SimpleContainer inv = new SimpleContainer(1);
                BalanceAim.press(gun, L.shooter, inv, stack, true);
                boolean aimed = ItemGunBaseNT.getIsAiming(stack);
                var config = gun.getConfig(stack, 0);
                var r = config.getReceivers(stack)[0];
                // NTM's spread as Lego.calcSpread works it out (no wear); not calcSpread itself, which is the system SPREAD's
                float[] sp = BalanceAim.spread(r, stack, ammo);
                float base = sp[0], hip = sp[1];
                line("aim\t" + g[0] + "\t" + g[1] + "\t" + aimed + "\t" + String.format(Locale.ROOT, "%.4f", aimed ? base : hip)
                        + "\t" + String.format(Locale.ROOT, "%.4f", base) + "\t" + String.format(Locale.ROOT, "%.4f", hip)
                        + "\t" + gun.getConfigCount() + "\t" + BalanceAim.key(stack));
                BalanceAim.press(gun, L.shooter, inv, stack, false);
                L.shooter.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            });
            wait(5);
        }
    }

    /** the pairs of Fable №3's table of stage 4 (gun, ammo, set) and its distances */
    private static final String[][] RF_PAIRS = {{"spas12", "g12", "hev"}, {"spas12", "g12_flechette", "hev"}, {"spas12", "g12_magnum", "hev"}, {"spas12", "g12_slug", "hev"},
            {"autoshotgun", "g12", "hev"}, {"autoshotgun", "g12_slug", "hev"}, {"autoshotgun", "g12_explosive", "hev"}, {"maresleg", "g12_bp", "dieselsuit"},
            {"double_barrel", "g10_slug", "hev"}, {"autoshotgun_shredder", "g12", "bj"}, {"autoshotgun_shredder", "g12_slug", "bj"},
            {"autoshotgun_shredder", "g12_explosive", "bj"}, {"tesla_cannon", "capacitor", "cmb"}, {"carbine", "r762_du", "hev"}};
    private static final int[] RF_DIST = {4, 8, 16, 24, 32, 48, 64, 96, 128};
    /** a shotgun's shot: not past this (the table's "—") */
    private static final int RF_SHOT_FAR = 64;

    /**
     * The pass rf (Fable №3, stage 4): fights by distance. The shooter aimed standing, the target in its set from full
     * health {@code d} blocks off; the clicks on the model's schedule (a click each interval, after a magazine its
     * reload) until the target dies or the limit (-PbalanceFightLimit ticks, 1200). A line a fight: the time from the
     * first click to the one that killed (its projectile's age at the hit tells which), seconds. -PbalanceFights=<n> fights
     * a cell (9), -PbalanceRangePairs / -PbalanceRangeDist as the pass rg.
     */
    private static void planFights() {
        String[][] pairs = System.getProperty(PROPERTY + ".rangePairs", "").isBlank() ? RF_PAIRS : rangePairs();
        int[] dists = shareDists(PROPERTY + ".rangeDist", -1);
        if (dists.length == 1 && dists[0] < 0) dists = RF_DIST;
        int fights = Integer.getInteger(PROPERTY + ".fights", 9), limit = Integer.getInteger(PROPERTY + ".fightLimit", 1200);
        line("#\t--- rf: бои по дистанции (Fable №3, этап 4), по " + fights + " боёв, стрелок прицельно стоя, предел " + limit + " тактов");
        line("#\trf\tgun\tammo\tset\tdist\tfight\ttime s (> limit)\tclicks fired\tkilling click\thits");
        STEPS.add(() -> {
            keys("balanceArmor", "true", "balanceGuns", "true", "damageCap", "true", "damageCapMobs", "false", "balance.final.range", BalanceRangeRule.LINES);
            airClicks = 0;
            eachLane(() -> {
                L.shooter.setInvulnerable(true);
                for (int cx = (L.o.getX() - 8) >> 4; cx <= (L.o.getX() + 8) >> 4; cx++)
                    for (int cz = (L.o.getZ() - 8) >> 4; cz <= (L.o.getZ() + RG_FAR) >> 4; cz++) level.setChunkForced(cx, cz, true);
            });
        });
        wait(60);
        List<Runnable> run = new ArrayList<>();
        for (String[] p : pairs) {
            Item gun = BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("hbm", "gun_" + p[0]));
            BulletConfig ammo = ammoNamed(gun, p[1]);
            var pr = BalanceFinal.pairs().get(p[0] + "." + p[1]);
            if (ammo == null || pr == null) {
                line("rf\t" + p[0] + "\t" + p[1] + "\tнет патрона");
                continue;
            }
            // a shot of a gun of a line of its own (the Shredder's beam) goes as far as the line
            boolean shot = pr.pellets() >= 4 && BalanceFinal.lineOf(pr.gun()) == null;
            for (int d : dists) {
                if (shot && d > RF_SHOT_FAR) continue;
                for (int f = 0; f < fights; f++) {
                    int n = f;
                    run.add(() -> fightCell(p, gun, ammo, pr, d, n, limit));
                }
            }
        }
        parallel(run);
        STEPS.add(() -> check(airClicks == 0, "rf: стрелок на земле на каждом клике (в воздухе " + airClicks + ")"));
        STEPS.add(() -> eachLane(() -> {
            L.fightOn = false;
            L.shooter.setInvulnerable(false);
            for (EquipmentSlot slot : SLOTS) L.target.setItemSlot(slot, ItemStack.EMPTY);
            wearAttributes(L.target);
            L.target.snapTo(L.o.getX() + 0.5, L.o.getY(), L.o.getZ() + 0.5 + DIST, 180F, 0F);
            for (int cx = (L.o.getX() - 8) >> 4; cx <= (L.o.getX() + 8) >> 4; cx++)
                for (int cz = ((L.o.getZ() + 24) >> 4) + 1; cz <= (L.o.getZ() + RG_FAR) >> 4; cz++) level.setChunkForced(cx, cz, false);
        }));
        wait(10);
    }

    /** the tick of click {@code i} of a fight: an interval each, a reload after each magazine (Fable's r3.ttk) */
    static int fightTick(int i, int iv, double magClicks, int reload) {
        return i * iv + (int) Math.floor(i / magClicks) * reload;
    }

    private static void fightCell(String[] p, Item gun, BulletConfig ammo, BalanceFinalRule.Pair pr, int d, int fight, int limit) {
        int iv = Math.max(1, BalanceFinalRule.intervalTicks(pr));
        double magClicks = pr.mag() > 0 ? Math.max(1.0, pr.mag() / (double) Math.max(1, pr.roundsPerClick())) : Double.POSITIVE_INFINITY;
        java.util.Set<Integer> at = new java.util.HashSet<>();
        for (int i = 0; ; i++) {
            int t = fightTick(i, iv, magClicks, pr.reload());
            if (t >= limit) break;
            at.add(t);
        }
        List<Long> fired = new ArrayList<>();
        int[] end = {0}, hits = {0};
        STEPS.add(() -> {
            begin(L.target);
            for (EquipmentSlot slot : SLOTS) L.target.setItemSlot(slot, ItemStack.EMPTY);
            dress(L.target, p[2], true);
            wearAttributes(L.target);
            EuphImmunity.forget(L.target);
            L.shooter.setDeltaMovement(Vec3.ZERO);
            L.shooter.snapTo(L.o.getX() + 0.5, L.o.getY(), L.o.getZ() + 0.5, 0F, 0F);
            for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) level.setBlockAndUpdate(L.o.offset(x, -1, d + z), Blocks.STONE.defaultBlockState());
            L.target.setDeltaMovement(Vec3.ZERO);
            L.target.snapTo(L.o.getX() + 0.5, L.o.getY(), L.o.getZ() + 0.5 + d, 180F, 0F);
            L.aimHead = false;
        });
        wait(3);
        STEPS.add(() -> {
            begin(L.target);
            hits[0] = L.seen.hits;
            L.fightKill = Long.MIN_VALUE;
            L.fightFlight = 0;
            L.fightOn = true;
        });
        for (int t = 0; t < limit; t++) {
            boolean fire = at.contains(t);
            STEPS.add(() -> {
                if (L.fightKill != Long.MIN_VALUE || L.target.getHealth() <= 0F || L.target.isDeadOrDying()) {
                    L.skipTo = end[0];
                    return;
                }
                L.target.setDeltaMovement(Vec3.ZERO);
                L.target.snapTo(L.o.getX() + 0.5, L.o.getY(), L.o.getZ() + 0.5 + d, 180F, 0F);
                if (!fire) return;
                L.shooter.setOnGround(true);
                if (Spread.inAir(L.shooter)) airClicks++;
                fired.add(level.getGameTime());
                fireAimed(L.shooter, gun, ammo, pr.hands() > 1);
            });
        }
        end[0] = STEPS.size();
        STEPS.add(() -> {
            L.fightOn = false;
            boolean dead = L.fightKill != Long.MIN_VALUE || L.target.getHealth() <= 0F || L.target.isDeadOrDying();
            long kill = L.fightKill != Long.MIN_VALUE ? L.fightKill : level.getGameTime() - 1;
            // the killing click: the last one fired by the killing hit's tick (a tick's give either way)
            int k = -1;
            for (int i = 0; i < fired.size(); i++) if (fired.get(i) <= kill + 1) k = i;
            String time = !dead || k < 0 ? "> " + f(limit / 20F) : f((fired.get(k) - fired.get(0)) / 20F);
            line("rf\t" + p[0] + "\t" + p[1] + "\t" + p[2] + "\t" + d + "\t" + fight + "\t" + time + "\t" + fired.size() + "\t" + (k + 1) + "\t"
                    + (L.seen.hits - hits[0]));
            L.target.setHealth(L.target.getMaxHealth());
        });
        // the shots still flying land before the next fight
        wait(d >= 64 ? 60 : 25);
    }

    // ---------------------------------------------------------------- a bot in armour (owner 2026-11-13, pass bot)

    /** the pass bot: the sets, the X942 table's hits to kill of stg77.r556_fmj on each (body) */
    private static final String[][] BOT_SETS = {{"security", "15"}, {"steel", "13"}, {"hev", "22"}, {"t51", "17"}, {"", "13"}};
    private static final int BOT_DIST = 12;

    /**
     * The owner (X949): an FBI agent in Security took 24 hits of the STG-77 (FMJ) in the head and lived, its table kills
     * it with 9. The pass: an FBI agent and the player, each in a set of BOT_SETS (and bare), BOT_DIST blocks off, the
     * shooter aimed at the head or the chest until it dies (MAX_CLICKS at most), with balance.final.mobArmor own (X949)
     * and player. A line each: the clicks and hits to kill, what a hit took. Checked with player: the agent dies in the
     * player's hits in the same set; with own, the agent in a set takes less than half a hit.
     */
    private static void planBot() {
        line("#\t--- bot: FBI и игрок в броне, stg77 · r556_fmj, " + BOT_DIST + " бл., прицельно; было (mobArmor own) / стало (player)");
        line("#\tbot\tрежим\tкто\tнабор\tкуда\tкликов\tударов\tснято за удар\tздоровье\tванильная броня\tтаблица X942 (тело)");
        Item gun = BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("hbm", "gun_stg77"));
        BulletConfig ammo = ammoNamed(gun, "r556_fmj");
        if (ammo == null) {
            check(false, "bot: нет патрона r556_fmj у stg77");
            return;
        }
        java.util.Map<String, Float> hitOf = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.Map<String, Integer> hitsOf = new java.util.concurrent.ConcurrentHashMap<>();
        for (String mode : new String[] {"own", "player"}) {
            STEPS.add(() -> {
                keys("balance.final", "true", "balance.final.mobArmor", mode);
                eachLane(() -> {
                    L.shooter.setInvulnerable(true);
                    for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) level.setBlockAndUpdate(L.o.offset(x, -1, BOT_DIST + z), Blocks.STONE.defaultBlockState());
                });
            });
            wait(30);
            List<Runnable> cases = new ArrayList<>();
            // the key is a mob's: with own, the agent alone
            for (String[] set : BOT_SETS) for (String who : mode.equals("own") ? new String[] {"fbi"} : new String[] {"fbi", "player"})
                for (boolean head : new boolean[] {true, false}) cases.add(() -> botCase(mode, set, who, head, gun, ammo, hitOf, hitsOf));
            parallel(cases);
        }
        STEPS.add(() -> {
            for (String[] set : BOT_SETS) for (String aim : new String[] {"голова", "тело"}) {
                String name = set[0].isEmpty() ? "без брони" : set[0];
                Integer fbi = hitsOf.get("player fbi " + set[0] + " " + aim), player = hitsOf.get("player player " + set[0] + " " + aim);
                Integer fbiOwn = hitsOf.get("own fbi " + set[0] + " " + aim);
                Float fbiHit = hitOf.get("player fbi " + set[0] + " " + aim), playerHit = hitOf.get("player player " + set[0] + " " + aim);
                Float ownHit = hitOf.get("own fbi " + set[0] + " " + aim);
                if (set[0].isEmpty()) {
                    // bare: NTM's damage on a mob (the scheme takes no bare mob), the same in both
                    check(fbiHit != null && ownHit != null && Math.abs(fbiHit - ownHit) <= 0.02F * Math.max(ownHit, 0.01F),
                            "bot: FBI без брони, " + aim + ": урон NTM, тот же в обоих режимах (" + f(nz(ownHit)) + " / " + f(nz(fbiHit)) + ")");
                    continue;
                }
                check(fbi != null && player != null && fbi > 0 && player > 0 && Math.abs(fbi - player) <= 1 && fbiHit != null && playerHit != null
                        && Math.abs(fbiHit - playerHit) <= 0.05F * playerHit,
                        "bot: FBI в " + name + ", " + aim + ": ударов " + fbi + ", снято за удар (из 20) " + f(nz(fbiHit)) + " = игрок " + player + ", " + f(nz(playerHit)));
                check(ownHit != null && fbiHit != null && ownHit < fbiHit * 0.5F,
                        "bot: было (own) у FBI в " + name + ", " + aim + ": " + f(nz(ownHit)) + " за удар против " + f(nz(fbiHit)) + " (ударов "
                                + (fbiOwn == null || fbiOwn == 0 ? "> " + MAX_CLICKS + " кликов" : fbiOwn) + ")");
            }
            // the table: the body's hits to kill
            for (String[] set : BOT_SETS) {
                if (set[0].isEmpty()) continue;
                Integer body = hitsOf.get("player fbi " + set[0] + " тело");
                int table = Integer.parseInt(set[1]);
                check(body != null && body > 0 && Math.abs(body - table) <= Math.max(1, table / 10), "bot: FBI в " + set[0]
                        + ", тело: ударов до смерти " + body + " против таблицы X942 " + table);
            }
            keys("balance.final.mobArmor", "player", "balance.final", String.valueOf(Boolean.getBoolean(PROPERTY + ".final")));
            eachLane(() -> L.shooter.setInvulnerable(false));
        });
        wait(10);
    }

    private static float nz(Float v) {
        return v == null ? Float.NaN : v;
    }

    private static void botCase(String mode, String[] set, String who, boolean head, Item gun, BulletConfig ammo, java.util.Map<String, Float> hitOf,
            java.util.Map<String, Integer> hitsOf) {
        String aim = head ? "голова" : "тело";
        String key = mode + " " + who + " " + set[0] + " " + aim;
        int[] end = {0};
        STEPS.add(() -> {
            L.shooter.setDeltaMovement(Vec3.ZERO);
            L.shooter.snapTo(L.o.getX() + 0.5, L.o.getY(), L.o.getZ() + 0.5, 0F, 0F);
            LivingEntity v;
            if (who.equals("fbi")) {
                var created = BuiltInRegistries.ENTITY_TYPE.getValue(FBI).create(level, EntitySpawnReason.COMMAND);
                if (!(created instanceof Mob mob)) throw new IllegalStateException("no FBI agent");
                L.fbi = mob;
                mob.setNoAi(true);
                mob.snapTo(L.o.getX() + 0.5, L.o.getY(), L.o.getZ() + 0.5 + BOT_DIST, 180F, 0F);
                level.addFreshEntity(mob);
                for (EquipmentSlot slot : SLOTS) mob.setItemSlot(slot, ItemStack.EMPTY);
                if (!set[0].isEmpty()) dress(mob, set[0], true);
                mob.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                // the player out of the line of fire
                L.target.snapTo(L.o.getX() + 6.5, L.o.getY(), L.o.getZ() + 0.5 + DIST, 180F, 0F);
                v = mob;
            } else {
                v = L.target;
                for (EquipmentSlot slot : SLOTS) v.setItemSlot(slot, ItemStack.EMPTY);
                if (!set[0].isEmpty()) dress(v, set[0], true);
                wearAttributes(v);
                EuphImmunity.forget(L.target);
                v.snapTo(L.o.getX() + 0.5, L.o.getY(), L.o.getZ() + 0.5 + BOT_DIST, 180F, 0F);
            }
            begin(v);
            L.noShield = true;
            L.aimHead = head;
        });
        wait(3);
        for (int i = 0; i < MAX_CLICKS; i++) {
            STEPS.add(() -> {
                if (L.deathClick > 0 || L.victim.getHealth() <= 0F) return;
                // NTM's shield of a full set: the scheme counts none, as the model
                if (L.victim instanceof net.minecraft.world.entity.player.Player p) com.hbm.extprop.HbmPlayerProps.getData(p).shield = 0F;
                L.victim.setDeltaMovement(Vec3.ZERO);
                L.victim.snapTo(L.o.getX() + 0.5, L.o.getY(), L.o.getZ() + 0.5 + BOT_DIST, 180F, 0F);
                fireAimed(L.shooter, gun, ammo);
                L.clicks++;
            });
            for (int t = 0; t < SPACING; t++) STEPS.add(() -> {
                if (L.deathClick <= 0 && L.clicks > 0 && L.victim.getHealth() <= 0F) {
                    L.deathClick = L.clicks;
                    L.skipTo = end[0];
                }
            });
        }
        end[0] = STEPS.size();
        wait(5);
        STEPS.add(() -> {
            float per = L.hits > 0 ? L.lost / L.hits : 0F;
            // a hit's share of the health, on 20 HP (the scheme scales a hit by the health)
            hitOf.put(key, per * 20F / L.victim.getMaxHealth());
            hitsOf.put(key, L.deathClick > 0 ? L.hits : 0);
            float vanilla = (float) L.victim.getAttributeValue(Attributes.ARMOR);
            line("bot\t" + (mode.equals("own") ? "было" : "стало") + "\t" + (who.equals("fbi") ? "FBI" : "игрок") + "\t" + (set[0].isEmpty() ? "без брони" : set[0])
                    + "\t" + aim + "\t" + (L.deathClick > 0 ? String.valueOf(L.deathClick) : "> " + L.clicks) + "\t" + (L.deathClick > 0 ? String.valueOf(L.hits) : "> " + L.hits)
                    + "\t" + f(per) + "\t" + f(L.victim.getMaxHealth()) + "\t" + f(vanilla) + "\t" + set[1]);
            L.aimHead = false;
            L.noShield = false;
            if (who.equals("fbi")) {
                L.fbi.discard();
                L.fbi = null;
                L.target.snapTo(L.o.getX() + 0.5, L.o.getY(), L.o.getZ() + 0.5 + DIST, 180F, 0F);
            } else {
                for (EquipmentSlot slot : SLOTS) L.target.setItemSlot(slot, ItemStack.EMPTY);
                wearAttributes(L.target);
                L.target.snapTo(L.o.getX() + 0.5, L.o.getY(), L.o.getZ() + 0.5 + DIST, 180F, 0F);
            }
        });
    }

    // ---------------------------------------------------------------- the share of hits by one measure (Fable 2026-11-12 №2, pass ps)

    /** the role's working distance the share of hits is measured at (Fable №2, 1): shot and slug 7, pistol, SMG, MG 12, rifle and beam 20, sniper 30 */
    static int homeOf(BalanceFinalRule.Pair pr) {
        if (pr.gun().equals("autoshotgun_shredder")) return 7;
        return switch (pr.role()) {
            case "shotgun", "slug" -> 7;
            case "pistol", "smg", "mg" -> 12;
            case "sniper" -> 30;
            default -> 20;
        };
    }

    /** the clicks of the passes that measure the share of hits (ps, mx at the role's distance) with the shooter in the air */
    private static int airClicks;

    /**
     * The pass ps: every pair of the scheme's file, the shooter standing (aimed, then from the hip), the target bare and
     * standing at the role's working distance; an automatic fires a held burst of at least 3 s (a click every interval:
     * the bloom as held), the rest clicks at the gun's pace; at least 200 projectiles (a shotgun's shot: 25 clicks;
     * -PbalanceShareShots another number). The spread's settings are the server's defaults. A line: the projectiles
     * fired (clicks × pellets × rounds × hands), the hits the target took, their share. The shooter on the ground at
     * every click (Spread.inAir: no), or the pass is red.
     */
    private static void planShare() {
        line("#\t--- ps: доля попаданий единой меркой (Fable №2), стрелок стоит, цель на рабочей дистанции роли, без брони");
        line("#\tps\tgun\tammo\trole\thome\tmode\tclicks\tprojectiles\thits\tshare");
        STEPS.add(() -> {
            keys("balanceArmor", "true", "balanceGuns", "true", "damageCap", "false", "damageCapMobs", "false", "balance.final.range", "off");
            // -PbalanceShareKeys="key=value ...": server keys of this run of the pass (the spread's, to take a part of it apart)
            for (String kv : System.getProperty("ntmgunfeel.checkBalance.shareKeys", "").trim().split("\\s+")) {
                int eq = kv.indexOf('=');
                if (eq > 0) keys(kv.substring(0, eq), kv.substring(eq + 1));
            }
            BalanceFinal.standShots = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
            airClicks = 0;
            eachLane(() -> {
                L.shooter.setInvulnerable(true);
                for (int cx = (L.o.getX() - 8) >> 4; cx <= (L.o.getX() + 8) >> 4; cx++)
                    for (int cz = (L.o.getZ() - 8) >> 4; cz <= (L.o.getZ() + 40) >> 4; cz++) level.setChunkForced(cx, cz, true);
            });
        });
        wait(40);
        String onlyPairs = System.getProperty("ntmgunfeel.checkBalance.share", "").trim();
        java.util.Set<String> want = onlyPairs.isEmpty() ? null : java.util.Set.of(onlyPairs.split("\\s*,\\s*"));
        List<Runnable> run = new ArrayList<>();
        for (var pr : BalanceFinal.pairs().values()) {
            if (want != null && !want.contains(pr.id()) && !want.contains(pr.gun())) continue;
            Item gun = BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("hbm", "gun_" + pr.gun()));
            BulletConfig ammo = ammoNamed(gun, pr.ammo());
            if (ammo == null) {
                line("ps\t" + pr.gun() + "\t" + pr.ammo() + "\tнет патрона");
                continue;
            }
            // -PbalanceShareDist=<blocks,...>: these distances for all in place of the role's; -PbalanceShareCrouch=<blocks,...>:
            // an aimed cell crouched at each of these too
            for (int d : shareDists("ntmgunfeel.checkBalance.shareDist", homeOf(pr))) {
                for (boolean aimed : new boolean[] {true, false}) run.add(() -> shareCell(pr, gun, ammo, aimed, d, false));
            }
            for (int d : shareDists("ntmgunfeel.checkBalance.shareCrouch", -1)) if (d > 0) run.add(() -> shareCell(pr, gun, ammo, true, d, true));
        }
        parallel(run);
        STEPS.add(() -> {
            BalanceFinal.standShots = null;
            check(airClicks == 0, "ps: стрелок на земле на каждом клике (в воздухе " + airClicks + ")");
            eachLane(() -> {
                L.shooter.setInvulnerable(false);
                L.target.snapTo(L.o.getX() + 0.5, L.o.getY(), L.o.getZ() + 0.5 + DIST, 180F, 0F);
            });
        });
        wait(10);
    }

    /** the distances a list property names, or {@code def} alone when it is not set */
    private static int[] shareDists(String key, int def) {
        String v = System.getProperty(key, "").trim();
        if (v.isEmpty()) return new int[] {def};
        return java.util.Arrays.stream(v.split("\\s*,\\s*")).mapToInt(Integer::parseInt).toArray();
    }

    private static void shareCell(BalanceFinalRule.Pair pr, Item gun, BulletConfig ammo, boolean aimed, int d, boolean crouch) {
        int iv = BalanceFinalRule.intervalTicks(pr);
        double proj = pr.projectiles();
        // -PbalanceShareShots=<n>: this many projectiles an aimed cell (from the hip half), in place of 200
        int asked = Integer.getInteger(PROPERTY + ".shareShots", 200), want = aimed ? asked : Math.max(1, asked / 2);
        int clicks = pr.pellets() >= 4 ? (aimed ? 25 : 13) : (int) Math.ceil(want / proj);
        if (pr.auto()) clicks = Math.max(clicks, (int) Math.ceil(60.0 / iv));
        int n = clicks;
        int[] seen = {0};
        STEPS.add(() -> {
            begin(L.target);
            for (EquipmentSlot slot : SLOTS) L.target.setItemSlot(slot, ItemStack.EMPTY);
            wearAttributes(L.target);
            EuphImmunity.forget(L.target);
            L.shooter.setDeltaMovement(Vec3.ZERO);
            L.shooter.snapTo(L.o.getX() + 0.5, L.o.getY(), L.o.getZ() + 0.5, 0F, 0F);
            for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) level.setBlockAndUpdate(L.o.offset(x, -1, d + z), Blocks.STONE.defaultBlockState());
            L.target.setDeltaMovement(Vec3.ZERO);
            L.target.snapTo(L.o.getX() + 0.5, L.o.getY(), L.o.getZ() + 0.5 + d, 180F, 0F);
            L.aimHead = false;
        });
        // the bloom of the cell before gone (it halves every 5 ticks)
        wait(40);
        int[] shots = {0};
        STEPS.add(() -> {
            seen[0] = L.seen.hits;
            var m = BalanceFinal.standShots;
            shots[0] = m == null ? 0 : m.getOrDefault(L.shooter, 0);
        });
        for (int c = 0; c < n; c++) {
            STEPS.add(() -> {
                L.target.setHealth(L.target.getMaxHealth());
                L.target.setAbsorptionAmount(0F);
                L.target.setDeltaMovement(Vec3.ZERO);
                L.target.snapTo(L.o.getX() + 0.5, L.o.getY(), L.o.getZ() + 0.5 + d, 180F, 0F);
                L.shooter.setDeltaMovement(Vec3.ZERO);
                // standing on the floor: a mannequin that never moves is never told it landed (Spread.inAir: spreadAir)
                L.shooter.setOnGround(true);
                if (Spread.inAir(L.shooter)) airClicks++;
                // crouched: NTM's spread through Spread.apply takes isCrouching (spreadSneak)
                if (crouch) L.shooter.setPose(net.minecraft.world.entity.Pose.CROUCHING);
                // akimbo: both guns a click (the pair's hands)
                if (aimed) fireAimed(L.shooter, gun, ammo, pr.hands() > 1);
                else fireBoth(L.shooter, gun, ammo, pr.hands() > 1);
                if (crouch) L.shooter.setPose(net.minecraft.world.entity.Pose.STANDING);
            });
            for (int t = 1; t < iv; t++) STEPS.add(() -> L.target.setHealth(L.target.getMaxHealth()));
        }
        for (int t = 0; t < 30; t++) STEPS.add(() -> L.target.setHealth(L.target.getMaxHealth()));
        STEPS.add(() -> {
            int hits = L.seen.hits - seen[0];
            double fired = n * proj;
            var m = BalanceFinal.standShots;
            int out = (m == null ? 0 : m.getOrDefault(L.shooter, 0)) - shots[0];
            line("ps\t" + pr.gun() + "\t" + pr.ammo() + "\t" + pr.role() + "\t" + d + "\t" + (aimed ? crouch ? "aim-crouch" : "aim" : "hip") + "\t" + n + "\t" + String.format(Locale.ROOT, "%.0f", fired)
                    + "\t" + hits + "\t" + String.format(Locale.ROOT, "%.4f", hits / fired) + "\tshots " + out);
        });
    }

    // ---------------------------------------------------------------- the acid, the gas on the pools, the smoke (pass g4)

    /** the pass g4's sets: the acid's on each at K 0 and 0.75 */
    private static final String[] G4_SETS = {"t51", "hev", "bj", "fau"};
    private static final String G4_GUN = "carbine", G4_AMMO = "r762_sp";

    /**
     * The pass g4 (Fable №2, stage 4), the final scheme on: (1) the acid: a click of the carbine's SP on each set at the
     * corrosion K 0 and 0.75, what a click took (as the pass mx: 4 blocks, no range, the pools all but nothing) by the
     * solver's clicks at the set's tier and at the tier the acid leaves; (2) the gas on FAU's pool: the mustard's step
     * by the tick, what it did against what the pool lost and the health, then clean air; (3) the GL-54's laser and a
     * cloud of smoke: a point behind it, before it, beside it.
     */
    private static void planAcidGas() {
        line("#\t--- g4: кислота, газ по запасу, дым и дальномер (Fable №2, этап 4), balance.final вкл.");
        STEPS.add(() -> {
            keys("balance.final", "true", "balanceArmor", "true", "balanceGuns", "true", "damageCap", "true", "damageCapMobs", "false",
                    "balance.immune.dns.mode", "pool", "balance.immune.fau.mode", "pool", "balance.immune.mode", "pool", "balance.immune.pool", "0.0001",
                    "balance.immune.window", "600", "balance.immune.recover", "1", "balance.immune.dns.pool", "0.0001", "balance.immune.dns.window", "600",
                    "balance.immune.dns.recover", "1", "balance.immune.fau.pool", "0.0001", "balance.immune.fau.window", "600", "balance.immune.fau.recover", "1",
                    "balance.final.range", "off");
            eachLane(() -> L.shooter.setInvulnerable(true));
        });
        wait(30);
        line("#\tg4\tacid\tset\tK\ttier\tsolver clicks at it\t(mx line: HP a click, hits a click)");
        Item gun = BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("hbm", "gun_" + G4_GUN));
        BulletConfig ammo = ammoNamed(gun, G4_AMMO);
        String pair = G4_GUN + "." + G4_AMMO;
        List<Runnable> run = new ArrayList<>();
        for (String set : G4_SETS) for (float k : new float[] {0F, 0.75F}) run.add(() -> {
            STEPS.add(() -> {
                L.target.removeData(acup.gunfeel.gas.GasAttach.STATE.get());
                if (k > 0F) acup.gunfeel.gas.GasServer.state(L.target).k = k;
                double t = BalanceFinal.acidTier(set, k);
                line("g4\tacid\t" + set + "\t" + k + "\t" + String.format(Locale.ROOT, "%.3f", t) + "\t"
                        + String.format(Locale.ROOT, "%.3f", BalanceFinal.hitClicksAt(pair, set, t)));
            });
            matrixCell(new Cell(set, G4_GUN, G4_AMMO, false), gun, ammo, set + "@K" + k + "\t" + G4_GUN + "\t" + G4_AMMO + "\tbody");
            STEPS.add(() -> L.target.removeData(acup.gunfeel.gas.GasAttach.STATE.get()));
        });
        parallel(run);
        STEPS.add(() -> line("g4\tacid-ref\tt51 at tier 3\t" + String.format(Locale.ROOT, "%.3f", BalanceFinal.hitClicks(pair, "t51"))
                + "\tbj at tier 5.33\t" + String.format(Locale.ROOT, "%.3f", BalanceFinal.hitClicksAt(pair, "bj", 6 - 2 / 3.0))));
        planGasPool();
        planSmokeRange();
        STEPS.add(() -> {
            keys("balance.final", String.valueOf(Boolean.getBoolean(PROPERTY + ".final")), "balance.immune.pool", "150", "balance.immune.window", "15",
                    "balance.immune.recover", "60", "balance.immune.dns.mode", "formula", "balance.immune.fau.mode", "formula", "balance.immune.dns.pool", "40",
                    "balance.immune.dns.window", "15", "balance.immune.dns.recover", "60", "balance.immune.fau.pool", "45", "balance.immune.fau.window", "15",
                    "balance.immune.fau.recover", "60", "balance.final.range", BalanceRangeRule.LINES);
            eachLane(() -> {
                L.shooter.setInvulnerable(false);
                L.target.removeData(acup.gunfeel.gas.GasAttach.STATE.get());
                for (EquipmentSlot slot : SLOTS) L.target.setItemSlot(slot, ItemStack.EMPTY);
                wearAttributes(L.target);
            });
        });
        wait(30);
    }

    /** the pool's HP now, the kit's size when it has no state yet, NaN out of the pool's phase */
    private static float g4Pool(EuphImmunity.Kit kit) {
        String d = EuphImmunity.describe(kit, L.target);
        if (d.equals("нет")) return EuphImmunity.pool(kit).size();
        return EuphImmunity.poolOf(kit, L.target);
    }

    /**
     * FAU with its own pool (the game's keys of the pool) in the mustard's core, no hazmat over it: (a) the gas until the
     * pool is half or 120 s, what the gas did (GasState.dealt) against what the pool lost, the health; (b) clean air until
     * the pool is full again or 120 s; (c) the gas again until the health falls or 180 s.
     */
    private static void planGasPool() {
        line("#\tg4\tgas\tphase\tticks\tgas HP\tpool lost\thealth lost\tpool now\tstate");
        EuphImmunity.Kit kit = EuphImmunity.Kit.FAU;
        float[] g = new float[6]; // pool at the phase's start, dealt at it, health at it, ticks, the last gas tick, a flag: the phase is over
        STEPS.add(() -> {
            // the game's keys of FAU's pool (ntmgunfeel-server.properties)
            keys("balance.immune.fau.mode", "pool", "balance.immune.fau.pool", "45", "balance.immune.fau.window", "15",
                    "balance.immune.fau.recover", "60", "balance.final.pools.gas", "drain");
            begin(L.target);
            for (EquipmentSlot slot : SLOTS) L.target.setItemSlot(slot, ItemStack.EMPTY);
            dress(L.target, "fau", true);
            wearAttributes(L.target);
            EuphImmunity.forget(L.target);
            L.target.removeData(acup.gunfeel.gas.GasAttach.STATE.get());
            homePool();
        });
        wait(10);
        String[] phases = {"gas", "air", "gas-again"};
        int[] limit = {2400, 2400, 3600};
        for (int ph = 0; ph < 3; ph++) {
            String name = phases[ph];
            boolean gas = ph != 1;
            int lim = limit[ph];
            int phase = ph;
            STEPS.add(() -> {
                g[0] = g4Pool(kit);
                var st = acup.gunfeel.gas.GasServer.state(L.target);
                g[1] = st.dealt;
                g[2] = L.target.getHealth();
                g[3] = 0;
                g[5] = 0;
            });
            for (int t = 0; t < lim; t++) STEPS.add(() -> {
                if (g[5] > 0) return;
                L.target.setDeltaMovement(Vec3.ZERO);
                float before = acup.gunfeel.gas.GasServer.state(L.target).dealt;
                acup.gunfeel.gas.GasServer.standStep(level, L.target, acup.gunfeel.gas.GasKind.MUSTARD, gas ? 1F : 0F);
                if (acup.gunfeel.gas.GasServer.state(L.target).dealt > before) g[4] = g[3];
                g[3]++;
                float pool = g4Pool(kit);
                boolean over = phase == 0 ? pool <= g[0] / 2F : phase == 1 ? pool >= EuphImmunity.pool(kit).size() - 1e-3F : L.target.getHealth() < g[2] - 0.5F;
                if (over || g[3] >= lim) {
                    g[5] = 1;
                    float dealt = acup.gunfeel.gas.GasServer.state(L.target).dealt - g[1];
                    line("g4\tgas\t" + name + "\t" + (int) g[3] + "\t" + f(dealt) + "\t" + f(Float.isNaN(pool) ? Float.NaN : g[0] - pool) + "\t"
                            + f(g[2] - L.target.getHealth()) + "\t" + f(pool) + "\t" + EuphImmunity.describe(kit, L.target)
                            + (phase == 1 ? "\tlast gas hurt at tick " + (int) g[4] : ""));
                }
            });
        }
        STEPS.add(() -> {
            L.target.removeData(acup.gunfeel.gas.GasAttach.STATE.get());
            EuphImmunity.forget(L.target);
            L.target.setHealth(L.target.getMaxHealth());
        });
    }

    /**
     * The GL-54's laser and a cloud of smoke 10 blocks before the shooter: the optical depth and smoked() to a point
     * behind the cloud, before it, beside it, and the same with gl54.rangeSmoke false.
     */
    private static void planSmokeRange() {
        line("#\tg4\tsmoke\tpoint\trangeSmoke\tdepth\tsmoked");
        STEPS.add(() -> {
            acup.gunfeel.smoke.SmokeClouds.forget(level);
            homePool();
            int id = acup.gunfeel.smoke.SmokeClouds.spawn(level, new Vec3(L.o.getX() + 0.5, L.o.getY() + 1.5, L.o.getZ() + 10.5), new Vec3(0, 0, 1),
                    acup.gunfeel.smoke.SmokeKind.SMOKE, 1F);
            line("#\tg4\tsmoke: облако " + id);
        });
        wait(60);
        for (String flag : new String[] {"true", "false"}) {
            STEPS.add(() -> {
                keys("gl54.rangeSmoke", flag);
                Vec3 eye = L.shooter.getEyePosition();
                var clouds = acup.gunfeel.smoke.SmokeClouds.get(level);
                double x = L.o.getX() + 0.5, z = L.o.getZ() + 0.5;
                Object[][] pts = {{"behind 22", new Vec3(x, eye.y, z + 22)}, {"before 4", new Vec3(x, eye.y, z + 4)},
                        {"beside 12 x", new Vec3(x + 12, eye.y, z)}, {"beside 12 x 3 z", new Vec3(x + 12, eye.y, z + 3)}};
                for (Object[] p : pts) {
                    Vec3 at = (Vec3) p[1];
                    float depth = clouds == null ? 0F : clouds.opticalDepth(eye, at);
                    line("g4\tsmoke\t" + p[0] + "\t" + flag + "\t" + f(depth) + "\t" + acup.gunfeel.gl54.Gl54Range.smoked(level, eye, at));
                }
            });
            wait(2);
        }
        STEPS.add(() -> {
            keys("gl54.rangeSmoke", "true");
            acup.gunfeel.smoke.SmokeClouds.forget(level);
        });
        wait(5);
    }

    // ---------------------------------------------------------------- Trenchmaster's block with the scheme on (pass tb)

    private static final String[][] TB_PAIRS = {{"g3", "r556_fmj"}, {"spas12", "g12"}, {"laser_pistol", "capacitor"}};

    /**
     * The pass tb: a bullet, a shotgun's shot and a beam at 4 blocks, aimed, on HEV and on Trenchmaster, the scheme on:
     * the hits that came to the damage (NTM's filterAttack blocks one in three of a full Trenchmaster's before it).
     */
    private static void planTrenchBlock() {
        line("#\t--- tb: гашение Trenchmaster при balance.final (filterAttack), 4 бл, прицельно");
        line("#\ttb\tgun\tammo\tset\tclicks\tprojectiles\thits\tshare");
        STEPS.add(() -> {
            keys("balanceArmor", "true", "balanceGuns", "true", "damageCap", "false", "damageCapMobs", "false", "balance.final.range", "off");
            eachLane(() -> L.shooter.setInvulnerable(true));
        });
        wait(20);
        List<Runnable> run = new ArrayList<>();
        // -PbalanceTrenchPairs=<gun:ammo,...>: these in place of TB_PAIRS (Fable №3, 0.3)
        String asked = System.getProperty(PROPERTY + ".trenchPairs", "").trim();
        String[][] pairs = asked.isEmpty() ? TB_PAIRS : java.util.Arrays.stream(asked.split("\\s*,\\s*")).map(e -> e.split(":")).filter(e -> e.length == 2).toArray(String[][]::new);
        for (String[] p : pairs) {
            Item gun = BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("hbm", "gun_" + p[0]));
            BulletConfig ammo = ammoNamed(gun, p[1]);
            var pr = BalanceFinal.pairs().get(p[0] + "." + p[1]);
            if (ammo == null || pr == null) {
                line("tb\t" + p[0] + "\t" + p[1] + "\tнет патрона");
                continue;
            }
            for (String set : new String[] {"hev", "trenchmaster"}) run.add(() -> trenchCell(pr, gun, ammo, set));
        }
        parallel(run);
        STEPS.add(() -> eachLane(() -> {
            L.shooter.setInvulnerable(false);
            for (EquipmentSlot slot : SLOTS) L.target.setItemSlot(slot, ItemStack.EMPTY);
            wearAttributes(L.target);
        }));
        wait(10);
    }

    private static void trenchCell(BalanceFinalRule.Pair pr, Item gun, BulletConfig ammo, String set) {
        int iv = BalanceFinalRule.intervalTicks(pr);
        double proj = pr.projectiles();
        int n = (int) Math.ceil(90 / proj);
        int[] seen = {0};
        STEPS.add(() -> {
            begin(L.target);
            for (EquipmentSlot slot : SLOTS) L.target.setItemSlot(slot, ItemStack.EMPTY);
            dress(L.target, set, true);
            wearAttributes(L.target);
            EuphImmunity.forget(L.target);
            L.target.setDeltaMovement(Vec3.ZERO);
            L.target.snapTo(L.o.getX() + 0.5, L.o.getY(), L.o.getZ() + 0.5 + DIST, 180F, 0F);
            L.aimHead = false;
        });
        wait(20);
        STEPS.add(() -> seen[0] = L.seen.hits);
        for (int c = 0; c < n; c++) {
            STEPS.add(() -> {
                L.target.setHealth(L.target.getMaxHealth());
                L.target.setDeltaMovement(Vec3.ZERO);
                L.target.snapTo(L.o.getX() + 0.5, L.o.getY(), L.o.getZ() + 0.5 + DIST, 180F, 0F);
                fireAimed(L.shooter, gun, ammo);
            });
            for (int t = 1; t < Math.max(iv, 4); t++) STEPS.add(() -> L.target.setHealth(L.target.getMaxHealth()));
        }
        for (int t = 0; t < 20; t++) STEPS.add(() -> L.target.setHealth(L.target.getMaxHealth()));
        STEPS.add(() -> {
            int hits = L.seen.hits - seen[0];
            line("tb\t" + pr.gun() + "\t" + pr.ammo() + "\t" + set + "\t" + n + "\t" + String.format(Locale.ROOT, "%.0f", n * proj) + "\t" + hits + "\t"
                    + String.format(Locale.ROOT, "%.4f", hits / (n * proj)));
        });
    }

    // ---------------------------------------------------------------- Fable's fight log (pass lg)

    /** a player victim: it can be hurt by the stand's gunner, and its death goes to the event bus as a ServerPlayer's */
    private static final class LogVictim extends FakePlayer {
        LogVictim(ServerLevel level) {
            super(level, new GameProfile(UUID.nameUUIDFromBytes("balanceVictim".getBytes()), "balanceVictim"));
        }

        @Override
        public boolean canHarmPlayer(net.minecraft.world.entity.player.Player other) {
            return true;
        }

        @Override
        public boolean isInvulnerableTo(ServerLevel l, net.minecraft.world.damagesource.DamageSource source) {
            return isInvulnerableToBase(source); // a FakePlayer's connection never loads a client
        }

        @Override
        public void die(net.minecraft.world.damagesource.DamageSource source) {
            net.neoforged.neoforge.common.CommonHooks.onLivingDeath(this, source);
        }
    }

    private static LogVictim logVictim;
    private static long logLines;
    private static int logHeld;

    private static long logLines() {
        try {
            Path f = Path.of(BalanceLog.FILE);
            return Files.exists(f) ? Files.readAllLines(f).size() : 0;
        } catch (java.io.IOException e) {
            return -1;
        }
    }

    /**
     * balance.final.log: the stand's player gunner kills a player in HEV with G3 at about 6 blocks: a line in
     * logs/ntmgunfeel-fights.log with its fields; then with the log off: no line, and nothing held for a shot. The
     * victim's death is a player's (NeoForge's FakePlayer drops it: CommonHooks.onLivingDeath, as ServerPlayer.die).
     */
    private static void planLog() {
        line("#\t--- lg: журнал боёв (balance.final.log), стрелок-игрок убивает игрока в HEV из G3");
        Item g3 = BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("hbm", "gun_g3"));
        BulletConfig ammo = ammoNamed(g3, "r556_fmj");
        STEPS.add(() -> {
            keys("balanceArmor", "true", "balanceGuns", "true", "damageCap", "true", "balance.final.log", "true");
            logVictim = new LogVictim(level);
            logVictim.setGameMode(GameType.SURVIVAL);
            level.addNewPlayer(logVictim);
            logVictim.connection.markClientLoaded();
            L.target.snapTo(L.o.getX() + 6.5, L.o.getY(), L.o.getZ() + 0.5 + DIST, 180F, 0F);
        });
        wait(5);
        for (boolean on : new boolean[] {true, false}) {
            STEPS.add(() -> {
                keys("balance.final.log", String.valueOf(on));
                for (EquipmentSlot slot : SLOTS) logVictim.setItemSlot(slot, ItemStack.EMPTY);
                dress(logVictim, "hev", true);
                logVictim.snapTo(L.o.getX() + 0.5, L.o.getY(), L.o.getZ() + 0.5 + DIST, 180F, 0F);
                begin(logVictim);
                logVictim.setInvulnerable(false); // a ServerPlayer is born invulnerable until its client loads
                L.aimHead = false;
            });
            wait(5);
            int[] t = {0};
            STEPS.add(() -> {
                logLines = logLines();
                logHeld = BalanceLog.held();
            });
            STEPS.add(() -> {
                if (logVictim.getHealth() > 0F && t[0] < 600) {
                    if (t[0] % 3 == 0) fire(L.gunner, g3, ammo, EnumKeybind.GUN_PRIMARY);
                    if (logVictim.invulnerableTime > 0) logVictim.invulnerableTime--; // a FakePlayer does not tick
                    t[0]++;
                    L.hold = true;
                    return;
                }
                long now = logLines();
                boolean dead = logVictim.getHealth() <= 0F;
                if (on && !BalanceFinal.on()) check(dead && now == logLines, "журнал при balance.final = false: убийство без строки (" + now + " / " + logLines + ")");
                else if (on) {
                    String last = "";
                    try {
                        var all = Files.readAllLines(Path.of(BalanceLog.FILE));
                        last = all.isEmpty() ? "" : all.get(all.size() - 1);
                    } catch (java.io.IOException e) {
                        last = e.toString();
                    }
                    line("lg\t" + last);
                    line("#\tlg: убит " + dead + ", тактов " + t[0] + ", здоровье " + logVictim.getHealth() + ", держит " + BalanceLog.held() + " (было " + logHeld + "), журнал " + BalanceLog.on());
                    String[] c = last.split("\t");
                    check(dead && now == logLines + (logLines == 0 ? 2 : 1) && c.length == 16 && c[1].startsWith("balanceGunner") && c[2].equals("balanceVictim")
                            && c[3].equals("g3") && c[4].equals("r556_fmj") && c[8].equals("hev") && Double.parseDouble(c[11]) > 0 && Integer.parseInt(c[13]) >= 1
                            && Integer.parseInt(c[12]) >= Integer.parseInt(c[13]), "журнал: строка убийства с полями (" + last + ")");
                } else check(dead && now == logLines && BalanceLog.held() == logHeld, "журнал выключен: убийство без строки (" + now + " / " + logLines
                        + "), в выстреле ничего не держится (" + BalanceLog.held() + " / " + logHeld + ")");
            });
            STEPS.add(() -> logVictim.setHealth(logVictim.getMaxHealth()));
            wait(5);
        }
        STEPS.add(() -> {
            keys("balance.final.log", "true");
            level.removePlayerImmediately(logVictim, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
            logVictim = null;
            L.target.snapTo(L.o.getX() + 0.5, L.o.getY(), L.o.getZ() + 0.5 + DIST, 180F, 0F);
        });
        wait(5);
    }

    // ---------------------------------------------------------------- Pepperbox: 13th-century tech

    private static final String[] PEP_SETS = {"", "steel", "dieselsuit", "t51", "hev", "security"};
    private static final String[] PEP_AMMO = {"stone", "stone_ap", "stone_iron", "stone_shot"};

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
        line("#\t--- Pepperbox: кликов до смерти по наборам (тело / голова), 4 бл; секунды — 27 тиков клик, 6 в барабане, 67 перезарядка");
        STEPS.add(() -> keys("balanceArmor", "true", "balanceGuns", "true", "damageCap", "true", "damageCapMobs", "false"));
        wait(30);
        List<Runnable> cases = new ArrayList<>();
        for (String a : PEP_AMMO) for (String set : PEP_SETS) for (boolean head : new boolean[] {false, true}) cases.add(() -> {
            STEPS.add(() -> {
                begin(L.target);
                for (EquipmentSlot slot : SLOTS) L.target.setItemSlot(slot, ItemStack.EMPTY);
                if (!set.isEmpty()) dress(L.target, set, false);
                L.target.snapTo(L.o.getX() + 0.5, L.o.getY(), L.o.getZ() + 0.5 + DIST, 180F, 0F);
                L.aimHead = head;
                L.pepMaxClick = 0F;
                L.pepFromFull = false;
            });
            wait(3);
            int[] end = {0};
            for (int i = 0; i < 40; i++) {
                float[] hp = {0F};
                STEPS.add(() -> {
                    if (L.deathClick > 0 || L.target.getHealth() <= 0F) return;
                    hp[0] = L.target.getHealth();
                    fire(L.shooter, ModItems.GUN_PEPPERBOX.get(), pepAmmo(a), EnumKeybind.GUN_PRIMARY);
                    L.clicks++;
                });
                for (int t = 0; t < SPACING; t++) STEPS.add(() -> {
                    if (L.deathClick <= 0 && L.clicks > 0 && L.target.getHealth() <= 0F) {
                        // the killing click took all that was left
                        L.pepMaxClick = Math.max(L.pepMaxClick, hp[0]);
                        L.pepFromFull |= hp[0] >= L.target.getMaxHealth() - 0.01F;
                        L.deathClick = L.clicks;
                        L.skipTo = end[0];
                    }
                });
                STEPS.add(() -> L.pepMaxClick = Math.max(L.pepMaxClick, hp[0] - Math.max(0F, L.target.getHealth())));
            }
            end[0] = STEPS.size();
            STEPS.add(() -> {
                L.aimHead = false;
                int n = L.deathClick;
                String secs = n > 0 ? f(pepSeconds(n)) : "-";
                line("#\tpep\t" + a + "\t" + (set.isEmpty() ? "без брони" : set) + "\t" + (head ? "голова" : "тело") + "\t" + (n > 0 ? n : "> " + L.clicks)
                        + "\t" + secs + " с\tбольше всего за клик " + f(L.pepMaxClick));
                // no click from full health kills: a pellet round is no sniper's
                if (a.equals("stone_shot")) check(!L.pepFromFull && L.pepMaxClick <= 0.8F * L.target.getMaxHealth() + 0.05F, "pep: stone_shot " + (head ? "в голову" : "в тело") + ", "
                        + (set.isEmpty() ? "без брони" : set) + ": клик снял не больше 16 (взял " + f(L.pepMaxClick) + ")");
            });
            wait(10);
        });
        parallel(cases);
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
    private static final List<String> TSV3 = new ArrayList<>();

    private static Vec3 spot3() {
        return new Vec3(L.o.getX() + 0.5, L.o.getY(), L.o.getZ() + 0.5 + EXPL_DIST);
    }

    /** the target in a set (or bare), its shield emptied (the model counts none), standing on its spot */
    private static void ready3(String set) {
        begin(L.target);
        L.target.removeAllEffects();
        L.target.setForcedPose(null);
        for (EquipmentSlot slot : SLOTS) L.target.setItemSlot(slot, ItemStack.EMPTY);
        if (!set.isEmpty()) dress(L.target, set, true);
        var props = com.hbm.extprop.HbmPlayerProps.getData(L.target);
        props.maxShield = 0F;
        props.shield = 0F;
        Vec3 t = spot3();
        L.target.snapTo(t.x, t.y, t.z, 180F, 0F);
        L.target.setDeltaMovement(Vec3.ZERO);
        L.target.fallDistance = 0;
        L.shooter.setHealth(L.shooter.getMaxHealth());
        L.blastLost = 0F;
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
        ModelHits.put(level, L.target, 0F, boxes);
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
            round = new com.hbm.entity.projectile.EntityBulletBaseMK4(level, L.shooter, c, dmg, -c.spread, from.x, from.y, from.z, d.x, d.y, d.z);
        } else round = new com.hbm.entity.projectile.EntityBulletBaseMK4(level, L.shooter, c, dmg, -c.spread, t.x + r, t.y + 1.2, t.z, 0, -1, 0);
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
        line("#\t--- этап 3: взрывы NTM по лестнице брони, стоя, blastParts и blastPartsEven; урон за взрыв (среднее) и взрывов до смерти, стенд / модель");
        out(TSV3, "взрывчатка\tгде\tнабор\tвзрывов\tблок\tурон за взрыв\tстенд, до смерти\tмодель, урон\tмодель, до смерти\tD снаряда");
        STEPS.add(() -> {
            kitKeys(true);
            // formula C's blasts (X908; with balance.final on, its blasts off: X912's)
            keys("blastParts", "true", "blastPartsEven", "true", "damageCapBlasts", "true", "balanceArmorBlasts", "true", "balanceExplRawLimit", "60",
                    "balance.final.blast", "false");
            eachLane(() -> {
                // the mannequin (the rounds' and grenades' shooter) out of the rounds' way and the blasts' reach
                L.shooter.snapTo(L.o.getX() + 8.5, L.o.getY(), L.o.getZ() + 0.5, 0F, 0F);
                // DEMO breaks blocks: bedrock under the target's side
                for (int x = -6; x <= 6; x++) for (int z = 0; z <= 12; z++) level.setBlockAndUpdate(L.o.offset(x, -1, z), Blocks.BEDROCK.defaultBlockState());
            });
        });
        wait(45);
        List<Runnable> cases = new ArrayList<>();
        for (Blast3 b : BLASTS) {
            float[] where = b.boom().launched() ? new float[] {-1F, 1F, 3F} : new float[] {1F, 3F};
            for (float r : where) for (String set : SETS3) cases.add(() -> {
                boolean trench = set.equals("trenchmaster");
                int n = trench ? 9 : 2;
                int wait = !b.boom().launched() ? 3 : r < 0F ? 25 : 10;
                STEPS.add(L.blastCell::clear);
                for (int i = 0; i < n; i++) {
                    STEPS.add(() -> {
                        ready3(set);
                        boom3(b, r);
                    });
                    wait(wait);
                    STEPS.add(() -> {
                        L.blastCell.add(L.blastLost);
                        L.target.setHealth(L.target.getMaxHealth());
                    });
                }
                STEPS.add(() -> cell3(b, r, set));
            });
        }
        parallel(cases);
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
        for (float l : L.blastCell) {
            sum += l;
            if (trench && l <= 0F) blocked++;
        }
        float mean = L.blastCell.isEmpty() ? 0F : sum / L.blastCell.size();
        int game = BalanceBlasts.hits(mean);
        String name = set.isEmpty() ? "без брони" : set;
        BalanceArmor.SetOf def = set.isEmpty() ? null : BalanceArmor.SETS.get(set);
        float raw = BalanceBlasts.raw(b.boom(), r);
        int bt = def == null ? 0 : BalanceArmor.blastTier(set, def.tier());
        float power = def == null ? 1F : BalanceArmor.blastPowerFor(bt, b.boom().name().startsWith("ракета") && r < 0F, raw, BalanceArmor.blastPower());
        float lowFloor = def != null && bt <= 4 ? BalanceArmor.blastLowFloor() : 0F;
        float model = BalanceBlasts.damage(b.boom(), r, set.isEmpty() ? null : set, set.isEmpty() ? 0F : BalanceArmor.rating(set),
                def == null ? 0F : BalanceArmor.tau(set), power, lowFloor);
        int modelHits = BalanceBlasts.hits(model);
        Float shot = SHOT_DAMAGE.get(b.boom().name());
        out(TSV3, b.boom().name() + "\t" + where3(r) + "\t" + name + "\t" + L.blastCell.size() + "\t" + blocked + "\t" + f(mean) + "\t" + hits3(game) + "\t" + f(model)
                + "\t" + hits3(modelHits) + "\t" + (b.boom().launched() ? (shot == null ? "-" : f(shot)) : f(b.boom().d())) + "\t" + L.blastCell);
        String what = b.boom().name() + ", " + where3(r) + ", " + name + ": урон за взрыв " + f(mean) + ", до смерти " + hits3(game) + " (модель " + f(model) + " / "
                + hits3(modelHits) + (trench ? ", блок " + blocked + " из " + L.blastCell.size() : "") + ")";
        // a blast that takes nothing where the model has a number: the hole of 10.2 again
        if (game == Integer.MAX_VALUE && modelHits != Integer.MAX_VALUE) {
            check(false, what + ": ∞ на стенде");
            return;
        }
        // the damage of a blast against the model's; the hits differ by more on a rounding edge (9.8 and 10.0: 3 and 2)
        float off = model > 0F ? Math.abs(mean - model) / model : 0F;
        String line = what + ", урон " + (mean >= model ? "+" : "−") + Math.round(off * 100) + " %";
        if (off > 0.15F) check(false, line + " (больше 15 %)");
        else line("#\t" + line);
    }

    private static void writeTsv3() {
        try {
            Path out = Path.of(System.getProperty(PROPERTY)).resolveSibling("explosions.tsv");
            Files.createDirectories(out.getParent());
            Files.write(out, TSV3);
            line("#\tтаблица взрывов: " + out);
        } catch (java.io.IOException e) {
            check(false, "запись explosions.tsv: " + e);
        }
    }

    /** a blast of NTM's EXPLOSIVE class from the mannequin at {@code r} blocks to the target's side, of {@code damage} */
    private static void plainBlast(float r, float damage) {
        Vec3 t = spot3();
        var v = new com.hbm.explosion.vanillant.ExplosionVNT(level, t.x + r, t.y + 0.15, t.z, 5F, L.shooter);
        v.setEntityProcessor(new com.hbm.explosion.vanillant.standard.EntityProcessorCrossSmooth(1, damage));
        v.setPlayerProcessor(new com.hbm.explosion.vanillant.standard.PlayerProcessorStandard());
        v.explode();
    }

    private static float took() {
        return L.blastLost;
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
            L.target.setHealth(L.target.getMaxHealth());
        });
    }

    private static float stood, walled;
    private static final float[] LYING = new float[3];

    /** a hand grenade of {@code b} gone off on the ground at x, z, the mannequin its thrower */
    private static void grenadeAt(Blast3 b, double x, double z) {
        var grenade = new com.hbm.entity.grenade.EntityGrenadeUniversal(level, com.hbm.items.weapon.grenade.ItemGrenadeUniversal.make(
                com.hbm.items.weapon.grenade.ItemGrenadeShell.EnumGrenadeShell.STICK, b.filling(), com.hbm.items.weapon.grenade.ItemGrenadeFuze.EnumGrenadeFuze.IMPACT));
        grenade.setThrower(L.shooter);
        grenade.setPos(x, spot3().y + 0.15, z);
        grenade.explode();
    }

    /** the cap on blasts, the raw limit, the nuclear ones, lying and a wall; the keys off */
    private static void planExplRules() {
        line("#\t--- этап 3: правила взрывов (потолок, сырой урон, ядерные, лёжа, за стеной, ключи)");
        Blast3 he = BLASTS.get(1);
        // the click's cap on a blast: 60 % of 20
        rule3("", () -> boom3(he, 1F), 3, () -> check(took() > 11.9F && took() <= 12.01F,
                "потолок 60 % на EXPLOSIVE: граната HE в 1 бл, без брони (сырой ~23,8): снято " + f(took()) + " (ждали 12)"));
        // the head's cap: a blast's hurt through HeadDamage with the head's multiplier, as MixinExplosiveProcessor does it
        for (float m : new float[] {1.75F, 1F}) rule3("", () -> HeadDamage.attack(L.target, 30F, m,
                d -> com.hbm.util.EntityDamageUtil.attackEntityFromNT(L.target, level.damageSources().source(com.hbm.lib.ModDamageTypes.EXPLOSIVE, L.shooter), d, true,
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
        rule3("hev", () -> boom3(BLASTS.get(7), -1F), 25, () -> check(L.seen.hits == 1 && Math.abs(L.seen.in - 50F) < 2F,
                "ракета Missile Launcher прямое, HEV: контакт и взрыв одним ударом (ударов через броню " + L.seen.hits + ", сырой "
                        + f(L.seen.in) + ", ждали 1 и ≈50)"));
        // a blast of more raw damage than balanceExplRawLimit: not capped (100 at 1 of 5: 80 raw)
        rule3("", () -> plainBlast(1F, 100F), 3, () -> check(took() > 12.5F,
                "сырой урон > 60 мимо потолка: взрыв 100 в 1 бл (сырой ~93), без брони: снято " + f(took()) + " (ждали больше 12)"));
        rule3("", () -> plainBlast(1F, 60F), 3, () -> check(took() <= 12.01F && took() > 11.9F,
                "сырой урон ≤ 60 под потолком: взрыв 60 в 1 бл (сырой ~56), без брони: снято " + f(took()) + " (ждали 12)"));
        // just over the limit raw, well under it after HEV: the cap looks at the raw damage
        rule3("hev", () -> plainBlast(1F, 70F), 3, () -> check(took() > 12.5F && L.seen.in > 60F && L.seen.out < 60F,
                "сырой урон > 60 мимо потолка: взрыв 70 в 1 бл, HEV: сырой " + f(L.seen.in) + ", после брони " + f(L.seen.out) + ", снято "
                        + f(took()) + " (ждали больше 12)"));
        // nuclear: NTM's ExplosionNukeGeneric.dealDamage (NUCLEAR_BLAST, no shooter), and a nuclear hit from the mannequin
        rule3("", () -> com.hbm.explosion.ExplosionNukeGeneric.dealDamage(level, spot3().x + 1, spot3().y, spot3().z, 6), 3,
                () -> check(took() > 12.5F, "ядерный взрыв (ExplosionNukeGeneric.dealDamage, 1 бл): снято " + f(took()) + " (ждали больше 12)"));
        rule3("", () -> L.target.hurtServer(level, level.damageSources().source(com.hbm.lib.ModDamageTypes.NUCLEAR_BLAST, L.shooter), 30F), 3,
                () -> check(took() > 12.5F, "ядерный удар 30 от стрелка мимо потолка: снято " + f(took()) + " (ждали больше 12)"));
        // standing, lying, behind a wall: HE at 2 blocks on HEV (no cap in the way)
        STEPS.add(() -> {
            ready3("hev");
            line("#\tпоза игрока-цели (как от клиента): " + (ModelHits.hasPose(L.target) ? "есть, взрыв по частям" : "нет (урон NTM целиком)"));
        });
        rule3("bj", () -> boom3(he, 2F), 3, () -> stood = took());
        // lying (the head to -z): the grenade 2 blocks from its side, from its head's end, from its feet's end
        double[][] lyingAt = {{2, 0}, {0, -0.9 - 2}, {0, 1.1 + 2}};
        String[] lyingName = {"боком", "головой к гранате", "ногами к гранате"};
        for (int k = 0; k < 3; k++) {
            int kk = k;
            rule3("bj", () -> {
                L.target.setForcedPose(net.minecraft.world.entity.Pose.SWIMMING);
                L.target.refreshDimensions();
                pose3(true);
                Vec3 t = spot3();
                grenadeAt(he, t.x + lyingAt[kk][0], t.z + lyingAt[kk][1]);
            }, 3, () -> {
                LYING[kk] = took();
                L.target.setForcedPose(null);
                L.target.refreshDimensions();
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
            for (int k = 0; k < 3; k++) line("#\tграната HE в 2 бл, BJ, лёжа " + lyingName[k] + ": " + f(LYING[k]) + " (стоя " + f(stood) + ")");
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
        rule3("hev", () -> boom3(he, 1F), 3, () -> check(L.seen.hits > 0 && L.seen.out == L.seen.ntm,
                "balanceArmorBlasts = false: граната HE по HEV числами NTM (после брони " + f(L.seen.out) + " = NTM " + f(L.seen.ntm)
                        + "), снято " + f(took())));
        STEPS.add(() -> keys("balanceArmorBlasts", "true"));
        wait(30);
        rule3("hev", () -> boom3(he, 1F), 3, () -> check(L.seen.hits > 0 && L.seen.out != L.seen.ntm,
                "balanceArmorBlasts = true: граната HE по HEV формулой C (после брони " + f(L.seen.out) + ", NTM " + f(L.seen.ntm)
                        + "), снято " + f(took())));
        STEPS.add(() -> eachLane(() -> L.shooter.snapTo(L.o.getX() + 0.5, L.o.getY(), L.o.getZ() + 0.5, 0F, 0F)));
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
        line("#\t--- этап 3: ESP: VATS и термо не показывают игроков, кроме ступени 8 (16 бл, по прямой видимости)");
        BlockPos v = L.o.offset(-12, 0, 0);
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
                for (EquipmentSlot slot : SLOTS) L.gunner.setItemSlot(slot, ItemStack.EMPTY);
                try {
                    dress(L.gunner, name, true);
                } catch (IllegalStateException e) {
                    check(false, "ESP: набор " + s.name() + " (" + name + "): нет предметов");
                    continue;
                }
                int tier = ArmorAbilities.espTier(L.gunner);
                seen.add(s.name() + " " + tier);
                esp(s.name() + " (ступень " + tier + ", " + (bonus.thermal() ? "термо" : "") + (bonus.vats() ? " VATS" : "") + ")", tier, tier >= 8);
            }
            line("#\tESP: наборы с VATS/термо: " + seen);
            check(seen.stream().anyMatch(x -> x.startsWith("DIGAMMA 8")) && seen.stream().anyMatch(x -> x.startsWith("DNS 8")),
                    "ESP: FAU и DNS среди наборов с термо, ступень 8");
            for (EquipmentSlot slot : SLOTS) L.gunner.setItemSlot(slot, ItemStack.EMPTY);
            ItemStack penance = new ItemStack(ModItems.GUN_AMAT_PENANCE.get());
            var gun = (ItemGunBaseNT) penance.getItem();
            boolean sights = false;
            for (int i = 0; i < gun.getConfigCount(); i++) sights |= gun.getConfig(penance, i).hasThermalSights(penance);
            check(sights, "ESP: у AMAT Penance тепловой прицел (hasThermalSights)");
            esp("прицел AMAT Penance, без брони", ArmorAbilities.GUN_SIGHT_TIER, true);
            dress(L.gunner, "fau", true);
            keys("abilities.esp.players", "off");
        });
        wait(30);
        STEPS.add(() -> {
            String mode = ArmorAbilities.espModeNow();
            espCase("off, FAU", ArmorAbilities.espTier(L.gunner), mode, 10, false, false);
            keys("abilities.esp.players", "all");
            for (EquipmentSlot slot : SLOTS) L.gunner.setItemSlot(slot, ItemStack.EMPTY);
            dress(L.gunner, "t51", true);
        });
        wait(30);
        STEPS.add(() -> {
            String mode = ArmorAbilities.espModeNow();
            espCase("all (NTM), T-51b", ArmorAbilities.espTier(L.gunner), mode, 10, true, true);
            espCase("all (NTM), T-51b", ArmorAbilities.espTier(L.gunner), mode, 20, false, true);
            check(ArmorAbilities.vatsKeeps(mode, L.target), "ESP all (NTM): VATS показывает игрока");
            keys("abilities.esp.players", "tier8");
            for (EquipmentSlot slot : SLOTS) L.gunner.setItemSlot(slot, ItemStack.EMPTY);
            L.gunner.snapTo(L.o.getX() + 4.5, L.o.getY(), L.o.getZ() + 0.5, 0F, 0F);
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
        check(!ArmorAbilities.vatsKeeps(mode, L.target), "ESP " + what + ": VATS не показывает игрока");
        check(ArmorAbilities.vatsKeeps(mode, espMob) && ArmorAbilities.thermalKeeps(mode, ArmorAbilities.espRangeNow(), tier, L.gunner, espMob),
                "ESP " + what + ": зомби за стеной в 10 бл виден в термо и VATS, как в NTM");
    }

    /** the viewer at its spot, the target {@code dist} blocks in front ({@code wall}: behind the wall at 5) */
    private static void espCase(String what, int tier, String mode, int dist, boolean wall, boolean want) {
        BlockPos v = L.o.offset(-12, 0, 0);
        L.gunner.snapTo(v.getX() + 0.5, v.getY(), v.getZ() + 0.5, 0F, 0F);
        // behind the wall: straight on, the wall at +5; in the open: the other way (-z), nothing between
        double x = v.getX() + 0.5;
        double z = v.getZ() + 0.5 + (wall ? dist : -dist);
        L.target.snapTo(x, v.getY(), z, 180F, 0F);
        boolean keeps = ArmorAbilities.thermalKeeps(mode, ArmorAbilities.espRangeNow(), tier, L.gunner, L.target);
        check(keeps == want, "ESP " + what + ": игрок " + (wall ? "за стеной" : "на открытом месте") + " в " + f(L.gunner.distanceTo(L.target)) + " бл, видимость "
                + L.gunner.hasLineOfSight(L.target) + ": " + (keeps ? "виден" : "не виден") + " (ждали " + (want ? "виден" : "не виден") + ")");
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


    private static void planHigh() {
        line("#\t--- большие множители слабых патронов против брони ниже: один клик не убивает (потолок 60 %, с головой 80 %)");
        STEPS.add(() -> {
            kitKeys(true);
            keys("balanceGuns", "true");
        });
        wait(45);
        List<Runnable> cases = new ArrayList<>();
        for (High h : HIGH) for (String set : new String[] {"", "steel", "dieselsuit", "hev"}) cases.add(() -> {
            STEPS.add(() -> {
                begin(L.target);
                L.target.removeAllEffects();
                for (EquipmentSlot slot : SLOTS) L.target.setItemSlot(slot, ItemStack.EMPTY);
                if (!set.isEmpty()) dress(L.target, set, true);
                var props = com.hbm.extprop.HbmPlayerProps.getData(L.target);
                props.maxShield = 0F;
                props.shield = 0F;
                L.target.snapTo(L.o.getX() + 0.5, L.o.getY(), L.o.getZ() + 0.5 + 2, 180F, 0F);
                L.headHit = 0;
                DamageCap.last = "";
                fire(L.shooter, h.gun().get(), h.ammo().get(), h.key());
                L.clicks = 1;
            });
            wait(20);
            STEPS.add(() -> {
                float took = L.target.getMaxHealth() - Math.max(0F, L.target.getHealth());
                // a pellet in the head has the head's room of a click (0.8); a sniper's (a gun of 25 ticks and more) all of
                // it, so there a click may kill: written down, not checked (a question to the owner)
                String what = "один клик, 2 бл, " + (set.isEmpty() ? "без брони" : set) + " ← " + h.gunName() + ": снято " + f(took) + " HP из "
                        + f(L.target.getMaxHealth());
                if (L.headHit == 2) line("#\t" + what + ", дробина в голову, пушка снайперская по паузе (потолок 1,0): "
                        + (L.target.getHealth() > 0F ? "жив" : "убит") + ", ударов " + L.seen.hits);
                else {
                    float cap = L.headHit == 1 ? 0.8F : 0.6F;
                    check(L.target.getHealth() > 0F && took <= cap * L.target.getMaxHealth() + 0.01F, what + " (ждали не больше " + f(cap * L.target.getMaxHealth())
                            + (L.headHit == 1 ? ", дробина в голову" : "") + ", жив), ударов " + L.seen.hits);
                }
                L.target.setHealth(L.target.getMaxHealth());
            });
        });
        parallel(cases);
        STEPS.add(() -> keys("balanceGuns", "false"));
        wait(30);
    }

    // ---------------------------------------------------------------- the double barrel (the owner: "too little damage")

    private static final String[] DB_AMMO = {"g10", "g10_shrapnel", "g10_slug", "g10_explosive"};

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
        line("#\t--- двустволка: игрок и FBI в Security, NTM / сейчас (main) / после");
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
            List<Runnable> cases = new ArrayList<>();
            for (String a : DB_AMMO) for (EnumKeybind key : new EnumKeybind[] {EnumKeybind.GUN_PRIMARY, EnumKeybind.GUN_SECONDARY}) for (int kind = 0; kind < 3; kind++) {
                // NTM's primary fires both barrels (rounds 2), the secondary one; 0: the player, 1: the agent, 2: the agent at the head (shrapnel only: the owner's "one heart")
                if (kind == 2 && !a.equals("g10_shrapnel")) continue;
                boolean agent = kind > 0, head = kind == 2;
                String barrels = (key == EnumKeybind.GUN_PRIMARY ? "2 ствола" : "1 ствол") + (head ? ", в голову" : "");
                cases.add(() -> {
                STEPS.add(() -> {
                    L.aimHead = head;
                    if (agent) {
                        var created = BuiltInRegistries.ENTITY_TYPE.getValue(FBI).create(level, EntitySpawnReason.COMMAND);
                        if (!(created instanceof Mob mob)) throw new IllegalStateException("no FBI agent");
                        L.fbi = mob;
                        L.fbi.setNoAi(true);
                        L.fbi.snapTo(L.o.getX() + 0.5, L.o.getY(), L.o.getZ() + 0.5 + DIST, 180F, 0F);
                        level.addFreshEntity(L.fbi);
                        dress(L.fbi, "security", true);
                        L.fbi.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                        begin(L.fbi);
                    } else {
                        begin(L.target);
                        L.target.removeAllEffects();
                        dress(L.target, "security", true);
                        var props = com.hbm.extprop.HbmPlayerProps.getData(L.target);
                        props.maxShield = 0F;
                        props.shield = 0F;
                        L.target.snapTo(L.o.getX() + 0.5, L.o.getY(), L.o.getZ() + 0.5 + DIST, 180F, 0F);
                    }
                });
                clicks(L.shooter, ModItems.GUN_DOUBLE_BARREL::get, dbAmmo(a), key);
                STEPS.add(() -> {
                    String who = agent ? "FBI в security (ваниль " + f((float) L.fbi.getAttributeValue(Attributes.ARMOR)) + ")" : "игрок в security";
                    row("двустволка " + mode, who, a + ", " + barrels);
                    line("#\tдвустволка\t" + mode + "\t" + who + "\t" + a + "\t" + barrels + "\tкликов " + (L.deathClick > 0 ? L.deathClick : "> " + L.clicks)
                            + "\tснято первым " + f(Math.max(0F, L.firstClick)) + "\tмножитель " + f(BalanceGuns.pair(ModItems.GUN_DOUBLE_BARREL.get(), dbAmmo(a)).mult()));
                    if (mode.equals("после")) check("table".equals(BalanceGuns.from(ModItems.GUN_DOUBLE_BARREL.get()).get(a).from()),
                            "двустволка после: " + a + " — своя строка таблицы v4 (×" + f(BalanceGuns.pair(ModItems.GUN_DOUBLE_BARREL.get(), dbAmmo(a)).mult()) + ")");
                    if (head) line("#\tдвустволка " + mode + ", шрапнель в голову FBI, " + barrels + ": первый клик снял " + f(Math.max(0F, L.firstClick))
                            + " HP, ударов по броне " + L.seen.hits + " (до брони " + f(L.seen.in) + ", после " + f(L.seen.out) + ", NTM " + f(L.seen.ntm) + ")");
                    L.aimHead = false;
                    if (agent) {
                        L.fbi.discard();
                        L.fbi = null;
                    }
                });
                });
            }
            parallel(cases);
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

    // the balance's guns at their own pause, the full kit = Med-X (Resistance I), the shield at its cap (the mod and
    // the flasks), a super stimpak taken at the first click; the clicks as the stand took them after the balance
    // review's sets (2026-11-10; kit_pairs.py had X865's sets), so this pins them
    private static final List<KitPair> KIT_PAIRS = List.of(
            new KitPair("dieselsuit", "henry · m44_express", ModItems.GUN_HENRY::get, () -> XFactory44.m44_express, 3, 6, 5),
            new KitPair("hev", "star_f · p22_jhp", ModItems.GUN_STAR_F::get, () -> XFactory22lr.p22_jhp, 14, 35, 33),
            // the pair's floor 1.9 from tier 4 (balanceArmorFloor, the second review): 12 / 27 / 25 → 9 / 19 / 17 (stand 2026-11-11);
            // the third review's HEV (60 / 4): Star-F 10 / 23 / 21 → 14 / 35 / 33, Carbine DU → 12 / 29 / 27 (stand 2026-11-11)
            new KitPair("hev", "carbine · r762_du", ModItems.GUN_CARBINE::get, () -> XFactory762mm.r762_du, 12, 29, 27),
            new KitPair("bj", "lasrifle · capacitor", ModItems.GUN_LASRIFLE::get, () -> XFactoryEnergy.energy_las, 6, 14, 12),
            new KitPair("fau", "tau · tau_uranium", ModItems.GUN_TAU::get, () -> XFactoryAccelerator.tau_uranium, 8, 19, 16));


    private static void planKit() {
        line("#\t--- комплект в парах ступеней: Med-X I + щит до потолка + супер-стимпак, клики с паузой пушки");
        for (int cap : new int[] {30, 16}) {
            String m = "потолок щита " + cap;
            STEPS.add(() -> {
                kitKeys(true);
                keys("balanceGuns", "true", "balance.shield.cap", Integer.toString(cap));
            });
            wait(45);
            List<Runnable> cases = new ArrayList<>();
            for (KitPair p : KIT_PAIRS) cases.add(() -> {
                int[] base = {0};
                kitPairFight(p, false, m, base);
                kitPairFight(p, true, m, base);
                STEPS.add(() -> {
                    int want = cap == 30 ? p.kit30() : p.kit16();
                    // the stand's clicks move by one from run to run (HEV ← Star-F's base 13 or 14): they must be the pinned ones
                    // ±1, and the limit at cap 16 is the pinned ones' ratio
                    float x = base[0] > 0 && L.deathClick > 0 ? (float) L.deathClick / base[0] : Float.NaN;
                    String what = m + ", " + p.set() + " ← " + p.gunName() + ": кликов " + base[0] + " → " + (L.deathClick > 0 ? L.deathClick : "> " + L.clicks)
                            + " (×" + f(x) + "; модель " + p.base() + " → " + want + ")";
                    boolean model = Math.abs(base[0] - p.base()) <= 1 && Math.abs(L.deathClick - want) <= 1;
                    check(model && (cap != 16 || (float) want / p.base() <= 2.4F), "комплект: " + what + (cap == 16 ? ", ждали не больше ×2,4" : ""));
                });
            });
            parallel(cases);
        }
        STEPS.add(() -> {
            keys("balance.shield.cap", "16", "balanceGuns", "false");
            eachLane(() -> {
                L.target.removeAllEffects();
                var props = com.hbm.extprop.HbmPlayerProps.getData(L.target);
                props.maxShield = 0F;
                props.shield = 0F;
            });
        });
        wait(30);
    }

    private static void kitPairFight(KitPair p, boolean kit, String m, int[] base) {
        STEPS.add(() -> {
            begin(L.target);
            L.target.removeAllEffects();
            dress(L.target, p.set(), true);
            var props = com.hbm.extprop.HbmPlayerProps.getData(L.target);
            props.maxShield = 0F;
            props.shield = 0F;
            if (kit) {
                com.hbm.handler.ArmorModHandler.applyMod(L.target.getItemBySlot(EquipmentSlot.CHEST), hbm("australium_iii"));
                props.maxShield = 100F;
                // Med-X 5 s before the fight (its potion sickness over), the super stimpak at the first click
                for (String used : new String[] {"syringe_metal_medx", "syringe_metal_super"}) {
                    L.target.removeEffect(com.hbm.potion.HbmPotion.potionsickness());
                    ItemStack st = hbm(used);
                    L.target.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, st);
                    st.getItem().use(level, L.target, net.minecraft.world.InteractionHand.MAIN_HAND);
                }
                if (BalanceKit.healLeft(L.target) <= 0F || !L.target.hasEffect(net.minecraft.world.effect.MobEffects.RESISTANCE))
                    throw new IllegalStateException("the kit did not take: heal " + BalanceKit.healLeft(L.target));
                L.target.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                props.shield = props.getEffectiveMaxShield(L.target);
                L.healing = true;
            }
            L.target.snapTo(L.o.getX() + 0.5, L.o.getY(), L.o.getZ() + 0.5 + DIST, 180F, 0F);
        });
        int every = Math.max(2, delay(p.gun().get()));
        int[] end = {0};
        for (int i = 0; i < 60; i++) {
            STEPS.add(() -> {
                if (L.deathClick > 0 || L.victim.getHealth() <= 0F) return;
                fire(L.shooter, p.gun().get(), p.ammo().get(), EnumKeybind.GUN_PRIMARY);
                L.clicks++;
            });
            // a click every {@code every} ticks: the click's step, the empty ones, the check
            for (int t = 0; t < every - 2; t++) STEPS.add(() -> {
            });
            STEPS.add(() -> {
                if (L.deathClick <= 0 && L.clicks > 0 && L.victim.getHealth() <= 0F) {
                    L.deathClick = L.clicks;
                    L.skipTo = end[0];
                }
            });
        }
        end[0] = STEPS.size();
        STEPS.add(() -> {
            L.healing = false;
            BalanceKit.clearHeal(L.target);
            row(m, p.set() + (kit ? " + комплект" : ""), p.gunName());
            if (!kit) base[0] = L.deathClick;
        });
        wait(20);
    }

    private static void plan1a() {
        line("#\tрежим\tнабор\tпушка\tкликов до смерти\tснято первым кликом\tударов\tD за удар\tNTM за удар\tC за удар\tмодель: за удар / кликов");
        for (String mode : MODES) {
            STEPS.add(() -> keys("balanceArmor", mode.equals("NTM") ? "false" : "true", "balanceGuns", "false", "damageCap", "true", "damageCapMobs", "false"));
            wait(30);
            List<Runnable> cases = new ArrayList<>();
            for (Pair p : PAIRS) cases.add(() -> pair(p, mode));
            cases.add(() -> fbiCase(mode));
            cases.add(() -> rocket(mode));
            parallel(cases);
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
        line("#\t--- этап 2: щит, Med-X, супер-стимпак, вставки, ваниль 0, евфемий");
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
            for (EquipmentSlot slot : SLOTS) L.target.setItemSlot(slot, ItemStack.EMPTY);
            L.target.removeAllEffects();
            var props = com.hbm.extprop.HbmPlayerProps.getData(L.target);
            props.maxShield = 0F;
            props.shield = 0F;
        });
        wait(30);
    }

    private static void shield2(boolean on, String m) {
        var props = com.hbm.extprop.HbmPlayerProps.getData(L.target);
        dress(L.target, "hev", true);
        ItemStack chest = L.target.getItemBySlot(EquipmentSlot.CHEST);
        com.hbm.handler.ArmorModHandler.applyMod(chest, hbm("australium_iii"));
        props.maxShield = 0F;
        float mod = props.getEffectiveMaxShield(L.target);
        props.maxShield = 100F;
        float all = props.getEffectiveMaxShield(L.target);
        check(on ? near(mod, 10F) && near(all, 16F) : near(mod, 25F) && near(all, 125F),
                "2: " + m + ": щит: мод " + f(mod) + ", мод и 20 фляг " + f(all) + (on ? " (ждали 10 и потолок 16)" : " (NTM: 25 и 125)"));
        // the shield comes back: 150 and 200 ticks after a hit, the mod alone
        props.maxShield = 0F;
        props.shield = 0F;
        props.lastDamage = L.target.tickCount;
        float at150 = 0F;
        for (int t = 1; t <= 200; t++) {
            L.target.tickCount++;
            com.hbm.handler.PlayerShield.tick(L.target);
            if (t == 150) at150 = props.shield;
        }
        check(on ? near(at150, 2.55F) && near(props.shield, 10F) : near(at150, 20.48F) && near(props.shield, 25F),
                "2: " + m + ": щит после удара: через 150 тиков " + f(at150) + ", через 200 " + f(props.shield)
                        + (on ? " (ждали 2,55 и 10: пауза 100, 0,002·t)" : " (NTM: 20,48 и 25: пауза 60, 0,005·t)"));
        // a flask: NTM's count +5, the shield's most +2 (NTM +5); no more once the flasks alone reach the cap
        dress(L.target, "hev", true);
        props.maxShield = 70F;
        props.shield = 0F;
        ItemStack flask = hbm("flask_infusion_shield");
        L.target.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, flask);
        flask.getItem().finishUsingItem(flask, level, L.target);
        float max = props.getEffectiveMaxShield(L.target), top = props.shield;
        ItemStack again = hbm("flask_infusion_shield");
        L.target.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, again);
        var use = again.getItem().use(level, L.target, net.minecraft.world.InteractionHand.MAIN_HAND);
        L.target.stopUsingItem();
        boolean refused = use == net.minecraft.world.InteractionResult.PASS;
        check(on ? near(props.maxShield, 75F) && near(max, 16F) && near(top, 2F) && refused : near(props.maxShield, 75F) && near(max, 75F) && near(top, 5F) && !refused,
                "2: " + m + ": фляга: счёт NTM " + f(props.maxShield) + ", щит до " + f(max) + ", сразу +" + f(top) + ", ещё одну " + (refused ? "нельзя" : "можно")
                        + (on ? " (ждали 75, 16, +2, нельзя)" : " (NTM: 75, 75, +5, можно)"));
        L.target.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        props.maxShield = 0F;
        props.shield = 0F;
    }

    private static boolean near(float a, float b) {
        return Math.abs(a - b) <= 0.02F + 0.002F * Math.abs(b);
    }

    private static void medicine2(boolean on, String m) {
        for (EquipmentSlot slot : SLOTS) L.target.setItemSlot(slot, ItemStack.EMPTY);
        L.target.removeAllEffects();
        ItemStack medx = hbm("syringe_metal_medx");
        L.target.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, medx);
        medx.getItem().use(level, L.target, net.minecraft.world.InteractionHand.MAIN_HAND);
        var res = L.target.getEffect(net.minecraft.world.effect.MobEffects.RESISTANCE);
        check(res != null && (on ? res.getAmplifier() == 0 && res.getDuration() == 1200 : res.getAmplifier() == 2 && res.getDuration() == 4800),
                "2: " + m + ": Med-X: " + (res == null ? "нет Сопротивления" : "Сопротивление " + (res.getAmplifier() + 1) + ", " + res.getDuration() / 20 + " с")
                        + (on ? " (ждали I, 60 с)" : " (NTM: III, 240 с)"));
        L.target.removeAllEffects();
        L.target.setHealth(5F);
        ItemStack sup = hbm("syringe_metal_super");
        L.target.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, sup);
        sup.getItem().use(level, L.target, net.minecraft.world.InteractionHand.MAIN_HAND);
        float now = L.target.getHealth();
        boolean slow = L.target.hasEffect(net.minecraft.world.effect.MobEffects.SLOWNESS);
        for (int t = 0; t < 120; t++) BalanceKit.tickHeal(L.target);
        float half = L.target.getHealth();
        for (int t = 0; t < 130; t++) BalanceKit.tickHeal(L.target);
        float end = L.target.getHealth();
        check(slow && (on ? near(now, 5F) && near(half, 17.5F) && near(end, 20F) && BalanceKit.healLeft(L.target) == 0F : near(now, 20F) && near(end, 20F)),
                "2: " + m + ": супер-стимпак с 5 HP: сразу " + f(now) + ", через 6 с " + f(half) + ", через 12,5 с " + f(end) + (slow ? ", Замедление есть" : ", НЕТ Замедления")
                        + (on ? " (ждали 5, 17,5, 20)" : " (NTM: 20 сразу)"));
        L.target.removeAllEffects();
        L.target.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        L.target.setHealth(L.target.getMaxHealth());
    }

    private static void inserts2(boolean on, String m) {
        var bullet = level.damageSources().source(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DAMAGE_TYPE, Identifier.fromNamespaceAndPath("hbm", "physical")));
        String[][] cases = {{"insert_kevlar", "9.00", "9.00"}, {"insert_sapi", "9.00", "8.50"}, {"insert_esapi", "8.55", "7.60"}, {"insert_xsapi", "8.10", "6.75"},
                {"insert_du", "8.10", "7.65"}, {"insert_ghiorsium", "7.20", "6.00"}, {"insert_steel", "9.50", "9.50"}};
        for (String[] c : cases) {
            dress(L.target, "hev", true);
            com.hbm.handler.ArmorModHandler.applyMod(L.target.getItemBySlot(EquipmentSlot.CHEST), hbm(c[0]));
            float out = com.hbm.handler.ArmorModHandler.modifyDamage(L.target, bullet, 10F);
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
            // starmetal is a combat set of the table since review 3 (balanceS.starmetal 0: the joke set again)
            boolean combat = BalanceArmor.rating(set) > 0F;
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
            begin(L.target);
            L.target.removeAllEffects();
            dress(L.target, "hev", true);
            var props = com.hbm.extprop.HbmPlayerProps.getData(L.target);
            props.maxShield = 0F;
            props.shield = 0F;
            if (kit) {
                ItemStack chest = L.target.getItemBySlot(EquipmentSlot.CHEST);
                // 1: the shield mod; 2: the mod and every flask (NTM's count 100): the cap
                com.hbm.handler.ArmorModHandler.applyMod(chest, hbm("australium_iii"));
                if (kind == 2) props.maxShield = 100F;
                ItemStack medx = hbm("syringe_metal_medx");
                L.target.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, medx);
                medx.getItem().use(level, L.target, net.minecraft.world.InteractionHand.MAIN_HAND);
                L.target.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                props.shield = props.getEffectiveMaxShield(L.target);
            }
            L.target.snapTo(L.o.getX() + 0.5, L.o.getY(), L.o.getZ() + 0.5 + DIST, 180F, 0F);
        });
        // an insert takes the chestplate's socket 6, the shield mod's too: a kit has one of them, here the mod
        clicks(L.shooter, ModItems.GUN_STAR_F::get, XFactory22lr.p22_jhp, EnumKeybind.GUN_PRIMARY);
        STEPS.add(() -> {
            String what = kind == 0 ? "hev" : "hev + Med-X + щит " + f(com.hbm.extprop.HbmPlayerProps.getData(L.target).getEffectiveMaxShield(L.target));
            String r = row(m, what, "star_f · p22_jhp");
            if (!kit) {
                kitBase = L.deathClick;
                return;
            }
            float x = kitBase > 0 && L.deathClick > 0 ? (float) L.deathClick / kitBase : Float.NaN;
            // the numbers for the owner (the task: write them, not fit them); the balance's kit must kill at all
            line("#\t2: " + m + ": " + what + " против Star-F JHP: кликов " + kitBase + " → "
                    + (L.deathClick > 0 ? L.deathClick : "> " + L.clicks) + " (×" + (Float.isNaN(x) ? "?" : f(x)) + ")" + r);
            if (on) check(L.deathClick > 0, "2: баланс: " + what + ": убит за " + L.deathClick + " кликов (без комплекта " + kitBase + ", ×" + f(x) + ")");
            L.target.removeAllEffects();
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
            if (L.target.getHealth() <= 0F || euphStop) return;
            String phase = EuphImmunity.describe(L.target);
            if (stopAtRecover && phase.startsWith("RECOVER")) euphStop = true;
            suitTick(L.target);
            boolean full128 = has128(L.target);
            if (phase.startsWith("POOL") || phase.equals("нет")) euph128Pool |= full128;
            if (phase.startsWith("WINDOW")) euph128Window |= full128;
            pendingHp = L.target.getHealth();
            pendingPhase = phase;
            pendingTag = tag;
            fire(L.shooter, ModItems.GUN_AMAT.get(), XFactory50.bmg50_sm, EnumKeybind.GUN_PRIMARY);
            L.clicks++;
        });
        wait(SPACING - 1);
        STEPS.add(() -> {
            if (pendingTag == null) return;
            float took = Math.max(0F, pendingHp - L.target.getHealth());
            if (pendingPhase.startsWith("WINDOW")) euphLostWindow += took;
            else if (pendingPhase.startsWith("RECOVER")) euphLostRecover += took;
            else euphLostPool += took;
            EUPH_LOG.add(pendingTag + ", " + pendingPhase + ": −" + f(took) + " HP → " + f(L.target.getHealth()));
            pendingTag = null;
        });
    }

    private static float pendingHp;
    private static String pendingPhase, pendingTag;
    private static boolean euphStop, stopAtRecover;

    private static void euph2() {
        // a small pool for the stand: 4 (AMAT SM takes about 1 of it a shot), a window of 3 s, a recovery of 3 s
        STEPS.add(() -> {
            keys("balance.immune.pool", "4", "balance.immune.window", "3", "balance.immune.recover", "3", "balance.immune.hold", "0");
            begin(L.target);
            dress(L.target, "euphemium", true);
            L.target.removeAllEffects();
            EuphImmunity.forget(L.target);
            L.target.snapTo(L.o.getX() + 0.5, L.o.getY(), L.o.getZ() + 0.5 + DIST, 180F, 0F);
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
            for (String l : EUPH_LOG) line("#\t2: евфемий: " + l);
            check(euphLostPool == 0F && euphLostRecover == 0F && euphLostWindow > 0F && euph128Pool && !euph128Window,
                    "2: евфемий, пул 4: в пуле снято " + f(euphLostPool) + " HP, в окне " + f(euphLostWindow) + ", в восстановлении " + f(euphLostRecover)
                            + "; эффекты 128 в пуле " + (euph128Pool ? "есть" : "НЕТ") + ", в окне " + (euph128Window ? "ЕСТЬ" : "нет")
                            + " (ждали: 0, > 0, 0; есть, нет)");
            L.target.setHealth(L.target.getMaxHealth());
            euphStop = false;
            stopAtRecover = false;
        });
        // the recovery (3 s) runs out: poll until the pool is the pool's again, 200 ticks at most
        int[] polled = {0};
        for (int t = 0; t < 200; t++) STEPS.add(() -> {
            if (polled[0] == 0 && EuphImmunity.describe(L.target).startsWith("POOL")) polled[0] = 1;
        });
        STEPS.add(() -> check(EuphImmunity.describe(L.target).equals("POOL 4"), "2: евфемий: после восстановления пул полон: " + EuphImmunity.describe(L.target) + " (ждали POOL 4)"));
        // the tomb's radon: invulnerable no more while in it, the full pool notwithstanding
        STEPS.add(() -> {
            keys("balance.immune.pool", "2000", "balance.immune.window", "15", "balance.immune.recover", "60", "balance.immune.hold", "10");
            EuphImmunity.forget(L.target);
            begin(L.target);
            EUPH_LOG.clear();
            euphLostPool = euphLostWindow = euphLostRecover = 0F;
        });
        wait(30);
        for (int i = 0; i < 2; i++) {
            STEPS.add(() -> {
                var gas = BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath("hbm", "gas_radon_tomb")).defaultBlockState();
                net.minecraft.core.BlockPos at = L.target.blockPosition();
                gas.entityInside(level, at, L.target, net.minecraft.world.entity.InsideBlockEffectApplier.NOOP, false);
            });
            euphClickInGas();
        }
        STEPS.add(() -> {
            float inGas = euphLostPool;
            String pool = EuphImmunity.describe(L.target);
            check(inGas > 0F && (pool.equals("нет") || pool.equals("POOL 2000")), "2: евфемий в могильном газе (пул 2000 полон): снято " + f(inGas)
                    + " HP за 2 выстрела AMAT, пул " + pool + " (ждали > 0, пул не тронут)");
            L.target.setHealth(L.target.getMaxHealth());
            euphLostPool = 0F;
        });
        wait(5);
        for (int i = 0; i < 2; i++) euphClick("после газа");
        STEPS.add(() -> {
            String pool = EuphImmunity.describe(L.target);
            check(euphLostPool == 0F && pool.startsWith("POOL ") && !pool.equals("POOL 2000"),
                    "2: евфемий вне газа снова неуязвим, пул тратится: снято " + f(euphLostPool) + " HP, " + pool + " (ждали 0, пул меньше 2000)");
            for (String l : EUPH_LOG) line("#\t2: евфемий: " + l);
        });
        // NTM's mode: nothing gets through, nothing counted
        STEPS.add(() -> {
            keys("balance.immune.mode", "ntm");
            EuphImmunity.forget(L.target);
            begin(L.target);
            euphLostPool = euphLostWindow = euphLostRecover = 0F;
        });
        wait(30);
        for (int i = 0; i < 3; i++) euphClick("ntm");
        STEPS.add(() -> check(euphLostPool + euphLostWindow + euphLostRecover == 0F && EuphImmunity.describe(L.target).equals("нет"),
                "2: евфемий, balance.immune.mode = ntm: снято " + f(euphLostPool) + " HP, пул " + EuphImmunity.describe(L.target) + " (ждали 0, нет)"));
    }

    /** a click while the gas marks the wearer (the mark lasts a tick: put it again just before the shot) */
    private static void euphClickInGas() {
        STEPS.add(() -> {
            float hp = L.target.getHealth();
            var gas = BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath("hbm", "gas_radon_tomb")).defaultBlockState();
            gas.entityInside(level, L.target.blockPosition(), L.target, net.minecraft.world.entity.InsideBlockEffectApplier.NOOP, false);
            fire(L.shooter, ModItems.GUN_AMAT.get(), XFactory50.bmg50_sm, EnumKeybind.GUN_PRIMARY);
            pendingHp = hp;
        });
        // the bullet flies a tick or two: keep the mark on meanwhile
        for (int t = 0; t < 6; t++) STEPS.add(() -> {
            var gas = BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath("hbm", "gas_radon_tomb")).defaultBlockState();
            gas.entityInside(level, L.target.blockPosition(), L.target, net.minecraft.world.entity.InsideBlockEffectApplier.NOOP, false);
        });
        STEPS.add(() -> {
            euphLostPool += Math.max(0F, pendingHp - L.target.getHealth());
            EUPH_LOG.add("в газе: −" + f(Math.max(0F, pendingHp - L.target.getHealth())) + " HP");
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
        line("#\t--- этап 1г: способности наборов (уровни эффектов полного набора, рывки)");
        for (boolean on : new boolean[] {true, false}) {
            STEPS.add(() -> keys("abilities.mobilityCap", Boolean.toString(on), "abilities.strengthCap", on ? "2" : "0", "abilities.bismuth", Boolean.toString(on)));
            wait(30);
            for (Ab a : ABILITIES) STEPS.add(() -> abilities(a, on));
            STEPS.add(() -> {
                for (EquipmentSlot slot : SLOTS) L.target.setItemSlot(slot, ItemStack.EMPTY);
                L.target.removeAllEffects();
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
            L.target.removeAllEffects();
            dress(L.target, a.set(), true);
            // what NTM's tick does for a player in a full set
            Object bonus = activeBonus.invoke(null, L.target);
            if (bonus != null) applyEffects.invoke(null, L.target, bonus);
            com.hbm.items.armor.ArmorDash.tick(L.target, 0F, 0F);
            java.util.TreeMap<String, Integer> got = new java.util.TreeMap<>();
            for (var e : L.target.getActiveEffects()) got.put(BuiltInRegistries.MOB_EFFECT.getKey(e.getEffect().value()).getPath(), e.getAmplifier() + 1);
            StringBuilder s = new StringBuilder();
            got.forEach((k, v) -> s.append(s.isEmpty() ? "" : ", ").append(k).append(' ').append(v));
            int dashes = com.hbm.extprop.HbmPlayerProps.getData(L.target).dashCount;
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

    // notes/balance/work: review_model.after_rows (the review's S and tau), the pair's mult/neg/pen as BalanceGuns has them (the
    // HEV and Security rows by work/scripts/r3.py, the third review's HEV 60 / 4 and Security tau 4;
    // script work/scripts/pairs1b.py, the numbers in notes/balance/stage1b-guns.md)
    private static final List<Pair1b> PAIRS_1B = List.of(
            new Pair1b("steel", "henry · m44_express ×1,15", ModItems.GUN_HENRY::get, () -> XFactory44.m44_express, 10.873F, 2, 11.554F, 2, 17.25F, 15F, false, false),
            new Pair1b("dieselsuit", "greasegun · p9_sp ×2,65", ModItems.GUN_GREASEGUN::get, () -> XFactory9mm.p9_sp, 0.418F, 48, 2.173F, 10, 7.95F, 3F, false, false),
            new Pair1b("dieselsuit", "am180 · p22_sp ×2,8 (A2)", ModItems.GUN_AM180::get, () -> XFactory22lr.p22_sp, 0.266F, 76, 1.047F, 20, 5.6F, 2F, false, false),
            // NTM's blast of 116 is over balanceExplRawLimit (60), but an explosive bullet's: squeezed and capped (balanceBulletBlastCap)
            new Pair1b("t51", "autoshotgun · g12_explosive ×0,175", ModItems.GUN_AUTOSHOTGUN::get, () -> XFactory12ga.g12_explosive, 12.244F, 3, 5.819F, 4, 0F, 0F, false, false),
            new Pair1b("hev", "carbine · r762_he ×0,596", ModItems.GUN_CARBINE::get, () -> XFactory762mm.r762_he, 2.451F, 9, 1.642F, 13, 0F, 0F, false, false),
            // 1b with the pair's floor 1.3 (balanceArmorFloor, the second review): 0.868 → 1.05, as the stand took it 2026-11-11
            new Pair1b("hev", "autoshotgun_heretic · g10_du ×0,2 (H2)", ModItems.GUN_AUTOSHOTGUN_HERETIC::get, () -> XFactory10ga.g10_du, 4.388F, 2, 0.749F, 3, 5.0F, 25F, false, false),
            new Pair1b("security", "star_f · p22_jhp ×1,05", ModItems.GUN_STAR_F::get, () -> XFactory22lr.p22_jhp, 1.458F, 14, 1.499F, 14, 19.69F, 18.75F, false, false),
            new Pair1b("bj", "hangman · m44_express ×2 (пьед.)", ModItems.GUN_HANGMAN::get, () -> XFactory44.m44_express, 1.514F, 14, 3.941F, 6, 75F, 37.5F, false, false),
            new Pair1b("rpa", "g3_zebra · r556_inc_ap ×1,664", ModItems.GUN_G3_ZEBRA::get, () -> XFactory556mm.r556_inc_ap, 0.482F, 42, 0.678F, 30, 15.60F, 9.375F, false, false),
            new Pair1b("fau", "tau · tau_uranium ×2 +30", ModItems.GUN_TAU::get, () -> XFactoryAccelerator.tau_uranium, 0.561F, 36, 2.729F, 8, 50F, 25F, false, false),
            // Tau below tier 8 (under=, balanceGunUnder; notes/balance/tau-nerf-2026-11-12.md): its shot leaves with x2, the
            // armour takes it as x1.15 on tier 6 and x1.15 +18 on tier 7 (X904: 5.54 and 3.26 a click, 5 and 7 clicks)
            new Pair1b("bj", "tau · tau_uranium ×1,15 (under=7)", ModItems.GUN_TAU::get, () -> XFactoryAccelerator.tau_uranium, 1.18F, 17, 1.31F, 16, 50F, 25F, false, false),
            new Pair1b("cmb", "tau · tau_uranium ×1,15 +18 (under=8)", ModItems.GUN_TAU::get, () -> XFactoryAccelerator.tau_uranium, 0.78F, 26, 1.31F, 16, 50F, 25F, false, false),
            // N.I.4.N.I.'s arc has no ammo item: the shot must go on (no check of numbers)
            new Pair1b("hev", "ni4ni · arc (нет предмета патрона)", ModItems.GUN_N_I_4_N_I::get, () -> XFactoryAccelerator.ni4ni_arc, 0F, 0, 0F, 0, 0F, 0F, false, true),
            // cheese 2026-11-11: a tier 2 gun on a tier 4 set, its floor halved (balanceArmorTierFloor 2:4:0.5): 0.813 → 0.407
            new Pair1b("security", "FBI → игрок: greasegun · p9_sp ×2,65", ModItems.GUN_GREASEGUN::get, () -> XFactory9mm.p9_sp, 0.300F, 67, 0.407F, 50, 7.95F, 3F, true, false));

    private static final String[] MODES_1B = {"NTM", "1а", "1а+1б"};

    // ---------------------------------------------------------------- DNS without its invulnerability

    // the model (review_model.after_rows, the review's S, the pairs' mult and additions) against DNS (S 186). Tesla and Lacunae
    // with no model (info): the model has Lacunae's laser as PHYS (the game: EN, S x 0.75) and Tesla's overcharge at a
    // damage of 52.5 (the game: its beam and the chained ones); they are checked to get through
    private static final List<Pair1b> PAIRS_DNS = List.of(
            new Pair1b("dns", "tau · tau_uranium ×2 +30", ModItems.GUN_TAU::get, () -> XFactoryAccelerator.tau_uranium, 0F, 0, 2.143F, 10, 50F, 0F, false, false),
            new Pair1b("dns", "tesla_cannon · capacitor_overcharge ×2 +30", ModItems.GUN_TESLA_CANNON::get, () -> XFactoryEnergy.energy_tesla_overcharge, 0F, 0, 0F, 0, 0F, 0F, false, true),
            new Pair1b("dns", "minigun_lacunae · capacitor ×2 +50/+0,15", ModItems.GUN_MINIGUN_LACUNAE::get, () -> XFactory762mm.energy_lacunae, 0F, 0, 0F, 0, 0F, 0F, false, true),
            new Pair1b("dns", "amat · bmg50_sm ×1,109", ModItems.GUN_AMAT::get, () -> XFactory50.bmg50_sm, 0F, 0, 4.299F, 5, 83.17F, 0F, false, false));

    /**
     * DNS by its rating (balance.immune.dns = formula): the pairs against a charged full set as the model has them;
     * with ntm what NTM's invulnerability lets through (a bullet nothing; beams come with ignoreEarlyCancellation)
     */
    private static void planDns() {
        line("#\t--- DNS: неуязвимость снята (balance.immune.dns), формула C по S 186");
        for (String key : new String[] {"formula", "ntm"}) {
            String mode = key.equals("formula") ? "DNS формула" : "DNS как NTM";
            STEPS.add(() -> keys("balanceArmor", "true", "balanceGuns", "true", "damageCap", "true", "damageCapMobs", "false", "balance.immune.dns.mode", key));
            wait(45);
            List<Runnable> cases = new ArrayList<>();
            for (Pair1b p : PAIRS_DNS) cases.add(() -> {
                Pair1b q = key.equals("formula") ? p : new Pair1b(p.set(), p.gunName(), p.gun(), p.ammo(), 0F, 0, 0F, 0, 0F, 0F, false, true);
                pair1b(q, mode, true);
                if (key.equals("formula")) STEPS.add(() -> check(L.lost > 0F && L.deathClick > 0,
                        mode + ": " + p.gunName() + " по заряженному DNS проходит: снято " + f(L.lost) + ", кликов " + (L.deathClick > 0 ? L.deathClick : "> " + L.clicks)));
                if (key.equals("ntm") && p.gunName().startsWith("amat")) STEPS.add(() -> check(L.lost == 0F && L.deathClick == 0,
                        mode + ": пуля AMAT по заряженному DNS: снято " + f(L.lost) + " за " + L.clicks + " кликов (NTM: неуязвим, ждали 0)"));
            });
            parallel(cases);
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

    private static float poolNow(EuphImmunity.Kit kit) {
        return EuphImmunity.poolOf(kit, L.target);
    }

    /**
     * DNS and FAU with euphemium's round: (1) each gun's click by the set's rating (formula, X858) and on the full pool
     * (nothing through, the pool takes about the same: beams and blasts too, they come with ignoreEarlyCancellation);
     * (2) a small pool: pool → window → recovery → full pool, by AMAT; (3) the radon gas takes the pool off.
     */
    private static void planPool() {
        line("#\t--- пул DNS и FAU (balance.immune.dns.*, balance.immune.fau.*)");
        // the rocket bursts 4 blocks off: the mannequin must not die of it
        STEPS.add(() -> eachLane(() -> {
            L.shooter.setInvulnerable(true);
            for (int x = -6; x <= 6; x++) for (int z = -4; z <= 12; z++) level.setBlockAndUpdate(L.o.offset(x, -1, z), Blocks.BEDROCK.defaultBlockState());
        }));
        for (EuphImmunity.Kit kit : new EuphImmunity.Kit[] {EuphImmunity.Kit.DNS, EuphImmunity.Kit.FAU}) {
            String set = kit.set, k = kit.key;
            float full = kit.size;
            for (String mode : new String[] {"formula", "pool"}) {
                STEPS.add(() -> keys("balanceArmor", "true", "balanceGuns", "true", "damageCap", "true", "damageCapMobs", "false", k + ".mode", mode,
                        k + ".pool", String.valueOf(full), k + ".window", "15", k + ".recover", "60"));
                wait(30);
                List<Runnable> cases = new ArrayList<>();
                for (PoolGun g : POOL_GUNS) cases.add(() -> {
                    String id = set + " " + g.name();
                    STEPS.add(() -> {
                        begin(L.target);
                        dress(L.target, set, false);
                        EuphImmunity.forget(L.target);
                        homePool();
                    });
                    wait(5);
                    for (int c = 0; c < 3; c++) {
                        STEPS.add(() -> {
                            L.target.setHealth(L.target.getMaxHealth());
                            homePool();
                            float p = poolNow(kit);
                            L.poolBefore = Float.isNaN(p) ? full : p;
                            fire(L.shooter, g.gun().get(), g.ammo().get(), EnumKeybind.GUN_PRIMARY);
                            L.clicks++;
                        });
                        wait(SPACING + 10);
                        STEPS.add(() -> {
                            float p = poolNow(kit);
                            if (mode.equals("pool")) POOL_DRAIN.merge(id, L.poolBefore - (Float.isNaN(p) ? full : p), Float::sum);
                        });
                    }
                    STEPS.add(() -> {
                        if (mode.equals("formula")) {
                            POOL_HP.put(id, L.lost / 3F);
                            POOL_HITS.put(id, L.seen.hits);
                            line("#\tпул: " + id + ": формула (X858): по " + f(L.lost / 3F) + " HP за клик, ударов " + L.seen.hits + " за 3 клика");
                            check(L.lost > 0F, "пул: " + id + ": по рейтингу набора проходит (снято " + f(L.lost) + " за 3 клика)");
                            return;
                        }
                        float drain = POOL_DRAIN.getOrDefault(id, 0F) / 3F, hp = POOL_HP.getOrDefault(id, 0F);
                        line("#\tпул: " + id + ": пул " + f(full) + ": снято " + f(L.lost) + " HP за 3 клика, из пула по " + f(drain) + " за клик (по рейтингу "
                                + f(hp) + " HP), " + EuphImmunity.describe(kit, L.target));
                        // a click of several hits (pellets; Tesla's chained beams, 3 or 4 a click): the damage cap takes the
                        // click whole, the pool each hit on its own; a pellet's head multiplier (after the armour) not at all
                        float tol = g.name().startsWith("double_barrel") || g.name().startsWith("tesla") ? 0.3F : 0.15F;
                        check(L.lost == 0F && drain > 0F && Math.abs(drain - hp) <= Math.max(tol * hp, 0.3F), "пул: " + id + ": набор неуязвим (снято " + f(L.lost)
                                + "), пул берёт за клик " + f(drain) + " ≈ урону по рейтингу " + f(hp));
                    });
                });
                parallel(cases);
            }
            poolRound(kit);
        }
        STEPS.add(() -> keys("balance.immune.dns.mode", "formula", "balance.immune.fau.mode", "formula", "balance.immune.dns.pool", "40",
                "balance.immune.fau.pool", "45", "balance.immune.dns.window", "15", "balance.immune.fau.window", "15", "balance.immune.dns.recover", "60",
                "balance.immune.fau.recover", "60"));
        STEPS.add(() -> eachLane(() -> L.shooter.setInvulnerable(false)));
        wait(30);
    }

    /** the mannequin and the target back in their places (a rocket throws both), the air between them clear */
    private static void homePool() {
        for (int x = -2; x <= 2; x++) for (int y = 0; y <= 3; y++) for (int z = -1; z <= 6; z++) level.setBlockAndUpdate(L.o.offset(x, y, z), Blocks.AIR.defaultBlockState());
        L.shooter.setHealth(L.shooter.getMaxHealth());
        L.shooter.setDeltaMovement(Vec3.ZERO);
        L.shooter.snapTo(L.o.getX() + 0.5, L.o.getY(), L.o.getZ() + 0.5, 0F, 0F);
        L.target.setDeltaMovement(Vec3.ZERO);
        L.target.snapTo(L.o.getX() + 0.5, L.o.getY(), L.o.getZ() + 0.5 + DIST, 180F, 0F);
    }

    private static float roundPool, roundWindow, roundRecover;
    private static final java.util.List<String> ROUND_LOG = new ArrayList<>();

    /** a small pool (4; AMAT takes about 3 of it a shot), a window of 3 s, a recovery of 3 s; then the radon gas */
    private static void poolRound(EuphImmunity.Kit kit) {
        String set = kit.set, k = kit.key;
        STEPS.add(() -> {
            // the window its own 3 s (balance.immune.hold 0): AMAT's clicks every second would hold it open
            keys(k + ".mode", "pool", k + ".pool", "4", k + ".window", "3", k + ".recover", "3", "balance.immune.hold", "0");
            begin(L.target);
            dress(L.target, set, false);
            EuphImmunity.forget(L.target);
            homePool();
            roundPool = roundWindow = roundRecover = 0F;
            ROUND_LOG.clear();
            euphStop = false;
        });
        wait(30);
        for (int i = 0; i < 24; i++) {
            STEPS.add(() -> {
                pendingTag = null;
                if (L.target.getHealth() <= 0F || euphStop) return;
                String phase = EuphImmunity.describe(kit, L.target);
                if (phase.startsWith("RECOVER")) euphStop = true;
                // the window must not kill: what it takes is booked, the health put back
                L.target.setHealth(L.target.getMaxHealth());
                pendingHp = L.target.getHealth();
                pendingPhase = phase;
                pendingTag = "клик";
                fire(L.shooter, ModItems.GUN_AMAT.get(), XFactory50.bmg50_sm, EnumKeybind.GUN_PRIMARY);
            });
            wait(SPACING - 1);
            STEPS.add(() -> {
                if (pendingTag == null) return;
                float took = Math.max(0F, pendingHp - L.target.getHealth());
                if (pendingPhase.startsWith("WINDOW")) roundWindow += took;
                else if (pendingPhase.startsWith("RECOVER")) roundRecover += took;
                else roundPool += took;
                ROUND_LOG.add(pendingPhase + ": −" + f(took));
                pendingTag = null;
            });
        }
        STEPS.add(() -> {
            line("#\tпул: " + set + ", пул 4: " + String.join("; ", ROUND_LOG));
            check(roundPool == 0F && roundRecover == 0F && roundWindow > 0F && euphStop, "пул: " + set + ", пул 4 / окно 3 с / восст. 3 с: в пуле снято " + f(roundPool)
                    + ", в окне " + f(roundWindow) + ", в восстановлении " + f(roundRecover) + " (ждали 0, > 0, 0; дошли до восстановления: " + euphStop + ")");
            euphStop = false;
        });
        int[] seen = {0};
        for (int t = 0; t < 120; t++) STEPS.add(() -> {
            if (seen[0] == 0 && EuphImmunity.describe(kit, L.target).startsWith("POOL")) seen[0] = 1;
        });
        STEPS.add(() -> check(EuphImmunity.describe(kit, L.target).equals("POOL 4"), "пул: " + set + ": после восстановления пул полон: " + EuphImmunity.describe(kit, L.target)
                + " (ждали POOL 4)"));
        // the radon gas: the pool takes nothing, the hits go through
        STEPS.add(() -> {
            keys(k + ".pool", "450", k + ".window", "15", k + ".recover", "60", "balance.immune.hold", "10");
            EuphImmunity.forget(L.target);
            begin(L.target);
            euphLostPool = 0F;
        });
        wait(30);
        for (int i = 0; i < 2; i++) euphClickInGas();
        STEPS.add(() -> {
            String pool = EuphImmunity.describe(kit, L.target);
            check(euphLostPool > 0F && (pool.equals("нет") || pool.equals("POOL 450")), "пул: " + set + " в могильном газе: снято " + f(euphLostPool)
                    + " HP за 2 выстрела AMAT, пул " + pool + " (ждали > 0, пул не тронут)");
            euphLostPool = 0F;
            L.target.setHealth(L.target.getMaxHealth());
        });
    }

    private static void plan1b() {
        line("#	--- этап 1б: урон пушек; секунды = (кликов − 1) × пауза ствола / 20");
        for (String mode : MODES_1B) {
            boolean guns = mode.equals("1а+1б");
            STEPS.add(() -> keys("balanceArmor", mode.equals("NTM") ? "false" : "true", "balanceGuns", guns ? "true" : "false"));
            wait(45);
            STEPS.add(() -> receivers(mode, guns));
            List<Runnable> cases = new ArrayList<>();
            for (Pair1b p : PAIRS_1B) cases.add(() -> pair1b(p, mode, guns));
            cases.add(() -> fbiCase(mode));
            parallel(cases);
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
                + delay(ModItems.GUN_AMAT_SUBTLETY.get()) + ", MAS-36 " + delay(ModItems.GUN_MAS36.get()) + "; Morning Glory база " + f(gr.getBaseDamage(g))
                + "; Aberrator магазин " + mags(ModItems.GUN_ABERRATOR.get()) + ", Eyes of the Tempest " + mags(ModItems.GUN_ABERRATOR_EOTT.get());
        boolean want = on
                ? hr.getDelayAfterFire(h) == 12 && !hr.getRefireOnHold(h) && hm == 24 && ar.getDelayAfterFire(a) == 3 && am == 60
                        && delay(ModItems.GUN_AMAT_PENANCE.get()) == 15 && delay(ModItems.GUN_AMAT_SUBTLETY.get()) == 17 && delay(ModItems.GUN_MAS36.get()) == 17
                        && gr.getBaseDamage(g) == 40F && mags(ModItems.GUN_ABERRATOR.get()).equals("9") && mags(ModItems.GUN_ABERRATOR_EOTT.get()).equals("8 8")
                : hr.getDelayAfterFire(h) == 3 && hr.getRefireOnHold(h) && hm == 250 && ar.getDelayAfterFire(a) == 1 && am == 177
                        && delay(ModItems.GUN_AMAT_PENANCE.get()) == 25 && delay(ModItems.GUN_AMAT_SUBTLETY.get()) == 25 && delay(ModItems.GUN_MAS36.get()) == 25
                        && gr.getBaseDamage(g) == 20F && mags(ModItems.GUN_ABERRATOR.get()).equals("5") && mags(ModItems.GUN_ABERRATOR_EOTT.get()).equals("5 5");
        check(want, mode + ", стволы " + (on ? "по таблице" : "как в NTM") + ": " + seen);
        // balance.immune.reloadHold: a shot and the reload from empty, ticks, as the model has them (Henry and Lincoln
        // past the hold's 200, the rest under it)
        int henry = EuphImmunity.reachOf(new ItemStack(ModItems.GUN_HENRY.get())), lincoln = EuphImmunity.reachOf(new ItemStack(ModItems.GUN_HENRY_LINCOLN.get())),
                heavy = EuphImmunity.reachOf(new ItemStack(ModItems.GUN_HEAVY_REVOLVER.get())), light = EuphImmunity.reachOf(new ItemStack(ModItems.GUN_LIGHT_REVOLVER.get()));
        check(henry == 210 && lincoln == 210 && heavy == 60 && light == 66, mode + ", выстрел и перезарядка с пустого: Henry " + henry + ", Lincoln " + lincoln
                + ", Heavy Revolver " + heavy + ", Break-Action " + light + " тиков (ждали 210, 210, 60, 66)");
    }

    /** the magazines of a gun's configs (akimbo: both), by spaces */
    private static String mags(Item item) {
        ItemStack stack = new ItemStack(item);
        ItemGunBaseNT gun = (ItemGunBaseNT) item;
        StringBuilder out = new StringBuilder();
        for (int c = 0; c < gun.getConfigCount(); c++) {
            if (c > 0) out.append(' ');
            out.append(gun.getConfig(stack, c).getReceivers(stack)[0].getMagazine(stack).getCapacity(stack));
        }
        return out.toString();
    }

    private static void pair1b(Pair1b p, String mode, boolean guns) {
        STEPS.add(() -> {
            begin(L.target);
            dress(L.target, p.set(), true);
            L.target.snapTo(L.o.getX() + 0.5, L.o.getY(), L.o.getZ() + 0.5 + DIST, 180F, 0F);
            if (p.fbi()) {
                var created = BuiltInRegistries.ENTITY_TYPE.getValue(FBI).create(level, EntitySpawnReason.COMMAND);
                if (!(created instanceof Mob mob)) throw new IllegalStateException("no FBI agent");
                L.fbi = mob;
                L.fbi.setNoAi(true);
                L.fbi.snapTo(L.o.getX() + 0.5, L.o.getY(), L.o.getZ() + 0.5, 0F, 0F);
                level.addFreshEntity(L.fbi);
                L.fbiShooter = L.fbi;
                L.shooter.snapTo(L.o.getX() + 2.5, L.o.getY(), L.o.getZ() - 3.5, 0F, 0F);
            }
        });
        clicks1b(p);
        STEPS.add(() -> {
            float modelHit = guns ? p.hit1b() : p.hit1a();
            int modelClicks = guns ? p.clicks1b() : p.clicks1a();
            int dl = delay(p.gun().get());
            String secs = L.deathClick > 0 ? f((L.deathClick - 1) * dl / 20F) : "-";
            String r = row(mode, p.set(), p.gunName()) + "	с до смерти " + secs + "	модель: " + f(modelHit) + " / " + modelClicks + " кл., "
                    + f((modelClicks - 1) * dl / 20F) + " с";
            if (p.fbi()) {
                L.fbi.discard();
                L.fbi = null;
                L.fbiShooter = null;
                L.shooter.snapTo(L.o.getX() + 0.5, L.o.getY(), L.o.getZ() + 0.5, 0F, 0F);
            }
            if (mode.equals("NTM")) return;
            if (p.info()) {
                line("#	" + p.set() + ": " + p.gunName() + ": ударов " + L.seen.hits + ", снято " + f(L.lost) + ", кликов "
                        + (L.deathClick > 0 ? L.deathClick : "> " + L.clicks) + " (" + mode + ", без сверки с моделью)");
                return;
            }
            float hit = L.seen.hits > 0 ? L.seen.out / L.seen.hits : 0F;
            float off = Math.abs(hit - modelHit) / modelHit;
            check(L.seen.hits > 0 && off <= 0.15F, mode + ", " + p.set() + ", " + p.gunName() + ": урон за удар после брони "
                    + f(hit) + " против модели " + f(modelHit) + " (" + Math.round(off * 100) + " %)" + r);
            if (!guns && p.ntmD() > 0F && L.seen.hits > 0) {
                float d = L.seen.in / L.seen.hits, offD = Math.abs(d - p.ntmD()) / p.ntmD();
                check(offD <= 0.02F, mode + ", " + p.gunName() + ": урон удара до брони " + f(d) + " = NTM " + f(p.ntmD()) + " (balanceGuns выкл)");
            }
            if (guns && p.d1b() > 0F && L.seen.hits > 0) {
                float d = L.seen.in / L.seen.hits, offD = Math.abs(d - p.d1b()) / p.d1b();
                check(offD <= 0.02F, mode + ", " + p.gunName() + ": урон удара до брони " + f(d) + " = NTM × множитель " + f(p.d1b()));
            }
            if (L.deathClick > 0) line("#	" + p.set() + ", " + p.gunName() + ": кликов " + L.deathClick + " против модели " + modelClicks + " ("
                    + Math.round(100F * (L.deathClick - modelClicks) / modelClicks) + " %)");
            else line("#	" + p.set() + ", " + p.gunName() + ": жив после " + L.clicks + " кликов, модель " + modelClicks);
        });
    }


    private static void clicks1b(Pair1b p) {
        int[] end = {0};
        for (int i = 0; i < MAX_CLICKS + 25; i++) {
            STEPS.add(() -> {
                if (L.deathClick > 0 || L.victim.getHealth() <= 0F) return;
                fire(L.fbiShooter != null ? L.fbiShooter : L.shooter, p.gun().get(), p.ammo().get(), EnumKeybind.GUN_PRIMARY);
                L.clicks++;
            });
            for (int t = 0; t < SPACING; t++) STEPS.add(() -> {
                if (L.deathClick <= 0 && L.clicks > 0 && L.victim.getHealth() <= 0F) {
                    L.deathClick = L.clicks;
                    L.skipTo = end[0];
                }
            });
            if (i == 0) STEPS.add(() -> L.firstClick = L.lost);
        }
        end[0] = STEPS.size();
        wait(20);
    }

    // ---------------------------------------------------------------- the cases

    private static void pair(Pair p, String mode) {
        boolean vanilla = !mode.equals(MODEL);
        STEPS.add(() -> {
            begin(L.target);
            L.noShield = !vanilla;
            dress(L.target, p.set(), vanilla);
            L.headHit = 0;
            DamageCap.last = "";
            L.target.snapTo(L.o.getX() + 0.5, L.o.getY(), L.o.getZ() + 0.5 + DIST, 180F, 0F);
        });
        clicks(L.shooter, p.gun(), p.ammo(), EnumKeybind.GUN_PRIMARY);
        STEPS.add(() -> {
            String r = row(mode, p.set(), p.gunName()) + "\t" + f(p.modelHit()) + " / " + p.modelClicks();
            if (mode.equals("NTM")) return;
            if (p.modelHit() <= 0F) {
                // a sniper's bullet in the head has all of a click (cap 1.0): written down, not checked
                if (L.headHit == 2) {
                    line("#\t" + mode + ", " + p.set() + ", " + p.gunName() + ": клик снял " + f(L.firstClick) + ", пуля в голову, пушка снайперская (потолок 1,0)" + r);
                    return;
                }
                check(L.firstClick <= 12.01F && L.firstClick > 11F, mode + ", " + p.set() + ", " + p.gunName() + ": клик снял " + f(L.firstClick) + " ≤ 12 (потолок)" + r);
                return;
            }
            float hit = L.seen.hits > 0 ? L.seen.out / L.seen.hits : 0F;
            float off = Math.abs(hit - p.modelHit()) / p.modelHit();
            check(L.seen.hits > 0 && off <= 0.15F, mode + ", " + p.set() + ", " + p.gunName() + ": урон за удар после брони "
                    + f(hit) + " против модели " + f(p.modelHit()) + " (" + Math.round(off * 100) + " %)" + r);
            if (!vanilla) {
                if (L.deathClick > 0) line("#\t" + p.set() + ": кликов " + L.deathClick + " против модели " + p.modelClicks() + " ("
                        + Math.round(100F * (L.deathClick - p.modelClicks()) / p.modelClicks()) + " %)");
                else line("#\t" + p.set() + ": жив после " + L.clicks + " кликов, модель " + p.modelClicks());
            }
        });
    }

    private static void fbiCase(String mode) {
        STEPS.add(() -> {
            var created = BuiltInRegistries.ENTITY_TYPE.getValue(FBI).create(level, EntitySpawnReason.COMMAND);
            if (!(created instanceof Mob mob)) throw new IllegalStateException("no FBI agent");
            L.fbi = mob;
            L.fbi.setNoAi(true);
            L.fbi.snapTo(L.o.getX() + 4.5, L.o.getY(), L.o.getZ() + 0.5 + DIST, 180F, 0F);
            level.addFreshEntity(L.fbi);
            dress(L.fbi, "security", true);
            L.fbi.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            begin(L.fbi);
        });
        clicks(L.gunner, ModItems.GUN_HEAVY_REVOLVER::get, XFactory44.m44_express, EnumKeybind.GUN_PRIMARY);
        STEPS.add(() -> {
            float vanilla = (float) L.fbi.getAttributeValue(Attributes.ARMOR);
            line("#\tFBI: " + f(L.fbi.getMaxHealth()) + " HP, броня ванили " + f(vanilla));
            row(mode, "FBI в security", "игрок: heavy_revolver · m44_express");
            // balance.vanilla0 takes Security's 10 off, balance.fbiArmor gives the agent 10 of its own: 30 as before
            check(vanilla == 30F, mode + ", FBI: ваниль " + f(vanilla) + " (ждали 30: своя 20 + Security 10 или balance.fbiArmor 10)");
            if (mode.equals("1а+1б")) check(L.deathClick >= 12 && L.deathClick <= 16,
                    mode + ", игрок → FBI в Security, heavy_revolver m44_express ×2,04: кликов " + (L.deathClick > 0 ? L.deathClick : "> " + L.clicks) + " (ждали 12–16, прогоны 13 и 15: Security S 56, было 10–11 при S 22)");
            if (!mode.equals("NTM")) check(L.seen.hits > 0 && L.seen.out < L.seen.in,
                    mode + ", FBI: формула действует на моба (" + f(L.seen.out) + " из " + f(L.seen.in) + ")");
            L.fbi.discard();
            L.fbi = null;
        });
    }

    private static void rocket(String mode) {
        STEPS.add(() -> {
            begin(L.target);
            L.noShield = mode.equals(MODEL);
            dress(L.target, "security", !L.noShield);
            L.target.snapTo(L.o.getX() + 0.5, L.o.getY(), L.o.getZ() + 0.5 + DIST + 2, 180F, 0F);
        });
        clicks(L.shooter, ModItems.GUN_PANZERSCHRECK::get, XFactoryRocket.rocket_rpzb[0], EnumKeybind.GUN_PRIMARY);
        STEPS.add(() -> row(mode, "security", "panzerschreck · rocket_rpzb HE, прямое, 6 бл"));
    }

    /** clicks every SPACING ticks until the victim dies, MAX_CLICKS at most */
    private static void clicks(LivingEntity from, Supplier<Item> gun, BulletConfig ammo, EnumKeybind key) {
        int[] end = {0};
        for (int i = 0; i < MAX_CLICKS; i++) {
            STEPS.add(() -> {
                if (L.deathClick > 0 || L.victim.getHealth() <= 0F) return;
                // the model counts no shield: NTM's shield of a full set takes its share before the armour
                if (L.noShield && L.victim instanceof net.minecraft.world.entity.player.Player p) com.hbm.extprop.HbmPlayerProps.getData(p).shield = 0F;
                fire(from, gun.get(), ammo, key);
                L.clicks++;
            });
            for (int t = 0; t < SPACING; t++) STEPS.add(() -> {
                if (L.deathClick <= 0 && L.clicks > 0 && L.victim.getHealth() <= 0F) {
                    L.deathClick = L.clicks;
                    if (L.clicks > 1) L.skipTo = end[0];
                }
            });
            if (i == 0) STEPS.add(() -> {
                L.firstClick = L.lost;
                if (L.deathClick > 0) L.skipTo = end[0];
            });
        }
        end[0] = STEPS.size();
        wait(20);
    }

    // ---------------------------------------------------------------- the parts

    private static void begin(LivingEntity v) {
        L.seen = BalanceArmor.watch(L.victim, v);
        L.victim = v;
        L.noShield = false;
        L.clicks = L.hits = L.deathClick = 0;
        L.lost = L.incoming = 0F;
        L.incomingNote = "";
        L.firstClick = -1F;
        DamageCap.forget(v);
        v.setHealth(v.getMaxHealth());
        v.setAbsorptionAmount(0);
        v.setDeltaMovement(Vec3.ZERO);
    }

    private static final String[] SUFFIX = {"_helmet", "_plate", "_legs", "_boots"};
    private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    /** the full set, charged; {@code vanilla} off: its vanilla armour points taken off the pieces */
    private static void dress(LivingEntity v, String set, boolean vanilla) {
        // "a/b/c/d": a set by slot (helmet, chestplate, leggings, boots), "-" an empty slot (a mixed set)
        String[] bySlot = set.contains("/") ? set.split("/") : null;
        for (int i = 0; i < 4; i++) {
            String own = bySlot == null ? set : bySlot[i];
            if (own.equals("-")) {
                v.setItemSlot(SLOTS[i], ItemStack.EMPTY);
                continue;
            }
            Item item = BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("hbm", own + SUFFIX[i]));
            ItemStack stack = new ItemStack(item);
            if (stack.isEmpty()) throw new IllegalStateException("no hbm:" + own + SUFFIX[i]);
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
        aim(from, L.victim);
        var gun = (ItemGunBaseNT) stack.getItem();
        // a belt-fed gun (the Tau, Lacunae) takes its rounds from the inventory
        SimpleContainer inv = new SimpleContainer(1);
        if (ammo.ammoItem != null) inv.setItem(0, new ItemStack(ammo.ammoItem.get(), 64));
        gun.handleKeybind(from, inv, stack, key, true);
        gun.handleKeybind(from, inv, stack, key, false);
    }

    /** a primary click through the sights (the gun's aiming spread, Fable's "aimed standing") */
    private static void fireAimed(LivingEntity from, Item item, BulletConfig ammo) {
        fireAimed(from, item, ammo, false);
    }

    /** the same, with {@code both}: the secondary key too (an akimbo's other gun) */
    private static void fireAimed(LivingEntity from, Item item, BulletConfig ammo, boolean both) {
        fireKeys(from, item, ammo, true, both);
    }

    /** a click from the hip, with {@code both} the secondary key too */
    private static void fireBoth(LivingEntity from, Item item, BulletConfig ammo, boolean both) {
        fireKeys(from, item, ammo, false, both);
    }

    private static void fireKeys(LivingEntity from, Item item, BulletConfig ammo, boolean aimed, boolean both) {
        ItemStack stack = loaded(item, ammo);
        // a gun with no aim key (an akimbo, the SPAS-12) fires from the hip, as in the game (owner 2026-11-13)
        if (aimed && BalanceAim.key(stack) != BalanceAim.Key.NONE) ItemGunBaseNT.setIsAiming(stack, true);
        from.setItemSlot(EquipmentSlot.MAINHAND, stack);
        aim(from, L.victim);
        var gun = (ItemGunBaseNT) stack.getItem();
        SimpleContainer inv = new SimpleContainer(1);
        if (ammo.ammoItem != null) inv.setItem(0, new ItemStack(ammo.ammoItem.get(), 64));
        gun.handleKeybind(from, inv, stack, EnumKeybind.GUN_PRIMARY, true);
        if (both) gun.handleKeybind(from, inv, stack, EnumKeybind.GUN_SECONDARY, true);
        gun.handleKeybind(from, inv, stack, EnumKeybind.GUN_PRIMARY, false);
        if (both) gun.handleKeybind(from, inv, stack, EnumKeybind.GUN_SECONDARY, false);
    }

    private static void aim(LivingEntity from, LivingEntity at) {
        Vec3 eye = from.getEyePosition();
        Vec3 to = new Vec3(at.getX(), L.aimHead ? at.getEyeY() : at.getY() + 0.9, at.getZ());
        Vec3 d = to.subtract(eye);
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        from.snapTo(from.getX(), from.getY(), from.getZ(), yaw, pitch);
        from.setYHeadRot(yaw);
        from.setYBodyRot(yaw);
    }

    private static String row(String mode, String set, String gun) {
        int n = L.seen.hits;
        String r = "\t" + mode + "\t" + set + "\t" + gun + "\t" + (L.deathClick > 0 ? String.valueOf(L.deathClick) : "> " + L.clicks) + "\t"
                + f(Math.max(0F, L.firstClick)) + "\t" + n + "\t" + (n > 0 ? f(L.seen.in / n) : "-") + "\t"
                + (n > 0 ? f(L.seen.ntm / n) : "-") + "\t" + (n > 0 ? f(L.seen.out / n) : "-") + "\tвход " + f(L.incoming / Math.max(1, L.hits))
                + " (" + L.incomingNote + ")";
        line("#" + r);
        return r;
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve(ReaimTimes.FILE);
    }

    private static final java.util.Map<String, String> KEYS = new java.util.TreeMap<>();

    private static void keys(String... kv) {
        for (int i = 0; i < kv.length; i += 2) KEYS.put(kv[i], kv[i + 1]);
        try {
            if (kv.length == 0) Files.deleteIfExists(file());
            else {
                StringBuilder s = new StringBuilder();
                // a gun's own key (hbm:gun_x.key): the colon escaped, or the file reads it as the separator
                KEYS.forEach((k, v) -> s.append(k.replace(":", "\\:")).append(" = ").append(v).append('\n'));
                Files.writeString(file(), s);
                Files.setLastModifiedTime(file(), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        // read now: the run's ticks may go faster than the second ReaimTimes waits between two looks at the file
        ReaimTimes.reloadNow();
    }

    private static String f(float v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static void check(boolean pass, String what) {
        if (!pass) ok = false;
        line((pass ? "OK" : "FAIL") + "\t" + what);
    }
}
