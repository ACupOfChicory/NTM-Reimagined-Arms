package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import com.hbm.items.armor.ModArmorItem;
import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.mags.IMagazine;
import com.hbm.items.weapon.sedna.mags.MagazineBelt;

import acup.gunfeel.AmmoLock;
import acup.gunfeel.BalanceFinal;
import acup.gunfeel.BalanceGuns;
import net.minecraft.commands.CommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * The AI check's kit: the command itself, as an operator types it (/armsai spawn … armor … ammo …, /armsai kit …).
 * Kits that make no sense are refused in words and make no mob. A dummy with an StG 77, HEV armour and AP rounds fires
 * at another in HEV armour 12 blocks off, through its magazine and a reload from its reserve: the round in its gun
 * stays the one chosen, and the balance's scheme takes every hit as that pair on that set (the damage of a click is
 * told, to hold against the table of clicks). Then /armsai kit changes both where they stand (Security armour, FMJ
 * rounds; the one whose gun does not take the round keeps its own), and the hits are the new pair on the new set; a
 * piece by slot; a gun fed by a belt keeps the round chosen too.
 */
final class AiCheckKit {
    static final int X0 = 760, Z0 = 4, SIZE = 32, LIMIT = 900;
    /** ticks it holds fire at the end of a part, for the bullets in the air to land */
    static final int LAND = 20;
    static final double SX = 776.5, SZ = 30.5, TX = 776.5, TZ = 18.5;
    static final String GUN = "hbm:gun_stg77", AP = "r556_ap", FMJ = "r556_fmj";
    static final float HP = 1000F;
    /** the shots of a short part */
    static final int SOME = 8;
    /** the belt gun of the last part, when NTM has it (else its first), and the round wanted: one that ends so, else its last */
    static final String BELT = "hbm:gun_m2", BELT_ROUND = "_ap";
    private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private static AiCheckKit counting;
    private static boolean listening;

    private final AiCheck check;
    private final ServerLevel level;
    private final int y;
    private int phase, ticks, shotsFrom, capacity, cease = -1;
    /** the part's hits after the shooter's first reload */
    private boolean reloaded;
    private final Map<String, Integer> hitsAfter = new TreeMap<>();
    private Mob shooter, target;
    private String beltGun, beltRound;
    /** this phase: the scheme's words of each hit, the health each took */
    private final Map<String, Integer> hits = new TreeMap<>(), lost = new TreeMap<>();

    AiCheckKit(AiCheck check, ServerLevel level, int y) {
        this.check = check;
        this.level = level;
        this.y = y;
    }

