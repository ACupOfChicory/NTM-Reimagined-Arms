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
import acup.gunfeel.AmmoStats;
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
        bot.gunner.lob(chest, Long.MAX_VALUE);
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

        loads();
        menus();
        hud();

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
        // a role by word: whatever the gun, kept on the mob; auto: by its gun again
        AiBot who = AiBots.get(shooter);
        AiRole byGun = who == null ? null : AiRoles.of(who);
        String based = run("armsai kit " + shooter.getStringUUID() + " role base");
        boolean given = who != null && AiRoles.of(who) == AiRole.BASE && "base".equals(shooter.getPersistentData().getString(AiRole.ROLE_KEY).orElse(""));
        // as when the mob comes back into the world: read off the mob again
        if (who != null) {
            who.assigned = null;
            AiRoles.restore(who);
        }
        boolean kept = who != null && AiRoles.of(who) == AiRole.BASE && AiSquads.roles(List.of(who)).equals("base 1");
        String none = run("armsai kit " + shooter.getStringUUID() + " role sapper");
        String auto = run("armsai kit " + shooter.getStringUUID() + " role auto");
        // (its StG 77 has a scope: a marksman by its gun since R2; any role but the base's will do)
        check.check("kit: role base makes the mob (" + byGun + " by its gun) its squad's base of fire (" + based.trim() + "), kept on the mob; a role there is not is refused in words;"
                + " role auto: by its gun again (" + auto.trim() + ")", byGun != null && byGun != AiRole.BASE && based.contains("role base") && given && kept
                && none.contains("no role 'sapper'") && auto.contains("by their guns") && who != null && AiRoles.of(who) == byGun
                && shooter.getPersistentData().getString(AiRole.ROLE_KEY).isEmpty());
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
            java.util.Set<String> rounds = AiKitCommand.rounds(stack);
            if (!(AmmoLock.primary(stack) instanceof MagazineBelt) || rounds.size() < 2) continue;
            String id = BuiltInRegistries.ITEM.getKey(item).toString();
            guns.add(id);
            if (beltGun == null || id.equals(BELT)) {
                beltGun = id;
                beltRound = new ArrayList<>(rounds).get(rounds.size() - 1);
                for (String r : rounds) if (r.endsWith(BELT_ROUND)) beltRound = r;
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

    // AiKitBase.load (NtmGuns.rounds and loadRound, RoundTable) against AiKitCommand.load as it was (copied below), on
    // each of NTM's guns in the registry, each round its first magazine takes and one it does not: the same words, and
    // the stack after it the same byte for byte (its stream codec), the fill from nothing included
    private void loads() {
        Mob holder = AiCheck.holder(level);
        AiGunner gunner = new AiGunner(holder, new AiAmmo(0));
        int guns = 0, cases = 0, loaded = 0;
        List<String> differ = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (!(item instanceof ItemGunBaseNT)) continue;
            guns++;
            String id = BuiltInRegistries.ITEM.getKey(item).toString();
            List<String> tries = new ArrayList<>(oldRounds(new ItemStack(item)).keySet());
            tries.add("hbm:ammo_standard_nosuch");
            for (String round : tries) {
                ItemStack a = new ItemStack(item), b = new ItemStack(item);
                String was = oldLoad(a, round);
                holder.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, b);
                String now = AiKitBase.load(gunner, round);
                ItemStack after = holder.getMainHandItem();
                cases++;
                if (was == null) loaded++;
                if (!java.util.Objects.equals(was, now) || !java.util.Arrays.equals(bytes(a), bytes(after)))
                    differ.add(id + " " + round + " (" + was + " / " + now + ")");
            }
        }
        holder.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        check.out("     kit: loads compared: " + guns + " guns, " + cases + " rounds asked, " + loaded + " loaded, " + differ.size() + " differ");
        check.check("kit: AiKitBase.load is AiKitCommand.load as it was on each NTM gun and round, the words and the stack's bytes ("
                + guns + " guns, " + cases + " loads" + (differ.isEmpty() ? ")" : "): " + differ.subList(0, Math.min(8, differ.size()))),
                differ.isEmpty() && loaded > guns / 2);
    }

    // AmmoChoices (NtmGuns.menuRounds, reserve, choiceStats, roundStats, loadedRound) and RadialMenu's way from its
    // numbers to the menu's entries against RadialMenu.choices, statsFor and open's type as they were (copied below), on
    // a fake player holding each of NTM's guns in the registry with: no ammo, each round its magazine takes, every other
    // one, and each with the gun loaded with the last of them: the same entries, figures and type, and the stack after
    // them the same byte for byte
    private void menus() {
        var player = net.neoforged.neoforge.common.util.FakePlayerFactory.get(level,
                new com.mojang.authlib.GameProfile(java.util.UUID.nameUUIDFromBytes("aiCheckMenu".getBytes()), "aiCheckMenu"));
        acup.gunfeel.host.Body body = acup.gunfeel.EntityBody.of(player);
        int[] nums = new int[16]; // RadialDial.MAX
        int guns = 0, cases = 0, entries = 0, typed = 0, most = 0;
        List<String> differ = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (!(item instanceof ItemGunBaseNT)) continue;
            guns++;
            String id = BuiltInRegistries.ITEM.getKey(item).toString();
            Map<String, BulletConfig> takes = oldRounds(new ItemStack(item));
            List<BulletConfig> all = new ArrayList<>(takes.values());
            for (int s = 0; s < 4; s++) {
                player.getInventory().clearContent();
                // the old reads on one stack, the new on another made the same (NTM's reads write a belt's stack the first time)
                ItemStack stack = new ItemStack(item), other = new ItemStack(item);
                if (s == 3 && !takes.isEmpty()) {
                    String last = new ArrayList<>(takes.keySet()).get(takes.size() - 1);
                    oldLoad(stack, last);
                    oldLoad(other, last);
                }
                player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, stack);
                for (int i = 0; i < all.size(); i++) {
                    if (s == 0 || s == 2 && i % 2 == 1) continue;
                    MenuWas.give(player, all.get(i));
                }
                List<BulletConfig> was = MenuWas.choices(stack, player);
                AmmoStats[] wasStats = MenuWas.statsFor(stack, was);
                Object wasType = MenuWas.type(stack, player);
                byte[] wasBytes = bytes(stack);
                player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, other);
                stack = other;
                int n = acup.gunfeel.AmmoChoices.choices(body, nums);
                boolean gl = n == acup.gunfeel.host.NtmGuns.ROUNDS_GL54;
                List<BulletConfig> now = new ArrayList<>();
                if (gl) {
                    var inv = player.getInventory();
                    for (var e : acup.gunfeel.gl54.MagLogic.entries(acup.gunfeel.gl54.GrenadeType.magazines(inv), acup.gunfeel.gl54.GrenadeType.counts(inv)))
                        if (now.size() < 16) now.add(acup.gunfeel.gl54.GrenadeType.entry(e.contents(), e.magazines()));
                } else for (int i = 0; i < n; i++) now.add(acup.gunfeel.RoundNumbers.round(nums[i]));
                AmmoStats[] nowStats = was.isEmpty() ? wasStats : acup.gunfeel.AmmoChoices.stats(body, nums, now.size(), gl);
                Object nowType;
                if (gl) {
                    var grenades = (acup.gunfeel.gl54.GrenadeMagazine) AmmoLock.primary(stack);
                    var gm = acup.gunfeel.gl54.GrenadeMagazine.gun(stack);
                    nowType = acup.gunfeel.gl54.GrenadeType.entry(gm.mag().isEmpty() ? grenades.wanted(stack, player.getInventory()) : gm.mag(), 0);
                } else {
                    int t = acup.gunfeel.AmmoChoices.current(body);
                    nowType = t == 0 ? null : acup.gunfeel.RoundNumbers.round(t);
                }
                cases++;
                entries += was.size();
                if (wasType != null) typed++;
                most = Math.max(most, all.size());
                String what = (was.equals(now) ? "" : "L") + (java.util.Arrays.equals(wasStats, nowStats) ? "" : "S")
                        + (java.util.Objects.equals(wasType, nowType) ? "" : "T(" + name(wasType) + "/" + name(nowType) + ")")
                        + (java.util.Arrays.equals(wasBytes, bytes(stack)) ? "" : "B");
                if (!what.isEmpty()) differ.add(id + " #" + s + " " + what + " (" + was.size() + " / " + now.size() + ")");
            }
        }
        player.getInventory().clearContent();
        check.out("     kit: menus compared: " + guns + " guns, " + cases + " inventories, " + entries + " entries, " + typed
                + " with a type, most rounds a gun takes " + most + ", " + differ.size() + " differ");
        check.check("kit: AmmoChoices is RadialMenu's choices, figures and type as they were on each NTM gun and inventory ("
                + guns + " guns, " + cases + " inventories" + (differ.isEmpty() ? ")" : "): " + differ.subList(0, Math.min(8, differ.size()))),
                differ.isEmpty() && entries > guns && typed > guns);
    }

    // HudBase.read (NtmGuns.hudBlocks and its arithmetic) against Hud.render's and block's reads and arithmetic as they
    // were (HudWas), on a fake player holding each of NTM's guns in the registry with: no ammo, each round its magazine
    // takes, every other one, and each with the gun loaded with the last of them: the same blocks (numbers, words,
    // colours, the durability's bits, the lock, the icon, the GL-54's model) and the stack after them the same byte for
    // byte; the most blocks a gun has (HudBase keeps room for 8). The GL-54 not here (a server cannot load its reads)
    private void hud() {
        var player = net.neoforged.neoforge.common.util.FakePlayerFactory.get(level,
                new com.mojang.authlib.GameProfile(java.util.UUID.nameUUIDFromBytes("aiCheckHud".getBytes()), "aiCheckHud"));
        acup.gunfeel.host.Body body = acup.gunfeel.EntityBody.of(player);
        int guns = 0, cases = 0, blocks = 0, most = 0, iconed = 0, reserved = 0, gl54 = 0;
        String mostGun = "";
        List<String> differ = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (!(item instanceof ItemGunBaseNT gun)) continue;
            // the GL-54's HUD reads NTM's animation (HbmAnimations), a class a server cannot load (it reads the client's
            // player): its gun left to the client, its arithmetic to core's HudBaseTest
            if (AmmoLock.primary(new ItemStack(item)) instanceof acup.gunfeel.gl54.GrenadeMagazine) {
                gl54++;
                continue;
            }
            guns++;
            String id = BuiltInRegistries.ITEM.getKey(item).toString();
            Map<String, BulletConfig> takes = oldRounds(new ItemStack(item));
            List<BulletConfig> all = new ArrayList<>(takes.values());
            for (int s = 0; s < 4; s++) {
                player.getInventory().clearContent();
                // the old reads on one stack, the new on another made the same (NTM's reads write a belt's stack the first time)
                ItemStack stack = new ItemStack(item), other = new ItemStack(item);
                if (s == 3 && !takes.isEmpty()) {
                    String last = new ArrayList<>(takes.keySet()).get(takes.size() - 1);
                    oldLoad(stack, last);
                    oldLoad(other, last);
                }
                player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, stack);
                for (int i = 0; i < all.size(); i++) {
                    if (s == 0 || s == 2 && i % 2 == 1) continue;
                    MenuWas.give(player, all.get(i));
                }
                List<HudWas.Block> was = HudWas.render(player, stack, gun);
                byte[] wasBytes = bytes(stack);
                player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, other);
                int n = acup.gunfeel.HudBase.read(body, acup.gunfeel.host.Body.MAIN_HAND);
                cases++;
                blocks += was.size();
                if (was.size() > most) {
                    most = was.size();
                    mostGun = id;
                }
                StringBuilder what = new StringBuilder();
                if (n != was.size()) what.append("N(").append(was.size()).append("/").append(n).append(")");
                for (int i = 0; i < Math.min(n, was.size()); i++) {
                    HudWas.Block w = was.get(i);
                    acup.gunfeel.HudBase.Block b = acup.gunfeel.HudBase.block(i);
                    if (w.icon() != null && !w.icon().isEmpty()) iconed++;
                    if (w.small() != null) reserved++;
                    String d = (java.util.Objects.equals(w.big(), b.big) ? "" : "b") + (java.util.Objects.equals(w.small(), b.small) ? "" : "s")
                            + (w.low() == b.low ? "" : "l") + (Float.floatToRawIntBits(w.health()) == Float.floatToRawIntBits(b.health) ? "" : "h")
                            + (w.drawGun() == b.drawGun ? "" : "g") + (w.lead() == b.lead ? "" : "z") + (w.color() == b.color && w.dim() == b.dim ? "" : "c")
                            + (w.locked() == b.locked ? "" : "k") + (sameIcon(w.icon(), b.icon) ? "" : "i") + (sameGl(w.gl(), b.gl) ? "" : "G");
                    if (!d.isEmpty()) what.append(" ").append(i).append(d);
                }
                if (!java.util.Arrays.equals(wasBytes, bytes(other))) what.append(" B");
                if (what.length() > 0) differ.add(id + " #" + s + what);
            }
        }
        player.getInventory().clearContent();
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        check.out("     kit: hud compared: " + guns + " guns, " + cases + " inventories, " + blocks + " blocks, " + iconed + " with an icon, "
                + reserved + " with a reserve, most blocks a gun has " + most + " (" + mostGun + "), " + differ.size() + " differ; the GL-54's "
                + gl54 + " left to the client");
        check.check("kit: HudBase is Hud's blocks as they were on each NTM gun but the GL-54 and inventory (" + guns + " guns, " + cases + " inventories, most blocks "
                + most + (differ.isEmpty() ? ")" : "): " + differ.subList(0, Math.min(8, differ.size()))),
                differ.isEmpty() && blocks >= cases && iconed > guns && reserved > guns && gl54 == 1);
    }

    private static boolean sameIcon(ItemStack was, Object now) {
        ItemStack n = (ItemStack) now;
        if (was == null || n == null) return was == n;
        return ItemStack.matches(was, n);
    }

    private static boolean sameGl(acup.gunfeel.gl54.Gl54Hud.Model a, acup.gunfeel.gl54.Gl54Hud.Model b) {
        if (a == null || b == null) return a == b;
        return a.big().equals(b.big()) && a.small().equals(b.small()) && a.color() == b.color() && a.dim() == b.dim()
                && Float.floatToRawIntBits(a.bar()) == Float.floatToRawIntBits(b.bar()) && java.util.Arrays.equals(a.dots(), b.dots())
                && a.lowAmmo() == b.lowAmmo();
    }

    private static String name(Object round) {
        return round instanceof BulletConfig b ? BalanceGuns.ammoName(b) + "@" + System.identityHashCode(b) : String.valueOf(round);
    }

    private byte[] bytes(ItemStack s) {
        var buf = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), level.registryAccess());
        try {
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, s);
            byte[] out = new byte[buf.readableBytes()];
            buf.readBytes(out);
            return out;
        } finally {
            buf.release();
        }
    }

    // AiKitCommand.rounds, cannotLoad and load before NtmGuns.rounds and loadRound (2026-11-20), the stand's "before";
    // load's bot.gunner.loadFree() as NtmGuns.fill did it then (AiGunner.Guns.fill)
    private static Map<String, BulletConfig> oldRounds(ItemStack gun) {
        Map<String, BulletConfig> out = new java.util.LinkedHashMap<>();
        if (!(gun.getItem() instanceof ItemGunBaseNT)) return out;
        com.hbm.items.weapon.sedna.mags.IMagazine<?> mag;
        try {
            mag = AmmoLock.primary(gun);
        } catch (RuntimeException e) {
            return out;
        }
        if (mag == null || mag instanceof acup.gunfeel.gl54.GrenadeMagazine) return out;
        for (BulletConfig b : AmmoLock.accepted(mag)) out.putIfAbsent(BalanceGuns.ammoName(b), b);
        return out;
    }

    private static String oldRoundName(String typed) {
        String p = typed.startsWith("hbm:") ? typed.substring(4) : typed;
        if (p.startsWith("ammo_standard_")) return p.substring("ammo_standard_".length());
        return p.startsWith("ammo_") ? p.substring(5) : p;
    }

    private static String oldCannotLoad(ItemStack gun, String round) {
        String name = BuiltInRegistries.ITEM.getKey(gun.getItem()).toString();
        if (!(gun.getItem() instanceof ItemGunBaseNT)) return (gun.isEmpty() ? "an empty hand" : name) + " is no NTM gun: no round to choose";
        Map<String, BulletConfig> rounds = oldRounds(gun);
        if (rounds.isEmpty()) return name + " has no rounds to choose from";
        if (!rounds.containsKey(oldRoundName(round))) return name + " does not take " + round + "; it takes: " + String.join(", ", rounds.keySet());
        return null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String oldLoad(ItemStack stack, String round) {
        String why = oldCannotLoad(stack, round);
        if (why != null) return why;
        BulletConfig type = oldRounds(stack).get(oldRoundName(round));
        com.hbm.items.weapon.sedna.mags.IMagazine mag = AmmoLock.primary(stack);
        int id = BulletConfig.configs.indexOf(type);
        if (id < 0) return BuiltInRegistries.ITEM.getKey(stack.getItem()) + " cannot be told to load " + round + " (NTM does not list the round)";
        if (mag instanceof MagazineBelt) {
            MagazineBelt.setMagType(stack, id);
            ItemGunBaseNT.setValueInt(stack, AmmoLock.KEY, 1);
        } else if (mag instanceof com.hbm.items.weapon.sedna.mags.MagazineSingleTypeBase) {
            mag.setType(stack, type);
        } else return BuiltInRegistries.ITEM.getKey(stack.getItem()) + " has no rounds to choose from";
        // loadFree: NtmGuns.fill
        com.hbm.items.weapon.sedna.mags.IMagazine m;
        try {
            m = ((ItemGunBaseNT) stack.getItem()).getConfig(stack, 0).getReceivers(stack)[0].getMagazine(stack);
        } catch (RuntimeException e) {
            m = null;
        }
        if (m != null) m.reloadAction(stack, null);
        return null;
    }

}