    boolean tick() {
        if (phase == 0) {
            for (int cx = X0 >> 4; cx <= (X0 + SIZE) >> 4; cx++)
                for (int cz = Z0 >> 4; cz <= (Z0 + SIZE) >> 4; cz++) level.setChunkForced(cx, cz, true);
            if (!listening) NeoForge.EVENT_BUS.addListener(LivingIncomingDamageEvent.class, e -> {
                AiCheckKit c = counting;
                if (c == null || e.getEntity() != c.target || e.getSource().getEntity() != c.shooter) return;
                String words = String.valueOf(BalanceFinal.describe(c.target, e.getSource()));
                c.hits.merge(words, 1, Integer::sum);
                if (c.reloaded) c.hitsAfter.merge(words, 1, Integer::sum);
            });
            listening = true;
            counting = this;
            AiBots.awake = true;
            return !setup() && finish();
        }
        ticks++;
        float h = target.getHealth();
        if (h < HP) {
            lost.merge(String.format(Locale.ROOT, "%.3f", HP - h), 1, Integer::sum);
            target.setHealth(HP);
        }
        shooter.setHealth(shooter.getMaxHealth());
        AiBot bot = AiBots.get(shooter);
        if (bot == null) {
            check.check("kit: the shooter is an AI mob", false);
            return finish();
        }
        Vec3 chest = target.position().add(0, 1.0, 0);
        bot.gunner.aim(chest);
        bot.gunner.exact = chest;
        int shots = bot.gunner.shots - shotsFrom;
        if (phase == 1 && bot.gunner.ammo.loadedTotal >= capacity) reloaded = true;
        // a part: through the magazine, a reload from the reserve and some of the next; then a few shots
        boolean fired = shots >= (phase == 1 ? capacity + SOME : SOME) || ticks >= LIMIT;
        bot.gunner.fire(!fired);
        if (!fired) return false;
        if (cease < 0) cease = ticks;
        if (ticks < cease + LAND) return false;
        switch (phase) {
            case 1 -> {
                told("AP on HEV, through a reload", bot);
                check.out("     kit: of those after the reload " + hitsAfter);
                check.check("kit: it reloaded from its reserve (" + bot.gunner.ammo.loadedTotal + " rounds, the reload took " + bot.gunner.lastReload
                        + " ticks) and its gun still loads " + AP + " (" + round(shooter) + ")", bot.gunner.ammo.loadedTotal >= capacity && AP.equals(round(shooter)));
                check.check("kit: every hit is the scheme's stg77." + AP + " on hev, those after the reload too", only(hits, "stg77." + AP + " on hev,")
                        && only(hitsAfter, "stg77." + AP + " on hev,"));
                rekit();
                next(bot, 2);
            }
            case 2 -> {
                told("FMJ on Security, after /armsai kit", bot);
                check.check("kit: after /armsai kit every hit is the scheme's stg77." + FMJ + " on security", only(hits, "stg77." + FMJ + " on security,"));
                pieces();
                if (!belt()) return finish();
                next(AiBots.get(shooter), 3);
            }
            default -> {
                String pair = beltGun.substring(beltGun.indexOf("gun_") + 4) + "." + beltRound;
                told("a belt gun (" + beltGun + ") with " + beltRound, bot);
                check.check("kit: the belt of " + beltGun + " feeds " + beltRound + " still (" + round(shooter) + "): " + shots + " shots, every hit the scheme's "
                        + pair + " on hev", shots >= SOME && beltRound.equals(round(shooter)) && only(hits, pair + " on hev,"));
                return finish();
            }
        }
        return false;
    }

    private void next(AiBot bot, int to) {
        phase = to;
        ticks = 0;
        shotsFrom = bot == null ? 0 : bot.gunner.shots;
        cease = -1;
        reloaded = false;
        hitsAfter.clear();
        hits.clear();
        lost.clear();
        target.setHealth(HP);
    }

    private void told(String what, AiBot bot) {
        check.out("     kit, " + what + ": " + (bot.gunner.shots - shotsFrom) + " shots in " + ticks + " ticks; the scheme's hits " + hits + "; health lost a hit of "
                + (int) HP + " " + lost + " (a click of the scheme's is its damage to 20 health: " + String.format(Locale.ROOT, "%.3f", HP / 20) + " times as much here)");
    }

    /** every hit of this phase began with these words, and there were some */
    private static boolean only(Map<String, Integer> hits, String words) {
        if (hits.isEmpty()) return false;
        for (String k : hits.keySet()) if (!k.startsWith(words)) return false;
        return true;
    }

    /** the command as an operator in the field types it; what it answered */
    private String run(String command) {
        List<String> said = new ArrayList<>();
        CommandSource ear = new CommandSource() {
            @Override
            public void sendSystemMessage(Component c) {
                said.add(c.getString());
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        var server = level.getServer();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSource(ear).withLevel(level).withPosition(new Vec3(SX, y, (SZ + TZ) / 2)),
                command);
        String all = String.join(" | ", said);
        check.out("     kit: /" + command + "  ->  " + all);
        return all;
    }

    /** the AI mobs the command made */
    private List<Mob> spawned(String command) {
        List<AiBot> before = AiBots.all(level);
        run(command);
        List<Mob> out = new ArrayList<>();
        for (AiBot b : AiBots.all(level)) if (!before.contains(b)) out.add(b.mob);
        return out;
    }

    private void place(Mob m, double x, double z, float yaw) {
        m.setPos(x, y, z);
        m.setYRot(yaw);
        m.setYHeadRot(yaw);
        m.setYBodyRot(yaw);
        AiBot bot = AiBots.get(m);
        if (bot != null) bot.brain = null;
    }

    private boolean setup() {
        var stone = Blocks.STONE.defaultBlockState();
        var air = Blocks.AIR.defaultBlockState();
        for (int x = X0; x < X0 + SIZE; x++)
            for (int z = Z0; z < Z0 + SIZE; z++) {
                level.setBlock(new BlockPos(x, y - 1, z), stone, Block.UPDATE_CLIENTS);
                for (int h = 0; h < 4; h++) level.setBlock(new BlockPos(x, y + h, z), air, Block.UPDATE_CLIENTS);
            }
        TacticalMap.of(level).want(X0 + SIZE / 2, y, Z0 + SIZE / 2, SIZE / 2 + 4, 6);

        // kits that make no sense: told in words, no mob made
        int before = AiBots.all(level).size();
        String wrongRound = run("armsai spawn dummy 1 " + GUN + " ammo m44_ap");
        String wrongSet = run("armsai spawn dummy 1 " + GUN + " armor nosuch ammo " + AP);
        String wrongItem = run("armsai spawn dummy 1 " + GUN + " armor chest=hbm:no_such_plate");
        String noGun = run("armsai spawn dummy 1 minecraft:stick ammo " + AP);
        check.check("kit: a round the gun does not take, a set and an item there are not, a round for what is no gun: each refused in words, no mob made",
                wrongRound.contains("does not take m44_ap") && wrongRound.contains(AP) && wrongSet.contains("unknown armour set 'nosuch'")
                        && wrongItem.contains("no item hbm:no_such_plate") && noGun.contains("is no NTM gun") && AiBots.all(level).size() == before);

        // the words in any order, after the gun and after the kind alone
        List<Mob> a = spawned("armsai spawn dummy 1 " + GUN + " ammo " + AP + " team kit_a armor hev");
        List<Mob> b = spawned("armsai spawn dummy armor hev team kit_b");
        if (a.size() != 1 || b.size() != 1) {
            check.check("kit: the command made the shooter and the target", false);
            return false;
        }
        shooter = a.get(0);
        target = b.get(0);
        place(shooter, SX, SZ, 180F);
        place(target, TX, TZ, 0F);
        var hp = target.getAttribute(Attributes.MAX_HEALTH);
        if (hp != null) hp.setBaseValue(HP);
        target.setHealth(HP);
        var speed = target.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) speed.setBaseValue(0);
        AiBot bot = AiBots.get(shooter);
        capacity = bot == null ? 0 : bot.gunner.capacity();
        boolean charged = true;
        for (EquipmentSlot s : SLOTS) {
            ItemStack piece = target.getItemBySlot(s);
            if (piece.getItem() instanceof ModArmorItem armor && armor.isPowered()) charged &= armor.getCharge(piece) == armor.maxPower(piece);
        }
        check.check("kit: spawned in HEV armour, both, its powered pieces charged (the shooter " + worn(shooter) + ", the target " + worn(target) + ")",
                worn(shooter).equals("hev_helmet hev_plate hev_legs hev_boots") && worn(target).equals(worn(shooter)) && charged);
        check.check("kit: the shooter's gun is full of " + AP + " (" + round(shooter) + ", " + (bot == null ? -1 : bot.gunner.loaded()) + " of " + capacity
                + "), in their teams (" + team(shooter) + ", " + team(target) + ")", bot != null && AP.equals(round(shooter)) && capacity > 0
                        && bot.gunner.loaded() == capacity && "kit_a".equals(team(shooter)) && "kit_b".equals(team(target)));
        next(bot, 1);
        return true;
    }

    /** /armsai kit on both where they stand: Security armour and FMJ; the target's revolver does not take the round */
    private void rekit() {
        String wrong = run("armsai kit " + shooter.getStringUUID() + " ammo m44_ap");
        check.check("kit: /armsai kit with a round the mob's gun does not take is refused, its gun as it was (" + round(shooter) + ")",
                wrong.contains("does not take m44_ap") && AP.equals(round(shooter)));
        String team = run("armsai kit " + shooter.getStringUUID() + " armor hev team red");
        check.check("kit: /armsai kit names no team", team.contains("a team is named when the mobs are spawned"));
        String both = run("armsai kit @e[tag=" + AiAdapter.TAG + ",distance=..30] ammo " + FMJ + " armor sec");
        AiBot bot = AiBots.get(shooter);
        check.check("kit: /armsai kit puts Security armour on both (" + worn(shooter) + "; " + worn(target) + "), the StG 77 is full of " + FMJ + " ("
                + round(shooter) + ", " + (bot == null ? -1 : bot.gunner.loaded()) + "), the revolver keeps its round (" + round(target) + ")",
                worn(shooter).equals("security_helmet security_plate security_legs security_boots") && worn(target).equals(worn(shooter))
                        && FMJ.equals(round(shooter)) && bot != null && bot.gunner.loaded() == capacity && !FMJ.equals(round(target))
                        && both.contains("1 loading " + FMJ) && both.contains("1 keeps its rounds"));
    }

    /** pieces by slot: the slots not named stay */
    private void pieces() {
        run("armsai kit " + target.getStringUUID() + " armor chest=hbm:t51_plate head=none feet=steel");
        check.check("kit: pieces by slot, the legs as they were (" + worn(target) + ")", worn(target).equals("- t51_plate security_legs steel_boots"));
        run("armsai kit " + target.getStringUUID() + " armor none");
        check.check("kit: armor none takes it all off (" + worn(target) + ")", worn(target).equals("- - - -"));
    }

    /** a gun fed by a belt, with a round that is not its first: a new shooter in the old one's place; false when NTM has no such gun */
    private boolean belt() {
        List<String> guns = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (!(item instanceof ItemGunBaseNT)) continue;
            ItemStack stack = new ItemStack(item);
            Map<String, BulletConfig> rounds = AiKitCommand.rounds(stack);
            if (!(AmmoLock.primary(stack) instanceof MagazineBelt) || rounds.size() < 2) continue;
            String id = BuiltInRegistries.ITEM.getKey(item).toString();
            guns.add(id);
            if (beltGun == null || id.equals(BELT)) {
                beltGun = id;
                beltRound = new ArrayList<>(rounds.keySet()).get(rounds.size() - 1);
                for (String r : rounds.keySet()) if (r.endsWith(BELT_ROUND)) beltRound = r;
            }
        }
        check.out("     kit: NTM's guns fed by a belt: " + guns);
        if (beltGun == null) return false;
        shooter.discard();
        run("armsai kit " + target.getStringUUID() + " armor hev");
        // of the first shooter's team: a mob of no team is no foe of the target's, and it would hold its fire
        List<Mob> made = spawned("armsai spawn dummy 1 " + beltGun + " team kit_a armor hev ammo " + beltRound);
        if (made.size() != 1) {
            check.check("kit: the command made the belt gunner", false);
            return false;
        }
        shooter = made.get(0);
        place(shooter, SX, SZ, 180F);
        check.check("kit: the belt gun is to feed " + beltRound + " (" + round(shooter) + ")", beltRound.equals(round(shooter)));
        return true;
    }

    private boolean finish() {
        counting = null;
        if (shooter != null) shooter.discard();
        if (target != null) target.discard();
        return true;
    }

    private static String team(Mob m) {
        return m.getTeam() == null ? "-" : m.getTeam().getName();
    }

    /** the pieces it wears, helmet to boots, "-" for none */
    private static String worn(Mob m) {
        StringBuilder b = new StringBuilder();
        for (EquipmentSlot s : SLOTS) {
            ItemStack piece = m.getItemBySlot(s);
            b.append(b.isEmpty() ? "" : " ").append(piece.isEmpty() ? "-" : BuiltInRegistries.ITEM.getKey(piece.getItem()).getPath());
        }
        return b.toString();
    }

    /** the round its gun's first magazine holds, by the balance's name */
    private static String round(Mob m) {
        ItemStack stack = m.getMainHandItem();
        if (!(stack.getItem() instanceof ItemGunBaseNT)) return "-";
        IMagazine<?> mag = AmmoLock.primary(stack);
        return mag != null && mag.getType(stack, null) instanceof BulletConfig b ? BalanceGuns.ammoName(b) : "-";
    }
}
